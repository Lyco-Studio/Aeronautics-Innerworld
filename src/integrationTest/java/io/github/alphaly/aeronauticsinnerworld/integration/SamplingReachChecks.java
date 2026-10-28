package io.github.alphaly.aeronauticsinnerworld.integration;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("aeronautics_innerworld_sampling")
@PrefixGameTestTemplate(false)
public final class SamplingReachChecks {
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void reachesRecessedOreWithoutCrossingWallsOrExceedingReach(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var rig = new ProbeTestRig(level, new BlockPos(256, 180, 256));
        rig.workEquipment();
        var original = new HashMap<BlockPos, BlockState>();
        var targets = new BlockPos[3];
        var docked = new boolean[1];
        Runnable cleanUp = () -> {original.forEach(level::setBlockAndUpdate);rig.cleanUp();};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) { }
            public void testPassed(GameTestInfo test, GameTestRunner runner) { }
            public void testFailed(GameTestInfo test, GameTestRunner runner) {cleanUp.run();}
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { }
        });
        helper.onEachTick(() -> {
            level.resetEmptyTime();
            if (docked[0]) {
                var body = RigidBodyHandle.of(rig.subLevel);
                if (body.isValid()) body.addLinearAndAngularVelocity(
                        body.getLinearVelocity().mul(-1, new org.joml.Vector3d()),
                        body.getAngularVelocity().mul(-1, new org.joml.Vector3d()));
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(), "Waiting for sampling rig"))
                .thenExecute(() -> {
                    rig.assemble();rig.power();rig.motor(ProbeTestRig.SAMPLER, 32);rig.sampler().clearContent();
                }).thenExecuteAfter(35, () -> {
                    docked[0] = true;
                    var origin = rig.world(ProbeTestRig.SAMPLER);
                    var direction = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, -1));
                    for (int i = 1; i <= 7; i++) {
                        var pos = BlockPos.containing(origin.add(direction.scale(i)));
                        original.put(pos, level.getBlockState(pos));
                        level.setBlockAndUpdate(pos, Blocks.LAVA.defaultBlockState());
                        if (i == 3) targets[0] = pos;
                        if (i == 5) targets[1] = pos;
                        if (i == 7) targets[2] = pos;
                    }
                    level.setBlockAndUpdate(targets[0], Blocks.BEDROCK.defaultBlockState());
                    for (int i = 1; i <= 2; i++) level.setBlockAndUpdate(targets[i],
                            AeronauticsInnerworld.HARMONIC_ORE.get().defaultBlockState());
                }).thenExecuteAfter(30, () -> {
                    helper.assertTrue(level.getBlockState(targets[1]).is(AeronauticsInnerworld.HARMONIC_ORE.get()),
                            "Sampler crossed an unmineable obstruction");
                    level.setBlockAndUpdate(targets[0], Blocks.LAVA.defaultBlockState());
                }).thenExecuteAfter(30, () -> {
                    helper.assertTrue(!level.getBlockState(targets[1]).is(AeronauticsInnerworld.HARMONIC_ORE.get()),
                            "Sampler could not reach recessed ore beyond the bow clearance");
                    helper.assertTrue(rig.sampler().inventory.getStackInSlot(0).is(AeronauticsInnerworld.RAW_HARMONIC.get())
                                    && rig.sampler().inventory.getStackInSlot(0).getCount() == 1,
                            "Recessed sampling lost or duplicated ore");
                    helper.assertTrue(level.getBlockState(targets[2]).is(AeronauticsInnerworld.HARMONIC_ORE.get()),
                            "Sampler excavated beyond its working reach");
                    cleanUp.run();
                }).thenSucceed();
    }
}
