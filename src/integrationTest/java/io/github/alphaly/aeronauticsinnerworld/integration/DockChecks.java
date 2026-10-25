package io.github.alphaly.aeronauticsinnerworld.integration;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DockChecks {
    @GameTest(template = "empty", timeoutTicks = 2800)
    public static void launchesThroughFloodingGateAndReturnsToDryDock(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var origin = new BlockPos(768, 175, 128);
        var player = new net.neoforged.neoforge.common.util.FakePlayer(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.fromString("e03d3af2-6ae0-4bae-8596-4a90e7dbdf26"), "DockPilot"));
        var forced = new java.util.ArrayList<net.minecraft.world.level.ChunkPos>();
        for (int x = 47; x <= 48; x++) for (int z = 7; z <= 9; z++) {
            var chunk = new net.minecraft.world.level.ChunkPos(x, z);
            if (!level.getForcedChunks().contains(chunk.toLong())) {
                level.setChunkForced(x, z, true);
                forced.add(chunk);
            }
        }
        var original = new HashMap<BlockPos, BlockState>();
        for (var pos : BlockPos.betweenClosed(origin.offset(-3, -2, -2), origin.offset(7, 8, 26))) {
            var before = level.getBlockState(pos);
            if (pos.getZ() > origin.getZ() + 8) {
                if (before.is(Blocks.BASALT) || before.is(AeronauticsInnerworld.HARMONIC_ORE.get())) {
                    original.put(pos.immutable(), before);
                    level.setBlockAndUpdate(pos, Blocks.LAVA.defaultBlockState());
                }
                continue;
            }
            original.put(pos.immutable(), before);
            boolean wall = pos.getX() == origin.getX() - 3 || pos.getX() == origin.getX() + 7
                    || pos.getY() == origin.getY() - 2 || pos.getY() == origin.getY() + 8
                    || pos.getZ() == origin.getZ() - 2 || pos.getZ() == origin.getZ() + 8;
            level.setBlockAndUpdate(pos, (wall ? Blocks.DEEPSLATE_BRICKS : Blocks.AIR).defaultBlockState());
        }
        var rig = new ProbeTestRig(level, origin);
        rig.landingSkids();
        var cleaned = new boolean[1];
        Runnable cleanUp = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            rig.cleanUp();
            original.forEach(level::setBlockAndUpdate);
            forced.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, false));
        };
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) { }
            public void testPassed(GameTestInfo test, GameTestRunner runner) { cleanUp.run(); }
            public void testFailed(GameTestInfo test, GameTestRunner runner) { cleanUp.run(); }
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { }
        });
        var elapsed = new int[1];
        helper.onEachTick(() -> { level.resetEmptyTime(); elapsed[0]++; });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(), "Waiting for dock assembly"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getFluidState(origin.offset(1, 1, 6)).isEmpty(), "Dock was not dry before assembly");
                    rig.assemble();
                    rig.power();
                    rig.cabin().addCoolant(240);
                    rig.motor(ProbeTestRig.LEFT_DRIVE, 32);
                    rig.motor(ProbeTestRig.RIGHT_DRIVE, 32);
                }).thenExecuteAfter(30, () -> {
                    helper.assertTrue(rig.cabin().isSealed(), "Dry dock assembly did not seal its cabin");
                    helper.assertTrue(!drive(rig).isImmersed(), "Dry thruster became immersed at "
                            + rig.world(ProbeTestRig.LEFT_DRIVE) + ": " + level.getFluidState(BlockPos.containing(rig.world(ProbeTestRig.LEFT_DRIVE)))
                            + ", source=" + level.getFluidState(origin.offset(1, 1, 6)));
                    helper.assertTrue(rig.subLevel.boundingBox().maxZ() < origin.getZ() + 8,
                            "Dry dock bounds: minZ=" + rig.subLevel.boundingBox().minZ() + ", maxZ=" + rig.subLevel.boundingBox().maxZ()
                                    + ", hull=" + rig.world(BlockPos.ZERO) + ", plot=" + rig.subLevel.getPlot().getBoundingBox());
                    gate(rig, origin, false);
                    helper.assertTrue(level.getFluidState(origin.offset(1, 1, 9)).is(FluidTags.LAVA), "Dock gate has no lava outside");
                }).thenWaitUntil(() -> helper.assertTrue(drive(rig).isImmersed(), "Waiting for natural lava to reach the thruster: gate="
                        + level.getBlockState(origin.offset(1, 1, 8)) + ", drive=" + rig.world(ProbeTestRig.LEFT_DRIVE)
                        + ", source=" + level.getFluidState(origin.offset(1, 1, 9)) + ", ticking="
                        + level.shouldTickBlocksAt(new net.minecraft.world.level.ChunkPos(origin).toLong())))
                .thenWaitUntil(() -> helper.assertTrue(rig.subLevel.boundingBox().minZ() > origin.getZ() + 9,
                        "Waiting for the whole probe to leave the dock: front=" + rig.subLevel.boundingBox().minZ()
                                + ", rear=" + rig.subLevel.boundingBox().maxZ() + ", immersed=" + drive(rig).isImmersed()
                                + ", velocity=" + dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(rig.subLevel).getLinearVelocity()))
                .thenExecute(() -> {
                    com.mojang.logging.LogUtils.getLogger().info("Dock departure completed after {} ticks", elapsed[0]);
                    helper.assertTrue(rig.cabin().isSealed(), "Flooding the dock exposed the cabin");
                    rig.motor(ProbeTestRig.LEFT_DRIVE, 0);
                    rig.motor(ProbeTestRig.RIGHT_DRIVE, 0);
                    trim(rig, player, 1);
                }).thenWaitUntil(() -> helper.assertTrue(rig.subLevel.boundingBox().minY() > origin.getY() - .8,
                        "Waiting for ballast to clear the docking sill"))
                .thenExecute(() -> trim(rig, player, 0))
                .thenExecuteAfter(30, () -> {
                    rig.motor(ProbeTestRig.LEFT_DRIVE, -32);
                    rig.motor(ProbeTestRig.RIGHT_DRIVE, -32);
                }).thenWaitUntil(() -> helper.assertTrue(rig.subLevel.boundingBox().maxZ() < origin.getZ() + 7.5,
                        "Waiting for return: rear=" + rig.subLevel.boundingBox().maxZ() + ", bottom=" + rig.subLevel.boundingBox().minY()
                                + ", top=" + rig.subLevel.boundingBox().maxY() + ", velocity="
                                + dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(rig.subLevel).getLinearVelocity()))
                .thenExecute(() -> {
                    com.mojang.logging.LogUtils.getLogger().info("Dock return completed after {} ticks", elapsed[0]);
                    rig.motor(ProbeTestRig.LEFT_DRIVE, 0);
                    rig.motor(ProbeTestRig.RIGHT_DRIVE, 0);
                    gate(rig, origin, true);
                }).thenWaitUntil(() -> {
                    for (var pos : BlockPos.betweenClosed(origin.offset(-2, -1, -1), origin.offset(6, 7, 7))) {
                        helper.assertTrue(!level.getFluidState(pos).is(FluidTags.LAVA), "Waiting for dock lava to drain at " + pos);
                    }
                }).thenExecute(() -> {
                    helper.assertTrue(rig.cabin().isSealed(), "Returning to dry dock broke the cabin");
                    helper.assertTrue(!drive(rig).isImmersed(), "Dry dock thrusters remained immersed");
                }).thenSucceed();
    }

    private static ProbeDriveBlockEntity drive(ProbeTestRig rig) {
        return (ProbeDriveBlockEntity) rig.level.getBlockEntity(rig.local(ProbeTestRig.LEFT_DRIVE));
    }

    private static void gate(ProbeTestRig rig, BlockPos origin, boolean closed) {
        for (var pos : BlockPos.betweenClosed(origin.offset(-2, -1, 8), origin.offset(6, 7, 8))) {
            rig.level.setBlockAndUpdate(pos, (closed ? Blocks.DEEPSLATE_BRICKS : Blocks.AIR).defaultBlockState());
        }
        // Falling lava at drive height primes launch; a side opening only wets the floor.
        for (var pos : BlockPos.betweenClosed(origin.offset(0, 8, -1), origin.offset(4, 8, 7))) {
            rig.level.setBlockAndUpdate(pos, (closed ? Blocks.DEEPSLATE_BRICKS : Blocks.AIR).defaultBlockState());
        }
    }

    private static void trim(ProbeTestRig rig, net.minecraft.world.entity.player.Player player, int mode) {
        for (int x : new int[] {1, 3}) {
            var drive = (ProbeDriveBlockEntity) rig.level.getBlockEntity(rig.local(new BlockPos(x, 0, 2)));
            while (drive.getTrimMode() != mode) drive.cycleTrimMode(player);
        }
    }
}
