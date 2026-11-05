package dev.taskwork.areaminer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.block.BlockState;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client-only area miner. All block breaking goes through the normal interaction manager. */
public final class AreaMinerClient implements ClientModInitializer {
    private static final int MAX_BLOCKS = 32_768;
    private static final int MAX_PATH_NODES = 2_000;
    private static final double SURVIVAL_REACH = 4.45;
    private static final double CREATIVE_REACH = 5.0;

    private static KeyBinding selectCornerKey;
    private static KeyBinding startStopKey;

    private static BlockPos cornerOne;
    private static BlockPos cornerTwo;
    private static boolean setSecondNext;
    private static boolean running;
    private static boolean warnedPathFailure;
    private static final ArrayDeque<BlockPos> targets = new ArrayDeque<>();
    private static final ArrayDeque<BlockPos> route = new ArrayDeque<>();
    private static BlockPos routeTarget;
    private static BlockPos breakingTarget;
    private static boolean movementKeysOwned;
    private static boolean savedForwardPressed;
    private static boolean savedJumpPressed;
    private static BlockPos trackedWaypoint;
    private static double lastWaypointDistance = Double.POSITIVE_INFINITY;
    private static int stalledTicks;
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("areaminer", "controls"));
    private static RegistryKey<World> selectionDimension;

    @Override
    public void onInitializeClient() {
        selectCornerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.areaminer.select_corner", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z,
                CATEGORY));
        startStopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.areaminer.start_stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8,
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(AreaMinerClient::tick);
        WorldRenderEvents.END_MAIN.register(AreaMinerClient::renderSelection);
    }

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
        if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
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

        targets.clear();
        route.clear();
        routeTarget = null;
        breakingTarget = null;
        warnedPathFailure = false;

        int minX = Math.min(cornerOne.getX(), cornerTwo.getX());
        int maxX = Math.max(cornerOne.getX(), cornerTwo.getX());
        int minY = Math.min(cornerOne.getY(), cornerTwo.getY());
        int maxY = Math.max(cornerOne.getY(), cornerTwo.getY());
        int minZ = Math.min(cornerOne.getZ(), cornerTwo.getZ());
        int maxZ = Math.max(cornerOne.getZ(), cornerTwo.getZ());

        // Bottom-up ordering helps expose blocks that were hidden behind lower layers.
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isMineable(client.world, pos)) targets.addLast(pos);
                }
            }
        }
        if (targets.isEmpty()) {
            client.player.sendMessage(Text.literal("选区内没有可挖掘的方块"), true);
            return;
        }

        running = true;
        client.player.sendMessage(Text.literal("区域挖掘已启动：" + targets.size()
                + " 个方块。按 F8 随时停止；移动路径仅适用于已加载的安全地面"), true);
    }

    private static boolean isMineable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty() && state.getHardness(world, pos) >= 0.0f;
    }

    private static void mineTick(MinecraftClient client) {
        if (client.currentScreen != null) {
            cancelBreaking(client);
            releaseMovement(client);
            return;
        }
        while (!targets.isEmpty() && !isMineable(client.world, targets.peekFirst())) {
            BlockPos removed = targets.removeFirst();
            if (removed.equals(breakingTarget)) breakingTarget = null;
        }
        if (targets.isEmpty()) {
            stop(client, "区域挖掘完成");
            return;
        }

        BlockPos target = targets.peekFirst();
        Vec3d eye = client.player.getEyePos();
        double reach = client.player.isCreative() ? CREATIVE_REACH : SURVIVAL_REACH;
        BlockHitResult visibleHit = findVisibleHit(client.world, client.player, eye, target, reach);
        if (visibleHit == null) {
            BlockPos exposedTarget = null;
            BlockHitResult exposedHit = null;
            double nearestDistance = Double.MAX_VALUE;
            double candidateRange = reach + 0.9;
            for (BlockPos candidate : targets) {
                double distance = eye.squaredDistanceTo(Vec3d.ofCenter(candidate));
                if (distance >= nearestDistance || distance > candidateRange * candidateRange) continue;
                BlockHitResult candidateHit = findVisibleHit(client.world, client.player, eye, candidate, reach);
                if (candidateHit != null) {
                    exposedTarget = candidate;
                    exposedHit = candidateHit;
                    nearestDistance = distance;
                }
            }
            if (exposedTarget != null) {
                targets.remove(exposedTarget);
                targets.addFirst(exposedTarget);
                target = exposedTarget;
                visibleHit = exposedHit;
            }
        }
        if (visibleHit == null) {
            cancelBreaking(client);
            if (!target.equals(routeTarget) || route.isEmpty()) {
                routeTarget = target;
                route.clear();
                List<BlockPos> found = findRoute(client, target, reach);
                if (found == null) {
                    if (!warnedPathFailure) {
                        client.player.sendMessage(Text.literal("找不到通往目标的安全地面路径，已自动暂停。请靠近目标或检查区块是否已加载，再按 F8 重试"), true);
                        warnedPathFailure = true;
                    }
                    stop(client, null);
                    return;
                }
                route.addAll(found);
                resetWaypointProgress();
            }
            followRoute(client);
            return;
        }

        releaseMovement(client);
        route.clear();
        routeTarget = null;
        warnedPathFailure = false;
        aimAt(client, visibleHit.getPos());

        // Use the same world raycast/face and interaction-manager calls as ordinary mining.
        // Wait until the player's actual view ray hits this exact block before starting.
        BlockHitResult hit = raycastBlock(client.world, client.player, client.player.getEyePos(),
                client.player.getRotationVec(1.0f), reach);
        if (hit == null || !hit.getBlockPos().equals(target)) {
            cancelBreaking(client);
            return;
        }
        if (!target.equals(breakingTarget)) {
            cancelBreaking(client);
            client.interactionManager.attackBlock(target, hit.getSide());
            breakingTarget = target;
        }
        client.interactionManager.updateBlockBreakingProgress(target, hit.getSide());
    }

    private static void cancelBreaking(MinecraftClient client) {
        if (breakingTarget != null && client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
            breakingTarget = null;
        }
    }

    private static void followRoute(MinecraftClient client) {
        if (route.isEmpty()) {
            releaseMovement(client);
            resetWaypointProgress();
            // Rebuild from the new player position next tick, allowing small steering corrections.
            routeTarget = null;
            return;
        }

        BlockPos waypoint = route.peekFirst();
        Vec3d feet = client.player.getEntityPos();
        Vec3d destination = new Vec3d(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5);
        double dx = destination.x - feet.x;
        double dy = destination.y - feet.y;
        double dz = destination.z - feet.z;
        if (dx * dx + dz * dz < 0.16 && Math.abs(dy) < 0.8) {
            route.removeFirst();
            releaseMovement(client);
            resetWaypointProgress();
            routeTarget = null;
            return;
        }

        double distance = Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 1.5;
        if (!waypoint.equals(trackedWaypoint)) {
            trackedWaypoint = waypoint;
            lastWaypointDistance = distance;
            stalledTicks = 0;
        } else if (distance < lastWaypointDistance - 0.02) {
            lastWaypointDistance = distance;
            stalledTicks = 0;
        } else if (++stalledTicks >= 100) {
            client.player.sendMessage(Text.literal("移動路點持續無進展，已停止區域挖掘"), true);
            stop(client, null);
            return;
        }

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        client.player.setYaw(yaw);
        client.player.setPitch(0.0f);
        if (!movementKeysOwned) {
            savedForwardPressed = client.options.forwardKey.isPressed();
            savedJumpPressed = client.options.jumpKey.isPressed();
        }
        client.options.forwardKey.setPressed(true);
        boolean stepUp = waypoint.getY() > client.player.getBlockY();
        client.options.jumpKey.setPressed(stepUp);
        movementKeysOwned = true;
    }

    private static List<BlockPos> findRoute(MinecraftClient client, BlockPos target, double reach) {
        BlockPos start = client.player.getBlockPos().toImmutable();
        if (!canStand(client.world, start)) {
            start = findNearestStand(client.world, start);
            if (start == null) return null;
        }

        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        open.add(start);
        parent.put(start, start);
        BlockPos goal = null;
        int visited = 0;
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};

        while (!open.isEmpty() && visited++ < MAX_PATH_NODES) {
            BlockPos current = open.removeFirst();
            if (canReachTarget(client.world, client.player, current, target, reach)) {
                goal = current;
                break;
            }
            for (int i = 0; i < dx.length; i++) {
                int x = current.getX() + dx[i];
                int z = current.getZ() + dz[i];
                if (!client.world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = current.getY() + 1; y >= current.getY() - 1; y--) {
                    BlockPos next = new BlockPos(x, y, z);
                    if (!parent.containsKey(next) && canStand(client.world, next)) {
                        parent.put(next, current);
                        open.addLast(next);
                        break;
                    }
                }
            }
        }
        if (goal == null) return null;

        ArrayList<BlockPos> reversed = new ArrayList<>();
        for (BlockPos pos = goal; !pos.equals(start); pos = parent.get(pos)) reversed.add(pos);
        ArrayList<BlockPos> result = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) result.add(reversed.get(i));
        return result;
    }

    private static BlockPos findNearestStand(World world, BlockPos origin) {
        for (int radius = 0; radius <= 2; radius++) {
            for (int dy = 1; dy >= -2; dy--) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                        BlockPos candidate = origin.add(x, dy, z);
                        if (canStand(world, candidate)) return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static boolean canStand(World world, BlockPos feet) {
        if (!world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        BlockPos head = feet.up();
        BlockPos floor = feet.down();
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        BlockState floorState = world.getBlockState(floor);
        return feetState.getFluidState().isEmpty() && headState.getFluidState().isEmpty()
                && feetState.getCollisionShape(world, feet).isEmpty()
                && headState.getCollisionShape(world, head).isEmpty()
                && !floorState.getCollisionShape(world, floor).isEmpty()
                && floorState.getFluidState().isEmpty();
    }

    private static boolean canReachTarget(World world, net.minecraft.entity.player.PlayerEntity player,
                                          BlockPos feet, BlockPos target, double reach) {
        Vec3d eye = new Vec3d(feet.getX() + 0.5, feet.getY() + player.getEyeHeight(player.getPose()), feet.getZ() + 0.5);
        return findVisibleHit(world, player, eye, target, reach) != null;
    }

    private static BlockHitResult findVisibleHit(World world, net.minecraft.entity.player.PlayerEntity player,
                                                 Vec3d eye, BlockPos target, double reach) {
        double[] samples = {0.5, 0.2, 0.8};
        for (double x : samples) {
            for (double y : samples) {
                for (double z : samples) {
                    Vec3d point = new Vec3d(target.getX() + x, target.getY() + y, target.getZ() + z);
                    if (eye.squaredDistanceTo(point) > reach * reach) continue;
                    BlockHitResult hit = world.raycast(new RaycastContext(eye, point,
                            RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
                    if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return hit;
                }
            }
        }
        return null;
    }

    private static BlockHitResult raycastBlock(World world, net.minecraft.entity.player.PlayerEntity player,
                                                Vec3d eye, Vec3d rotation, double reach) {
        Vec3d end = eye.add(rotation.multiply(reach));
        HitResult hit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        return hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK ? blockHit : null;
    }

    private static void aimAt(MinecraftClient client, Vec3d target) {
        Vec3d eye = client.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        client.player.setYaw(MathHelper.wrapDegrees(yaw));
        client.player.setPitch(MathHelper.clamp(pitch, -90.0f, 90.0f));
    }

    private static void resetWaypointProgress() {
        trackedWaypoint = null;
        lastWaypointDistance = Double.POSITIVE_INFINITY;
        stalledTicks = 0;
    }

    private static void releaseMovement(MinecraftClient client) {
        if (movementKeysOwned && client.options != null) {
            client.options.forwardKey.setPressed(savedForwardPressed);
            client.options.jumpKey.setPressed(savedJumpPressed);
        }
        movementKeysOwned = false;
    }

    private static void stop(MinecraftClient client, String message) {
        running = false;
        cancelBreaking(client);
        releaseMovement(client);
        route.clear();
        routeTarget = null;
        resetWaypointProgress();
        targets.clear();
        if (message != null && client.player != null) client.player.sendMessage(Text.literal(message), true);
    }

    private static void renderSelection(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext context) {
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
                VoxelShapes.cuboid(0.0, 0.0, 0.0, maxX - minX + 1.0, maxY - minY + 1.0, maxZ - minZ + 1.0),
                minX, minY, minZ, 0xE626D9FF, 2.0f);
        if (running && !targets.isEmpty()) {
            BlockPos current = targets.peekFirst();
            VertexRendering.drawOutline(matrices, lines, VoxelShapes.fullCube(),
                    current.getX(), current.getY(), current.getZ(), 0xFFFFBF33, 2.0f);
        }
        matrices.pop();
    }

    private static String format(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
