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
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
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
        player.setGameMode(GameType.SURVIVAL);
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
        rig.workEquipment();rig.survivalPower();
        rig.landingSkids();
        var shutter = new FloodingShutter(level, origin, player);
        var portcullis = new DockPortcullis(level, origin, player);
        var cleaned = new boolean[1];
        Runnable cleanUp = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            portcullis.cleanUp();
            shutter.cleanUp();
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
        helper.onEachTick(() -> { level.resetEmptyTime(); elapsed[0]++;shutter.tick();portcullis.tick(); });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(), "Waiting for dock assembly"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getFluidState(origin.offset(1, 1, 6)).isEmpty(), "Dock was not dry before assembly");
                    rig.assemble();
                    rig.cabin().addCoolant(240);
                }).thenExecuteAfter(30, () -> {
                    helper.assertTrue(rig.cabin().isSealed(), "Dry dock assembly did not seal its cabin");
                    helper.assertTrue(!drive(rig).isImmersed(), "Dry thruster became immersed at "
                            + rig.world(ProbeTestRig.LEFT_DRIVE) + ": " + level.getFluidState(BlockPos.containing(rig.world(ProbeTestRig.LEFT_DRIVE)))
                            + ", source=" + level.getFluidState(origin.offset(1, 1, 6)));
                    helper.assertTrue(rig.subLevel.boundingBox().maxZ() < origin.getZ() + 8,
                            "Dry dock bounds: minZ=" + rig.subLevel.boundingBox().minZ() + ", maxZ=" + rig.subLevel.boundingBox().maxZ()
                                    + ", hull=" + rig.world(BlockPos.ZERO) + ", plot=" + rig.subLevel.getPlot().getBoundingBox());
                    portcullis.command(false);shutter.command(false);
                    helper.assertTrue(level.getFluidState(origin.offset(1, 1, 9)).is(FluidTags.LAVA), "Dock gate has no lava outside");
                }).thenWaitUntil(() -> helper.assertTrue(shutter.atRequestedPosition() && shutter.consumedFuel(), "Waiting for coal-powered shutter to open: " + shutter.status()))
                .thenWaitUntil(() -> helper.assertTrue(portcullis.atRequestedPosition() && portcullis.consumedFuel(), "Waiting for coal-powered dock gate to open: " + portcullis.status()))
                .thenWaitUntil(() -> helper.assertTrue(drive(rig).isImmersed(), "Waiting for natural lava to reach the thruster: gate="
                        + level.getBlockState(origin.offset(1, 1, 8)) + ", drive=" + rig.world(ProbeTestRig.LEFT_DRIVE)
                        + ", source=" + level.getFluidState(origin.offset(1, 1, 9)) + ", ticking="
                        + level.shouldTickBlocksAt(new net.minecraft.world.level.ChunkPos(origin).toLong())))
                .thenExecute(() -> trim(rig, player, 1))
                .thenWaitUntil(() -> helper.assertTrue(rig.subLevel.boundingBox().minY() > origin.getY() - .8,
                        "Waiting for fueled probe to lift off the dock floor: bottom=" + rig.subLevel.boundingBox().minY()
                                + ", ballast=" + ballast(rig).getSpeed() + "/" + ballast(rig).isImmersed()
                                + ", support=" + ballast(rig).getSupportedMass()))
                .thenExecute(() -> trim(rig, player, 0))
                .thenExecuteAfter(30, () -> propulsion(rig, player, true, false))
                .thenExecuteAfter(10, () -> helper.assertTrue(Math.abs(drive(rig).getSpeed()) == 32,
                        "Native departure controls did not engage the fueled thruster: " + drive(rig).getSpeed()))
                .thenWaitUntil(() -> helper.assertTrue(rig.subLevel.boundingBox().minZ() > origin.getZ() + 9,
                        "Waiting for the whole probe to leave the dock: front=" + rig.subLevel.boundingBox().minZ()
                                + ", rear=" + rig.subLevel.boundingBox().maxZ() + ", immersed=" + drive(rig).isImmersed()
                                + ", rpm=" + drive(rig).getSpeed() + ", bottom=" + rig.subLevel.boundingBox().minY()
                                + ", velocity=" + dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(rig.subLevel).getLinearVelocity()))
                .thenExecute(() -> {
                    com.mojang.logging.LogUtils.getLogger().info("Dock departure completed after {} ticks", elapsed[0]);
                    helper.assertTrue(rig.cabin().isSealed(), "Flooding the dock exposed the cabin");
                    propulsion(rig, player, false, false);
                    trim(rig, player, 1);
                }).thenWaitUntil(() -> helper.assertTrue(rig.subLevel.boundingBox().minY() > origin.getY() - .8,
                        "Waiting for ballast to clear the docking sill"))
                .thenExecute(() -> trim(rig, player, 0))
                .thenExecuteAfter(30, () -> {
                    propulsion(rig, player, true, true);
                }).thenWaitUntil(() -> helper.assertTrue(rig.subLevel.boundingBox().maxZ() < origin.getZ() + 7.5,
                        "Waiting for return: rear=" + rig.subLevel.boundingBox().maxZ() + ", bottom=" + rig.subLevel.boundingBox().minY()
                                + ", top=" + rig.subLevel.boundingBox().maxY() + ", velocity="
                                + dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(rig.subLevel).getLinearVelocity()))
                .thenExecute(() -> {
                    com.mojang.logging.LogUtils.getLogger().info("Dock return completed after {} ticks", elapsed[0]);
                    propulsion(rig, player, false, true);
                    portcullis.command(true);shutter.command(true);
                }).thenWaitUntil(() -> helper.assertTrue(shutter.atRequestedPosition(), "Waiting for mechanical shutter to close: " + shutter.status()))
                .thenWaitUntil(() -> helper.assertTrue(portcullis.atRequestedPosition(), "Waiting for mechanical dock gate to close: " + portcullis.status()))
                .thenWaitUntil(() -> {
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

    private static ProbeDriveBlockEntity ballast(ProbeTestRig rig) {
        return (ProbeDriveBlockEntity) rig.level.getBlockEntity(rig.local(new BlockPos(1, 0, 2)));
    }

    static void trim(ProbeTestRig rig, FakePlayer player, int mode) {
        player.setShiftKeyDown(true);
        try {
            for (int x : new int[] {1, 3}) {
                var pos = rig.local(new BlockPos(x, 0, 2));
                var drive = (ProbeDriveBlockEntity) rig.level.getBlockEntity(pos);
                for (int count = 0; drive.getTrimMode() != mode && count < 3; count++) use(rig, player, pos);
                if (drive.getTrimMode() != mode) throw new IllegalStateException("Dock ballast input did not select " + mode);
            }
        } finally {player.setShiftKeyDown(false);}
    }

    static void propulsion(ProbeTestRig rig, FakePlayer player, boolean on, boolean reverse) {
        for (var relative : java.util.List.of(ProbeTestRig.LEFT_REVERSE, ProbeTestRig.RIGHT_REVERSE,
                ProbeTestRig.LEFT_THROTTLE, ProbeTestRig.RIGHT_THROTTLE)) {
            var pos = rig.local(relative);
            boolean powered = relative.equals(ProbeTestRig.LEFT_REVERSE) || relative.equals(ProbeTestRig.RIGHT_REVERSE) ? reverse : !on;
            if (rig.level.getBlockState(pos).getValue(LeverBlock.POWERED) == powered) continue;
            use(rig, player, pos);
        }
    }

    private static void use(ProbeTestRig rig, FakePlayer player, BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.gameMode.useItemOn(player, rig.level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }
}
