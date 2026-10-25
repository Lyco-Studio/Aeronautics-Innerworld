package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillHeadEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks")
public final class DrillLifecycleCheck {
    private static final BlockPos CONTROLLER = new BlockPos(4096, -55, 4096);
    private static final TicketType<BlockPos> TICKET = TicketType.create("innerworld_lifecycle_check",
            Comparator.comparingLong(BlockPos::asLong), 40);
    private static State state;
    private static int stage;
    private static int ticks;
    private static boolean sourceLease;
    private static int builtAt;

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("innerworld.lifecycleCheck")) {
            return;
        }
        var server = event.getServer();
        state = server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(State::new, State::load), "innerworld_lifecycle_check");
        require(!state.complete, "Lifecycle check already completed; use a fresh validation world");
        stage = state.inMotion ? 20 : state.ready ? 10 : 0;
        ticks = 0;
        builtAt = -1;
        sourceLease = true;
        server.tickRateManager().setTickRate(200);
        LogUtils.getLogger().info("Drill lifecycle check starting: {}",
                state.inMotion ? "working restart" : state.ready ? "parked restart" : "prepare and unload");
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (state == null) {
            return;
        }
        var server = event.getServer();
        var source = server.overworld();
        var mantle = server.getLevel(Mantle.LEVEL);
        require(mantle != null, "Mantle dimension is missing");
        require(++ticks < 3000, "Lifecycle timeout at stage " + stage);
        source.resetEmptyTime();
        mantle.resetEmptyTime();
        if (sourceLease) {
            source.getChunkSource().addRegionTicket(TICKET, new ChunkPos(CONTROLLER), 2, CONTROLLER);
            source.getChunkAt(CONTROLLER);
        }
        if (stage == 3) {
            mantle.getChunkSource().addRegionTicket(TICKET, new ChunkPos(state.position), 2, CONTROLLER);
        }
        switch (stage) {
            case 0 -> {
                if (builtAt < 0 && source.areEntitiesLoaded(new ChunkPos(CONTROLLER).toLong())
                        && source.isPositionEntityTicking(CONTROLLER)) {
                    build(source);
                    builtAt = ticks;
                }
                if (builtAt >= 0 && ticks >= builtAt + 5) {
                    require(source.getEntitiesOfClass(SuperGlueEntity.class,
                            new net.minecraft.world.phys.AABB(CONTROLLER).inflate(5)).size() == 4, "Fixture glue is not queryable");
                    controller(source).toggleWork();
                    state.identity = controller(source).movedContraption.getUUID();
                    stage = 1;
                }
            }
            case 1 -> {
                if (controller(source).movedContraption instanceof DrillHeadEntity head && head.isRemote()) {
                    state.position = head.blockPosition();
                    assertHead(head);
                    releaseSource(source);
                    stage = 2;
                }
            }
            case 2 -> {
                if (!source.isLoaded(CONTROLLER) && !source.areEntitiesLoaded(new ChunkPos(CONTROLLER).toLong())
                        && !mantle.areEntitiesLoaded(new ChunkPos(state.position).toLong())) {
                    sourceLease = true;
                    stage = 4;
                    LogUtils.getLogger().info("Drill lifecycle: both source and destination unloaded");
                }
            }
            case 4 -> {
                if (controller(source).movedContraption instanceof DrillHeadEntity head) {
                    assertHead(head);
                    mantle.getChunkSource().addRegionTicket(TICKET, new ChunkPos(state.position), 2, CONTROLLER);
                    releaseSource(source);
                    stage = 3;
                }
            }
            case 3 -> {
                if (!source.isLoaded(CONTROLLER) && !source.areEntitiesLoaded(new ChunkPos(CONTROLLER).toLong())
                        && mantle.getEntity(state.identity) instanceof DrillHeadEntity head) {
                    assertHead(head);
                    sourceLease = true;
                    stage = 5;
                    LogUtils.getLogger().info("Drill lifecycle: source unloaded with destination retained");
                }
            }
            case 5 -> {
                if (controller(source).movedContraption instanceof DrillHeadEntity head) {
                    assertHead(head);
                    state.ready = true;
                    state.setDirty();
                    LogUtils.getLogger().info("Drill lifecycle preparation passed; saving for restart");
                    finish(server, "prepared");
                }
            }
            case 10 -> {
                if (controller(source).movedContraption instanceof DrillHeadEntity head) {
                    assertHead(head);
                    require(head.blockPosition().equals(state.position), "Restart changed the parked head position");
                    source.removeBlock(CONTROLLER, false);
                    require(head.isAlive(), "Controller removal discarded the head above build height");
                    source.setBlockAndUpdate(CONTROLLER, AeronauticsInnerworld.DRILL_CONTROLLER.get().defaultBlockState()
                            .setValue(DrillControllerBlock.FACING, Direction.DOWN));
                    var player = FakePlayerFactory.getMinecraft(mantle);
                    player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
                    head.interact(player, InteractionHand.MAIN_HAND);
                    require(controller(source).movedContraption == head, "Replacement controller did not reconnect");
                    assertHead(head);
                    stage = 11;
                    LogUtils.getLogger().info("Drill lifecycle: disk restart and replacement controller passed");
                }
            }
            case 11 -> {
                if (controller(source).getSpeed() != 0) {
                    controller(source).toggleWork();
                    stage = 12;
                }
            }
            case 12 -> {
                var controller = controller(source);
                require(controller.movedContraption instanceof DrillHeadEntity, "Resumed drill is missing");
                if (!state.inMotion && controller.movedContraption.getY() < 255.5) {
                    state.inMotion = true;
                    state.resumeY = controller.movedContraption.getY();
                    state.setDirty();
                    LogUtils.getLogger().info("Drill lifecycle: saving during active roof excavation");
                    finish(server, "working");
                    return;
                }
                if (controller.movedContraption.getY() <= 252.5) {
                    controller.requestPause();
                    stage = 13;
                }
            }
            case 20 -> {
                var controller = controller(source);
                if (controller.movedContraption instanceof DrillHeadEntity head && controller.canWork()) {
                    assertHead(head);
                    require(head.getY() <= state.resumeY && head.getY() >= state.resumeY - 0.5,
                            "Working restart changed the drill position");
                    stage = 12;
                    LogUtils.getLogger().info("Drill lifecycle: active excavation resumed after restart");
                }
            }
            case 13 -> {
                var controller = controller(source);
                if (controller.getStatus().equals("paused")) {
                    var head = (DrillHeadEntity) controller.movedContraption;
                    assertHead(head);
                    require(controller.tryPlaceHead(), "Restored head could not be placed");
                    var chest = (ChestBlockEntity) mantle.getBlockEntity(head.blockPosition().east());
                    require(chest != null && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 3,
                            "Placement after restart lost cargo");
                    require(mantle.getEntity(state.identity) == null, "Placement duplicated the head");
                    state.complete = true;
                    state.setDirty();
                    LogUtils.getLogger().info("Drill lifecycle verification passed: unload, restart, recovery, excavation and cargo");
                    finish(server, "verified");
                }
            }
            default -> throw new IllegalStateException("Unexpected lifecycle stage " + stage);
        }
    }

    private static void releaseSource(ServerLevel source) {
        sourceLease = false;
        source.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(CONTROLLER), 2, CONTROLLER);
    }

    private static void finish(MinecraftServer server, String result) {
        try {
            Files.writeString(server.getServerDirectory().resolve("lifecycle-check.result"), result);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        server.halt(false);
        state = null;
    }

    private static DrillControllerBlockEntity controller(ServerLevel source) {
        require(source.getBlockEntity(CONTROLLER) instanceof DrillControllerBlockEntity, "Source controller is missing");
        return (DrillControllerBlockEntity) source.getBlockEntity(CONTROLLER);
    }

    private static void assertHead(DrillHeadEntity head) {
        require(head.isAlive() && head.getUUID().equals(state.identity), "Head identity changed");
        require(head.getContraption().getBlocks().size() == 5, "Head structure changed: " + head.getContraption().getBlocks());
        int diamonds = 0;
        var inventory = head.getContraption().getStorage().getAllItems();
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (inventory.getStackInSlot(i).is(Items.DIAMOND)) {
                diamonds += inventory.getStackInSlot(i).getCount();
            }
        }
        require(diamonds == 3, "Head cargo was lost or duplicated");
    }

    private static void build(ServerLevel source) {
        var root = CONTROLLER.below();
        for (var pos : BlockPos.betweenClosed(root.offset(-2, -4, -2), root.offset(4, 3, 2))) {
            source.removeBlock(pos, false);
        }
        source.setBlockAndUpdate(CONTROLLER, AeronauticsInnerworld.DRILL_CONTROLLER.get().defaultBlockState()
                .setValue(DrillControllerBlock.FACING, Direction.DOWN));
        source.setBlockAndUpdate(CONTROLLER.above(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(BlockStateProperties.FACING, Direction.DOWN));
        ((CreativeMotorBlockEntity) source.getBlockEntity(CONTROLLER.above())).generatedSpeed.setValue(64);
        source.setBlockAndUpdate(root, AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().defaultBlockState());
        source.setBlockAndUpdate(root.east(), Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) source.getBlockEntity(root.east())).setItem(0, new net.minecraft.world.item.ItemStack(Items.DIAMOND, 3));
        for (var pos : new BlockPos[] {root.east().above(), root.east().below(), root.east(2)}) {
            source.setBlockAndUpdate(pos, AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().defaultBlockState());
            source.addFreshEntity(new SuperGlueEntity(source, SuperGlueEntity.span(root.east(), pos)));
        }
        source.addFreshEntity(new SuperGlueEntity(source, SuperGlueEntity.span(root, root.east())));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class State extends SavedData {
        private UUID identity;
        private BlockPos position = BlockPos.ZERO;
        private boolean ready;
        private boolean complete;
        private boolean inMotion;
        private double resumeY;

        private static State load(CompoundTag tag, HolderLookup.Provider registries) {
            var state = new State();
            state.identity = tag.hasUUID("Head") ? tag.getUUID("Head") : null;
            state.position = BlockPos.of(tag.getLong("Position"));
            state.ready = tag.getBoolean("Ready");
            state.complete = tag.getBoolean("Complete");
            state.inMotion = tag.getBoolean("InMotion");
            state.resumeY = tag.getDouble("ResumeY");
            return state;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            if (identity != null) {
                tag.putUUID("Head", identity);
            }
            tag.putLong("Position", position.asLong());
            tag.putBoolean("Ready", ready);
            tag.putBoolean("Complete", complete);
            tag.putBoolean("InMotion", inMotion);
            tag.putDouble("ResumeY", resumeY);
            return tag;
        }
    }
}
