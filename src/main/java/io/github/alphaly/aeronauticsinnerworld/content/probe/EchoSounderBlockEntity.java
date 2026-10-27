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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
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
    public enum Readout { NAVIGATION, SYSTEMS, SURVEY, DAMAGE }

    private final EnumMap<Readout, List<MutableComponent>> readouts = new EnumMap<>(Readout.class);
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
    private boolean hasScan;

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
        if (level != null) updateReadouts();
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
    }

    public List<MutableComponent> getReadout(Readout page) {
        if (level == null || !level.dimension().equals(Mantle.LEVEL)) {
            return List.of(reading("sounder_dimension"));
        }
        if (Math.abs(getSpeed()) < 16) return List.of(reading("sounder_unpowered").withStyle(ChatFormatting.GOLD));
        if (!hasScan) return List.of(reading("sounder_scanning"));
        var lines = readouts.get(page);
        return lines == null ? List.of(reading("sounder_scanning")) : lines.stream().map(MutableComponent::copy).toList();
    }

    private static MutableComponent reading(String key, Object... args) {
        return Component.translatable("goggle.aeronautics_innerworld." + key, args);
    }

    private static MutableComponent coordinates(BlockPos pos) {
        return reading("coordinates", pos.getX(), pos.getY(), pos.getZ());
    }

    private Component bearing(Vec3 origin, BlockPos destination) {
        var direction = Direction.getNearest(destination.getX() + .5 - origin.x, 0, destination.getZ() + .5 - origin.z);
        return Component.translatable("message.aeronautics_innerworld.sonar_bearing",
                Math.round(origin.distanceTo(Vec3.atCenterOf(destination))),
                Component.translatable("direction.aeronautics_innerworld." + direction.getName()));
    }

    private void updateReadouts() {
        var origin = position();
        var ship = Sable.HELPER.getContaining(this);
        double speed = ship instanceof ServerSubLevel serverShip && RigidBodyHandle.of(serverShip).isValid()
                ? RigidBodyHandle.of(serverShip).getLinearVelocity().length() : 0;
        int heat = 0, coolant = 0, cabins = 0, sealed = 0, engines = 0, running = 0, faults = 0;
        var damage = new ArrayList<MutableComponent>();
        var damagedPositions = new java.util.HashSet<BlockPos>();
        if (ship instanceof ServerSubLevel serverShip) {
            for (var actor : serverShip.getPlot().getBlockEntityActors()) {
                BlockPos problem = null;
                if (actor instanceof CabinRegulatorBlockEntity cabin) {
                    cabins++;
                    if (cabin.isSealed()) sealed++;
                    heat = Math.max(heat, Math.round(cabin.getHeat()));
                    coolant += cabin.getCoolant();
                    problem = cabin.getDamagedPlate();
                } else if (actor instanceof ProbeDriveBlockEntity drive && drive.isFaulted()) {
                    problem = drive.getBlockPos();
                } else if (actor instanceof HeatEngineBlockEntity engine) {
                    engines++;
                    if (engine.isRunning()) running++;
                }
                if (problem != null && damagedPositions.add(problem)) {
                    faults++;
                    if (damage.size() < 8) {
                        damage.add(reading("damaged_device", level.getBlockState(problem).isAir()
                                ? Component.translatable("message.aeronautics_innerworld.hull_breach")
                                : level.getBlockState(problem).getBlock().getName()).withStyle(ChatFormatting.GOLD));
                        damage.add(coordinates(BlockPos.containing(ship.logicalPose().transformPosition(Vec3.atCenterOf(problem)))));
                    }
                }
            }
        }
        var navigation = new ArrayList<MutableComponent>();
        navigation.add(coordinates(BlockPos.containing(origin)));
        navigation.add(reading("clearance", clearance).withStyle(clearance <= 4 ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        navigation.add(reading("velocity", Math.round(speed * 10) / 10.0));
        var destination = waypoint == null ? home : waypoint;
        if (destination == null) navigation.add(reading("no_home"));
        else {
            String key = recoveryTarget != null ? recoveryLoaded ? "sonar_recovery_bearing" : "sonar_recovery_last_known"
                    : waypoint == null ? "sonar_home_bearing" : "sonar_target_bearing";
            navigation.add(Component.translatable("message.aeronautics_innerworld." + key,
                    bearing(origin, destination), destination.getY()));
            navigation.add(coordinates(destination));
        }
        readouts.put(Readout.NAVIGATION, navigation);
        readouts.put(Readout.SYSTEMS, List.of(
                reading("cabins", sealed, cabins).withStyle(sealed < cabins || cabins == 0 ? ChatFormatting.GOLD : ChatFormatting.AQUA),
                reading("heat", heat).withStyle(heat >= 80 ? ChatFormatting.RED : heat >= 60 ? ChatFormatting.GOLD : ChatFormatting.GRAY),
                reading("coolant_total", coolant, cabins * 600).withStyle(coolant <= cabins * 120 ? ChatFormatting.GOLD : ChatFormatting.AQUA),
                reading("engines", running, engines),
                reading("faults", faults).withStyle(faults > 0 ? ChatFormatting.GOLD : ChatFormatting.GRAY)));
        var survey = new ArrayList<MutableComponent>();
        survey.add(mineral == null ? reading("no_mineral") : reading("mineral", bearing(origin, mineral)));
        if (mineral != null) survey.add(coordinates(mineral));
        survey.add(relay == null ? reading("no_relay") : reading("relay",
                Component.translatable("structure.aeronautics_innerworld." + (deepWorks ? "deep_works" : "mantle_relay")), bearing(origin, relay)));
        if (relay != null) survey.add(coordinates(relay));
        survey.add(threat == null || !threat.isAlive() ? reading("no_contact")
                : reading("contact", threat.getName(), bearing(origin, threat.blockPosition())).withStyle(ChatFormatting.GOLD));
        if (threat != null && threat.isAlive()) {
            survey.add(coordinates(threat.blockPosition()));
            if (threat.isNeutral()) survey.add(reading("sample_wait", (threat.getSampleCooldown() + 19) / 20));
        }
        readouts.put(Readout.SURVEY, survey);
        if (damage.isEmpty()) damage.add(reading("no_damage"));
        if (faults > 4) damage.add(reading("more_damage", faults - 4));
        readouts.put(Readout.DAMAGE, damage);
        sendData();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        super.addToGoggleTooltip(tooltip, sneaking);
        for (var page : sneaking ? List.of(Readout.SURVEY, Readout.DAMAGE) : List.of(Readout.NAVIGATION, Readout.SYSTEMS)) {
            tooltip.add(Component.empty());
            tooltip.add(Component.literal("    ").append(reading(page.name().toLowerCase(java.util.Locale.ROOT)))
                    .withStyle(ChatFormatting.GRAY));
            for (var line : getReadout(page)) tooltip.add(Component.literal("    ").append(line));
        }
        if (!sneaking) tooltip.add(Component.literal("    ").append(reading("sounder_details")).withStyle(ChatFormatting.DARK_GRAY));
        return true;
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
        hasScan = true;
        updateReadouts();
    }

    public void interact(Player player) {
        if (player.isShiftKeyDown()) {
            if (waypoint != null) {
                waypoint = null;
                recoveryTarget = null;
                setChanged();
                updateReadouts();
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
        updateReadouts();
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
        if (clientPacket) {
            tag.putBoolean("HasScan", hasScan);
            var data = new CompoundTag();
            readouts.forEach((page, lines) -> {
                var list = new ListTag();
                for (var line : lines) list.add(StringTag.valueOf(Component.Serializer.toJson(line, registries)));
                data.put(page.name(), list);
            });
            tag.put("Readouts", data);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        home = tag.contains("Home") ? BlockPos.of(tag.getLong("Home")) : null;
        waypoint = tag.contains("Waypoint") ? BlockPos.of(tag.getLong("Waypoint")) : null;
        var target = tag.getCompound("RecoveryTarget");
        recoveryTarget = waypoint != null && target.hasUUID("Ship") ? target.copy() : null;
        recoveryLoaded = false;
        hasScan = clientPacket && tag.getBoolean("HasScan");
        readouts.clear();
        if (clientPacket) {
            var data = tag.getCompound("Readouts");
            for (var page : Readout.values()) {
                if (!data.contains(page.name())) continue;
                var lines = new ArrayList<MutableComponent>();
                for (var line : data.getList(page.name(), Tag.TAG_STRING)) {
                    var text = Component.Serializer.fromJson(line.getAsString(), registries);
                    if (text != null) lines.add(text);
                }
                readouts.put(page, lines);
            }
        }
    }
}
