package io.github.alphaly.aeronauticsinnerworld.integration.client;

import java.util.ArrayDeque;
import java.util.HashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class CoreMazeWalk {
    private final ArrayDeque<BlockPos> steps = new ArrayDeque<>();
    private final BlockPos control;
    private final boolean interact;
    private final boolean stepsAllowed;
    private volatile BlockPos target;
    private boolean used;

    CoreMazeWalk(ServerLevel level, BoundingBox bounds, BlockPos from, BlockPos control) {
        this(level, bounds, from, control, true);
    }

    CoreMazeWalk(ServerLevel level, BoundingBox bounds, BlockPos from, BlockPos control, boolean interact) {
        this(level, bounds, from, control, interact, false);
    }

    CoreMazeWalk(ServerLevel level, BoundingBox bounds, BlockPos from, BlockPos control, boolean interact, boolean stepsAllowed) {
        this(level,bounds,from,control,interact,stepsAllowed,false);
    }

    CoreMazeWalk(ServerLevel level, BoundingBox bounds, BlockPos from, BlockPos control, boolean interact, boolean stepsAllowed, boolean avoidHazards) {
        this.control = control;this.interact = interact;
        this.stepsAllowed = stepsAllowed;
        var parents = new HashMap<BlockPos, BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        parents.put(from, from);queue.add(from);
        BlockPos end = null;
        while (!queue.isEmpty()) {
            var pos = queue.remove();
            if (interact ? pos.distManhattan(control) == 1 : pos.equals(control)) {end = pos;break;}
            for (var direction : Direction.Plane.HORIZONTAL) {
                for (int height : stepsAllowed ? new int[] {0, 1, -1} : new int[] {0}) {
                    var next = pos.relative(direction).above(height);
                    if (!bounds.isInside(next) || parents.containsKey(next)
                            || !level.getBlockState(next).getCollisionShape(level, next).isEmpty()
                            || !level.getBlockState(next.above()).getCollisionShape(level, next.above()).isEmpty()
                            || !level.getBlockState(next.below()).isFaceSturdy(level, next.below(), Direction.UP)
                            || avoidHazards&&(!level.getFluidState(next).isEmpty()||!level.getFluidState(next.above()).isEmpty()
                                    ||level.getBlockState(next.below()).is(Blocks.MAGMA_BLOCK))) continue;
                    if (height > 0 && !level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty()) continue;
                    if (height < 0 && !level.getBlockState(next.above(2)).getCollisionShape(level, next.above(2)).isEmpty()) continue;
                    parents.put(next, pos);queue.add(next);
                }
            }
        }
        if (end == null) throw new IllegalStateException("No walking approach to core control " + control);
        while (!end.equals(from)) {steps.addFirst(end);end = parents.get(end);}
        target = steps.poll();
    }

    void advance(Vec3 position) {
        if (target != null && position.distanceToSqr(Vec3.atBottomCenterOf(target)) < .12) target = steps.poll();
    }

    boolean arrived() {return target == null;}

    void tickClient(Minecraft client) {
        var next = target;
        client.options.keyDown.setDown(false);
        client.options.keyUse.setDown(false);
        if (client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);
        if (next != null) {
            var offset = Vec3.atBottomCenterOf(next).subtract(client.player.position());
            client.player.setYRot((float)Math.toDegrees(Math.atan2(-offset.x, offset.z)));
            client.player.setXRot(0);
            if (stepsAllowed) client.options.keyShift.setDown(false);
            client.options.keyUp.setDown(offset.horizontalDistanceSqr() > .07);
            client.options.keyJump.setDown(offset.y > .6);
            return;
        }
        client.options.keyUp.setDown(false);
        client.options.keyJump.setDown(false);
        if (stepsAllowed) {
            client.options.keyShift.setDown(false);
            if (client.player.isShiftKeyDown()) {
                client.player.setShiftKeyDown(false);
                client.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(client.player,
                        net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            }
        }
        if (!interact || used) return;
        client.player.getInventory().selected = 2;
        var point = new Vec3(control.getX() + .5, control.getY() + .15, control.getZ() + .5);
        var eye = client.player.getEyePosition();
        var offset = point.subtract(eye);
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-offset.x, offset.z)));
        client.player.setXRot((float)-Math.toDegrees(Math.atan2(offset.y, offset.horizontalDistance())));
        var hit = client.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(control) || offset.lengthSqr() > 4.5 * 4.5)
            throw new IllegalStateException("Core control cannot be reached from its walking approach: " + control + ", hit=" + hit.getBlockPos());
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        used = true;
    }
}
