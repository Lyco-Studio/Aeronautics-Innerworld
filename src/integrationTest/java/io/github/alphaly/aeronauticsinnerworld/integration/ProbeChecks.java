package io.github.alphaly.aeronauticsinnerworld.integration;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.PressureHatchBlock;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProbeChecks {
    @GameTest(template = "empty", timeoutTicks = 500)
    public static void navigatesAndResealsMantleProbe(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var rig = new ProbeTestRig(level, new BlockPos(128, 170, 128));
        helper.onEachTick(level::resetEmptyTime);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(), "Waiting for source entity chunks"))
                .thenExecute(() -> {
            rig.assemble();
            rig.power();
            rig.ballast(0);
        }).thenExecuteAfter(10, () -> {
            var velocity = RigidBodyHandle.of(rig.subLevel).getLinearVelocity();
            helper.assertTrue(velocity.y() < -0.02, "Unpowered probe did not sink: " + velocity);
            rig.power();
        }).thenExecuteAfter(35, () -> {
            helper.assertTrue(rig.cabin().isSealed(), "Powered cabin did not form a sealed compartment: speed="
                    + rig.cabin().getSpeed() + ", seed=" + level.getBlockState(rig.local(ProbeTestRig.CABIN).south())
                    + ", cabin=" + rig.cabin().getBlockPos() + ", pose=" + rig.subLevel.logicalPose()
                    + ", mass=" + rig.subLevel.getMassTracker().getMass());
            var interior = rig.world(new BlockPos(2, 1, 2));
            helper.assertTrue(WaterOcclusionContainer.getContainer(level).isOccluded(interior),
                    "Native occlusion did not cover cabin interior");
            var body = RigidBodyHandle.of(rig.subLevel);
            helper.assertTrue(body.isValid(), "Probe has no native rigid body");
            helper.assertTrue(Math.abs(body.getLinearVelocity().y()) < 0.8,
                    "Two ballast units failed to hold neutral buoyancy: " + body.getLinearVelocity()
                            + ", gravity=" + dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData.getGravity(level)
                            + ", mass=" + rig.subLevel.getMassTracker().getMass()
                            + ", support=" + ((ProbeDriveBlockEntity) level.getBlockEntity(rig.local(new BlockPos(1,0,2)))).getSupportedMass()
                            + "/" + ((ProbeDriveBlockEntity) level.getBlockEntity(rig.local(new BlockPos(3,0,2)))).getSupportedMass());
            rig.ballast(96);
        }).thenExecuteAfter(30, () -> {
            helper.assertTrue(RigidBodyHandle.of(rig.subLevel).getLinearVelocity().y() > .2,
                    "Ballast climb control did not ascend");
            rig.ballast(-96);
        }).thenExecuteAfter(45, () -> {
            helper.assertTrue(RigidBodyHandle.of(rig.subLevel).getLinearVelocity().y() < -.2,
                    "Ballast dive control did not descend");
            rig.ballast(32);
        }).thenExecuteAfter(30, () -> {
            var body = RigidBodyHandle.of(rig.subLevel);
            double startZ = rig.subLevel.logicalPose().position().z();
            rig.obstacle();
            rig.motor(ProbeTestRig.LEFT_DRIVE, 64);
            rig.motor(ProbeTestRig.RIGHT_DRIVE, 64);
            helper.runAfterDelay(80, () -> {
                helper.assertTrue(rig.subLevel.logicalPose().position().z() > startZ + 0.3,
                        "Powered molten thrusters failed to propel native structure: " + body.getLinearVelocity());
                helper.assertTrue(rig.subLevel.boundingBox().maxZ() < 139.4,
                        "Probe penetrated solid terrain at the obstacle");
                helper.assertTrue(Math.abs(body.getLinearVelocity().z()) < .3,
                        "Probe did not stop against the obstacle: " + body.getLinearVelocity());
                rig.motor(ProbeTestRig.LEFT_DRIVE, -64);
                rig.motor(ProbeTestRig.RIGHT_DRIVE, -64);
                helper.runAfterDelay(60, () -> {
                    helper.assertTrue(body.getLinearVelocity().z() < -0.05, "Reversed rotation did not reverse thrust");
                    rig.motor(ProbeTestRig.LEFT_DRIVE, 64);
                    rig.motor(ProbeTestRig.RIGHT_DRIVE, -64);
                    helper.runAfterDelay(25, () -> {
                        helper.assertTrue(Math.abs(body.getAngularVelocity().y()) > 0.02,
                                "Differential thrust did not turn the probe");
                        rig.motor(ProbeTestRig.LEFT_DRIVE, 0);
                        rig.motor(ProbeTestRig.RIGHT_DRIVE, 0);
                        var hatchPos = rig.local(ProbeTestRig.HATCH);
                        var hatch = level.getBlockState(hatchPos);
                        level.setBlockAndUpdate(hatchPos, hatch.setValue(PressureHatchBlock.OPEN, true));
                        helper.runAfterDelay(12, () -> {
                            helper.assertTrue(!rig.cabin().isSealed(), "Open hatch retained a sealed cabin");
                            level.setBlockAndUpdate(hatchPos, hatch);
                            helper.runAfterDelay(15, () -> {
                                helper.assertTrue(rig.cabin().isSealed(), "Closed hatch did not restore cabin seal");
                                rig.motor(ProbeTestRig.CABIN, 0);
                                helper.runAfterDelay(8, () -> {
                                    helper.assertTrue(!rig.cabin().isSealed(), "Unpowered regulator retained a sealed cabin");
                                    rig.motor(ProbeTestRig.CABIN, 32);
                                    var uuid = rig.subLevel.getUniqueId();
                                    rig.unload();
                                    helper.runAfterDelay(2, rig::restore);
                                    helper.runAfterDelay(22, () -> {
                                        helper.assertTrue(rig.subLevel.getUniqueId().equals(uuid), "Probe identity changed on restore");
                                        helper.assertTrue(rig.cabin().isSealed(), "Restored regulator did not rebuild the seal");
                                        helper.assertTrue(RigidBodyHandle.of(rig.subLevel).isValid(), "Restored probe has no physics body");
                                        rig.cleanUp();
                                        helper.succeed();
                                    });
                                });
                            });
                        });
                    });
                });
            });
        });
    }
}
