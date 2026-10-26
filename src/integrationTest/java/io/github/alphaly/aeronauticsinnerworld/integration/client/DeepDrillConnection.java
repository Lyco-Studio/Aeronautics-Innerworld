package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class DeepDrillConnection {
    private static final int FUEL = 16;
    private final ServerPlayer player;
    private final ServerLevel mantle, core;
    private final BoundingBox sourceBounds;
    private final BlockPos landing, root, controller, engine, sourceStation, headStation, coupler, chest;
    private final Map<GlobalPos, BlockState> original = new LinkedHashMap<>();
    private final Set<UUID> oldGlue = new HashSet<>();
    private final Set<UUID> oldCoreEntities = new HashSet<>();
    private final int alloy, matrices;
    private final long started;
    private long phaseStarted;
    private long progressed;
    private double lastHeight = Double.NaN;
    private volatile int phase;
    private volatile CoreMazeWalk walk;
    private volatile Vec3 aim;
    private volatile BlockPos target;
    private volatile Direction face;
    private volatile boolean pause;
    private ServerLevel walkingLevel;
    private int observed = -1;
    private volatile int frames;
    private UUID identity;
    private BlockPos coreRoot, coreStation, coreChest;
    private volatile CoreMazeExpedition expedition;

    DeepDrillConnection(ServerPlayer player, BlockPos landing, BoundingBox bounds) {
        this.player = player;this.landing = landing;sourceBounds = bounds;
        mantle = player.server.getLevel(Mantle.LEVEL);core = player.server.getLevel(Core.LEVEL);started = mantle.getGameTime();phaseStarted = started;
        alloy = count(AeronauticsInnerworld.RELIC_ALLOY.get());matrices = count(AeronauticsInnerworld.PRESSURE_MATRIX.get());
        root = findRoot();controller = root.above();engine = root.above(2).south();
        core.getEntities(null, coreArea()).forEach(entity -> oldCoreEntities.add(entity.getUUID()));
        headStation = root.offset(-1, 1, -1);coupler = root.offset(1, 1, 1);chest = root.offset(-1, 1, 1);
        BlockPos station = null;
        for (var side : Direction.Plane.HORIZONTAL) {
            var pos = landing.relative(side);
            if (clear(mantle, pos) && solidFloor(mantle, pos) && !insideHead(pos)) {station = pos;break;}
        }
        if (station == null) throw new IllegalStateException("Deep drill has no dry source station site");
        sourceStation = station;
        for (var pos : BlockPos.betweenClosed(root.offset(-1, 0, -1), root.offset(1, 0, 1))) {
            for (int y = 1; y <= root.getY() + 3; y++) remember(mantle, new BlockPos(pos.getX(), y, pos.getZ()));
            for (int y = 0; y < core.getMaxBuildHeight(); y++) remember(core, new BlockPos(pos.getX(), y, pos.getZ()));
            set(mantle, pos, AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().defaultBlockState());
        }
        for (var level : java.util.List.of(mantle, core))
            level.getEntitiesOfClass(SuperGlueEntity.class, column()).forEach(entity -> oldGlue.add(entity.getUUID()));
        set(mantle, controller, AeronauticsInnerworld.DRILL_CONTROLLER.get().defaultBlockState().setValue(DrillControllerBlock.FACING, Direction.DOWN));
        set(mantle, root.above(2), AllBlocks.GEARBOX.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        set(mantle, engine, SimBlocks.PORTABLE_ENGINES.get(DyeColor.BROWN).getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        set(mantle, chest, Blocks.CHEST.defaultBlockState());
        set(mantle, headStation, AeronauticsInnerworld.BORE_STATION.get().defaultBlockState().setValue(BoreStationBlock.FACING, Direction.EAST));
        var facing = Direction.getNearest(landing.getX() - sourceStation.getX(), 0, landing.getZ() - sourceStation.getZ());
        set(mantle, sourceStation, AeronauticsInnerworld.BORE_STATION.get().defaultBlockState().setValue(BoreStationBlock.FACING, facing));
        glue(root.offset(-1, 0, -1), root.offset(1, 0, 1));
        for (var pos : java.util.List.of(chest, headStation, coupler)) glue(pos.below(), pos);
        player.getInventory().add(new ItemStack(Items.COAL, FUEL));player.getInventory().add(AllItems.WRENCH.asStack());
        player.getInventory().add(new ItemStack(AeronauticsInnerworld.DRILL_REMOTE.get()));player.containerMenu.broadcastChanges();
        approach(mantle, coupler.below(), Direction.UP, true);
        LogUtils.getLogger().info("Earned deep upgrade drill prepared: root={}, source={}, recovered cargo={} alloy/{} matrix", root, sourceStation, alloy, matrices);
    }

    private BlockPos findRoot() {
        var candidates = new ArrayList<BlockPos>();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) candidates.add(landing.offset(x, 0, z));
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(landing)));
        search: for (var candidate : candidates) {
            if (Math.abs(candidate.getX() - landing.getX()) <= 1 && Math.abs(candidate.getZ() - landing.getZ()) <= 1) continue;
            for (var pos : BlockPos.betweenClosed(candidate.offset(-1, 0, -1), candidate.offset(1, 3, 1))) {
                if (!sourceBounds.isInside(pos) || !mantle.isEmptyBlock(pos)) continue search;
                if (pos.getY() == candidate.getY()) {
                    if (!solidFloor(mantle, pos)) continue search;
                    for (int y = 1; y < pos.getY(); y++) if (mantle.getBlockEntity(new BlockPos(pos.getX(), y, pos.getZ())) != null) continue search;
                    core.getChunkAt(pos);
                    for (int y = 0; y < core.getMaxBuildHeight(); y++) {
                        var cell = new BlockPos(pos.getX(), y, pos.getZ());
                        var state = core.getBlockState(cell);
                        if (core.getBlockEntity(cell) != null || y >= 32 && !state.isAir() && !state.is(Blocks.DEEPSLATE_TILES)
                                && !state.is(Blocks.BASALT) && !state.is(Blocks.POLISHED_BASALT)) continue search;
                    }
                }
            }
            return candidate;
        }
        throw new IllegalStateException("No dry deep drill bay with a clear core arrival column");
    }

    private boolean insideHead(BlockPos pos) {return Math.abs(pos.getX() - root.getX()) <= 1 && Math.abs(pos.getZ() - root.getZ()) <= 1;}
    private static boolean clear(ServerLevel level, BlockPos pos) {return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty() && level.getFluidState(pos).isEmpty();}
    private static boolean solidFloor(ServerLevel level, BlockPos pos) {return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP) && !level.getBlockState(pos.below()).is(Blocks.MAGMA_BLOCK);}
    private void remember(ServerLevel level, BlockPos pos) {original.putIfAbsent(GlobalPos.of(level.dimension(), pos.immutable()), level.getBlockState(pos));}
    private void set(ServerLevel level, BlockPos pos, BlockState state) {if (level.getBlockEntity(pos) != null) throw new IllegalStateException("Deep drill overlaps existing equipment: " + pos);remember(level, pos);level.setBlockAndUpdate(pos, state);}
    private void glue(BlockPos first, BlockPos last) {mantle.addFreshEntity(new SuperGlueEntity(mantle, SuperGlueEntity.span(first, last)));}
    private int count(Item item) {return player.getInventory().countItem(item);}
    private DrillControllerBlockEntity machine() {return (DrillControllerBlockEntity) mantle.getBlockEntity(controller);}
    private BoreStationBlockEntity station(ServerLevel level, BlockPos pos) {return (BoreStationBlockEntity) level.getBlockEntity(pos);}
    private GlobalPos owner() {return GlobalPos.of(Mantle.LEVEL, controller);}
    private AABB column() {return new AABB(root.getX() - 2, 0, root.getZ() - 2, root.getX() + 3, 256, root.getZ() + 3);}
    private AABB coreArea() {return new AABB(root).inflate(80);}

    private void approach(ServerLevel level, BlockPos pos, Direction requiredFace, boolean crouch) {
        var bounds = level == mantle ? sourceBounds : new BoundingBox(root.getX() - 4, 30, root.getZ() - 4, root.getX() + 4, 36, root.getZ() + 4);
        var stands = new ArrayList<BlockPos>();
        for (var cell : BlockPos.betweenClosed(pos.offset(-4, -2, -4), pos.offset(4, 1, 4)))
            if (bounds.isInside(cell) && clear(level, cell) && solidFloor(level, cell) && !cell.equals(coupler)) stands.add(cell.immutable());
        stands.sort(Comparator.comparingDouble(cell -> cell.distSqr(player.blockPosition())));
        for (var stand : stands) {
            var eye = Vec3.atBottomCenterOf(stand).add(0, crouch ? 1.27 : 1.62, 0);
            var points = requiredFace == Direction.UP ? java.util.List.of(Vec3.atCenterOf(pos).add(0, .499, 0))
                    : java.util.List.of(Vec3.atCenterOf(pos).add(0, -.35, 0), Vec3.atCenterOf(pos), Vec3.atCenterOf(pos).add(0, .49, 0));
            for (var point : points) {
                var hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || requiredFace != null && hit.getDirection() != requiredFace || hit.getLocation().distanceTo(eye) > 4.4) continue;
                try {
                    var path = new CoreMazeWalk(level, bounds, player.blockPosition(), stand, false, true);
                    walk = path;walkingLevel = level;target = pos;face = requiredFace;aim = point;return;
                } catch (IllegalStateException ignored) { }
            }
        }
        throw new IllegalStateException("Deep drill has no native walking approach to " + pos);
    }

    void tickClient(Minecraft client) {
        if (client.player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu) client.player.closeContainer();
        if (phase != 2 && phase != 12 && client.player.containerMenu instanceof ChestMenu) client.player.closeContainer();
        var expected = phase == 12 || phase == 13 || phase == 15 ? Core.LEVEL : Mantle.LEVEL;
        if (!client.level.dimension().equals(expected)) return;
        if (observed != phase) {observed = phase;frames = 0;ProbeConstruction.release(client);sneak(client, false);}
        if (phase == 15) {expedition.tickClient(client);return;}
        if (walk != null && !walk.arrived()) {walk.tickClient(client);return;}
        ProbeConstruction.release(client);frames++;
        if (phase == 0) {if (frames <= 10) equip(client, AeronauticsInnerworld.DEEP_BORE_COUPLER.get().asItem());sneak(client, true);if (frames == 10) use(client);}
        else if (phase == 1) {if (frames <= 10) equip(client, Items.COAL);if (frames == 10) use(client);}
        else if (phase == 2 || phase == 12) {
            if (!(client.player.containerMenu instanceof ChestMenu menu)) {
                empty(client);if (frames % 10 == 0) use(client);return;
            }
            if (frames % 5 == 0) for (int slot = phase == 12 ? 0 : 27; slot < (phase == 12 ? 27 : 63); slot++) {
                var item = menu.getSlot(slot).getItem();
                if (phase == 12 ? !item.isEmpty() : item.is(AeronauticsInnerworld.RELIC_ALLOY.get()) || item.is(AeronauticsInnerworld.PRESSURE_MATRIX.get())) {
                    client.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, client.player);break;
                }
            }
        } else {
            if (client.player.containerMenu instanceof ChestMenu) client.player.closeContainer();
            if (phase >= 3 && phase <= 9) equip(client, AeronauticsInnerworld.DRILL_REMOTE.get());
            if (phase >= 3 && phase <= 5 && frames == 10) use(client);
            else if ((phase == 6 || phase == 8) && frames == 10 || phase == 9 && pause && frames % 6 == 0)
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            else if (phase == 10) {equip(client, AllItems.WRENCH.get());sneak(client, true);if (frames == 10) use(client);}
            else if (phase == 11 || phase == 13) {empty(client);if (frames >= (phase == 13 ? 50 : 10) && frames % 10 == 0) use(client);}
        }
    }

    private static void sneak(Minecraft client, boolean down) {
        client.options.keyShift.setDown(down);
        if (client.player.isShiftKeyDown() == down) return;
        client.player.setShiftKeyDown(down);client.player.connection.send(new ServerboundPlayerCommandPacket(client.player,
                down ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
    }
    static void empty(Minecraft client) {
        for (int slot = 0; slot < 36; slot++) if (client.player.getInventory().getItem(slot).isEmpty()) {
            if (slot < 9) client.player.getInventory().selected = slot;
            else {client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, slot, 0, ClickType.SWAP, client.player);client.player.getInventory().selected = 0;}
            return;
        }
        throw new IllegalStateException("Deep drill operator must store cargo before freeing a hand slot");
    }
    private static void equip(Minecraft client, Item item) {
        for (int slot = 0; slot < 36; slot++) if (client.player.getInventory().getItem(slot).is(item)) {
            if (slot < 9) client.player.getInventory().selected = slot;
            else {client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, slot, 0, ClickType.SWAP, client.player);client.player.getInventory().selected = 0;}return;
        }
        throw new IllegalStateException("Deep drill operator lost " + item);
    }
    private void use(Minecraft client) {
        var delta = aim.subtract(client.player.getEyePosition());client.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        client.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
        var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target) || face != null && hit.getDirection() != face || hit.getLocation().distanceTo(client.player.getEyePosition()) > 4.5)
            throw new IllegalStateException("Deep drill interaction obstructed: phase=" + phase + ", target=" + target + ", hit=" + hit.getBlockPos() + ", eye=" + client.player.getEyePosition());
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
    }

    boolean advance() {
        mantle.resetEmptyTime();core.resetEmptyTime();
        if (walk != null && player.serverLevel() == walkingLevel) walk.advance(player.position());
        var head = machine().movedContraption;
        if (head != null && (Double.isNaN(lastHeight) || Math.abs(head.getY() - lastHeight) >= .25)) {lastHeight = head.getY();progressed = mantle.getGameTime();}
        if (head != null && (phase == 7 || phase == 9) && (mantle.getGameTime() - phaseStarted) % 200 == 0)
            LogUtils.getLogger().info("Native deep cutting: phase={}, head={}, speed={}, stalled={}, cutter={}", phase, head.position(), machine().getSpeed(), head.isStalled(),
                    head.getContraption().getActors().stream().map(actor -> actor.getRight()).filter(context -> context.stall).map(context -> context.localPos + ":" + context.data).findFirst().orElse("clear"));
        if (!player.isAlive() || player.isInLava() || player.getHealth() < 6 || mantle.getGameTime() - started > (DeepUpgradeClientCheck.story() ? 14000 : 7200)
                || phase != 15 && mantle.getGameTime() - (phase == 7 || phase == 9 ? progressed : phaseStarted) > 600)
            throw new IllegalStateException("Earned deep drill failed: phase=" + phase + ", position=" + player.position() + ", status=" + machine().getStatus()
                    + ", head=" + (head == null ? "none" : head.position()) + ", speed=" + machine().getSpeed()
                    + ", arrived=" + (walk == null || walk.arrived()) + ", menu=" + player.containerMenu.getClass().getSimpleName());
        switch (phase) {
            case 0 -> {if (mantle.getBlockState(coupler).is(AeronauticsInnerworld.DEEP_BORE_COUPLER.get())) {if (count(AeronauticsInnerworld.DEEP_BORE_COUPLER.get().asItem()) != 0) throw new IllegalStateException("Coupler placement duplicated its crafted item");next(1);approach(mantle, engine, null, false);}}
            case 1 -> {if (Math.abs(machine().getSpeed()) == 32 && count(Items.COAL) < FUEL) {next(2);approach(mantle, chest, null, false);}}
            case 2 -> {
                if (count(AeronauticsInnerworld.RELIC_ALLOY.get()) != 0 || count(AeronauticsInnerworld.PRESSURE_MATRIX.get()) != 0) break;
                verifyChest(mantle, chest);next(3);approach(mantle, controller, null, false);
            }
            case 3 -> {if (frames >= 15) {next(4);approach(mantle, sourceStation, null, false);}}
            case 4 -> {if (owner().equals(station(mantle, sourceStation).getOwner())) {next(5);approach(mantle, headStation, null, false);}}
            case 5 -> {
                if (!owner().equals(station(mantle, headStation).getOwner())) break;
                walk = new CoreMazeWalk(mantle, sourceBounds, player.blockPosition(), landing, false, true);walkingLevel = mantle;next(6);
            }
            case 6 -> {if (machine().movedContraption != null) {identity = machine().movedContraption.getUUID();if (machine().movedContraption.getContraption().getBlocks().size() != 12) throw new IllegalStateException("Deep drill did not assemble all twelve moving blocks");walk = null;next(7);}}
            case 7 -> {
                if (machine().movedContraption == null || machine().movedContraption.level() != core) break;
                if (!machine().movedContraption.getUUID().equals(identity) || player.serverLevel() != mantle) throw new IllegalStateException("Deep crossing changed the head identity or moved its operator");
                next(8);
            }
            case 8 -> {if (machine().getStatus().equals("working")) next(9);}
            case 9 -> {
                pause = machine().movedContraption.getY() <= 32.75 && machine().getStatus().equals("working");
                if (!machine().getStatus().equals("paused")) break;
                coreRoot = machine().movedContraption.blockPosition();coreStation = coreRoot.offset(-1, 1, -1);coreChest = coreRoot.offset(-1, 1, 1);
                if (coreRoot.getY() < 31 || coreRoot.getY() > 33) throw new IllegalStateException("Core head paused outside the camp floor: " + coreRoot);
                next(10);approach(mantle, controller, null, true);
            }
            case 10 -> {
                if (machine().movedContraption != null || station(mantle, sourceStation).getPartner() == null) break;
                if (!GlobalPos.of(Core.LEVEL, coreStation).equals(station(mantle, sourceStation).getPartner())
                        || !GlobalPos.of(Mantle.LEVEL, sourceStation).equals(station(core, coreStation).getPartner())) throw new IllegalStateException("Actual landing did not pair both core supply stations");
                verifyChest(core, coreChest);
                int tiles = 0;var contents = (ChestBlockEntity) core.getBlockEntity(coreChest);
                for (int slot = 0; slot < contents.getContainerSize(); slot++) if (contents.getItem(slot).is(Items.DEEPSLATE_TILES)) tiles += contents.getItem(slot).getCount();
                boolean tiledShaft = original.entrySet().stream().anyMatch(entry -> entry.getKey().dimension().equals(Core.LEVEL)
                        && entry.getKey().pos().getY() >= coreRoot.getY() && entry.getValue().is(Blocks.DEEPSLATE_TILES));
                if (tiledShaft && tiles == 0) throw new IllegalStateException("Core drilling did not recover its cut maintenance walls");
                LogUtils.getLogger().info("Native core landing paired its stations and retained {} cut maintenance tiles", tiles);
                next(11);approach(mantle, sourceStation, null, false);
            }
            case 11 -> {if (player.serverLevel() == core) {next(12);approach(core, coreChest, null, false);}}
            case 12 -> {if (count(AeronauticsInnerworld.RELIC_ALLOY.get()) == alloy && count(AeronauticsInnerworld.PRESSURE_MATRIX.get()) == matrices) {
                player.closeContainer();
                if (DeepUpgradeClientCheck.story()) {expedition = new CoreMazeExpedition(player, coreRoot.offset(0, 1, -1));next(15);}
                else {next(13);approach(core, coreStation, null, false);}
            }}
            case 13 -> {
                if (player.serverLevel() != mantle) break;
                if (count(AeronauticsInnerworld.RELIC_ALLOY.get()) != alloy || count(AeronauticsInnerworld.PRESSURE_MATRIX.get()) != matrices
                        || player.position().distanceToSqr(Vec3.atBottomCenterOf(landing)) > .1) throw new IllegalStateException("Core cargo did not return to its original dry source exit");
                if (Math.abs(machine().getSpeed()) != 32 || ((PortableEngineBlockEntity) mantle.getBlockEntity(engine)).inventory.getItem(0).getCount() >= FUEL)
                    throw new IllegalStateException("Core supply connection did not consume its ordinary coal");
                LogUtils.getLogger().info("Earned deep drill complete: head={}, core floor={}, cargo={} alloy/{} matrix, fuel remaining={}, ticks={}", identity, coreRoot, alloy, matrices,
                        ((PortableEngineBlockEntity) mantle.getBlockEntity(engine)).inventory.getItem(0).getCount(), mantle.getGameTime() - started);next(14);return true;
            }
            case 14 -> {return true;}
            case 15 -> {if (expedition.advance()) {next(13);approach(core, coreStation, null, false);}}
            default -> throw new IllegalStateException("Unknown deep drilling phase " + phase);
        }
        return false;
    }

    private void verifyChest(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity box)) throw new IllegalStateException("Earned drill cargo chest is missing");
        int foundAlloy = 0, foundMatrices = 0;
        for (int slot = 0; slot < box.getContainerSize(); slot++) {var item = box.getItem(slot);if (item.is(AeronauticsInnerworld.RELIC_ALLOY.get())) foundAlloy += item.getCount();if (item.is(AeronauticsInnerworld.PRESSURE_MATRIX.get())) foundMatrices += item.getCount();}
        if (foundAlloy != alloy || foundMatrices != matrices) throw new IllegalStateException("Earned drill cargo changed during assembly or crossing");
    }
    private void next(int value) {phase = value;phaseStarted = mantle.getGameTime();LogUtils.getLogger().info("Native earned drill phase {}", value);}

    void cleanUp() {
        if (expedition != null) expedition.cleanUp();
        player.closeContainer();
        if (machine() != null && machine().movedContraption != null) {machine().movedContraption.getContraption().stop(machine().movedContraption.level());machine().movedContraption.discard();machine().disassemble();}
        for (var level : java.util.List.of(mantle, core)) for (var entity : level.getEntitiesOfClass(SuperGlueEntity.class, column())) if (!oldGlue.contains(entity.getUUID())) entity.discard();
        for (var entity : core.getEntities(null, coreArea())) if (!oldCoreEntities.contains(entity.getUUID())
                && (entity instanceof net.minecraft.world.entity.Mob || entity instanceof net.minecraft.world.entity.item.ItemEntity
                || entity instanceof net.minecraft.world.entity.ExperienceOrb || entity instanceof net.minecraft.world.entity.projectile.AbstractArrow)) entity.discard();
        original.keySet().forEach(pos -> Clearable.tryClear(player.server.getLevel(pos.dimension()).getBlockEntity(pos.pos())));
        original.forEach((pos, state) -> {var level = player.server.getLevel(pos.dimension());if (!level.getBlockState(pos.pos()).equals(state)) level.setBlockAndUpdate(pos.pos(), state);});
    }
}
