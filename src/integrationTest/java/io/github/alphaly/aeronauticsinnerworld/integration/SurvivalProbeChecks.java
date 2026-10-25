package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurvivalProbeChecks {
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void pilotsWithFuelAndMechanicalControls(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var rig = new ProbeTestRig(level, new BlockPos(384, 180, 128));
        rig.workEquipment();
        rig.survivalPower();
        var player = new FakePlayer(level, new GameProfile(UUID.fromString("ce148abc-9f65-4e93-a570-f5ef41499f17"), "ProbePilot"));
        double[] initialZ = {0};
        float[] initialSpeed = {0};
        helper.onEachTick(level::resetEmptyTime);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(), "Waiting for mechanical probe assembly"))
                .thenExecute(rig::assemble)
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(rig.cabin().isSealed(), "Fueled engine did not protect the cabin");
                    helper.assertTrue(Math.abs(rig.sounder().getSpeed()) == 32, "Sounder lacks real engine power");
                    var bounds = rig.subLevel.getPlot().getBoundingBox();
                    int engines = 0;
                    for (var pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                            bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                        var entity = level.getBlockEntity(pos);
                        helper.assertTrue(!(entity instanceof CreativeMotorBlockEntity), "Survival probe contains a creative motor");
                        if (entity instanceof PortableEngineBlockEntity engine) {
                            engines++;
                            helper.assertTrue(engine.inventory.getItem(0).getCount() == 3, "Engine did not consume its coal");
                        }
                    }
                    helper.assertTrue(engines == 7, "Survival power layout lost an engine: " + engines);
                    helper.assertTrue(drive(rig, ProbeTestRig.LEFT_DRIVE).getSpeed() == 0
                            && drive(rig, ProbeTestRig.RIGHT_DRIVE).getSpeed() == 0, "Powered clutches did not stop propulsion");
                    initialZ[0] = rig.subLevel.logicalPose().position().z();
                    toggle(player, rig, ProbeTestRig.LEFT_THROTTLE);
                    toggle(player, rig, ProbeTestRig.RIGHT_THROTTLE);
                }).thenExecuteAfter(70, () -> {
                    initialSpeed[0] = drive(rig, ProbeTestRig.LEFT_DRIVE).getSpeed();
                    helper.assertTrue(Math.abs(initialSpeed[0]) == 32
                            && drive(rig, ProbeTestRig.RIGHT_DRIVE).getSpeed() == initialSpeed[0],
                            "Mechanical clutches did not engage matching fueled propulsion");
                    helper.assertTrue((rig.subLevel.logicalPose().position().z() - initialZ[0]) * Math.signum(initialSpeed[0]) > .15,
                            "Fueled probe did not travel in its thrust direction");
                    toggle(player, rig, ProbeTestRig.LEFT_THROTTLE);
                }).thenExecuteAfter(35, () -> {
                    helper.assertTrue(drive(rig, ProbeTestRig.LEFT_DRIVE).getSpeed() == 0
                            && Math.abs(drive(rig, ProbeTestRig.RIGHT_DRIVE).getSpeed()) == 32,
                            "Independent clutch control did not preserve differential thrust");
                    helper.assertTrue(Math.abs(RigidBodyHandle.of(rig.subLevel).getAngularVelocity().y()) > .001,
                            "Differential mechanical propulsion did not turn the probe");
                    toggle(player, rig, ProbeTestRig.RIGHT_THROTTLE);
                    toggle(player, rig, ProbeTestRig.LEFT_REVERSE);
                    toggle(player, rig, ProbeTestRig.RIGHT_REVERSE);
                }).thenExecuteAfter(10, () -> {
                    toggle(player, rig, ProbeTestRig.LEFT_THROTTLE);
                    toggle(player, rig, ProbeTestRig.RIGHT_THROTTLE);
                }).thenExecuteAfter(70, () -> {
                    helper.assertTrue(drive(rig, ProbeTestRig.LEFT_DRIVE).getSpeed() == -initialSpeed[0]
                            && drive(rig, ProbeTestRig.RIGHT_DRIVE).getSpeed() == -initialSpeed[0],
                            "Native gearshifts did not reverse both drives");
                    var forward = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, 1));
                    var velocity = RigidBodyHandle.of(rig.subLevel).getLinearVelocity();
                    helper.assertTrue((velocity.x() * forward.x + velocity.z() * forward.z) * Math.signum(initialSpeed[0]) < -.02,
                            "Fueled probe failed to reverse its travel");
                    helper.assertTrue(rig.cabin().isSealed(), "Mechanical controls broke the cabin seal");
                    toggle(player, rig, ProbeTestRig.LEFT_THROTTLE);
                    toggle(player, rig, ProbeTestRig.RIGHT_THROTTLE);
                }).thenExecuteAfter(10, () -> {
                    helper.assertTrue(drive(rig, ProbeTestRig.LEFT_DRIVE).getSpeed() == 0
                            && drive(rig, ProbeTestRig.RIGHT_DRIVE).getSpeed() == 0, "Propulsion did not stop after docking controls");
                    rig.cleanUp();
                }).thenSucceed();
    }

    private static ProbeDriveBlockEntity drive(ProbeTestRig rig, BlockPos relative) {
        return (ProbeDriveBlockEntity) rig.level.getBlockEntity(rig.local(relative));
    }

    private static void toggle(FakePlayer player, ProbeTestRig rig, BlockPos relative) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var pos = rig.local(relative);
        player.gameMode.useItemOn(player, rig.level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }
}
