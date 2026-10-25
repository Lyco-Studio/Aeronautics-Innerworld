package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.InnerworldConfig;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillHeadEntity;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
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
            return new Rig(player, player.blockPosition().offset(4, 6, 0));
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
        if (!client.level.dimension().equals(checkpoint.dimension)) {
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
        if (entity == null && settledTicks < 100) {
            return false;
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
        if (checkpoint.dimension.equals(Level.OVERWORLD)) {
            var controller = (DrillControllerBlockEntity) client.level.getBlockEntity(checkpoint.controllerPos);
            if (controller == null || controller.getMovementDirection() != checkpoint.direction
                    || !controller.getStatus().equals("paused")) {
                throw new IllegalStateException("Client controller did not receive direction and pause state");
            }
        } else if (!entity.isRemote()) {
            throw new IllegalStateException("Mantle head lost its source controller dimension");
        }
        if (turns == 2) {
            Screenshot.grab(client.gameDirectory, "mantle-drill-check.png", client.getMainRenderTarget(),
                    message -> LogUtils.getLogger().info("Mantle arrival screenshot: {}", message.getString()));
        }
        if (turns < 4) {
            LogUtils.getLogger().info("Client drill check stage {} passed in {}", turns, checkpoint.dimension.location());
            int next = ++turns;
            sample = server.submit(() -> {
                switch (next) {
                    case 1 -> rig.turn();
                    case 2 -> rig.startCrossing();
                    case 3 -> rig.descendInMantle();
                    case 4 -> rig.turn();
                    default -> throw new IllegalStateException("Unexpected client check stage");
                }
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

    String describe() {
        return rig == null ? "waiting for setup" : rig.state;
    }

    private record Snapshot(UUID identity, Vec3 position, BlockPos controllerPos, Direction direction, ResourceKey<Level> dimension) {
    }

    private static final class Rig {
        private final ServerLevel level;
        private final BlockPos controllerPos;
        private final Vec3 origin;
        private final ServerPlayer player;
        private final Vec3 playerOrigin;
        private final GameType playerMode;
        private final boolean wasFlying;
        private final float originalYaw;
        private final float originalPitch;
        private DrillControllerBlockEntity controller;
        private UUID identity;
        private boolean horizontal;
        private int phase;
        private Integer originalBoundary;
        private Vec3 mantleOrigin;
        private boolean playerMoved;
        private final ItemStack originalHand;
        private final ItemStack remote = new ItemStack(AeronauticsInnerworld.DRILL_REMOTE.get());
        private volatile String state = "setup";

        private Rig(ServerPlayer player, BlockPos root) {
            var level = player.serverLevel();
            this.level = level;
            this.player = player;
            playerOrigin = player.position();
            playerMode = player.gameMode.getGameModeForPlayer();
            wasFlying = player.getAbilities().flying;
            originalYaw = player.getYRot();
            originalPitch = player.getXRot();
            originalHand = player.getMainHandItem().copy();
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
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            remote.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(controllerPos), Direction.UP, controllerPos, false)));
        }

        private void start() {
            controller.toggleWork();
            if (!(controller.movedContraption instanceof DrillHeadEntity)) {
                throw new IllegalStateException("Client check drill head did not assemble");
            }
            identity = controller.movedContraption.getUUID();
        }

        private Snapshot sample() {
            if (level.getBlockEntity(controllerPos) instanceof DrillControllerBlockEntity liveController) {
                controller = liveController;
            }
            var head = controller.movedContraption;
            state = "phase=" + phase + ", status=" + controller.getStatus() + ", removed=" + controller.isRemoved()
                    + ", head=" + (head == null ? "missing" : head.position() + ", tick=" + head.tickCount
                            + ", stalled=" + head.isStalled() + ", speed=" + controller.getSpeed()
                            + ", actors=" + head.getContraption().getActors().stream().map(actor -> {
                                var context = actor.getRight();
                                return context.localPos + ": " + context.stall + ", " + context.relativeMotion + ", " + context.data;
                            }).toList());
            if (head == null || !head.getUUID().equals(identity)) {
                throw new IllegalStateException("Client check lost its drill head");
            }
            if (phase == 2) {
                if (!head.level().dimension().equals(Mantle.LEVEL)) {
                    return null;
                }
                if (!playerMoved) {
                    mantleOrigin = head.position();
                    player.setGameMode(GameType.CREATIVE);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.teleportTo((ServerLevel) head.level(), head.getX() - 5, head.getY() + 2, head.getZ() + 5, 0, 0);
                    playerMoved = true;
                }
                return snapshot();
            }
            var start = phase >= 3 ? mantleOrigin : origin;
            double distance = horizontal ? head.getX() - start.x : start.y - head.getY();
            if (distance >= (phase == 3 ? 4.4 : 1.4) && controller.canWork()) {
                controller.requestPause();
            }
            return controller.getStatus().equals("paused") ? snapshot() : null;
        }

        private Snapshot snapshot() {
            var head = controller.movedContraption;
            return new Snapshot(identity, head.position(), controllerPos, controller.getMovementDirection(), head.level().dimension());
        }

        private void turn() {
            if (!controller.setMovementDirection(Direction.EAST)) {
                throw new IllegalStateException("Client check head could not turn");
            }
            horizontal = true;
            if (phase == 3) {
                phase = 4;
                mantleOrigin = controller.movedContraption.position();
            }
            controller.toggleWork();
        }

        private void startCrossing() {
            originalBoundary = InnerworldConfig.OVERWORLD_BOUNDARY.get();
            InnerworldConfig.OVERWORLD_BOUNDARY.set(controller.movedContraption.getBlockY() - 2);
            if (!controller.setMovementDirection(Direction.DOWN)) {
                throw new IllegalStateException("Client check could not select layer approach");
            }
            phase = 2;
            controller.toggleWork();
        }

        private void descendInMantle() {
            phase = 3;
            horizontal = false;
            remote.getItem().use(player.level(), player, InteractionHand.MAIN_HAND);
        }

        private void cleanUp() {
            var head = controller.movedContraption;
            if (head != null) {
                var anchor = BlockPos.containing(head.position());
                var blocks = head.getContraption().getBlocks().keySet().stream().map(anchor::offset).toList();
                var headLevel = head.level();
                if (controller.tryPlaceHead()) {
                    blocks.forEach(pos -> headLevel.removeBlock(pos, false));
                } else {
                    head.discard();
                    controller.movedContraption = null;
                    controller.disassemble();
                }
            }
            level.removeBlock(controllerPos.above(), false);
            level.removeBlock(controllerPos, false);
            if (originalBoundary != null) {
                InnerworldConfig.OVERWORLD_BOUNDARY.set(originalBoundary);
            }
            if (playerMoved) {
                player.teleportTo(level, playerOrigin.x, playerOrigin.y, playerOrigin.z, originalYaw, originalPitch);
                player.setGameMode(playerMode);
                player.getAbilities().flying = wasFlying;
                player.onUpdateAbilities();
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, originalHand);
        }
    }
}
