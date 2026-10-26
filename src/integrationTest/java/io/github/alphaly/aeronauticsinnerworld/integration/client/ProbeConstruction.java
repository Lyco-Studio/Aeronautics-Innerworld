package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.PressureHatchBlock;
import io.github.alphaly.aeronauticsinnerworld.integration.ProbeTestRig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class ProbeConstruction {
    final ProbeTestRig rig;
    final BlockPos origin, assembler;
    private final ServerPlayer player;
    private final ServerLevel level;
    private final BoundingBox bounds;
    private final Rotation rotation;
    private final boolean reuseExcavation;
    private final Map<BlockPos, BlockState> terrain = new LinkedHashMap<>();
    private final Map<BlockPos, BlockState> plan;
    private final java.util.Set<java.util.UUID> previousGlue = new java.util.HashSet<>();
    private final java.util.Set<BlockPos> fuelled = new java.util.HashSet<>();
    private final long started;
    private volatile Action action;
    private volatile CoreMazeWalk walk;
    private volatile int frames;
    private Action observed;
    private Vec3 inputPoint;
    private int glueCorner, operations;
    private long selectedAt;
    private volatile boolean aboard;
    volatile boolean complete;

    private enum Kind {PLACE, ROTATE, USE, FUEL, GLUE}
    private record Action(Kind kind, BlockPos stand, BlockPos target, BlockPos against, Direction face,
            Vec3 point, Item item, BlockState before) { }

    ProbeConstruction(ServerPlayer player, ServerLevel level, BlockPos origin) {
        this(player, level, origin, Rotation.NONE, false);
    }

    ProbeConstruction(ServerPlayer player, ServerLevel level, BlockPos origin, Rotation rotation, boolean reuseExcavation) {
        this.player = player;this.level = level;this.origin = origin;this.rotation = rotation;this.reuseExcavation = reuseExcavation;
        bounds = BoundingBox.fromCorners(at(-3, -2, -3), at(5, 4, 9));
        level.getEntitiesOfClass(SuperGlueEntity.class, SuperGlueEntity.span(at(-4, -3, -4), at(6, 7, 10)))
                .forEach(entity -> previousGlue.add(entity.getUUID()));
        if (!reuseExcavation) for (var pos : BlockPos.betweenClosed(at(-4, -3, -4), at(6, 7, 10))) {
            if (level.getBlockEntity(pos) != null) throw new IllegalStateException("Construction bay contains an existing block entity");
            terrain.put(pos.immutable(), level.getBlockState(pos));
        }
        for (var pos : terrain.keySet()) {
            var relative = relative(pos);
            boolean boundary = relative.getX() == -4 || relative.getX() == 6 || relative.getZ() == -4 || relative.getZ() == 10
                    || relative.getY() <= -2 || relative.getY() == 7;
            level.setBlockAndUpdate(pos, boundary ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        if (!reuseExcavation) for (var pos : BlockPos.betweenClosed(at(1, -2, -1), at(3, -2, 7)))
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        for (int z = -2; z <= 7; z++) for (int x = -1; x <= 5; x++)
            if (x == -1 || x == 5 || z == -2 || z == 7) scaffold(x, 1, z);
        temporary(at(-1, 1, 2), Blocks.AIR.defaultBlockState());scaffold(-1, 0, 2);
        if (reuseExcavation) {scaffold(-3, -1, 0);scaffold(-3, 0, 1);scaffold(-3, 1, 2);scaffold(-2, 1, 2);scaffold(-2, 1, 1);}
        else {scaffold(-3, -1, -2);scaffold(-3, 0, -1);scaffold(-3, 1, 0);scaffold(-2, 1, 0);}
        scaffold(5, 2, 6);
        rig = new ProbeTestRig(level, origin, false, rotation);rig.workEquipment();rig.survivalPower();rig.landingSkids();
        assembler = rig.moveAssemblerInside();plan = rig.takeConstructionPlan();
        if (reuseExcavation) for (var pos : plan.keySet()) if (!level.isEmptyBlock(pos))
            throw new IllegalStateException("Excavated construction bay is obstructed at " + pos);
        if (plan.values().stream().anyMatch(state -> state.is(com.simibubi.create.AllBlocks.CREATIVE_MOTOR.get())))
            throw new IllegalStateException("Construction plan contains creative power");
        started = level.getGameTime();
        LogUtils.getLogger().info("Native probe construction begins: {} blocks at {}", plan.size(), origin);
    }

    private BlockPos at(BlockPos relative) {return origin.offset(relative.rotate(rotation));}
    private BlockPos at(int x, int y, int z) {return at(new BlockPos(x, y, z));}
    private BlockPos relative(BlockPos pos) {return pos.subtract(origin).rotate(switch (rotation) {
        case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;default -> rotation;
    });}
    private void temporary(BlockPos pos, BlockState state) {
        if (level.getBlockEntity(pos) != null) throw new IllegalStateException("Construction support overlaps existing equipment");
        terrain.putIfAbsent(pos.immutable(), level.getBlockState(pos));level.setBlockAndUpdate(pos, state);
    }
    private void scaffold(int x, int y, int z) {temporary(at(x, y, z), Blocks.STONE.defaultBlockState());}
    Vec3 startingPosition() {return Vec3.atBottomCenterOf(reuseExcavation ? at(-3, -1, -1) : at(-3, -1, -3));}

    void supply() {
        var counts = new LinkedHashMap<Item, Integer>();
        plan.values().forEach(state -> counts.merge(state.getBlock().asItem(), 1, Integer::sum));
        var items = new ArrayList<ItemStack>();
        counts.forEach((item, count) -> {
            while (count > 0) {int size = Math.min(count, item.getDefaultInstance().getMaxStackSize());items.add(new ItemStack(item, size));count -= size;}
        });
        items.add(AllItems.WRENCH.asStack());items.add(AllItems.SUPER_GLUE.asStack());
        for (int index = 0; index < 7; index++) items.add(new ItemStack(Items.COAL, 4));
        if (items.size() > 35) throw new IllegalStateException("Construction supplies do not fit the normal inventory");
        for (int slot = 0; slot < items.size(); slot++) player.getInventory().setItem(slot + 1, items.get(slot));
    }

    void tickClient(Minecraft client) {
        var current = action;
        if (current == null) {
            if (aboard && walk != null && !walk.arrived()) walk.tickClient(client);else release(client);
            return;
        }
        if (current != observed) {release(client);observed = current;frames = 0;inputPoint = current.point;}
        if (!walk.arrived()) {walk.tickClient(client);return;}
        client.options.keyUp.setDown(false);client.options.keyJump.setDown(false);
        frames++;
        if (frames > 80) throw new IllegalStateException("Construction input failed: " + current + ", player="
                + client.player.position() + ", state=" + client.level.getBlockState(current.target));
        if (frames <= 10) equip(client, current.item);
        client.options.keyShift.setDown(current.kind == Kind.PLACE);
        look(client, inputPoint);
        if (frames != 10) return;
        net.minecraft.world.phys.BlockHitResult hit = null;
        double[] offsets = current.kind == Kind.GLUE ? new double[] {0} : new double[] {0, -.25, .25};
        search: for (double a : offsets) for (double b : offsets) {
            var point = current.point.add(switch (current.face.getAxis()) {
                case X -> new Vec3(0, a, b);case Y -> new Vec3(a, 0, b);case Z -> new Vec3(a, b, 0);
            });
            var candidate = client.level.clip(new ClipContext(client.player.getEyePosition(), point, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, client.player));
            if (candidate.getType() == HitResult.Type.BLOCK && candidate.getBlockPos().equals(current.against)
                    && candidate.getDirection() == current.face && candidate.getLocation().distanceTo(client.player.getEyePosition()) <= 4.5) {
                if ((current.kind == Kind.ROTATE || current.kind == Kind.FUEL)
                        && settingsAt(current.target, current.face, candidate.getLocation(), current.item)) continue;
                hit = candidate;inputPoint = point;break search;
            }
        }
        if (hit == null)
            throw new IllegalStateException("Construction view is obstructed: " + current + ", player=" + client.player.position()
                    + ", eye=" + client.player.getEyePosition());
        look(client, inputPoint);
        if (current.kind == Kind.GLUE) KeyMapping.click(client.options.keyUse.getKey());
        else client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
    }

    void advance() {
        if (complete) return;
        if (!player.isAlive() || level.getGameTime() - started > 16000)
            throw new IllegalStateException("Probe construction failed after " + operations + " operations: " + action);
        if (action != null) {
            if (level.getGameTime() - selectedAt > 600)
                throw new IllegalStateException("Construction approach timed out: " + action + ", player=" + player.position());
            walk.advance(player.position());
            if (!walk.arrived() || frames <= 10) return;
            var now = level.getBlockState(action.target);
            boolean done = switch (action.kind) {
                case PLACE -> now.is(plan.get(action.target).getBlock());
                case ROTATE -> !matches(now, action.before);
                case USE -> !now.equals(action.before);
                case FUEL -> level.getBlockEntity(action.target) instanceof PortableEngineBlockEntity engine
                        && (!engine.inventory.isEmpty() || engine.getCurrentBurnTime() > 0);
                case GLUE -> glueCorner % 2 == 0 ? frames >= 18 : glueDamage() >= (glueCorner + 1) / 2;
            };
            if (!done) return;
            if (action.kind == Kind.GLUE) glueCorner++;
            if (action.kind == Kind.FUEL) fuelled.add(action.target);
            action = null;operations++;
        }
        var area = walkingArea();
        var ordered = area.keySet().stream().sorted(Comparator.comparingInt(area::get)).toList();
        boolean finishing = glueCorner >= 4;
        if (!finishing) for (var pos : plan.keySet()) {
            var state = level.getBlockState(pos);
            if (state.is(AeronauticsInnerworld.PRESSURE_HATCH.get()) && !state.getValue(PressureHatchBlock.OPEN)) {
                var next = interaction(Kind.USE, pos, Items.AIR, ordered);
                if (next != null) {select(next);return;}
            }
        }
        var remaining = plan.keySet().stream().filter(pos -> !matches(level.getBlockState(pos), plan.get(pos)))
                .sorted(Comparator.comparingInt((BlockPos pos) -> relative(pos).getY())
                        .thenComparingInt(pos -> relative(pos).getZ()).thenComparingInt(pos -> relative(pos).getX())).toList();
        for (var pos : remaining) {
            var state = level.getBlockState(pos);
            var next = state.isAir() ? placement(pos, ordered) : interaction(Kind.ROTATE, pos, AllItems.WRENCH.get(), ordered);
            if (next != null) {select(next);return;}
        }
        if (!remaining.isEmpty()) throw new IllegalStateException("No native construction approach: " + remaining.stream()
                .map(pos -> pos.subtract(origin) + "=" + level.getBlockState(pos) + " -> " + plan.get(pos)).toList());
        for (var pos : plan.keySet()) if (plan.get(pos).is(Blocks.LEVER)
                && level.getBlockState(pos).getValue(LeverBlock.POWERED) != plan.get(pos).getValue(LeverBlock.POWERED)) {
            var next = interaction(Kind.USE, pos, Items.AIR, ordered);
            if (next == null) throw new IllegalStateException("Constructed throttle lever is inaccessible");select(next);return;
        }
        for (var pos : plan.keySet()) {
            if (!(level.getBlockEntity(pos) instanceof PortableEngineBlockEntity) || fuelled.contains(pos)) continue;
            var next = interaction(Kind.FUEL, pos, Items.COAL, ordered);
            if (next == null) throw new IllegalStateException("Constructed engine is inaccessible: " + pos.subtract(origin));
            select(next);return;
        }
        if (glueCorner < 4) {
            var corner = at(switch (glueCorner) {case 0 -> new BlockPos(0, -1, 0);case 1 -> new BlockPos(4, 3, 6);
                case 2 -> ProbeTestRig.CABIN;default -> ProbeTestRig.CABIN.north();});
            var next = interaction(Kind.GLUE, corner, AllItems.SUPER_GLUE.get(), ordered);
            if (next == null) throw new IllegalStateException("Cannot reach glue corner " + corner.subtract(origin));
            select(next);return;
        }
        var pilot = rig.source(new BlockPos(2, 1, 2));
        if (!aboard) {
            if (walk == null || !walk.arrived() || player.position().distanceToSqr(Vec3.atBottomCenterOf(pilot)) > .15) {
                if (!area.containsKey(pilot)) throw new IllegalStateException("Constructed cockpit is not walkable");
                walk = new CoreMazeWalk(level, bounds, player.blockPosition(), pilot, false, true);aboard = true;return;
            }
            aboard = true;
        }
        if (!walk.arrived()) {walk.advance(player.position());return;}
        for (var hatch : plan.keySet()) if (plan.get(hatch).is(AeronauticsInnerworld.PRESSURE_HATCH.get())
                && level.getBlockState(hatch).getValue(PressureHatchBlock.OPEN)) {
            var next = interaction(Kind.USE, hatch, Items.AIR, List.of(pilot));
            if (next == null) throw new IllegalStateException("Cannot close the constructed cockpit");select(next);return;
        }
        if (player.getInventory().countItem(Items.COAL) != 0 || glueDamage() < 2)
            throw new IllegalStateException("Construction fuel or glue was not consumed");
        complete = true;
        LogUtils.getLogger().info("Native probe construction complete: blocks={}, operations={}, ticks={}, glue={}",
                plan.size(), operations, level.getGameTime() - started, glueCount());
    }

    private int glueCount() {return level.getEntitiesOfClass(SuperGlueEntity.class,
            SuperGlueEntity.span(at(-1, -2, -2), at(6, 5, 8))).size();}

    private int glueDamage() {return player.getInventory().items.stream().filter(stack -> stack.is(AllItems.SUPER_GLUE.get()))
            .findFirst().orElseThrow().getDamageValue();}

    void verifyAssembly() {
        for (var entry : plan.entrySet()) {
            var pos = rig.local(relative(entry.getKey()));
            if (!matches(level.getBlockState(pos), entry.getValue()))
                throw new IllegalStateException("Constructed assembly lost or rotated a block: " + entry.getKey().subtract(origin));
        }
        var box = rig.subLevel.getPlot().getBoundingBox();
        long blocks = java.util.stream.StreamSupport.stream(BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ()).spliterator(), false).filter(pos -> !level.isEmptyBlock(pos)).count();
        if (blocks != plan.size()) throw new IllegalStateException("Constructed assembly collected unexpected blocks: " + blocks);
    }

    private void select(Action next) {
        walk = new CoreMazeWalk(level, bounds, player.blockPosition(), next.stand, false, true);selectedAt = level.getGameTime();action = next;
        LogUtils.getLogger().info("Build operation {}: {} {} from {}", operations, next.kind, next.target.subtract(origin), next.stand.subtract(origin));
    }

    private Action placement(BlockPos pos, List<BlockPos> stands) {
        for (var stand : stands) {
            if (pos.equals(stand) || pos.equals(stand.above())) continue;
            for (var face : Direction.values()) {
                if (plan.get(pos).getBlock() instanceof FaceAttachedHorizontalDirectionalBlock && face != Direction.UP) continue;
                var support = pos.relative(face.getOpposite());
                if (level.isEmptyBlock(support)) continue;
                var point = facePoint(support, face);
                if (visible(stand, support, face, point, true)) return new Action(Kind.PLACE, stand, pos, support, face, point,
                        plan.get(pos).getBlock().asItem(), level.getBlockState(pos));
            }
        }
        return null;
    }

    private Action interaction(Kind kind, BlockPos pos, Item item, List<BlockPos> stands) {
        var state = level.getBlockState(pos);
        var candidates = new ArrayList<Action>();
        for (var stand : stands) for (var face : Direction.values()) {
            if (kind == Kind.ROTATE && !(state.getBlock() instanceof IWrenchable)) continue;
            var center = facePoint(pos, face);
            for (var point : List.of(center, center.add(face.getAxis() == Direction.Axis.X ? 0 : .25,
                    face.getAxis() == Direction.Axis.Y ? 0 : .25, face.getAxis() == Direction.Axis.Z ? 0 : .25),
                    center.add(face.getAxis() == Direction.Axis.X ? 0 : -.25,
                            face.getAxis() == Direction.Axis.Y ? 0 : -.25, face.getAxis() == Direction.Axis.Z ? 0 : -.25))) {
                if ((kind == Kind.ROTATE || kind == Kind.FUEL) && settingsAt(pos, face, point, item)) continue;
                if (visible(stand, pos, face, point, false)) {
                    var candidate = new Action(kind, stand, pos, pos, face, point, item, state);
                    if (kind != Kind.ROTATE) return candidate;
                    candidates.add(candidate);
                }
            }
        }
        if (candidates.isEmpty()) return null;
        var faces = java.util.EnumSet.noneOf(Direction.class);
        candidates.forEach(candidate -> faces.add(candidate.face));
        int distance = rotations(state, plan.get(pos), faces);
        var wrench = (IWrenchable) state.getBlock();
        return candidates.stream().filter(candidate -> rotations(wrench.getRotatedBlockState(state, candidate.face), plan.get(pos), faces) < distance)
                .findFirst().orElse(null);
    }

    private boolean settingsAt(BlockPos pos, Direction face, Vec3 point, Item item) {
        if (!(level.getBlockEntity(pos) instanceof SmartBlockEntity entity)) return false;
        for (var behaviour : entity.getAllBehaviours()) {
            if (!(behaviour instanceof ValueSettingsBehaviour settings) || !settings.isActive()
                    || settings.bypassesInput(item.getDefaultInstance())
                    || settings.onlyVisibleWithWrench() && !item.getDefaultInstance().is(net.neoforged.neoforge.common.Tags.Items.TOOLS_WRENCH)) continue;
            if (settings.getSlotPositioning() instanceof ValueBoxTransform.Sided sided) {
                sided.fromSide(face);
            }
            if (settings.testHit(point)) return true;
        }
        return false;
    }

    private Vec3 facePoint(BlockPos block, Direction face) {
        var box = level.getBlockState(block).getShape(level, block).bounds();
        var center = box.getCenter();
        return Vec3.atLowerCornerOf(block).add(switch (face) {
            case DOWN -> new Vec3(center.x, box.minY + .001, center.z);
            case UP -> new Vec3(center.x, box.maxY - .001, center.z);
            case NORTH -> new Vec3(center.x, center.y, box.minZ + .001);
            case SOUTH -> new Vec3(center.x, center.y, box.maxZ - .001);
            case WEST -> new Vec3(box.minX + .001, center.y, center.z);
            case EAST -> new Vec3(box.maxX - .001, center.y, center.z);
        });
    }

    private boolean visible(BlockPos stand, BlockPos block, Direction face, Vec3 point, boolean crouch) {
        var eye = Vec3.atBottomCenterOf(stand).add(0, crouch ? 1.27 : 1.62, 0);
        if (eye.distanceToSqr(point) > 4.4 * 4.4) return false;
        var hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block) && hit.getDirection() == face;
    }

    private Map<BlockPos, Integer> walkingArea() {
        var steps = new HashMap<BlockPos, Integer>();var queue = new ArrayDeque<BlockPos>();
        steps.put(player.blockPosition(), 0);queue.add(player.blockPosition());
        while (!queue.isEmpty()) {
            var pos = queue.remove();
            for (var face : Direction.Plane.HORIZONTAL) for (int height : new int[] {0, 1, -1}) {
                var next = pos.relative(face).above(height);
                if (!bounds.isInside(next) || steps.containsKey(next)
                        || !level.getBlockState(next).getCollisionShape(level, next).isEmpty()
                        || !level.getBlockState(next.above()).getCollisionShape(level, next.above()).isEmpty()
                        || !level.getBlockState(next.below()).isFaceSturdy(level, next.below(), Direction.UP)
                        || height > 0 && !level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty()
                        || height < 0 && !level.getBlockState(next.above(2)).getCollisionShape(level, next.above(2)).isEmpty()) continue;
                steps.put(next, steps.get(pos) + 1);queue.add(next);
            }
        }
        return steps;
    }

    private static boolean matches(BlockState state, BlockState expected) {
        if (!state.is(expected.getBlock())) return false;
        if (state.hasProperty(BlockStateProperties.AXIS) && state.getValue(BlockStateProperties.AXIS) != expected.getValue(BlockStateProperties.AXIS)) return false;
        if (state.hasProperty(BlockStateProperties.FACING) && state.getValue(BlockStateProperties.FACING) != expected.getValue(BlockStateProperties.FACING)) return false;
        return state.getBlock() instanceof FaceAttachedHorizontalDirectionalBlock || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                || state.getValue(BlockStateProperties.HORIZONTAL_FACING) == expected.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    private static int rotations(BlockState from, BlockState goal, java.util.Set<Direction> faces) {
        if (matches(from, goal)) return 0;
        if (!(from.getBlock() instanceof IWrenchable wrench)) return 100;
        var steps = new HashMap<BlockState, Integer>();var queue = new ArrayDeque<BlockState>();steps.put(from, 0);queue.add(from);
        while (!queue.isEmpty()) {
            var state = queue.remove();
            for (var face : faces) {
                var next = wrench.getRotatedBlockState(state, face);
                if (steps.containsKey(next)) continue;
                int distance = steps.get(state) + 1;
                if (matches(next, goal)) return distance;
                steps.put(next, distance);queue.add(next);
            }
        }
        return 100;
    }

    private static void equip(Minecraft client, Item item) {
        if (item == Items.AIR) {
            for (int slot = 0; slot < 9; slot++) if (client.player.getInventory().getItem(slot).isEmpty()) {client.player.getInventory().selected = slot;return;}
            throw new IllegalStateException("No empty construction hotbar slot");
        }
        for (int slot = 0; slot < 36; slot++) if (client.player.getInventory().getItem(slot).is(item)) {
            if (slot < 9) client.player.getInventory().selected = slot;
            else {client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, slot, 0, ClickType.SWAP, client.player);client.player.getInventory().selected = 0;}
            return;
        }
        throw new IllegalStateException("Missing construction supply " + item);
    }

    private static void look(Minecraft client, Vec3 point) {
        var delta = point.subtract(client.player.getEyePosition());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        client.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
    }

    static void release(Minecraft client) {client.options.keyUp.setDown(false);client.options.keyJump.setDown(false);client.options.keyShift.setDown(false);client.options.keyUse.setDown(false);}
    void removeSupports() {if (reuseExcavation) {terrain.forEach(level::setBlockAndUpdate);terrain.clear();}}
    void cleanUp() {
        level.getEntitiesOfClass(SuperGlueEntity.class, SuperGlueEntity.span(at(-4, -3, -4), at(6, 7, 10)))
                .stream().filter(entity -> !previousGlue.contains(entity.getUUID())).forEach(net.minecraft.world.entity.Entity::discard);
        terrain.forEach(level::setBlockAndUpdate);
    }
}
