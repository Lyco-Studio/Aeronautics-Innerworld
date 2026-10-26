package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.HashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SalvageChecks {
    @GameTest(template = "empty", timeoutTicks = 250)
    public static void recoversLooseCargoWithoutLosingRemaindersOrComponents(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var rig = new ProbeTestRig(level, new BlockPos(1408, 180, 128));
        rig.workEquipment();
        var loose = new ItemEntity[1];
        var barrier = new HashMap<BlockPos, BlockState>();
        var scales = new ItemStack(AeronauticsInnerworld.MOLTEN_SCALE.get(), 3);
        scales.set(DataComponents.CUSTOM_NAME, Component.literal("Recovered scales"));
        var tool = new ItemStack(Items.NETHERITE_PICKAXE);tool.setDamageValue(17);
        var player = new FakePlayer(level, new GameProfile(UUID.fromString("f348f3f3-bc3d-46bc-ab0a-a36bfc629122"), "SalvageEngineer"));
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) { }
            public void testPassed(GameTestInfo test, GameTestRunner runner) { cleanUp(); }
            public void testFailed(GameTestInfo test, GameTestRunner runner) { cleanUp(); }
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { }
            private void cleanUp() {
                if (loose[0] != null) loose[0].discard();
                barrier.forEach(level::setBlockAndUpdate);rig.cleanUp();
            }
        });
        helper.onEachTick(() -> {
            level.resetEmptyTime();
            if (loose[0] != null && loose[0].isAlive() && rig.subLevel != null) {
                var direction = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, -1));
                double distance = level.getGameTime() % 16 < 8 ? 2.75 : 3;
                loose[0].setPos(rig.world(ProbeTestRig.SAMPLER).add(direction.scale(distance)).add(0, -.125, 0));
                loose[0].setDeltaMovement(Vec3.ZERO);
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(), "Waiting for salvage probe"))
                .thenExecute(() -> {
                    rig.assemble();rig.power();rig.motor(ProbeTestRig.SAMPLER, 0);
                    rig.sampler().inventory.setStackInSlot(0, scales.copyWithCount(63));
                    for (int slot = 1; slot < 9; slot++) rig.sampler().inventory.setStackInSlot(slot, new ItemStack(Items.COBBLED_DEEPSLATE, 64));
                }).thenExecuteAfter(35, () -> {
                    var position = rig.creatureSamplingTarget();
                    loose[0] = new ItemEntity(level, position.x, position.y, position.z, scales.copy());
                    loose[0].setNoGravity(true);loose[0].setNoPickUpDelay();level.addFreshEntity(loose[0]);
                }).thenExecuteAfter(10, () -> {
                    helper.assertTrue(loose[0].getItem().getCount() == 3 && rig.sampler().inventory.getStackInSlot(0).getCount() == 63,
                            "An unpowered sampler recovered cargo");
                    rig.motor(ProbeTestRig.SAMPLER, 32);
                }).thenExecuteAfter(10, () -> {
                    helper.assertTrue(rig.sampler().inventory.getStackInSlot(0).getCount() == 64
                                    && loose[0].isAlive() && loose[0].getItem().getCount() == 2
                                    && ItemStack.isSameItemSameComponents(loose[0].getItem(), scales),
                            "Partial salvage lost the remainder or item components: status=" + rig.sampler().getStatus());
                    var direction = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, -1));
                    var center = BlockPos.containing(rig.world(ProbeTestRig.SAMPLER).add(direction.scale(2)));
                    for (int x = -2; x <= 2; x++) for (int y = -1; y <= 1; y++) {
                        var pos = center.offset(x, y, 0);barrier.put(pos, level.getBlockState(pos));
                        level.setBlockAndUpdate(pos, Blocks.OBSIDIAN.defaultBlockState());
                    }
                    rig.sampler().inventory.extractItem(0, 2, false);
                }).thenExecuteAfter(10, () -> {
                    helper.assertTrue(loose[0].isAlive() && loose[0].getItem().getCount() == 2
                                    && rig.sampler().inventory.getStackInSlot(0).getCount() == 62,
                            "Salvage pulled items through a solid barrier");
                    rig.unload();helper.runAfterDelay(2, rig::restore);
                }).thenExecuteAfter(4, () -> {
                    helper.assertTrue(rig.sampler().inventory.getStackInSlot(0).getCount() == 62
                                    && ItemStack.isSameItemSameComponents(rig.sampler().inventory.getStackInSlot(0), scales)
                                    && loose[0].getItem().getCount() == 2, "Unload/reload changed salvage cargo or its loose remainder");
                    barrier.forEach(level::setBlockAndUpdate);barrier.clear();
                }).thenExecuteAfter(10, () -> {
                    helper.assertTrue(!loose[0].isAlive() && rig.sampler().inventory.getStackInSlot(0).getCount() == 64,
                            "Salvage did not resume after clearing the barrier");
                    rig.sampler().inventory.setStackInSlot(1, ItemStack.EMPTY);
                    var point = rig.world(ProbeTestRig.SAMPLER).add(0, 0, -3);
                    loose[0] = new ItemEntity(level, point.x, point.y, point.z, tool.copy());
                    loose[0].setNoGravity(true);loose[0].setNoPickUpDelay();level.addFreshEntity(loose[0]);
                }).thenExecuteAfter(10, () -> {
                    helper.assertTrue(!loose[0].isAlive() && ItemStack.isSameItemSameComponents(rig.sampler().inventory.getStackInSlot(1), tool),
                            "Recovered equipment lost its durability or other item components");
                    rig.sampler().interact(player);rig.sampler().interact(player);
                    helper.assertTrue(player.getInventory().countItem(AeronauticsInnerworld.MOLTEN_SCALE.get()) == 64
                                    && player.getInventory().items.stream().anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, tool))
                                    && rig.sampler().inventory.getStackInSlot(0).isEmpty() && rig.sampler().inventory.getStackInSlot(1).isEmpty(),
                            "Unloading recovered cargo lost or duplicated items");
                }).thenSucceed();
    }
}
