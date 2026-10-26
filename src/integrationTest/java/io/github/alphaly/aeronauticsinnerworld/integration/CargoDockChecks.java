package io.github.alphaly.aeronauticsinnerworld.integration;

import dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CargoDockChecks {
    private static final BlockPos SHIP_PORT = new BlockPos(4, 0, 2);

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void unloadsSamplerThroughNativeDockWithoutLosingBlockedCargo(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var origin = new BlockPos(896, 180, 128);
        var original = new HashMap<BlockPos, BlockState>();
        for (var pos : BlockPos.betweenClosed(origin.offset(-3, -2, -2), origin.offset(10, 6, 8))) {
            original.put(pos.immutable(), level.getBlockState(pos));
            boolean wall = pos.getX() == origin.getX() - 3 || pos.getX() == origin.getX() + 10
                    || pos.getY() == origin.getY() - 2 || pos.getY() == origin.getY() + 6
                    || pos.getZ() == origin.getZ() - 2 || pos.getZ() == origin.getZ() + 8;
            level.setBlockAndUpdate(pos, (wall ? Blocks.DEEPSLATE_BRICKS : Blocks.AIR).defaultBlockState());
        }
        var rig = new ProbeTestRig(level, origin);
        rig.landingSkids();
        rig.cargoConnection();
        var shore = origin.offset(7, 0, 2);
        var shoreChunk = new net.minecraft.world.level.ChunkPos(shore);
        boolean wasForced = level.getForcedChunks().contains(shoreChunk.toLong());
        level.setChunkForced(shoreChunk.x, shoreChunk.z, true);
        level.setBlockAndUpdate(shore, SimBlocks.DOCKING_CONNECTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
        level.setBlockAndUpdate(shore.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(shore.below(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(shore.below().east(), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(shore.above(), Blocks.HOPPER.defaultBlockState());
        level.setBlockAndUpdate(shore.above(2), Blocks.CHEST.defaultBlockState());
        var supplies = (Container) level.getBlockEntity(shore.above(2));
        supplies.setItem(0, new ItemStack(Items.COAL, 7));
        var chest = (Container) level.getBlockEntity(shore.below().east());
        for (int slot = 0; slot < chest.getContainerSize(); slot++) chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        var cleaned = new boolean[1];
        Runnable cleanUp = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            if (rig.subLevel != null) {
                port(rig).clearContent();
                for (var pos : java.util.List.of(new BlockPos(3, 0, 0), new BlockPos(4, 0, 0), new BlockPos(4, 0, 1),
                        SHIP_PORT.below(), SHIP_PORT.below().south())) {
                    ((Container) level.getBlockEntity(rig.local(pos))).clearContent();
                }
            }
            ((DockingConnectorBlockEntity) level.getBlockEntity(shore)).unDock();
            ((DockingConnectorBlockEntity) level.getBlockEntity(shore)).clearContent();
            ((Container) level.getBlockEntity(shore.below())).clearContent();
            ((Container) level.getBlockEntity(shore.above())).clearContent();
            supplies.clearContent();
            chest.clearContent();
            rig.cleanUp();
            original.forEach(level::setBlockAndUpdate);
            if (!wasForced) level.setChunkForced(shoreChunk.x, shoreChunk.z, false);
        };
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) { }
            public void testPassed(GameTestInfo test, GameTestRunner runner) { cleanUp.run(); }
            public void testFailed(GameTestInfo test, GameTestRunner runner) { cleanUp.run(); }
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { }
        });
        helper.onEachTick(level::resetEmptyTime);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(), "Waiting for cargo probe assembly"))
                .thenExecute(() -> { rig.assemble(); rig.power(); })
                .thenWaitUntil(() -> helper.assertTrue(port(rig).isLocked(), "Waiting for native docking lock: powered="
                        + port(rig).powered + ", extended=" + port(rig).isExtended() + ", shore="
                        + ((DockingConnectorBlockEntity) level.getBlockEntity(shore)).powered + ", signal=" + level.hasNeighborSignal(shore)
                        + ", lever=" + level.getBlockState(shore.east().above())))
                .thenExecuteAfter(180, () -> {
                    helper.assertTrue(count(chest) == 0 && total(rig, shore, chest) == 17,
                            "Full station storage lost or duplicated sampler cargo");
                    helper.assertTrue(fuelOnShip(rig) == 7 && total(rig, shore, chest, Items.COAL) == 7,
                            "Blocked unloading prevented independent fuel loading or changed its total");
                    chest.setItem(0, ItemStack.EMPTY);
                }).thenWaitUntil(() -> helper.assertTrue(count(chest) == 17,
                        "Waiting for hopper unloading through native dock: total=" + total(rig, shore, chest)
                                + ", shore hopper=" + level.getBlockState(shore.below())))
                .thenExecute(() -> {
                    helper.assertTrue(total(rig, shore, chest) == 17, "Completed unloading duplicated cargo");
                    helper.assertTrue(rig.cabin().isSealed(), "Cargo connection opened the passenger cabin");
                    toggle(rig);
                }).thenWaitUntil(() -> helper.assertTrue(port(rig).isRetracted() && !port(rig).hasOtherConnector(),
                        "Waiting for native dock disconnection"))
                .thenExecute(() -> rig.sampler().inventory.setStackInSlot(0, new ItemStack(AeronauticsInnerworld.RAW_HARMONIC.get(), 3)))
                .thenExecuteAfter(60, () -> {
                    helper.assertTrue(count(chest) == 17 && total(rig, shore, chest) == 20,
                            "Disconnected dock transferred, lost or duplicated cargo");
                    supplies.setItem(0, new ItemStack(Items.COAL, 3));
                }).thenExecuteAfter(40, () -> {
                    helper.assertTrue(fuelOnShip(rig) == 7 && total(rig, shore, chest, Items.COAL) == 10,
                            "Disconnected dock transferred or lost fuel supplies");
                    toggle(rig);
                }).thenWaitUntil(() -> helper.assertTrue(port(rig).isLocked() && count(chest) == 20 && fuelOnShip(rig) == 10,
                        "Waiting for reconnect and queued cargo delivery"))
                .thenExecute(() -> {
                    rig.sampler().inventory.setStackInSlot(0, new ItemStack(AeronauticsInnerworld.RAW_HARMONIC.get(), 5));
                    supplies.setItem(0, new ItemStack(Items.COAL, 5));
                    rig.unload();
                }).thenExecuteAfter(2, rig::restore)
                .thenWaitUntil(() -> helper.assertTrue(count(chest) == 25 && fuelOnShip(rig) == 15,
                        "Waiting for restored dock to deliver saved cargo and fuel"))
                .thenExecute(() -> {
                    helper.assertTrue(total(rig, shore, chest) == 25, "Dock reload lost or duplicated cargo");
                    helper.assertTrue(total(rig, shore, chest, Items.COAL) == 15, "Dock reload lost or duplicated fuel");
                }).thenSucceed();
    }

    private static DockingConnectorBlockEntity port(ProbeTestRig rig) {
        return (DockingConnectorBlockEntity) rig.level.getBlockEntity(rig.local(SHIP_PORT));
    }

    private static void toggle(ProbeTestRig rig) {
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(rig.level);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var pos = rig.local(SHIP_PORT.south());
        player.gameMode.useItemOn(player, rig.level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static int total(ProbeTestRig rig, BlockPos shore, Container chest) {
        return total(rig, shore, chest, AeronauticsInnerworld.RAW_HARMONIC.get());
    }

    private static int total(ProbeTestRig rig, BlockPos shore, Container chest, Item item) {
        int count = count(chest, item) + count((Container) rig.level.getBlockEntity(shore.below()), item)
                + count((Container) rig.level.getBlockEntity(shore.above()), item)
                + count((Container) rig.level.getBlockEntity(shore.above(2)), item);
        count += count(port(rig).inventory, item) + count(((DockingConnectorBlockEntity) rig.level.getBlockEntity(shore)).inventory, item);
        for (var pos : java.util.List.of(new BlockPos(3, 0, 0), new BlockPos(4, 0, 0), new BlockPos(4, 0, 1),
                SHIP_PORT.below(), SHIP_PORT.below().south())) {
            count += count((Container) rig.level.getBlockEntity(rig.local(pos)), item);
        }
        for (int slot = 0; slot < rig.sampler().inventory.getSlots(); slot++) {
            var stack = rig.sampler().inventory.getStackInSlot(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int count(Container container) {
        return count(container, AeronauticsInnerworld.RAW_HARMONIC.get());
    }

    private static int fuelOnShip(ProbeTestRig rig) {
        return count((Container) rig.level.getBlockEntity(rig.local(SHIP_PORT.below().south())), Items.COAL);
    }

    private static int count(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            var stack = container.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }
}
