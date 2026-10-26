package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.PressureHatchBlock;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeRecovery;
import io.github.alphaly.aeronauticsinnerworld.integration.ProbeTestRig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class RelayVoyage {
    private final ServerPlayer player;
    private final ServerLevel level;
    private final BlockPos intake, landing, launch, berth, base, baseDoor;
    private final BlockPos obstruction;
    private final BlockState obstructionState;
    private final BoundingBox relayBounds;
    private final List<Vec3> outward, homeward;
    private final Map<BlockPos, BlockState> terrain = new HashMap<>();
    private final Map<BlockPos, BlockState> assemblyWater = new HashMap<>();
    private final long started;
    private final boolean transferOnly = Boolean.getBoolean("innerworld.relayTransfer");
    private ProbeTestRig rig;
    private int age, observed = -1, frames, leg, settled;
    private long since;
    private float minimumHealth = 20;
    private boolean alignmentStopping;
    private boolean placedCraftingInputs;
    private volatile int stage, left, right, trim, currentLeftTrim, currentRightTrim, coolant;
    private volatile BlockPos waypoint;
    private volatile Vec3 walkTarget;
    private volatile Vec3 swimOrigin;
    private volatile CoreMazeWalk dryWalk;
    private final ArrayDeque<Vec3> swimming = new ArrayDeque<>();

    RelayVoyage(ServerPlayer player, ServerLevel level, BlockPos intake, BlockPos landing, BoundingBox bounds) {
        this.player = player;this.level = level;this.intake = intake;this.landing = landing;relayBounds = bounds;
        var plan = new RelayVoyagePlan(level, intake);
        obstruction = plan.obstruction;obstructionState = level.getBlockState(obstruction);
        launch = plan.route.getFirst();berth = plan.route.getLast();
        outward = transferOnly ? List.of(Vec3.atCenterOf(berth), Vec3.atCenterOf(berth))
                : plan.route.stream().map(Vec3::atCenterOf).toList();
        var reversed = new ArrayList<>(outward);java.util.Collections.reverse(reversed);homeward = List.copyOf(reversed);
        base = launch.offset(-6, 0, -1);baseDoor = base.east(2);
        started = level.getGameTime();since = started;
        LogUtils.getLogger().info("Natural relay voyage route={}, base={}, intake={}, direct obstruction={} {}", plan.route, base, intake,
                obstruction, obstructionState.getBlock().getName().getString());
    }

    void build() {
        for (var pos : BlockPos.betweenClosed(base.offset(-2, -1, -2), base.offset(2, 2, 2)))
            if (level.getBlockEntity(pos) != null) throw new IllegalStateException("Voyage outpost would overwrite an existing container");
        for (int x = -2; x <= 2; x++) for (int y = -1; y <= 2; y++) for (int z = -2; z <= 2; z++)
            place(base.offset(x, y, z), x == -2 || x == 2 || y == -1 || y == 2 || z == -2 || z == 2
                    ? AeronauticsInnerworld.REFRACTORY_HULL.get().defaultBlockState() : Blocks.AIR.defaultBlockState());
        place(base.above(2), Blocks.SEA_LANTERN.defaultBlockState());
        place(base.west(), Blocks.CRAFTING_TABLE.defaultBlockState());
        var hatch = AeronauticsInnerworld.PRESSURE_HATCH.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        place(baseDoor, hatch);place(baseDoor.above(), hatch);
        var origin = (transferOnly ? berth : launch).offset(-2, -1, -3);
        for (var pos : BlockPos.betweenClosed(origin.offset(-1, -2, -2), origin.offset(5, 5, 8)))
            assemblyWater.put(pos.immutable(), level.getBlockState(pos));
        rig = new ProbeTestRig(level, origin, false);rig.workEquipment();rig.survivalPower();
        for (var pos : BlockPos.betweenClosed(origin.offset(-1, -1, -1), origin.offset(5, 4, 7))) {
            if (level.getBlockEntity(pos) instanceof PortableEngineBlockEntity engine)
                engine.inventory.setItem(0, pos.equals(origin.offset(ProbeTestRig.SAMPLER.south()))
                        ? ItemStack.EMPTY : new ItemStack(Items.COAL, 16));
        }
        var sampler = (io.github.alphaly.aeronauticsinnerworld.content.probe.MantleSamplerBlockEntity) level.getBlockEntity(origin.offset(ProbeTestRig.SAMPLER));
        for (int slot = 0; slot < 9; slot++) sampler.inventory.setStackInSlot(slot, ItemStack.EMPTY);
        ((io.github.alphaly.aeronauticsinnerworld.content.probe.EchoSounderBlockEntity) level.getBlockEntity(origin.offset(ProbeTestRig.SOUNDER))).setHome(base);
    }

    Vec3 initialPosition() {return Vec3.atBottomCenterOf(base);}
    boolean atRelay() {return stage == 17;}
    boolean returned() {return transferOnly ? stage == 36 : stage == 35;}

    void beginReturn() {
        next(19);
    }

    void tickClient(Minecraft client) {
        if (observed != stage) {
            release(client);
            if (stage != 40 && client.player.containerMenu != client.player.inventoryMenu) client.player.closeContainer();
            observed = stage;frames = 0;
        }
        frames++;
        if (stage == 40) {craftMask(client);return;}
        if (stage == 41) {
            if (frames % 5 == 0) for (int slot = 0; slot < 9; slot++)
                if (client.player.getInventory().getItem(slot).is(AeronauticsInnerworld.HEATWEAVE_HELMET.get())) {
                    client.player.getInventory().selected = slot;client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);break;
                }
            return;
        }
        boolean inside = stage == 9 || stage == 10 || stage == 11 || stage == 12 || stage == 25 || stage == 26 || stage == 27;
        if (inside && coolant < 60 && frames % 10 == 0
                && client.player.getInventory().getItem(0).is(AeronauticsInnerworld.COOLANT_CELL.get())) {
            client.player.getInventory().selected = 0;use(client, rig.local(ProbeTestRig.CABIN));return;
        }
        if ((stage == 9 || stage == 19 || stage == 25) && (client.player.getHealth() < 18 || client.player.getFoodData().getFoodLevel() < 18)) {
            client.player.getInventory().selected = 1;drinkOrEat(client);return;
        }
        if (stage == 1 || stage == 12 || stage == 19 || stage == 25) {
            var effect = client.player.getEffect(MobEffects.FIRE_RESISTANCE);
            if (effect == null || effect.getDuration() < (stage == 25 ? 6000 : 1200)) {
                int slot = client.player.getInventory().getItem(3).is(Items.POTION) ? 3 : 4;
                client.player.getInventory().selected = slot;drinkOrEat(client);return;
            }
        }
        client.options.keyUse.setDown(false);
        if (client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);
        client.player.getInventory().selected = 2;
        if (stage == 9 && frames % 10 == 0 && !intake.equals(waypoint)) {
            client.player.getInventory().selected = 5;use(client, rig.local(ProbeTestRig.SOUNDER));return;
        }
        if (stage == 25 && frames % 10 == 0 && waypoint != null) {
            client.player.setShiftKeyDown(true);
            client.player.connection.send(new ServerboundPlayerCommandPacket(client.player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
            use(client, rig.local(ProbeTestRig.SOUNDER));
            client.player.setShiftKeyDown(false);
            client.player.connection.send(new ServerboundPlayerCommandPacket(client.player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));return;
        }
        if (stage == 20) {dryWalk.tickClient(client);return;}
        if (stage == 10 || stage == 11 || stage == 26 || stage == 27) {
            if (frames % 6 == 0) {
                lever(client, ProbeTestRig.LEFT_THROTTLE, left == 0);lever(client, ProbeTestRig.RIGHT_THROTTLE, right == 0);
                lever(client, ProbeTestRig.LEFT_REVERSE, left < 0);lever(client, ProbeTestRig.RIGHT_REVERSE, right < 0);
                for (int x : new int[] {1, 3}) {
                    var relative = new BlockPos(x, 0, 2);
                    if ((x == 1 ? currentLeftTrim : currentRightTrim) != trim) {
                        client.player.setShiftKeyDown(true);
                        client.player.connection.send(new ServerboundPlayerCommandPacket(client.player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
                        use(client, rig.local(relative));
                        client.player.setShiftKeyDown(false);
                        client.player.connection.send(new ServerboundPlayerCommandPacket(client.player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                    }
                }
            }
            return;
        }
        BlockPos door = switch (stage) {
            case 2, 4, 32, 34 -> baseDoor;
            case 6, 8, 13, 15, 22, 24, 28, 30 -> rig.local(ProbeTestRig.BOARDING_HATCH);
            default -> null;
        };
        if (door != null && frames == 5) use(client, door);
        var target = walkTarget;
        if (target != null) {
            if (stage == 5 || stage == 16 || stage == 21 || stage == 31) swim(client, target);
            else walk(client, target);
        }
    }

    void advance() {
        level.resetEmptyTime();minimumHealth = Math.min(minimumHealth, player.getHealth());
        if (rig.subLevel != null) {
            coolant = rig.cabin().getCoolant();waypoint = rig.sounder().getWaypoint();
            currentLeftTrim = ((ProbeDriveBlockEntity) level.getBlockEntity(rig.local(new BlockPos(1, 0, 2)))).getTrimMode();
            currentRightTrim = ((ProbeDriveBlockEntity) level.getBlockEntity(rig.local(new BlockPos(3, 0, 2)))).getTrimMode();
            if (stage == 7 || stage == 23) walkTarget = boatInside();
            if (stage == 14 || stage == 29) walkTarget = boatOutside();
        }
        int stageLimit = stage == 10 || stage == 11 || stage == 26 || stage == 27 ? 2400 : 1200;
        if (stage != 17 && level.getGameTime() - since > stageLimit)
            throw new IllegalStateException("Natural voyage stage timed out: " + stage + ", crew=" + player.position() + ", target=" + walkTarget);
        if (!player.isAlive() || level.getGameTime() - started > 18000)
            throw new IllegalStateException("Natural voyage failed at " + stage + ", player=" + player.position() + ", health=" + player.getHealth());
        if (++age % 200 == 0) LogUtils.getLogger().info("Natural voyage stage={}, leg={}, ship={}, heading={}, crew={}, health={}, coolant={}, target={}",
                stage, leg, rig.subLevel == null ? launch : rig.world(ProbeTestRig.SOUNDER), rig.subLevel == null ? 0 : Math.toDegrees(heading()),
                player.position(), player.getHealth(), rig.subLevel == null ? 0 : rig.cabin().getCoolant(), walkTarget);
        if (stage == 0) {
            if (rig.subLevel == null) {if (!rig.ready()) return;rig.assemble();assemblyWater.forEach(level::setBlockAndUpdate);}
            if (age >= 40 && rig.cabin().isSealed()) next(40);
        } else if (stage == 40 && player.getInventory().countItem(AeronauticsInnerworld.HEATWEAVE_HELMET.get()) == 1) {
            if (player.getInventory().countItem(AeronauticsInnerworld.MOLTEN_SCALE.get()) != 0
                    || player.getInventory().countItem(com.simibubi.create.AllItems.COPPER_SHEET.get()) != 0
                    || player.getInventory().countItem(Items.GLASS) != 0)
                throw new IllegalStateException("Early mask crafting did not consume its native ingredients");
            next(41);
        } else if (stage == 41 && player.getItemBySlot(EquipmentSlot.HEAD).is(AeronauticsInnerworld.HEATWEAVE_HELMET.get())) {
                LogUtils.getLogger().info("Early expedition mask crafted and equipped through native workbench input before recovering alloy");
                if (transferOnly) {
                    var point = boatInside();player.teleportTo(level, point.x, point.y, point.z, 0, 0);next(9);
                } else next(1);
        } else if (stage == 1 && player.hasEffect(MobEffects.FIRE_RESISTANCE)) next(2);
        else if (stage == 2 && open(baseDoor)) {walkTarget = Vec3.atBottomCenterOf(baseDoor.east());next(3);}
        else if (stage == 3 && reached(walkTarget, .5)) {walkTarget = null;next(4);}
        else if (stage == 4 && !open(baseDoor)) {swimTo(boatOutside());next(5);}
        else if (stage == 5 && swimStep()) next(6);
        else if (stage == 6 && boatOpen()) {walkTarget = boatInside();next(7);}
        else if (stage == 7 && reached(walkTarget, .5) && fullyInside()) {walkTarget = null;next(8);}
        else if (stage == 8 && !boatOpen() && rig.cabin().isSealed()) next(9);
        else if (stage == 9 && rig.cabin().getCoolant() >= 60 && player.getHealth() >= 18 && player.getFoodData().getFoodLevel() >= 18
                && intake.equals(rig.sounder().getWaypoint())) {
            leg = 1;next(10);
        } else if (stage == 10 && navigate(outward)) next(11);
        else if (stage == 11 && align(Math.atan2(intake.getZ() - berth.getZ(), berth.getX() - intake.getX()))) next(12);
        else if (stage == 12 && fireReady(1200)) next(13);
        else if (stage == 13 && boatOpen()) {walkTarget = boatOutside();next(14);}
        else if (stage == 14 && reached(walkTarget, .5) && fullyOutside()) {walkTarget = null;next(15);}
        else if (stage == 15 && !boatOpen()) {swimTo(Vec3.atBottomCenterOf(landing));next(16);}
        else if (stage == 16 && swimStep()) {
            LogUtils.getLogger().info("Natural fueled voyage reached relay gallery: health={}, coolant={}", player.getHealth(), rig.cabin().getCoolant());next(17);
        } else if (stage == 19 && player.getHealth() >= player.getMaxHealth() && player.getFoodData().getFoodLevel() >= 18 && fireReady(1200)) {
            dryWalk = new CoreMazeWalk(level, relayBounds, player.blockPosition(), landing, false);next(20);
        } else if (stage == 20) {
            dryWalk.advance(player.position());
            if (dryWalk.arrived()) {dryWalk = null;swimTo(boatOutside());next(21);}
        } else if (stage == 21 && swimStep()) next(22);
        else if (stage == 22 && boatOpen()) {walkTarget = boatInside();next(23);}
        else if (stage == 23 && reached(walkTarget, .5) && fullyInside()) {walkTarget = null;next(24);}
        else if (stage == 24 && !boatOpen() && rig.cabin().isSealed()) next(25);
        else if (stage == 25 && rig.cabin().getCoolant() >= 60 && player.getHealth() >= 18 && player.getFoodData().getFoodLevel() >= 18
                && fireReady(6000) && rig.sounder().getWaypoint() == null) {
            if (transferOnly) next(36);else {leg = 1;next(26);}
        }
        else if (stage == 26 && navigate(homeward)) next(27);
        else if (stage == 27 && align(0)) next(28);
        else if (stage == 28 && boatOpen()) {walkTarget = boatOutside();next(29);}
        else if (stage == 29 && reached(walkTarget, .5) && fullyOutside()) {walkTarget = null;next(30);}
        else if (stage == 30 && !boatOpen()) {swimTo(Vec3.atBottomCenterOf(baseDoor.east()));next(31);}
        else if (stage == 31 && swimStep()) next(32);
        else if (stage == 32 && open(baseDoor)) {walkTarget = Vec3.atBottomCenterOf(base);next(33);}
        else if (stage == 33 && reached(walkTarget, .5) && level.getFluidState(BlockPos.containing(player.getEyePosition())).isEmpty()) {walkTarget = null;next(34);}
        else if (stage == 34 && !open(baseDoor)) {
            if (!level.getBlockState(obstruction).equals(obstructionState))
                throw new IllegalStateException("Natural voyage removed its direct-path obstruction");
            LogUtils.getLogger().info("Natural relay cargo returned to dry outpost: health={}, minimum={}, coolant={}, alloy={}, ticks={}",
                    player.getHealth(), minimumHealth, rig.cabin().getCoolant(), player.getInventory().countItem(AeronauticsInnerworld.RELIC_ALLOY.get()), level.getGameTime() - started);next(35);
        }
    }

    private boolean navigate(List<Vec3> route) {
        var target = route.get(leg);var position = rig.world(ProbeTestRig.SOUNDER);var delta = target.subtract(position);
        var body = RigidBodyHandle.of(rig.subLevel);var velocity = body.getLinearVelocity();
        double horizontal = delta.horizontalDistance();
        var previous = route.get(leg - 1);var segment = target.subtract(previous);
        double progress = segment.horizontalDistanceSqr() < .01 ? 1 : Mth.clamp(
                ((position.x - previous.x) * segment.x + (position.z - previous.z) * segment.z) / segment.horizontalDistanceSqr(), 0, 1);
        double height = horizontal < .8 ? target.y : previous.y + segment.y * progress;
        double vertical = height - position.y - velocity.y() * 1.2;
        trim = vertical > .25 ? 1 : vertical < -.25 ? -1 : 0;
        if (horizontal < .8) {
            left = right = 0;
            if (Math.abs(delta.y) < .5 && velocity.length() < .15) {
                LogUtils.getLogger().info("Natural voyage leg {} reached at {}", leg, position);
                settled = 0;since = level.getGameTime();
                if (++leg >= route.size()) {trim = 0;return true;}
            }
            return false;
        }
        int thrust = route == homeward && leg == route.size() - 1 ? -1 : 1;
        double error = wrap(Math.atan2(delta.x * thrust, delta.z * thrust) - heading());
        double turn = error - body.getAngularVelocity().y() * 4;
        if (Math.abs(error) > Math.toRadians(12)) {left = turn > 0 ? 1 : -1;right = -left;return false;}
        var forward = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, 1));
        double speed = velocity.x() * forward.x + velocity.z() * forward.z;
        if (speed * thrust > Math.min(.8, horizontal * .3) + .05) {left = right = 0;return false;}
        left = right = thrust;
        if (Math.abs(turn) > Math.toRadians(3)) {if (turn * thrust > 0) right = 0;else left = 0;}
        return false;
    }

    private boolean align(double target) {
        trim = 0;
        var body = RigidBodyHandle.of(rig.subLevel);double rate = body.getAngularVelocity().y();
        double error = wrap(target - heading());
        if (alignmentStopping) {
            left = right = 0;
            for (var drive : List.of(ProbeTestRig.LEFT_DRIVE, ProbeTestRig.RIGHT_DRIVE))
                if (((ProbeDriveBlockEntity) level.getBlockEntity(rig.local(drive))).getSpeed() != 0) return false;
            if (++settled < 20) return false;
            if (Math.abs(error) < Math.toRadians(8) && Math.abs(rate) < .015) return true;
            alignmentStopping = false;settled = 0;
        }
        if (Math.abs(error) < Math.toRadians(5) && Math.abs(rate) < .03) {alignmentStopping = true;left = right = 0;}
        else {left = error - rate * 4 > 0 ? 1 : -1;right = -left;}
        return false;
    }

    private double heading() {
        var forward = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, 1));return Math.atan2(forward.x, forward.z);
    }
    private static double wrap(double radians) {return Math.toRadians(Mth.wrapDegrees(Math.toDegrees(radians)));}
    private Vec3 boatOutside() {return rig.world(new BlockPos(-1, 1, 2)).add(0, -.45, 0);}
    private Vec3 boatInside() {return rig.world(new BlockPos(1, 1, 2)).add(0, -.45, 0);}
    private boolean reached(Vec3 point, double tolerance) {return player.position().distanceTo(point) < tolerance;}
    private boolean fireReady(int duration) {
        var effect = player.getEffect(MobEffects.FIRE_RESISTANCE);return effect != null && effect.getDuration() >= duration;
    }
    private boolean fullyOutside() {
        var box = player.getBoundingBox();double plane = rig.local(ProbeTestRig.BOARDING_HATCH).getX();
        for (double x : new double[] {box.minX, box.maxX}) for (double y : new double[] {box.minY, box.maxY})
            for (double z : new double[] {box.minZ, box.maxZ})
                if (rig.subLevel.logicalPose().transformPositionInverse(new Vec3(x, y, z)).x >= plane - .03) return false;
        return true;
    }
    private boolean fullyInside() {
        var box = player.getBoundingBox();double plane = rig.local(ProbeTestRig.BOARDING_HATCH).getX() + 1.03;
        for (double x : new double[] {box.minX, box.maxX}) for (double y : new double[] {box.minY, box.maxY})
            for (double z : new double[] {box.minZ, box.maxZ})
                if (rig.subLevel.logicalPose().transformPositionInverse(new Vec3(x, y, z)).x < plane) return false;
        return ProbeRecovery.contains(rig.subLevel, player);
    }
    private boolean open(BlockPos pos) {return level.getBlockState(pos).getValue(PressureHatchBlock.OPEN);}
    private boolean boatOpen() {return open(rig.local(ProbeTestRig.BOARDING_HATCH));}
    private void next(int value) {
        settled = 0;alignmentStopping = false;since = level.getGameTime();stage = value;
        LogUtils.getLogger().info("Natural voyage stage {}: crew={}, target={}", value, player.position(), walkTarget);
    }


    private void swimTo(Vec3 destination) {
        var from = player.blockPosition();var goal = BlockPos.containing(destination);
        var parents = new HashMap<BlockPos, BlockPos>();var queue = new ArrayDeque<BlockPos>();
        parents.put(from, from);queue.add(from);
        BlockPos end = null;
        while (!queue.isEmpty()) {
            var pos = queue.remove();
            if (pos.equals(goal)) {end = pos;break;}
            for (var direction : Direction.values()) {
                var next = pos.relative(direction);
                if (parents.containsKey(next) || next.getX() < Math.min(from.getX(), goal.getX()) - 4
                        || next.getX() > Math.max(from.getX(), goal.getX()) + 4 || next.getZ() < Math.min(from.getZ(), goal.getZ()) - 4
                        || next.getZ() > Math.max(from.getZ(), goal.getZ()) + 4 || next.getY() < Math.min(from.getY(), goal.getY()) - 3
                        || next.getY() > Math.max(from.getY(), goal.getY()) + 3
                        || !level.getBlockState(next).getCollisionShape(level, next).isEmpty()
                        || !level.getBlockState(next.above()).getCollisionShape(level, next.above()).isEmpty()
                        || !level.getFluidState(next).is(FluidTags.LAVA)
                                && !level.getFluidState(next.below()).is(FluidTags.LAVA)
                                && !level.getBlockState(next.below()).isFaceSturdy(level, next.below(), Direction.UP)) continue;
                parents.put(next, pos);queue.add(next);
            }
        }
        if (end == null) throw new IllegalStateException("No native swimming path from " + from + " to " + goal);
        swimming.clear();
        for (var point = end; !point.equals(from); point = parents.get(point))
            swimming.addFirst(point.equals(goal) ? destination : Vec3.atBottomCenterOf(point));
        if (swimming.isEmpty()) swimming.add(destination);
        var path = new ArrayList<>(swimming);swimming.clear();
        var previous = player.position();
        for (int index = 0; index < path.size();) {
            int next = path.size() - 1;
            while (next > index && !swimClear(previous, path.get(next))) next--;
            previous = path.get(next);swimming.add(previous);index = next + 1;
        }
        LogUtils.getLogger().info("Native swim route from {}: {}", player.position(), swimming);
        swimOrigin = player.position();walkTarget = swimming.poll();
    }

    private boolean swimClear(Vec3 from, Vec3 to) {
        int steps = Math.max(1, (int)Math.ceil(from.distanceTo(to) * 5));
        for (int i = 1; i <= steps; i++) {
            var point = from.lerp(to, i / (double)steps);
            var box = new net.minecraft.world.phys.AABB(point.x - .31, point.y + .01, point.z - .31,
                    point.x + .31, point.y + 1.8, point.z + .31);
            if (level.getBlockCollisions(player, box).iterator().hasNext()) return false;
        }
        return true;
    }

    private boolean swimStep() {
        if (walkTarget != null && reached(walkTarget, .55)) {swimOrigin = walkTarget;walkTarget = swimming.poll();}
        return walkTarget == null;
    }
    private void swim(Minecraft client, Vec3 target) {
        var from = swimOrigin;
        double height = target.y - from.y;
        if (Math.abs(height) > .5) {
            double progress = Mth.clamp((client.player.getY() - from.y) / height, 0, 1);
            // Lava ascent is slower than forward motion; follow the cleared segment instead of reaching its X/Z first.
            var horizontal = from.lerp(target, progress);
            walk(client, new Vec3(horizontal.x, target.y, horizontal.z));
        } else walk(client, target);
    }
    private static void walk(Minecraft client, Vec3 point) {
        var delta = point.subtract(client.player.position());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));client.player.setXRot(-15);
        client.options.keyUp.setDown(delta.horizontalDistanceSqr() > .0025);
        client.options.keyJump.setDown(delta.y > .03);
        client.options.keyShift.setDown(delta.y < -.5 && delta.horizontalDistanceSqr() < .16
                && client.player.isInLava() && !client.player.onGround());
    }
    private void lever(Minecraft client, BlockPos relative, boolean powered) {
        var pos = rig.local(relative);var state = client.level.getBlockState(pos);
        if (state.getValue(LeverBlock.POWERED) != powered) use(client, pos);
    }
    private static void use(Minecraft client, BlockPos pos) {
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }
    private static void drinkOrEat(Minecraft client) {
        if (client.player.containerMenu != client.player.inventoryMenu) client.player.closeContainer();
        client.player.setXRot(-80);
        client.options.keyUse.setDown(true);
        if (!client.player.isUsingItem()) client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
    }
    private void craftMask(Minecraft client) {
        client.player.getInventory().selected = 2;
        if (!(client.player.containerMenu instanceof CraftingMenu menu)) {
            if (frames % 10 == 0) use(client, base.west());
            return;
        }
        if (!placedCraftingInputs) {
            click(client, menu, 43, 0, ClickType.PICKUP);
            for (int slot : new int[] {1, 2, 3}) click(client, menu, slot, 1, ClickType.PICKUP);
            click(client, menu, 44, 0, ClickType.PICKUP);
            for (int slot : new int[] {4, 6}) click(client, menu, slot, 1, ClickType.PICKUP);
            click(client, menu, 45, 0, ClickType.PICKUP);click(client, menu, 5, 0, ClickType.PICKUP);
            placedCraftingInputs = true;
        }
        if (frames % 5 == 0 && menu.getSlot(0).getItem().is(AeronauticsInnerworld.HEATWEAVE_HELMET.get()))
            click(client, menu, 0, 0, ClickType.QUICK_MOVE);
    }
    private static void click(Minecraft client, CraftingMenu menu, int slot, int button, ClickType type) {
        client.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, client.player);
    }
    private static void release(Minecraft client) {
        client.options.keyUp.setDown(false);client.options.keyJump.setDown(false);client.options.keyShift.setDown(false);client.options.keyUse.setDown(false);
        if (client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);
    }
    private void place(BlockPos pos, BlockState state) {terrain.putIfAbsent(pos, level.getBlockState(pos));level.setBlockAndUpdate(pos, state);}
    void cleanUp() {
        if (rig != null) rig.cleanUp();
        assemblyWater.forEach(level::setBlockAndUpdate);terrain.forEach(level::setBlockAndUpdate);
    }
}
