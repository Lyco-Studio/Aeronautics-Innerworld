package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.InnerworldConfig;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillHeadEntity;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
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
    private boolean replacingController;
    private boolean clickedRecovery;
    private CompletableFuture<Boolean> recovery;
    private CompletableFuture<Boolean> stationProgress;
    private boolean stationComplete;
    private int stationViewTicks;

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
        if (turns == 5) {
            if (stationComplete) {
                if (!client.level.dimension().equals(Mantle.LEVEL)) {
                    return false;
                }
                client.player.lookAt(Anchor.EYES, Vec3.atCenterOf(rig.destinationStation));
                return ++stationViewTicks >= 10;
            }
            if (sample != null) {
                if (!sample.isDone()) {
                    return false;
                }
                sample.join();
                sample = null;
            }
            if (stationProgress == null) {
                stationProgress = server.submit(rig::sampleTransit);
                return false;
            }
            if (!stationProgress.isDone()) {
                return false;
            }
            stationComplete = stationProgress.join();
            stationProgress = null;
            return false;
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
                || entity.getContraption() == null || entity.getContraption().getBlocks().size() != 10) {
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
        if (turns == 0) {
            Screenshot.grab(client.gameDirectory, "drill-models-check.png", client.getMainRenderTarget(),
                    message -> LogUtils.getLogger().info("Drill models screenshot: {}", message.getString()));
        }
        if (turns == 2) {
            if (!replacingController) {
                Screenshot.grab(client.gameDirectory, "mantle-drill-check.png", client.getMainRenderTarget(),
                        message -> LogUtils.getLogger().info("Mantle arrival screenshot: {}", message.getString()));
                sample = server.submit(() -> {
                    rig.replaceController();
                    return null;
                });
                replacingController = true;
                return false;
            }
            if (sample != null) {
                if (!sample.isDone()) {
                    return false;
                }
                sample.join();
                sample = null;
                settledTicks = 0;
            }
            if (settledTicks < 10 || !AllItems.WRENCH.isIn(client.player.getMainHandItem())) {
                return false;
            }
            if (!clickedRecovery) {
                KeyMapping.click(client.options.keyUse.getKey());
                clickedRecovery = true;
                return false;
            }
            if (recovery == null) {
                recovery = server.submit(() -> rig.controller.movedContraption != null);
                return false;
            }
            if (!recovery.isDone()) {
                return false;
            }
            if (!recovery.join()) {
                recovery = null;
                return false;
            }
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
        sample = server.submit(() -> {
            rig.placeStation();
            return null;
        });
        turns = 5;
        return false;
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
        private final ItemStack originalSupplies;
        private final int originalCooldown;
        private final BlockPos sourceStation;
        private final java.util.List<BlockPos> sourceBlocks;
        private BlockPos destinationStation;
        private java.util.List<BlockPos> placedBlocks;
        private int transitPhase;
        private int nextTransitTick;
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
            originalSupplies = player.getInventory().getItem(17).copy();
            originalCooldown = player.getPortalCooldown();
            while (true) {
                boolean clear = true;
                for (var pos : BlockPos.betweenClosed(root.offset(-4, -4, -3), root.offset(6, 3, 3))) {
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
            level.setBlockAndUpdate(root, Blocks.CHEST.defaultBlockState());
            for (var cutter : new BlockPos[] {root.below(), root.east()}) {
                level.setBlockAndUpdate(cutter, AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().defaultBlockState());
                level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(root, cutter)));
            }
            sourceBlocks = java.util.List.of(root, root.below(), root.east(), root.north(), root.north().below(),
                    root.east().north().below(), root.east().north().below(2), root.east(2).north().below(),
                    root.east(2).north(), root.east(2).north().above(), root.west(3), root.west(3).north().below(),
                    controllerPos, controllerPos.above());
            sourceStation = root.west(3);
            level.setBlockAndUpdate(sourceStation, AeronauticsInnerworld.BORE_STATION.get().defaultBlockState());
            level.setBlockAndUpdate(sourceStation.north().below(), Blocks.IRON_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(root.north(), AeronauticsInnerworld.BORE_STATION.get().defaultBlockState()
                    .setValue(BoreStationBlock.FACING, Direction.EAST));
            for (var pos : new BlockPos[] {root.north().below(), root.east().north().below(2),
                    root.east(2).north().below(), root.east(2).north(), root.east(2).north().above()}) {
                level.setBlockAndUpdate(pos, AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().defaultBlockState());
            }
            level.setBlockAndUpdate(root.east().north().below(), Blocks.IRON_BLOCK.defaultBlockState());
            for (var edge : new BlockPos[][] {{root, root.north()}, {root.north(), root.north().below()},
                    {root.north().below(), root.east().north().below()},
                    {root.east().north().below(), root.east().north().below(2)},
                    {root.east().north().below(), root.east(2).north().below()},
                    {root.east(2).north().below(), root.east(2).north()},
                    {root.east(2).north(), root.east(2).north().above()}}) {
                level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(edge[0], edge[1])));
            }
            ((CreativeMotorBlockEntity) level.getBlockEntity(controllerPos.above())).generatedSpeed.setValue(32);
            controller = (DrillControllerBlockEntity) level.getBlockEntity(controllerPos);
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            player.gameMode.useItemOn(player, level, remote, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(controllerPos), Direction.UP, controllerPos, false));
            for (var station : new BlockPos[] {sourceStation, root.north()}) {
                player.gameMode.useItemOn(player, level, remote, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(station), Direction.UP, station, false));
            }
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
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            remote.getItem().use(player.level(), player, InteractionHand.MAIN_HAND);
        }

        private void replaceController() {
            var head = controller.movedContraption;
            level.removeBlock(controllerPos, false);
            level.setBlockAndUpdate(controllerPos, AeronauticsInnerworld.DRILL_CONTROLLER.get().defaultBlockState()
                    .setValue(DrillControllerBlock.FACING, Direction.DOWN));
            controller = (DrillControllerBlockEntity) level.getBlockEntity(controllerPos);
            player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
            player.teleportTo((ServerLevel) head.level(), head.getX() - 2, head.getY(), head.getZ() + 2, 0, 0);
        }

        private void placeStation() {
            var head = controller.movedContraption;
            var anchor = head.blockPosition();
            placedBlocks = head.getContraption().getBlocks().keySet().stream().map(anchor::offset).toList();
            destinationStation = anchor.north();
            if (!controller.tryPlaceHead()) {
                throw new IllegalStateException("Client station assembly could not be placed");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.getInventory().setItem(17, new ItemStack(Items.COPPER_INGOT, 17));
            player.setPortalCooldown(0);
            state = "waiting for bore station pairing";
        }

        private boolean sampleTransit() {
            if (level.getServer().getTickCount() < nextTransitTick || player.isOnPortalCooldown()) {
                return false;
            }
            var mantle = level.getServer().getLevel(Mantle.LEVEL);
            var source = (BoreStationBlockEntity) level.getBlockEntity(sourceStation);
            var target = (BoreStationBlockEntity) mantle.getBlockEntity(destinationStation);
            if (source.getPartner() == null || target.getPartner() == null) {
                state = "station pairing: " + source.getPartner() + "/" + target.getPartner()
                        + "; controller=" + controller.getStatus();
                return false;
            }
            if (transitPhase < 2) {
                if (transitPhase == 1) {
                    var previous = level.getBlockState(sourceStation);
                    level.removeBlock(sourceStation, false);
                    level.setBlockAndUpdate(sourceStation, previous);
                    player.setItemInHand(InteractionHand.MAIN_HAND, remote);
                    player.gameMode.useItemOn(player, level, remote, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(sourceStation), Direction.UP, sourceStation, false));
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                }
                var world = transitPhase == 0 ? mantle : level;
                var station = transitPhase == 0 ? destinationStation : sourceStation;
                player.gameMode.useItemOn(player, world, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(station), Direction.UP, station, false));
                var expected = transitPhase == 0 ? level : mantle;
                if (player.serverLevel() != expected) {
                    throw new IllegalStateException("Client station transit failed at leg " + transitPhase
                            + "; cooldown=" + player.getPortalCooldown() + "; speed=" + controller.getSpeed()
                            + "; hand=" + player.getMainHandItem() + "; owner=" + source.getOwner()
                            + "; pair=" + source.getPartner() + "/" + target.getPartner()
                            + "; exit=" + mantle.getBlockState(destinationStation.east())
                            + "; headroom=" + mantle.getBlockState(destinationStation.east().above())
                            + "; floor=" + mantle.getBlockState(destinationStation.east().below()));
                }
                state = "bore station transit leg " + ++transitPhase;
                nextTransitTick = level.getServer().getTickCount() + 45;
                return false;
            }
            var cargo = player.getInventory().getItem(17);
            if (!cargo.is(Items.COPPER_INGOT) || cargo.getCount() != 17) {
                throw new IllegalStateException("Client station transit lost carried supplies");
            }
            LogUtils.getLogger().info("Client bore station round trip and carried supplies passed");
            return true;
        }

        private void cleanUp() {
            var head = controller.movedContraption;
            if (head == null && identity != null) {
                var mantle = level.getServer().getLevel(Mantle.LEVEL);
                if (mantle != null && mantle.getEntity(identity) instanceof DrillHeadEntity parked) {
                    parked.discard();
                }
            }
            if (head != null) {
                var anchor = BlockPos.containing(head.position());
                var blocks = head.getContraption().getBlocks().keySet().stream().map(anchor::offset).toList();
                var headLevel = head.level();
                if (controller.tryPlaceHead()) {
                    blocks.forEach(pos -> {
                        if (headLevel.getBlockEntity(pos) instanceof net.minecraft.world.Container inventory) {
                            inventory.clearContent();
                        }
                        headLevel.removeBlock(pos, false);
                    });
                } else {
                    head.discard();
                    controller.movedContraption = null;
                    controller.disassemble();
                }
            }
            if (placedBlocks != null) {
                var mantle = level.getServer().getLevel(Mantle.LEVEL);
                for (var pos : placedBlocks) {
                    if (mantle.getBlockEntity(pos) instanceof net.minecraft.world.Container inventory) {
                        inventory.clearContent();
                    }
                    mantle.removeBlock(pos, false);
                }
            }
            for (var pos : sourceBlocks) {
                if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container inventory) {
                    inventory.clearContent();
                }
                level.removeBlock(pos, false);
            }
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
            player.getInventory().setItem(17, originalSupplies);
            player.setPortalCooldown(originalCooldown);
        }
    }
}
