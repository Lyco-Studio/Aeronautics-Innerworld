package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public final class ProbeDriveBlockEntity extends KineticBlockEntity implements BlockEntitySubLevelActor {
    private boolean immersed;
    private double supportedMass;
    private double verticalTrim;

    public ProbeDriveBlockEntity(BlockPos pos, BlockState state) {
        super(AeronauticsInnerworld.PROBE_DRIVE_ENTITY.get(), pos, state);
    }

    private boolean ballast() {
        return getBlockState().getBlock() instanceof ProbeDeviceBlock block && block.kind() == ProbeDeviceBlock.Kind.BALLAST;
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = ballast() ? 8 : 16;
        return lastStressApplied;
    }

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        var position = subLevel.logicalPose().transformPosition(Vec3.atCenterOf(worldPosition));
        var pos = BlockPos.containing(position);
        immersed = level.dimension().equals(Mantle.LEVEL) && level.hasChunkAt(pos)
                && level.getFluidState(pos).is(FluidTags.LAVA);
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle body, double dt) {
        if (!immersed || getSpeed() == 0 || !body.isValid()) {
            supportedMass = 0;
            verticalTrim = 0;
            return;
        }
        if (!ballast()) {
            var normal = getBlockState().getValue(ProbeDeviceBlock.FACING).getNormal();
            var impulse = new Vector3d(normal.getX(), normal.getY(), normal.getZ())
                    .mul(Mth.clamp(getSpeed(), -256, 256) * 2.5 * dt);
            subLevel.getOrCreateQueuedForceGroup(ForceGroups.PROPULSION.get()).applyAndRecordPointForce(
                    new Vector3d(worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5), impulse);
            return;
        }
        int count = 0;
        for (var actor : subLevel.getPlot().getBlockEntityActors()) {
            if (actor instanceof ProbeDriveBlockEntity drive && drive.ballast() && drive.immersed && drive.getSpeed() != 0) {
                count++;
            }
        }
        var mass = subLevel.getMassTracker();
        if (mass.isInvalid() || count == 0) {
            return;
        }
        double power = Math.min(1, Math.abs(getSpeed()) / 32.0);
        supportedMass = Math.min(128, mass.getMass() / count) * power;
        var pose = subLevel.logicalPose();
        var gravity = DimensionPhysicsData.getGravity(level, pose.position());
        double targetSpeed = Math.copySign(Math.min(1.5, Math.max(0, Math.abs(getSpeed()) - 32) / 64.0), getSpeed());
        double error = targetSpeed - body.getLinearVelocity().y();
        double capacity = gravity.length();
        // Native liquid buoyancy already supports the hull; trim only the remaining imbalance.
        verticalTrim = Mth.clamp(verticalTrim + error * dt * 2, -capacity, capacity);
        double lift = Mth.clamp(error * 3.5 + verticalTrim, -capacity, capacity);
        var impulse = new Vector3d(0, lift * supportedMass * dt, 0);
        pose.transformNormalInverse(impulse);

        // Restore pitch and roll while retaining yaw authority for differential thrust.
        var up = pose.transformNormal(new Vector3d(0, 1, 0));
        var correction = up.cross(new Vector3d(0, 1, 0)).mul(2.5);
        var angular = body.getAngularVelocity();
        correction.add(-angular.x() * 1.2, -angular.y() * 0.08, -angular.z() * 1.2);
        pose.transformNormalInverse(correction);
        correction.mul(Math.min(1, supportedMass / mass.getMass()) * dt);
        mass.getInertiaTensor().transform(correction);
        subLevel.getOrCreateQueuedForceGroup(ForceGroups.PROPULSION.get()).getForceTotal()
                .applyLinearAndAngularImpulse(impulse, correction);
    }

    public boolean isImmersed() {
        return immersed;
    }

    public double getSupportedMass() {
        return supportedMass;
    }

    public void showStatus(Player player) {
        player.displayClientMessage(Component.translatable("message.aeronautics_innerworld."
                + (ballast() ? "ballast_status" : "thruster_status"), Math.round(getSpeed()),
                Math.round(supportedMass), Component.translatable("message.aeronautics_innerworld."
                        + (immersed ? "probe_in_lava" : "probe_dry"))), true);
    }
}
