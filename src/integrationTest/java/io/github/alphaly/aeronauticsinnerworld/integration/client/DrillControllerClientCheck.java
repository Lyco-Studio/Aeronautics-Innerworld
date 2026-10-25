package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillHeadEntity;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

final class DrillControllerClientCheck {
    private final CompletableFuture<Rig> setup;
    private CompletableFuture<Snapshot> sample;
    private Snapshot checkpoint;
    private Rig rig;
    private int settledTicks;
    private int turns;
    private boolean started;

    DrillControllerClientCheck(Minecraft client) {
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        setup = server.submit(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            return new Rig(player.serverLevel(), player.blockPosition().offset(4, 6, 0));
        });
    }

    boolean tick(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (rig == null) {
            if (!setup.isDone()) {
                return false;
            }
            rig = setup.join();
        }
        if (!started) {
            if (client.level.getBlockEntity(rig.controllerPos) == null) {
                return false;
            }
            sample = server.submit(() -> {
                rig.start();
                return null;
            });
            started = true;
            return false;
        }
        if (checkpoint == null) {
            if (sample == null) {
                sample = server.submit(rig::sample);
            } else if (sample.isDone()) {
                checkpoint = sample.join();
                sample = null;
                settledTicks = 0;
            }
            return false;
        }
        client.player.lookAt(Anchor.EYES, checkpoint.position.add(0.5, 0.5, 0.5));
        if (++settledTicks < 10) {
            return false;
        }
        var head = client.level.entitiesForRendering().iterator();
        DrillHeadEntity entity = null;
        while (head.hasNext()) {
            var candidate = head.next();
            if (candidate.getUUID().equals(checkpoint.identity)) {
                entity = (DrillHeadEntity) candidate;
                break;
            }
        }
        if (entity == null || entity.position().distanceTo(checkpoint.position) > 0.01) {
            throw new IllegalStateException("Client head position did not match the paused server head: expected "
                    + checkpoint + "; actual " + (entity == null ? "missing" : entity.position()));
        }
        if (!(client.getEntityRenderDispatcher().getRenderer(entity) instanceof ContraptionEntityRenderer)
                || VisualizerRegistry.getVisualizer(AeronauticsInnerworld.DRILL_HEAD_ENTITY.get()) == null
                || entity.getContraption() == null || entity.getContraption().getBlocks().size() != 3) {
            throw new IllegalStateException("Client head renderer or structure was not restored");
        }
        var controller = (DrillControllerBlockEntity) client.level.getBlockEntity(checkpoint.controllerPos);
        if (controller == null || controller.getMovementDirection() != checkpoint.direction
                || !controller.getStatus().equals("paused")) {
            throw new IllegalStateException("Client controller did not receive direction and pause state");
        }
        if (turns++ == 0) {
            sample = server.submit(() -> {
                rig.turn();
                return null;
            });
            checkpoint = null;
            return false;
        }
        return true;
    }

    CompletableFuture<Void> cleanUp(Minecraft client) {
        return rig == null ? CompletableFuture.completedFuture(null)
                : client.getSingleplayerServer().submit(rig::cleanUp);
    }

    private record Snapshot(UUID identity, Vec3 position, BlockPos controllerPos, Direction direction) {
    }

    private static final class Rig {
        private final ServerLevel level;
        private final BlockPos controllerPos;
        private final Vec3 origin;
        private DrillControllerBlockEntity controller;
        private UUID identity;
        private boolean horizontal;

        private Rig(ServerLevel level, BlockPos root) {
            this.level = level;
            while (true) {
                boolean clear = true;
                for (var pos : BlockPos.betweenClosed(root.offset(-1, -4, -2), root.offset(5, 2, 2))) {
                    if (!level.isEmptyBlock(pos)) {
                        clear = false;
                        break;
                    }
                }
                if (clear) {
                    break;
                }
                root = root.above(4);
                if (root.getY() + 2 >= level.getMaxBuildHeight()) {
                    throw new IllegalStateException("Client drill check could not find empty space near the player");
                }
            }
            controllerPos = root.above();
            origin = Vec3.atLowerCornerOf(root);
            level.setBlockAndUpdate(controllerPos, AeronauticsInnerworld.DRILL_CONTROLLER.get().defaultBlockState()
                    .setValue(DrillControllerBlock.FACING, Direction.DOWN));
            level.setBlockAndUpdate(controllerPos.above(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(BlockStateProperties.FACING, Direction.DOWN));
            level.setBlockAndUpdate(root, Blocks.SLIME_BLOCK.defaultBlockState());
            for (var cutter : new BlockPos[] {root.below(), root.east()}) {
                level.setBlockAndUpdate(cutter, AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().defaultBlockState());
                level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(root, cutter)));
            }
            ((CreativeMotorBlockEntity) level.getBlockEntity(controllerPos.above())).generatedSpeed.setValue(32);
            controller = (DrillControllerBlockEntity) level.getBlockEntity(controllerPos);
        }

        private void start() {
            controller.toggleWork();
            if (!(controller.movedContraption instanceof DrillHeadEntity)) {
                throw new IllegalStateException("Client check drill head did not assemble");
            }
            identity = controller.movedContraption.getUUID();
        }

        private Snapshot sample() {
            var head = controller.movedContraption;
            if (head == null || !head.getUUID().equals(identity)) {
                throw new IllegalStateException("Client check lost its drill head");
            }
            double distance = horizontal ? head.getX() - origin.x : origin.y - head.getY();
            if (distance >= 1.4 && controller.canWork()) {
                controller.requestPause();
            }
            return controller.getStatus().equals("paused")
                    ? new Snapshot(identity, head.position(), controllerPos, controller.getMovementDirection()) : null;
        }

        private void turn() {
            if (!controller.setMovementDirection(Direction.EAST)) {
                throw new IllegalStateException("Client check head could not turn");
            }
            horizontal = true;
            controller.toggleWork();
        }

        private void cleanUp() {
            var head = controller.movedContraption;
            var anchor = BlockPos.containing(head.position());
            var blocks = head.getContraption().getBlocks().keySet().stream().map(anchor::offset).toList();
            if (!controller.tryPlaceHead()) {
                throw new IllegalStateException("Client check head could not be placed for cleanup");
            }
            blocks.forEach(pos -> level.removeBlock(pos, false));
            level.removeBlock(controllerPos.above(), false);
            level.removeBlock(controllerPos, false);
        }
    }
}
