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
import java.util.Iterator;
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
    /** Give up on a single block after ten seconds of swinging at it. */
    private static final int MAX_TARGET_TICKS = 200;
    private static final int STALL_TICKS = 50;
    private static final int MAX_IDLE_TICKS = 100;
    private static final int MAX_PATH_FAILURES = 3;
    private static final int MAX_STALL_REPATHS = 3;
    private static final double LOW_OBSTACLE_HEIGHT = 0.6;

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
    private static BlockPos target;
    private static BlockPos breaking;
    private static int targetTicks;

    private static final ArrayDeque<BlockPos> route = new ArrayDeque<>();
    private static int routeAge;
    private static int pruneTimer;
    private static int equipTimer;

    private static boolean movementOwned;
    private static boolean savedForward;
    private static boolean savedJump;
    private static boolean savedSneak;
    private static boolean savedSprint;
    private static BlockPos trackedWaypoint;
    private static double lastWaypointDistance = Double.POSITIVE_INFINITY;
    private static int stalledTicks;
    private static int stallRepaths;
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
        target = null;
        breaking = null;
        targetTicks = 0;
        route.clear();
        routeAge = 0;
        pruneTimer = 0;
        equipTimer = 0;
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

        // Keep the work list in sync with the world: cheap per-tick check for the current
        // target, full sweep only occasionally.
        if (target != null && !isMineable(client.world, target)) {
            remaining.remove(target);
            forgetTarget(client);
            unreachable.clear();
            pathFailures.clear();
        }
        if (--pruneTimer <= 0) {
            pruneTimer = 20;
            if (prune(client)) {
                unreachable.clear();
                pathFailures.clear();
            }
        }
        if (remaining.isEmpty()) {
            stop(client, "区域挖掘完成");
            return;
        }

        if (target == null) {
            target = chooseTarget(client);
            equipTimer = 0;
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
            stallRepaths = 0;
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
        if (equipTimer-- <= 0) {
            equipBestTool(client, client.world.getBlockState(target));
            equipTimer = 20;
        }

        Direction side = hit.getSide();
        if (!target.equals(breaking)) {
            cancelBreaking(client);
            targetTicks = 0;
            if (client.interactionManager.attackBlock(target, side)) breaking = target;
        }
        if (target.equals(breaking)) {
            client.interactionManager.updateBlockBreakingProgress(target, side);
        }
        if (++targetTicks > MAX_TARGET_TICKS) {
            // Something refuses to break (claim protection, wrong tool, ...). Skip it.
            blacklist(client, target);
        }
    }

    /** Picks the next block to work on: nearest visible one, else nearest exposed one. */
    private static BlockPos chooseTarget(MinecraftClient client) {
        World world = client.world;
        PlayerEntity player = client.player;
        Vec3d eye = player.getEyePos();
        double reach = reach(client);
        double reachSq = reach * reach;

        BlockPos bestVisible = null;
        double bestVisibleSq = Double.MAX_VALUE;
        BlockPos bestExposed = null;
        double bestExposedSq = Double.MAX_VALUE;
        BlockPos bestAny = null;
        double bestAnySq = Double.MAX_VALUE;

        for (BlockPos pos : remaining) {
            if (unreachable.contains(pos)) continue;
            double distSq = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (distSq < bestAnySq) {
                bestAnySq = distSq;
                bestAny = pos;
            }
            if (distSq <= reachSq && distSq < bestVisibleSq
                    && findHit(world, player, eye, pos, reach) != null) {
                bestVisibleSq = distSq;
                bestVisible = pos;
            }
            if (distSq < bestExposedSq && isExposed(world, pos)) {
                bestExposedSq = distSq;
                bestExposed = pos;
            }
        }
        if (bestVisible != null) return bestVisible;
        if (bestExposed != null) return bestExposed;
        return bestAny;
    }

    private static boolean prune(MinecraftClient client) {
        boolean changed = false;
        Iterator<BlockPos> iterator = remaining.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (isMineable(client.world, pos)) continue;
            iterator.remove();
            changed = true;
            if (pos.equals(target)) forgetTarget(client);
        }
        return changed;
    }

    private static void forgetTarget(MinecraftClient client) {
        if (breaking != null) cancelBreaking(client);
        target = null;
        targetTicks = 0;
        idleTicks = 0;
        route.clear();
        routeAge = 0;
        resetWaypointProgress();
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
        if (dx * dx + dz * dz < 0.30 && Math.abs(dy) < 1.1) {
            route.removeFirst();
            releaseMovement(client);
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
        client.options.jumpKey.setPressed(dy > 0.5 && client.player.isOnGround());

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
            if (++stallRepaths > MAX_STALL_REPATHS) {
                blacklist(client, target);
                releaseMovement(client);
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
