package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.world.structure.MantleRelayStructure;
import io.github.alphaly.aeronauticsinnerworld.world.structure.DeepWorksStructure;
import io.github.alphaly.aeronauticsinnerworld.world.structure.DeepWorksPiece;
import io.github.alphaly.aeronauticsinnerworld.world.structure.MantleRelayPiece;
import io.github.alphaly.aeronauticsinnerworld.content.combat.LavaStalker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class EchoSounderBlockEntity extends KineticBlockEntity implements BlockEntitySubLevelActor {
    private BlockPos home;
    private BlockPos waypoint;
    private CompoundTag recoveryTarget;
    private boolean recoveryLoaded;
    private BlockPos mineral;
    private BlockPos relay;
    private boolean deepWorks;
    private LavaStalker threat;
    private int clearance = 16;
    private int ticks;

    public EchoSounderBlockEntity(BlockPos pos, BlockState state) {
        super(AeronauticsInnerworld.ECHO_SOUNDER_ENTITY.get(), pos, state);
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = 4;
        return lastStressApplied;
    }

    public void setHome(BlockPos pos) {
        home = pos.immutable();
        setChanged();
    }

    public BlockPos getMineral() {
        return mineral;
    }

    public BlockPos getHome() {
        return home;
    }

    public BlockPos getWaypoint() {
        return waypoint;
    }

    public BlockPos getRelay() {
        return relay;
    }

    public LavaStalker getThreat() {
        return threat;
    }

    public int getClearance() {
        return clearance;
    }

    private Vec3 position() {
        var ship = Sable.HELPER.getContaining(this);
        return ship == null ? Vec3.atCenterOf(worldPosition)
                : ship.logicalPose().transformPosition(Vec3.atCenterOf(worldPosition));
    }

    public boolean isHudFor(Player player) {
        double distance = player.distanceToSqr(position());
        if (player.isSpectator() || distance > 64) return false;
        var ship = Sable.HELPER.getContaining(this);
        if (ship == null) return Sable.HELPER.getTrackingOrVehicleSubLevel(player) == null;
        if (!ProbeRecovery.contains(ship, player)) return false;
        if (ship instanceof ServerSubLevel serverShip) {
            for (var actor : serverShip.getPlot().getBlockEntityActors()) {
                if (actor instanceof EchoSounderBlockEntity other && other != this && Math.abs(other.getSpeed()) >= 16) {
                    double otherDistance = player.distanceToSqr(other.position());
                    if (otherDistance < distance || otherDistance == distance
                            && other.worldPosition.asLong() < worldPosition.asLong()) return false;
                }
            }
        }
        return true;
    }

    private void updateRecoveryWaypoint() {
        recoveryLoaded = false;
        if (recoveryTarget == null) return;
        var ship = SubLevelContainer.getContainer(level).getSubLevel(recoveryTarget.getUUID("Ship"));
        if (ship == null || ship.isRemoved()) return;
        var current = BlockPos.containing(ship.logicalPose().transformPosition(new Vec3(
                recoveryTarget.getDouble("X"), recoveryTarget.getDouble("Y"), recoveryTarget.getDouble("Z"))));
        if (!current.equals(waypoint)) {
            waypoint = current;
            setChanged();
        }
        recoveryLoaded = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide || Math.abs(getSpeed()) < 16 || !level.dimension().equals(Mantle.LEVEL) || ticks++ % 20 != 0) {
            return;
        }
        scan();
        var position = position();
        var ship = Sable.HELPER.getContaining(this);
        double speed = ship instanceof ServerSubLevel serverShip && RigidBodyHandle.of(serverShip).isValid()
                ? RigidBodyHandle.of(serverShip).getLinearVelocity().length() : 0;
        int heat = 0;
        int coolant = 0;
        int faults = 0;
        if (ship instanceof ServerSubLevel serverShip) {
            for (var actor : serverShip.getPlot().getBlockEntityActors()) {
                if (actor instanceof CabinRegulatorBlockEntity cabin) {
                    heat = Math.max(heat, Math.round(cabin.getHeat()));
                    coolant += cabin.getCoolant();
                } else if (actor instanceof ProbeDriveBlockEntity drive && drive.isFaulted()) {
                    faults++;
                }
            }
        }
        Component bearing = Component.translatable("message.aeronautics_innerworld.sonar_home_bearing", "—");
        var destination = waypoint == null ? home : waypoint;
        if (destination != null) {
            var direction = Direction.getNearest(destination.getX() + .5 - position.x, 0, destination.getZ() + .5 - position.z);
            var distance = Component.translatable("message.aeronautics_innerworld.sonar_bearing",
                    Math.round(position.distanceTo(Vec3.atCenterOf(destination))),
                    Component.translatable("direction.aeronautics_innerworld." + direction.getName()));
            bearing = waypoint == null ? Component.translatable("message.aeronautics_innerworld.sonar_home_bearing", distance)
                    : Component.translatable("message.aeronautics_innerworld.sonar_target_bearing", distance, waypoint.getY());
            if (recoveryTarget != null) bearing = Component.translatable("message.aeronautics_innerworld."
                    + (recoveryLoaded ? "sonar_recovery_bearing" : "sonar_recovery_last_known"), distance, waypoint.getY());
        }
        for (var player : level.players()) {
            if (isHudFor(player)) {
                if (threat != null && threat.isAlive()) {
                    var direction = Direction.getNearest(threat.getX() - position.x, 0, threat.getZ() - position.z);
                    player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_combat_hud",
                            Math.round(position.y), threat.getName(), Math.round(position.distanceTo(threat.position())),
                            Component.translatable("direction.aeronautics_innerworld." + direction.getName()),
                            Math.round(threat.getY()), heat, coolant), true);
                } else {
                    player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_hud",
                            Math.round(position.y), clearance, Math.round(speed * 10) / 10.0,
                            bearing, heat, coolant, faults), true);
                }
            }
        }
    }

    public void scan() {
        updateRecoveryWaypoint();
        var origin = position();
        var center = BlockPos.containing(origin);
        var ship = Sable.HELPER.getContaining(this);
        var direction = Vec3.atLowerCornerOf(getBlockState().getValue(ProbeDeviceBlock.FACING).getNormal());
        if (ship != null) {
            direction = ship.logicalPose().transformNormal(direction);
        }
        clearance = 16;
        for (int i = 1; i <= 16; i++) {
            var pos = BlockPos.containing(origin.add(direction.scale(i)));
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                clearance = i;
                break;
            }
        }
        mineral = null;
        double nearest = 16 * 16;
        for (var pos : BlockPos.betweenClosed(center.offset(-16, -16, -16), center.offset(16, 16, 16))) {
            double distance = pos.distToCenterSqr(origin);
            if (distance <= nearest && level.hasChunkAt(pos) && level.getBlockState(pos).is(AeronauticsInnerworld.HARMONIC_ORE.get())) {
                mineral = pos.immutable();
                nearest = distance;
            }
        }
        relay = null;
        deepWorks = false;
        threat = null;
        if (level instanceof ServerLevel server) {
            double threatDistance = 32 * 32;
            for (var creature : server.getEntitiesOfClass(LavaStalker.class, new AABB(origin, origin).inflate(32))) {
                double distance = creature.distanceToSqr(origin);
                if (creature.isAlive() && distance <= 32 * 32) {
                    creature.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false, true));
                    boolean hostile = !creature.isNeutral() || creature.getTarget() != null;
                    boolean currentHostile = threat != null && (!threat.isNeutral() || threat.getTarget() != null);
                    if (threat == null || hostile && !currentHostile || hostile == currentHostile && distance < threatDistance) {
                        threat = creature;
                        threatDistance = distance;
                    }
                }
            }
            double relayDistance = 128 * 128;
            for (int x = (center.getX() >> 4) - 8; x <= (center.getX() >> 4) + 8; x++) {
                for (int z = (center.getZ() >> 4) - 8; z <= (center.getZ() >> 4) + 8; z++) {
                    var chunk = server.getChunkSource().getChunkNow(x, z);
                    if (chunk == null) {
                        continue;
                    }
                    for (var start : chunk.getAllStarts().values()) {
                        if (start.isValid() && (start.getStructure() instanceof MantleRelayStructure
                                || start.getStructure() instanceof DeepWorksStructure)) {
                            var piece = start.getPieces().getFirst();
                            var pos = piece instanceof MantleRelayPiece station ? station.getEntrance()
                                    : piece instanceof DeepWorksPiece works ? works.getEntrance() : start.getBoundingBox().getCenter();
                            double distance = pos.distToCenterSqr(origin);
                            if (distance <= relayDistance) {
                                relay = pos;
                                deepWorks = start.getStructure() instanceof DeepWorksStructure;
                                relayDistance = distance;
                            }
                        }
                    }
                }
            }
        }
    }

    public void interact(Player player) {
        if (player.isShiftKeyDown()) {
            if (waypoint != null) {
                waypoint = null;
                recoveryTarget = null;
                setChanged();
                player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_route_home"), true);
                return;
            }
            if (!level.getFluidState(BlockPos.containing(position())).isEmpty()) {
                player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_home_wet"), true);
            } else {
                setHome(BlockPos.containing(position()));
                player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_home_set"), true);
            }
            return;
        }
        if (Math.abs(getSpeed()) < 16) {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_no_power"), true);
            return;
        }
        scan();
        if (mineral == null) {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_no_mineral"), false);
        } else {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_mineral",
                    AeronauticsInnerworld.HARMONIC_ORE.get().getName(), mineral.getX(), mineral.getY(), mineral.getZ(),
                    Math.round(position().distanceTo(Vec3.atCenterOf(mineral)) * 10) / 10.0), false);
        }
        if (home != null) {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_home",
                    home.getX(), home.getY(), home.getZ()), false);
        }
        if (waypoint != null) {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_waypoint",
                    waypoint.getX(), waypoint.getY(), waypoint.getZ()), false);
        }
        if (relay == null) {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_no_relay"), false);
        } else {
            var position = position();
            var direction = Direction.getNearest(relay.getX() + .5 - position.x, 0, relay.getZ() + .5 - position.z);
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_relay",
                    Component.translatable("structure.aeronautics_innerworld." + (deepWorks ? "deep_works" : "mantle_relay")),
                    relay.getX(), relay.getY(), relay.getZ(), Math.round(position.distanceTo(Vec3.atCenterOf(relay))),
                    Component.translatable("direction.aeronautics_innerworld." + direction.getName())), false);
        }
        if (threat != null && threat.isAlive()) {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_threat",
                    threat.getName(), threat.blockPosition().getX(), threat.blockPosition().getY(), threat.blockPosition().getZ(),
                    Math.round(position().distanceTo(threat.position()))), false);
            if (threat.isNeutral()) {
                player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.grazer_sample",
                        (threat.getSampleCooldown() + 19) / 20), false);
            }
        }
        if (Sable.HELPER.getContaining(this) instanceof ServerSubLevel ship) {
            for (var actor : ship.getPlot().getBlockEntityActors()) {
                BlockPos problem = null;
                if (actor instanceof ProbeDriveBlockEntity drive && drive.isFaulted()) {
                    problem = drive.getBlockPos();
                } else if (actor instanceof CabinRegulatorBlockEntity cabin) {
                    problem = cabin.getDamagedPlate();
                }
                if (problem != null) {
                    var position = ship.logicalPose().transformPosition(Vec3.atCenterOf(problem));
                    player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_fault",
                            level.getBlockState(problem).isAir() ? Component.translatable("message.aeronautics_innerworld.hull_breach")
                                    : level.getBlockState(problem).getBlock().getName(), Math.round(position.x),
                            Math.round(position.y), Math.round(position.z)), false);
                }
            }
        }
    }

    public void useCompass(Player player, ItemStack stack) {
        if (!level.dimension().equals(Mantle.LEVEL) || Math.abs(getSpeed()) < 16) {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_no_power"), true);
            return;
        }
        var tracker = stack.get(DataComponents.LODESTONE_TRACKER);
        if (stack.is(Items.RECOVERY_COMPASS)) {
            var death = player.getLastDeathLocation();
            if (death.isEmpty()) {
                player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_no_death"), true);
                return;
            }
            if (!death.get().dimension().equals(level.dimension())) {
                player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_route_dimension"), true);
                return;
            }
            waypoint = death.get().pos().immutable();
            recoveryTarget = ProbeRecovery.deathTarget(player);
            updateRecoveryWaypoint();
        } else if (tracker != null) {
            if (tracker.target().isEmpty() || !tracker.target().get().dimension().equals(level.dimension())) {
                player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_route_dimension"), true);
                return;
            }
            waypoint = tracker.target().get().pos().immutable();
            recoveryTarget = null;
        } else {
            scan();
            recoveryTarget = null;
            waypoint = relay != null ? relay : mineral != null ? mineral : BlockPos.containing(position());
            var recorded = stack.getCount() == 1 ? stack : stack.copyWithCount(1);
            // Untracked lodestone targets retain discovered coordinates after a site or ore is removed.
            recorded.set(DataComponents.LODESTONE_TRACKER,
                    new LodestoneTracker(java.util.Optional.of(GlobalPos.of(level.dimension(), waypoint)), false));
            recorded.set(DataComponents.ITEM_NAME, Component.translatable("item.aeronautics_innerworld.route_compass"));
            if (recorded != stack) {
                stack.shrink(1);
                if (!player.getInventory().add(recorded)) player.drop(recorded, false);
            }
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_route_saved"), false);
        }
        setChanged();
        player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.sonar_waypoint",
                waypoint.getX(), waypoint.getY(), waypoint.getZ()), false);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (home != null) {
            tag.putLong("Home", home.asLong());
        }
        if (waypoint != null) tag.putLong("Waypoint", waypoint.asLong());
        if (recoveryTarget != null) tag.put("RecoveryTarget", recoveryTarget.copy());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        home = tag.contains("Home") ? BlockPos.of(tag.getLong("Home")) : null;
        waypoint = tag.contains("Waypoint") ? BlockPos.of(tag.getLong("Waypoint")) : null;
        var target = tag.getCompound("RecoveryTarget");
        recoveryTarget = waypoint != null && target.hasUUID("Ship") ? target.copy() : null;
        recoveryLoaded = false;
    }
}
