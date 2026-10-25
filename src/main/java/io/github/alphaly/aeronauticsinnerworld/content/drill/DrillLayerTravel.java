package io.github.alphaly.aeronauticsinnerworld.content.drill;

import io.github.alphaly.aeronauticsinnerworld.InnerworldConfig;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.Comparator;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class DrillLayerTravel {
    static final TicketType<BlockPos> CHUNK_TICKET = TicketType.create("innerworld_drill",
            Comparator.comparingLong(BlockPos::asLong), 40);

    static boolean tryCross(DrillControllerBlockEntity controller, DrillHeadEntity head) {
        var level = (ServerLevel) head.level();
        var direction = controller.getMovementDirection();
        var blocks = head.getContraption().getBlocks().keySet();
        int minY = blocks.stream().mapToInt(BlockPos::getY).min().orElse(0);
        int maxY = blocks.stream().mapToInt(BlockPos::getY).max().orElse(0);
        boolean entering = level.dimension().equals(Level.OVERWORLD) && direction == Direction.DOWN
                && head.getY() + minY <= InnerworldConfig.OVERWORLD_BOUNDARY.get();
        boolean returning = level.dimension().equals(Mantle.LEVEL) && direction == Direction.UP
                && head.getY() + maxY >= level.getMaxBuildHeight();
        if (!entering && !returning) {
            return false;
        }
        controller.pauseForLayer("layer_blocked");
        var target = level.getServer().getLevel(entering ? Mantle.LEVEL : Level.OVERWORLD);
        if (target == null) {
            controller.pauseForLayer("layer_unavailable");
            return true;
        }
        if (head.isVehicle() || maxY - minY >= 64 || (!entering && head.getReturnPosition() == null)) {
            controller.pauseForLayer("layer_ineligible");
            return true;
        }
        var anchor = entering ? new BlockPos(head.getBlockX(), target.getMaxBuildHeight() - minY, head.getBlockZ())
                : new BlockPos(head.getBlockX(), head.getReturnPosition().getY(), head.getBlockZ());
        var chunks = new HashSet<ChunkPos>();
        for (var pos : blocks) {
            var destination = anchor.offset(pos);
            if (!target.getWorldBorder().isWithinBounds(destination)
                    || (!entering && target.isOutsideBuildHeight(destination))) {
                return true;
            }
            chunks.add(new ChunkPos(destination));
        }
        if (chunks.size() > InnerworldConfig.MAX_TRANSFER_CHUNKS.get()) {
            controller.pauseForLayer("layer_ineligible");
            return true;
        }
        for (var chunk : chunks) {
            target.getChunk(chunk.x, chunk.z);
        }
        for (var pos : blocks) {
            var destination = anchor.offset(pos);
            if (!target.getBlockState(destination).canBeReplaced() || !target.getFluidState(destination).isEmpty()) {
                return true;
            }
        }
        var destinationBounds = head.getContraption().bounds.move(Vec3.atLowerCornerOf(anchor));
        if (!target.getEntities((net.minecraft.world.entity.Entity) null, destinationBounds,
                entity -> entity.isAlive() && !entity.isSpectator()).isEmpty()
                || target.getEntity(head.getUUID()) != null) {
            return true;
        }
        head.getContraption().stop(level);
        var data = new CompoundTag();
        if (!head.save(data)) {
            controller.pauseForLayer("layer_unavailable");
            return true;
        }
        var restored = EntityType.loadEntityRecursive(data, target, entity -> entity);
        if (!(restored instanceof DrillHeadEntity transferred)) {
            controller.pauseForLayer("layer_unavailable");
            return true;
        }
        transferred.bindController(GlobalPos.of(controller.getLevel().dimension(), controller.getBlockPos()));
        if (entering) {
            transferred.setReturnPosition(head.blockPosition());
        }
        transferred.setPos(Vec3.atLowerCornerOf(anchor));
        transferred.setContraptionMotion(Vec3.ZERO);
        resetActors(transferred);
        if (!target.addFreshEntity(transferred)) {
            controller.pauseForLayer("layer_unavailable");
            return true;
        }
        head.discard();
        controller.acceptTransferredHead(transferred);
        return true;
    }

    static void resetActors(DrillHeadEntity head) {
        head.getContraption().forEachActor(head.level(), (behaviour, context) -> {
            behaviour.stopMoving(context);
            context.position = null;
            context.motion = Vec3.ZERO;
            context.relativeMotion = Vec3.ZERO;
            context.firstMovement = true;
            context.stall = false;
            behaviour.startMoving(context);
        });
        head.getContraption().stalled = false;
    }

    private DrillLayerTravel() {
    }
}
