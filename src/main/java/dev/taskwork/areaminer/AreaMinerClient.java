package dev.taskwork.areaminer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Client-only area miner.
 *
 * <p>Design rules:
 * <ul>
 *   <li>Only ever breaks blocks through {@code ClientPlayerInteractionManager}, i.e. ordinary
 *       left-click breaking. No packets, no server code, no direct world mutation.</li>
 *   <li>Never swings at a block it cannot actually see: every swing is preceded by a real raycast
 *       against the block's outline shape, so carpets, slabs, snow layers and other non-full-cube
 *       blocks are handled correctly.</li>
 *   <li>Walks to blocks instead of giving up: a small A* over standable positions (step up 1,
 *       drop up to 3) is used, and targets that turn out to be unreachable are skipped rather
 *       than aborting the whole job.</li>
 *   <li>Selects the best hotbar tool itself using vanilla's mining-speed calculation (so tool
 *       tiers, Efficiency and Haste behave exactly like a manual switch). The normal interaction
 *       call still fires Fabric's {@code AttackBlockCallback}, so mods hooking that keep working.</li>
 * </ul>
 */
public final class AreaMinerClient implements ClientModInitializer {
    private static final int MAX_BLOCKS = 32_768;
    private static final int MAX_PATH_NODES = 6_000;
    private static final long PATH_TIME_BUDGET_NS = 25_000_000L;
    private static final int REPATH_INTERVAL_TICKS = 40;
    /** Give up on a single block after three seconds of swinging at it. */
    private static final int MAX_TARGET_TICKS = 60;
    private static final int STALL_TICKS = 30;
    private static final int MAX_IDLE_TICKS = 20;
    private static final int MAX_PATH_FAILURES = 3;
    private static final int MAX_STALL_REPATHS = 2;
    private static final double LOW_OBSTACLE_HEIGHT = 0.6;
    /**
     * Blocks at or below this hardness break in a few ticks with a bare hand. Switching to a
     * "better" tool for them is pointless (efficiency does not even apply when the tool's own
     * speed multiplier is 1) and it costs durability, so those are mined without a tool.
     */
    private static final float INSTANT_BREAK_HARDNESS = 0.15f;
    /** Only look this far around the player for the next target before falling back to a full scan. */
    private static final int TARGET_SCAN_RADIUS = 12;
    /** Upper bound on ray tests per target selection, so a hotbar full of thin blocks cannot stall a tick. */
    private static final int MAX_HIT_TESTS = 24;
    /** Entries of the work list re-validated per tick (the whole list is swept in the background). */
    private static final int PRUNE_BATCH = 256;
    /** A client-predicted break is only trusted after this many ticks without a rollback. */
    private static final int PENDING_BREAK_TICKS = 80;
    /** Full re-scan of the selection when the work list runs dry, to pick up lost blocks. */
    private static final int MAX_RESCANS = 3;

    private static KeyBinding selectCornerKey;
    private static KeyBinding startStopKey;
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("areaminer", "controls"));

    private static BlockPos cornerOne;
    private static BlockPos cornerTwo;
    private static boolean setSecondNext;
    private static RegistryKey<World> selectionDimension;

    private static boolean running;
    private static final List<BlockPos> remaining = new ArrayList<>();
    private static final Set<BlockPos> unreachable = new HashSet<>();
    private static final Map<BlockPos, Integer> pathFailures = new HashMap<>();
    private static final Map<BlockPos, Integer> pendingBreaks = new HashMap<>();
    private static int rescanCount;
    private static int ticksSinceBreak;
    private static Vec3d lastProgressPos = Vec3d.ZERO;
    private static int areaMinX;
    private static int areaMaxX;
    private static int areaMinY;
    private static int areaMaxY;
    private static int areaMinZ;
    private static int areaMaxZ;
    private static BlockPos target;
    private static BlockPos breaking;
    private static int targetTicks;
    private static int lastBreakProgress;

    private static final ArrayDeque<BlockPos> route = new ArrayDeque<>();
    private static int routeAge;
    private static int pruneCursor;
    private static BlockPos equippedFor;
    private static int equipRefresh;

    private static boolean movementOwned;
    private static boolean savedForward;
    private static boolean savedJump;
    private static boolean savedSneak;
    private static boolean savedSprint;
    private static BlockPos trackedWaypoint;
    private static double lastWaypointDistance = Double.POSITIVE_INFINITY;
    private static int stalledTicks;
    private static int stallRepaths;
    private static int forceJump;
    private static int idleTicks;

    @Override
    public void onInitializeClient() {
        selectCornerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.areaminer.select_corner", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY));
        startStopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.areaminer.start_stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(AreaMinerClient::tick);
        WorldRenderEvents.END_MAIN.register(AreaMinerClient::renderSelection);
    }

    // ------------------------------------------------------------------ lifecycle

    private static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            if (running) stop(client, null);
            cornerOne = null;
            cornerTwo = null;
            selectionDimension = null;
            setSecondNext = false;
            return;
        }
        if (selectionDimension != null && !selectionDimension.equals(client.world.getRegistryKey())) {
            if (running) stop(client, "维度已切换，挖掘已停止");
            cornerOne = null;
            cornerTwo = null;
            selectionDimension = null;
            setSecondNext = false;
        }

        while (selectCornerKey.wasPressed()) selectCorner(client);
        while (startStopKey.wasPressed()) {
            if (running) stop(client, "区域挖掘已停止");
            else start(client);
        }
        if (running) mineTick(client);
    }

    private static void selectCorner(MinecraftClient client) {
        if (!(client.crosshairTarget instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            client.player.sendMessage(Text.literal("请先将准星对准一个方块，再按 Z 选择角点"), true);
            return;
        }
        BlockPos selected = hit.getBlockPos().toImmutable();
        if (!setSecondNext || cornerOne == null) {
            cornerOne = selected;
            cornerTwo = null;
            selectionDimension = client.world.getRegistryKey();
            setSecondNext = true;
            client.player.sendMessage(Text.literal("已设置角点 1：" + format(selected) + "。对准另一角再按 Z"), true);
        } else {
            cornerTwo = selected;
            setSecondNext = false;
            client.player.sendMessage(Text.literal("已设置角点 2：" + format(selected) + "。按 F8 开始挖掘"), true);
        }
    }

    private static void start(MinecraftClient client) {
        if (cornerOne == null || cornerTwo == null) {
            client.player.sendMessage(Text.literal("请先按 Z 依次选择两个角点"), true);
            return;
        }
        long volume = (long) (Math.abs(cornerOne.getX() - cornerTwo.getX()) + 1)
                * (Math.abs(cornerOne.getY() - cornerTwo.getY()) + 1)
                * (Math.abs(cornerOne.getZ() - cornerTwo.getZ()) + 1);
        if (volume > MAX_BLOCKS) {
            client.player.sendMessage(Text.literal("选区过大（最多 " + MAX_BLOCKS + " 格），请缩小后重试"), true);
            return;
        }
        int minChunkX = Math.min(cornerOne.getX(), cornerTwo.getX()) >> 4;
        int maxChunkX = Math.max(cornerOne.getX(), cornerTwo.getX()) >> 4;
        int minChunkZ = Math.min(cornerOne.getZ(), cornerTwo.getZ()) >> 4;
        int maxChunkZ = Math.max(cornerOne.getZ(), cornerTwo.getZ()) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!client.world.isChunkLoaded(chunkX, chunkZ)) {
                    client.player.sendMessage(Text.literal("选区包含尚未加载的区块，请靠近选区后重试"), true);
                    return;
                }
            }
        }

        resetRunState();
        int minX = Math.min(cornerOne.getX(), cornerTwo.getX());
        int maxX = Math.max(cornerOne.getX(), cornerTwo.getX());
        int minY = Math.min(cornerOne.getY(), cornerTwo.getY());
        int maxY = Math.max(cornerOne.getY(), cornerTwo.getY());
        int minZ = Math.min(cornerOne.getZ(), cornerTwo.getZ());
        int maxZ = Math.max(cornerOne.getZ(), cornerTwo.getZ());
        areaMinX = minX;
        areaMaxX = maxX;
        areaMinY = minY;
        areaMaxY = maxY;
        areaMinZ = minZ;
        areaMaxZ = maxZ;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isMineable(client.world, pos)) remaining.add(pos);
                }
            }
        }
        if (remaining.isEmpty()) {
            client.player.sendMessage(Text.literal("选区内没有可挖掘的方块"), true);
            return;
        }

        running = true;
        client.player.sendMessage(Text.literal("区域挖掘已启动：" + remaining.size()
                + " 个方块。会按需走到方块旁边，按 F8 停止"), true);
    }

    private static void stop(MinecraftClient client, String message) {
        running = false;
        cancelBreaking(client);
        releaseMovement(client);
        resetRunState();
        if (message != null && client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }

    private static void resetRunState() {
        remaining.clear();
        unreachable.clear();
        pathFailures.clear();
        pendingBreaks.clear();
        rescanCount = 0;
        target = null;
        breaking = null;
        targetTicks = 0;
        route.clear();
        routeAge = 0;
        pruneCursor = 0;
        equippedFor = null;
        equipRefresh = 0;
        resetWaypointProgress();
        stallRepaths = 0;
        idleTicks = 0;
    }

    // ------------------------------------------------------------------ mining loop

    private static void mineTick(MinecraftClient client) {
        if (client.currentScreen != null) {
            cancelBreaking(client);
            releaseMovement(client);
            return;
        }
        if (client.player.isRemoved() || client.player.getHealth() <= 0.0f) {
            stop(client, "玩家状态异常，区域挖掘已停止");
            return;
        }

        // Track "standing still": if the player neither moves nor breaks anything for three
        // seconds, tell them what the miner is waiting for instead of silently freezing.
        Vec3d playerPos = client.player.getEntityPos();
        if (playerPos.squaredDistanceTo(lastProgressPos) > 0.09) {
            lastProgressPos = playerPos;
            ticksSinceBreak = 0;
        } else if (++ticksSinceBreak > 60 && ticksSinceBreak % 40 == 0 && target != null) {
            client.player.sendMessage(Text.literal("区域挖掘等待中：目标 " + format(target)
                    + "，剩余 " + remaining.size() + " 格"), true);
        }

        // Keep the work list in sync with the world: cheap per-tick check for the current
        // target, full sweep only occasionally.
        verifyPendingBreaks(client);
        if (target != null && !isMineable(client.world, target)) {
            ticksSinceBreak = 0;
            // The client predicted the break, but the server may still roll it back. Keep the
            // block in the work list until the prediction has survived a few seconds.
            pendingBreaks.put(target, PENDING_BREAK_TICKS);
            forgetTarget(client);
            unreachable.clear();
            pathFailures.clear();
        }
        if (prune(client)) {
            unreachable.clear();
            pathFailures.clear();
        }
        if (remaining.isEmpty() && pendingBreaks.isEmpty()) {
            if (rescanCount < MAX_RESCANS && rescanSelection(client)) {
                rescanCount++;
                return;
            }
            stop(client, "区域挖掘完成");
            return;
        }

        if (target == null) {
            target = chooseTarget(client);
            equippedFor = null;
            if (target == null) {
                stop(client, "选区内剩余的方块都无法到达，已停止");
                return;
            }
        }

        double reach = reach(client);
        BlockHitResult hit = findHit(client.world, client.player, client.player.getEyePos(), target, reach);
        if (hit != null) {
            mine(client, hit);
            return;
        }

        // Not visible from here: walk to a spot where it is.
        cancelBreaking(client);
        if (route.isEmpty() || routeAge++ > REPATH_INTERVAL_TICKS) {
            List<BlockPos> path = findRoute(client, target);
            routeAge = 0;
            if (path == null) {
                // A single failure can be a timeout; only give up after a few honest attempts.
                int failures = pathFailures.merge(target, 1, Integer::sum);
                if (failures >= MAX_PATH_FAILURES) {
                    unreachable.add(target);
                    pathFailures.remove(target);
                    client.player.sendMessage(Text.literal("无法靠近 " + format(target) + "，已跳过"), true);
                }
                forgetTarget(client);
                releaseMovement(client);
                return;
            }
            if (path.isEmpty()) {
                // Path search claims we are already in reach, yet the raycast above failed
                // (mid-fall, eye inside a block, ...). Bounded wait, then give up on it.
                if (++idleTicks > MAX_IDLE_TICKS) blacklist(client, target);
                return;
            }
            idleTicks = 0;
            route.clear();
            route.addAll(path);
            resetWaypointProgress();
        }
        followRoute(client);
    }

    private static void mine(MinecraftClient client, BlockHitResult hit) {
        idleTicks = 0;
        releaseMovement(client);
        route.clear();
        routeAge = 0;
        resetWaypointProgress();

        lookAt(client.player, hit.getPos());
        // Re-evaluate the tool for every new block: a tool picked for the previous block must
        // never carry over (that is what made the miner crawl through stone with the wrong tool).
        if (!target.equals(equippedFor) || equipRefresh-- <= 0) {
            equipFor(client, target);
            equippedFor = target;
            equipRefresh = 40;
        }

        Direction side = hit.getSide();
        if (!target.equals(breaking)) {
            cancelBreaking(client);
            targetTicks = 0;
            lastBreakProgress = -1;
            if (client.interactionManager.attackBlock(target, side)) breaking = target;
        }
        if (target.equals(breaking)) {
            client.interactionManager.updateBlockBreakingProgress(target, side);
            int progress = client.interactionManager.getBlockBreakingProgress();
            if (progress > lastBreakProgress) {
                lastBreakProgress = progress;
                targetTicks = 0;
            }
        }
        if (++targetTicks > MAX_TARGET_TICKS) {
            // No breaking progress for three seconds (claim protection, wrong tool, ...). Skip it.
            blacklist(client, target);
        }
    }

    /**
     * Picks the next block to work on, closest first: the nearest block that can either be hit
     * from here or sits on an exposed surface. Distance is measured from the player's feet, so
     * blocks above/below are not artificially penalised.
     */
    private static BlockPos chooseTarget(MinecraftClient client) {
        BlockPos nearby = scanForTarget(client, TARGET_SCAN_RADIUS);
        if (nearby != null) return nearby;
        // Nothing left close by: fall back to a full scan for the next area to walk to.
        return scanForTarget(client, Integer.MAX_VALUE);
    }

    private static BlockPos scanForTarget(MinecraftClient client, int maxRadius) {
        World world = client.world;
        PlayerEntity player = client.player;
        Vec3d eye = player.getEyePos();
        Vec3d origin = player.getEntityPos();
        double reach = reach(client);
        double reachSq = reach * reach;
        double radiusSq = (double) maxRadius * maxRadius;

        BlockPos bestVisible = null;
        double bestVisibleSq = Double.MAX_VALUE;
        BlockPos bestExposed = null;
        double bestExposedSq = Double.MAX_VALUE;
        BlockPos bestAny = null;
        double bestAnySq = Double.MAX_VALUE;
        int hitTests = 0;
        // Mining the block you are standing on makes you fall; only pick it when nothing else is
        // anywhere near.
        BlockPos supportBlock = player.getBlockPos().down();

        for (BlockPos pos : remaining) {
            if (unreachable.contains(pos) || pendingBreaks.containsKey(pos)) continue;
            double dx = origin.x - (pos.getX() + 0.5);
            double dy = origin.y - (pos.getY() + 0.5);
            double dz = origin.z - (pos.getZ() + 0.5);
            double distSq = dx * dx + dy * dy + dz * dz;
            if (pos.equals(supportBlock)) distSq += 9.0;
            if (distSq > radiusSq) continue;
            if (distSq < bestAnySq) {
                bestAnySq = distSq;
                bestAny = pos;
            }
            if (distSq <= reachSq && distSq < bestVisibleSq && hitTests < MAX_HIT_TESTS) {
                hitTests++;
                if (findHit(world, player, eye, pos, reach) != null) {
                    bestVisibleSq = distSq;
                    bestVisible = pos;
                }
            }
            if (distSq < bestExposedSq && isExposed(world, pos)) {
                bestExposedSq = distSq;
                bestExposed = pos;
            }
        }
        // Closest wins; being able to hit it right away only breaks the tie in its favour.
        if (bestVisible != null && bestExposed != null) {
            return bestVisibleSq <= bestExposedSq ? bestVisible : bestExposed;
        }
        if (bestVisible != null) return bestVisible;
        if (bestExposed != null) return bestExposed;
        return bestAny;
    }

    /**
     * Time-sliced re-validation of the work list: a few hundred entries per tick instead of a
     * multi-million-lookup stall whenever the list is large.
     */
    private static boolean prune(MinecraftClient client) {
        int size = remaining.size();
        if (size == 0) return false;
        boolean changed = false;
        int budget = Math.min(PRUNE_BATCH, size);
        for (int i = 0; i < budget; i++) {
            if (pruneCursor >= remaining.size()) pruneCursor = 0;
            BlockPos pos = remaining.get(pruneCursor);
            if (pendingBreaks.containsKey(pos) || isMineable(client.world, pos)) {
                pruneCursor++;
                continue;
            }
            remaining.remove(pruneCursor);
            changed = true;
            if (pos.equals(target)) forgetTarget(client);
        }
        return changed;
    }

    /**
     * Confirms client-predicted breaks. A block that comes back (server rollback or lag) stays
     * in the work list and is simply mined again; a block that stays gone for a few seconds is
     * dropped for good.
     */
    private static void verifyPendingBreaks(MinecraftClient client) {
        if (pendingBreaks.isEmpty()) return;
        var iterator = pendingBreaks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            BlockPos pos = entry.getKey();
            if (isMineable(client.world, pos)) {
                iterator.remove();
                unreachable.remove(pos);
                pathFailures.remove(pos);
                continue;
            }
            int left = entry.getValue() - 1;
            if (left <= 0) {
                remaining.remove(pos);
                iterator.remove();
            } else {
                entry.setValue(left);
            }
        }
    }

    /** Last-resort sweep of the original selection: re-adds anything that is still there. */
    private static boolean rescanSelection(MinecraftClient client) {
        Set<BlockPos> known = new HashSet<>(remaining);
        known.addAll(pendingBreaks.keySet());
        boolean added = false;
        for (int x = areaMinX; x <= areaMaxX; x++) {
            for (int y = areaMinY; y <= areaMaxY; y++) {
                for (int z = areaMinZ; z <= areaMaxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (known.contains(pos)) continue;
                    if (!isMineable(client.world, pos)) continue;
                    remaining.add(pos);
                    added = true;
                }
            }
        }
        if (added) {
            unreachable.clear();
            pathFailures.clear();
        }
        return added;
    }

    private static void forgetTarget(MinecraftClient client) {
        if (breaking != null) cancelBreaking(client);
        target = null;
        targetTicks = 0;
        idleTicks = 0;
        equippedFor = null;
        route.clear();
        routeAge = 0;
        resetWaypointProgress();
        stallRepaths = 0;
    }

    private static void blacklist(MinecraftClient client, BlockPos pos) {
        unreachable.add(pos);
        if (pos.equals(target)) forgetTarget(client);
    }

    // ------------------------------------------------------------------ block queries

    private static boolean isMineable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        return state.getHardness(world, pos) >= 0.0f;
    }

    /** True when a neighbour is air/fluid/replaceable, i.e. the block is on an exposed surface. */
    private static boolean isExposed(World world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.offset(direction);
            if (!world.isChunkLoaded(neighbour.getX() >> 4, neighbour.getZ() >> 4)) return false;
            BlockState state = world.getBlockState(neighbour);
            if (state.isAir() || state.isReplaceable()) return true;
            if (!state.getFluidState().isEmpty()) return true;
            if (state.getCollisionShape(world, neighbour).isEmpty()) return true;
        }
        return false;
    }

    private static boolean canStand(World world, BlockPos feet) {
        if (!world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());
        BlockState floorState = world.getBlockState(feet.down());
        if (!feetState.getFluidState().isEmpty()) return false;
        if (!headState.getFluidState().isEmpty()) return false;
        if (!floorState.getFluidState().isEmpty()) return false;
        if (!isLowObstacle(world, feet, feetState)) return false;
        if (!headState.getCollisionShape(world, feet.up()).isEmpty()) return false;
        return !floorState.getCollisionShape(world, feet.down()).isEmpty();
    }

    /** Feet may share space with carpets, snow layers and similar flat blocks. */
    private static boolean isLowObstacle(World world, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return true;
        return shape.getBoundingBox().maxY <= LOW_OBSTACLE_HEIGHT;
    }

    private static BlockPos findNearestStand(World world, BlockPos origin, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dy = 1; dy >= -3; dy--) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos candidate = origin.add(dx, dy, dz);
                        if (canStand(world, candidate)) return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static double reach(MinecraftClient client) {
        return Math.max(1.0, client.player.getBlockInteractionRange() - 0.05);
    }

    // ------------------------------------------------------------------ targeting

    /**
     * Shape-aware visibility test. Samples points inside the target's outline box so that
     * non-full blocks (carpets, slabs, ...) are hittable, and requires the first hit to be the
     * target block itself.
     */
    private static BlockHitResult findHit(World world, PlayerEntity player, Vec3d eye,
                                          BlockPos pos, double reach) {
        BlockState state = world.getBlockState(pos);
        VoxelShape outline = state.getOutlineShape(world, pos);
        Box box = outline.isEmpty()
                ? new Box(pos)
                : outline.getBoundingBox().offset(pos.getX(), pos.getY(), pos.getZ());
        double reachSq = reach * reach;
        double[] fractions = {0.5, 0.15, 0.85};
        for (double fx : fractions) {
            for (double fy : fractions) {
                for (double fz : fractions) {
                    Vec3d point = new Vec3d(
                            box.minX + (box.maxX - box.minX) * fx,
                            box.minY + (box.maxY - box.minY) * fy,
                            box.minZ + (box.maxZ - box.minZ) * fz);
                    if (eye.squaredDistanceTo(point) > reachSq) continue;
                    BlockHitResult hit = world.raycast(new RaycastContext(eye, point,
                            RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
                    if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    private static boolean canHitFrom(World world, PlayerEntity player, BlockPos feet,
                                      BlockPos pos, double reach) {
        // Use the real eye position for the node the player actually occupies, so the
        // "already in reach" answer agrees with the check made in the mining loop.
        Vec3d eye = feet.equals(player.getBlockPos())
                ? player.getEyePos()
                : new Vec3d(feet.getX() + 0.5,
                        feet.getY() + player.getEyeHeight(player.getPose()),
                        feet.getZ() + 0.5);
        if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > (reach + 1.5) * (reach + 1.5)) return false;
        return findHit(world, player, eye, pos, reach) != null;
    }

    // ------------------------------------------------------------------ pathfinding

    private record PathNode(BlockPos pos, double g, double f) {
    }

    /**
     * A* over standable positions. Returns the waypoints to walk (excluding the start), or null
     * when no position within reach of {@code goal} can be found.
     */
    private static List<BlockPos> findRoute(MinecraftClient client, BlockPos goal) {
        World world = client.world;
        PlayerEntity player = client.player;
        double reach = reach(client);
        BlockPos start = player.getBlockPos().toImmutable();
        if (!canStand(world, start)) {
            BlockPos adjusted = findNearestStand(world, start, 3);
            if (adjusted == null) return null;
            start = adjusted;
        }
        if (canHitFrom(world, player, start, goal, reach)) return new ArrayList<>();

        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::f));
        best.put(start, 0.0);
        open.add(new PathNode(start, 0.0, heuristic(start, goal)));

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        long deadline = System.nanoTime() + PATH_TIME_BUDGET_NS;
        BlockPos found = null;
        int expanded = 0;

        while (!open.isEmpty() && expanded < MAX_PATH_NODES) {
            if ((++expanded & 31) == 0 && System.nanoTime() > deadline) break;
            PathNode node = open.poll();
            Double current = best.get(node.pos());
            if (current != null && node.g() > current + 1.0E-6) continue;
            if (canHitFrom(world, player, node.pos(), goal, reach)) {
                found = node.pos();
                break;
            }
            for (int[] direction : directions) {
                int x = node.pos().getX() + direction[0];
                int z = node.pos().getZ() + direction[1];
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                BlockPos level = new BlockPos(x, node.pos().getY(), z);
                BlockPos next = null;
                double stepCost = 0.0;
                if (canStand(world, level)) {
                    next = level;
                    stepCost = 1.0;
                } else if (canStand(world, level.up())) {
                    next = level.up();
                    stepCost = 1.4;
                } else {
                    for (int drop = 1; drop <= 3; drop++) {
                        BlockPos lower = level.down(drop);
                        if (canStand(world, lower)) {
                            next = lower;
                            stepCost = 1.0 + 0.15 * drop;
                            break;
                        }
                    }
                }
                if (next == null) continue;
                double cost = node.g() + stepCost;
                Double known = best.get(next);
                if (known != null && cost >= known - 1.0E-6) continue;
                best.put(next, cost);
                parent.put(next, node.pos());
                open.add(new PathNode(next, cost, cost + heuristic(next, goal)));
            }
        }

        if (found == null) return null;
        ArrayList<BlockPos> reversed = new ArrayList<>();
        for (BlockPos pos = found; !pos.equals(start); pos = parent.get(pos)) {
            reversed.add(pos);
            if (reversed.size() > MAX_PATH_NODES) break;
        }
        ArrayList<BlockPos> result = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) result.add(reversed.get(i));
        return result;
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        return Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ());
    }

    private static void followRoute(MinecraftClient client) {
        if (route.isEmpty()) {
            releaseMovement(client);
            return;
        }
        BlockPos waypoint = route.peekFirst();
        Vec3d feet = client.player.getEntityPos();
        double dx = waypoint.getX() + 0.5 - feet.x;
        double dz = waypoint.getZ() + 0.5 - feet.z;
        double dy = waypoint.getY() - feet.y;
        // Only treat the waypoint as reached when we are not still a block below it. Standing on
        // a carpet lifts the feet by 1/16, which used to make a one-block step look "reached":
        // the waypoint was dropped, the jump was never pressed and the miner stood against the
        // step forever.
        if (dx * dx + dz * dz < 0.30 && Math.abs(dy) < 0.5) {
            route.removeFirst();
            if (route.isEmpty()) {
                releaseMovement(client);
            }
            resetWaypointProgress();
            return;
        }

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        client.player.setYaw(MathHelper.wrapDegrees(yaw));
        client.player.setPitch(0.0f);
        client.player.setSprinting(false);

        if (!movementOwned) {
            savedForward = client.options.forwardKey.isPressed();
            savedJump = client.options.jumpKey.isPressed();
            savedSneak = client.options.sneakKey.isPressed();
            savedSprint = client.options.sprintKey.isPressed();
            movementOwned = true;
        }
        client.options.forwardKey.setPressed(true);
        // Jump before a step, but only when it is actually close enough to matter. A stall also
        // arms a short jump burst: most "stuck" cases are a one-block ledge the path expects us
        // to climb.
        boolean closeEnough = dx * dx + dz * dz < 4.0;
        boolean wantJump = (dy > 0.5 && closeEnough) || forceJump > 0;
        client.options.jumpKey.setPressed(wantJump && client.player.isOnGround());
        if (forceJump > 0) forceJump--;

        double distance = Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 1.5;
        if (!waypoint.equals(trackedWaypoint)) {
            trackedWaypoint = waypoint;
            lastWaypointDistance = distance;
            stalledTicks = 0;
        } else if (distance < lastWaypointDistance - 0.02) {
            lastWaypointDistance = distance;
            stalledTicks = 0;
        } else if (++stalledTicks > STALL_TICKS) {
            stalledTicks = 0;
            lastWaypointDistance = Double.POSITIVE_INFINITY;
            trackedWaypoint = null;
            forceJump = 12;
            if (++stallRepaths > MAX_STALL_REPATHS) {
                BlockPos stuck = target;
                blacklist(client, target);
                releaseMovement(client);
                if (stuck != null) {
                    client.player.sendMessage(Text.literal("走不到 " + format(stuck) + "，已跳过"), true);
                }
                return;
            }
            // Force a fresh path on the next tick.
            route.clear();
            routeAge = REPATH_INTERVAL_TICKS;
        }
    }

    private static void resetWaypointProgress() {
        trackedWaypoint = null;
        lastWaypointDistance = Double.POSITIVE_INFINITY;
        stalledTicks = 0;
    }

    // ------------------------------------------------------------------ tool selection

    /** Chooses the hotbar slot to hold while breaking {@code pos}. */
    private static void equipFor(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        if (state.isAir()) return;
        if (state.getHardness(client.world, pos) <= INSTANT_BREAK_HARDNESS) {
            equipNonTool(client);
        } else {
            equipBestTool(client, state);
        }
    }

    /**
     * Prefers an empty hand (or any non-damageable item) for blocks that break in a few ticks
     * anyway: it saves tool durability, and it guarantees the miner never carries a shears/hoe
     * picked for a carpet into the next stone block.
     */
    private static void equipNonTool(MinecraftClient client) {
        PlayerInventory inventory = client.player.getInventory();
        if (!inventory.getStack(inventory.getSelectedSlot()).isDamageable()) return;
        int empty = -1;
        int safe = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                if (empty < 0) empty = slot;
            } else if (!stack.isDamageable() && safe < 0) {
                safe = slot;
            }
        }
        if (empty >= 0) {
            inventory.setSelectedSlot(empty);
        } else if (safe >= 0) {
            inventory.setSelectedSlot(safe);
        }
    }

    /**
     * Picks the hotbar slot with the highest vanilla mining speed for {@code state}. This is the
     * same number vanilla uses for breaking progress, so Efficiency/Haste and tool tiers behave
     * exactly as if the player switched by hand.
     */
    private static void equipBestTool(MinecraftClient client, BlockState state) {
        if (state.isAir()) return;
        PlayerInventory inventory = client.player.getInventory();
        int current = inventory.getSelectedSlot();
        float bestSpeed = client.player.getBlockBreakingSpeed(state);
        int bestSlot = current;
        for (int slot = 0; slot < 9; slot++) {
            if (slot == current) continue;
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || isAboutToBreak(stack)) continue;
            inventory.setSelectedSlot(slot);
            float speed = client.player.getBlockBreakingSpeed(state);
            if (speed > bestSpeed + 1.0E-4f) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        inventory.setSelectedSlot(bestSlot);
    }

    private static boolean isAboutToBreak(ItemStack stack) {
        return stack.isDamageable() && stack.getMaxDamage() - stack.getDamage() <= 1;
    }

    private static void lookAt(PlayerEntity player, Vec3d point) {
        Vec3d eye = player.getEyePos();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        player.setYaw(MathHelper.wrapDegrees(yaw));
        player.setPitch(MathHelper.clamp(pitch, -90.0f, 90.0f));
    }

    // ------------------------------------------------------------------ input ownership

    private static void cancelBreaking(MinecraftClient client) {
        if (breaking != null) {
            client.interactionManager.cancelBlockBreaking();
            breaking = null;
        }
    }

    private static void releaseMovement(MinecraftClient client) {
        if (movementOwned) {
            client.options.forwardKey.setPressed(savedForward);
            client.options.jumpKey.setPressed(savedJump);
            client.options.sneakKey.setPressed(savedSneak);
            client.options.sprintKey.setPressed(savedSprint);
            movementOwned = false;
        }
    }

    // ------------------------------------------------------------------ rendering

    private static void renderSelection(
            net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext context) {
        if (cornerOne == null || context.matrices() == null || context.consumers() == null) return;
        int minX = cornerTwo == null ? cornerOne.getX() : Math.min(cornerOne.getX(), cornerTwo.getX());
        int maxX = cornerTwo == null ? cornerOne.getX() : Math.max(cornerOne.getX(), cornerTwo.getX());
        int minY = cornerTwo == null ? cornerOne.getY() : Math.min(cornerOne.getY(), cornerTwo.getY());
        int maxY = cornerTwo == null ? cornerOne.getY() : Math.max(cornerOne.getY(), cornerTwo.getY());
        int minZ = cornerTwo == null ? cornerOne.getZ() : Math.min(cornerOne.getZ(), cornerTwo.getZ());
        int maxZ = cornerTwo == null ? cornerOne.getZ() : Math.max(cornerOne.getZ(), cornerTwo.getZ());

        MatrixStack matrices = context.matrices();
        Vec3d camera = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer lines = context.consumers().getBuffer(RenderLayers.lines());
        VertexRendering.drawOutline(matrices, lines,
                VoxelShapes.cuboid(0.0, 0.0, 0.0,
                        maxX - minX + 1.0, maxY - minY + 1.0, maxZ - minZ + 1.0),
                minX, minY, minZ, 0xE626D9FF, 2.0f);
        if (running && target != null) {
            VertexRendering.drawOutline(matrices, lines, VoxelShapes.fullCube(),
                    target.getX(), target.getY(), target.getZ(), 0xFFFFBF33, 2.0f);
        }
        matrices.pop();
    }

    private static String format(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
