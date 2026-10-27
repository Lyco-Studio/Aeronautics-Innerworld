package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import dev.simulated_team.simulated.index.SimBlocks;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class ProbeWorkshop {
    private static final Item ENGINE = SimBlocks.PORTABLE_ENGINES.get(DyeColor.RED).asItem();
    private final ServerPlayer player;
    private final ServerLevel level;
    private final ProbeConstruction construction;
    private final EngineWorkshop engines;
    private final BlockPos materials, parts, remainders;
    private final BoundingBox bounds;
    private final Map<BlockPos, BlockState> terrain = new LinkedHashMap<>();
    private final Map<Item, Integer> expected = new LinkedHashMap<>();
    private Map<Item, Integer> stored;
    private final long started;
    private long since;
    private volatile int phase;
    private volatile CoreMazeWalk walk;
    private volatile BlockPos target;
    private int observed = -1, frames;
    volatile boolean complete;

    ProbeWorkshop(ServerPlayer player, ProbeConstruction construction, boolean warehouseOnly) {
        this.player = player;this.construction = construction;level = construction.rig.level;
        var origin = construction.origin;
        bounds = BoundingBox.fromCorners(origin.offset(-15, -1, -3), origin.offset(-3, -1, 7));
        for (var pos : BlockPos.betweenClosed(origin.offset(-16, -2, -4), origin.offset(-4, 4, 4))) {
            if (level.getBlockEntity(pos) != null) throw new IllegalStateException("Probe workshop overlaps existing equipment");
            terrain.put(pos.immutable(), level.getBlockState(pos));
        }
        for (var pos : terrain.keySet()) {
            var relative = pos.subtract(origin);
            boolean boundary = relative.getX() == -16 || relative.getX() == -4 || relative.getZ() == -4
                    || relative.getZ() == 4 || relative.getY() == -2 || relative.getY() == 4;
            level.setBlockAndUpdate(pos, boundary ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        for (int x : new int[] {-14, -8}) for (int z : new int[] {-2, 2})
            level.setBlockAndUpdate(origin.offset(x, 4, z), Blocks.GLOWSTONE.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(-4, -1, -3), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(-4, 0, -3), Blocks.AIR.defaultBlockState());
        materials = origin.offset(-14, -1, 3);parts = origin.offset(-12, -1, 3);remainders = origin.offset(-10, -1, 3);
        for (var pos : java.util.List.of(materials, parts, remainders)) level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        var stock = construction.assemblySupplies();stock.remove(ENGINE);
        fill(materials, ProbeFabrication.ingredients());fill(parts, stock);
        expected.putAll(ProbeFabrication.ingredients());stock.forEach((item, count) -> expected.merge(item, count, Integer::sum));
        expected.put(ENGINE, 7);
        started = level.getGameTime();
        if (warehouseOnly) {
            player.getInventory().add(new ItemStack(ENGINE, 7));
            player.getInventory().add(new ItemStack(Items.IRON_INGOT, 40));
            player.getInventory().add(new ItemStack(Items.BREAD, 45));
            player.getInventory().add(new ItemStack(Items.IRON_NUGGET, 3));
            engines = null;beginStorage();
        } else {
            player.getInventory().add(new ItemStack(Items.IRON_INGOT, 64));
            player.getInventory().add(new ItemStack(Items.BLAST_FURNACE, 7));
            player.getInventory().add(new ItemStack(Items.BREAD, 64));
            var bench = origin.offset(-8, -1, 2);level.setBlockAndUpdate(bench, Blocks.CRAFTING_TABLE.defaultBlockState());
            engines = new EngineWorkshop(player, origin.offset(-14, -1, -1), bench, bounds, 7);
        }
        player.containerMenu.broadcastChanges();
    }

    private ChestBlockEntity chest(BlockPos pos) {return (ChestBlockEntity) level.getBlockEntity(pos);}
    private void fill(BlockPos pos, Map<Item, Integer> contents) {
        int slot = 0;
        for (var entry : contents.entrySet()) {
            int remaining = entry.getValue();
            while (remaining > 0) {
                if (slot == 27) throw new IllegalStateException("Workshop stock exceeds its ordinary chest");
                int count = Math.min(remaining, entry.getKey().getDefaultInstance().getMaxStackSize());
                chest(pos).setItem(slot++, new ItemStack(entry.getKey(), count));remaining -= count;
            }
        }
    }
    private Map<Item, Integer> inventory() {
        var result = new LinkedHashMap<Item, Integer>();
        for (var stack : player.getInventory().items) if (!stack.isEmpty()) result.merge(stack.getItem(), stack.getCount(), Integer::sum);
        return result;
    }
    private Map<Item, Integer> contents(BlockPos pos) {
        var result = new LinkedHashMap<Item, Integer>();var box = chest(pos);
        for (int slot = 0; slot < box.getContainerSize(); slot++) {
            var stack = box.getItem(slot);if (!stack.isEmpty()) result.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return result;
    }
    private void beginStorage() {
        if (player.getInventory().countItem(ENGINE) != 7) throw new IllegalStateException("Workshop did not finish seven engines");
        stored = inventory();stored.remove(ENGINE);next(1, remainders);
    }
    private void next(int value, BlockPos chest) {
        player.closeContainer();target = chest;since = level.getGameTime();
        var destination = value == 5 ? BlockPos.containing(construction.startingPosition()) : chest.north();
        walk = new CoreMazeWalk(level, bounds, player.blockPosition(), destination, false);phase = value;
        LogUtils.getLogger().info("Native probe workshop phase {} at {}", value, chest);
    }

    void tickClient(Minecraft client) {
        if (phase == 0) {engines.tickClient(client);return;}
        if (observed != phase) {
            observed = phase;frames = 0;ProbeConstruction.release(client);
            if (client.player.containerMenu != client.player.inventoryMenu) client.player.closeContainer();
        }
        if (!walk.arrived()) {walk.tickClient(client);return;}
        ProbeConstruction.release(client);
        if (phase == 5) return;
        frames++;
        if (!(client.player.containerMenu instanceof ChestMenu menu)) {
            if (frames % 10 != 0) return;
            DeepDrillConnection.empty(client);
            var point = Vec3.atCenterOf(target);var offset = point.subtract(client.player.getEyePosition());
            client.player.setYRot((float)Math.toDegrees(Math.atan2(-offset.x, offset.z)));
            client.player.setXRot((float)-Math.toDegrees(Math.atan2(offset.y, offset.horizontalDistance())));
            var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)
                    || hit.getLocation().distanceTo(client.player.getEyePosition()) > 4.5) throw new IllegalStateException("Warehouse chest is unreachable: " + target);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);return;
        }
        if (frames % 4 != 0) return;
        for (int slot = phase <= 2 ? 27 : 0; slot < (phase <= 2 ? 63 : 27); slot++) {
            var stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty() || phase == 1 && stack.is(ENGINE) || phase == 2 && !stack.is(ENGINE)) continue;
            client.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, client.player);return;
        }
    }

    void advance() {
        if (complete) return;
        if (!player.isAlive() || level.getGameTime() - started > 32000) throw new IllegalStateException("Probe workshop timed out at phase " + phase);
        if (phase == 0) {if (engines.advance()) beginStorage();return;}
        walk.advance(player.position());
        if (level.getGameTime() - since > 1200) throw new IllegalStateException("Warehouse interaction stalled at phase " + phase);
        switch (phase) {
            case 1 -> {
                var left = inventory();left.remove(ENGINE);
                if (left.isEmpty()) {
                    verifyStoredRemainders();next(2, parts);
                }
            }
            case 2 -> {
                if (player.getInventory().countItem(ENGINE) == 0 && chest(parts).countItem(ENGINE) == 7) next(3, materials);
            }
            case 3 -> {if (chest(materials).isEmpty()) next(4, parts);}
            case 4 -> {if (chest(parts).isEmpty()) next(5, parts);}
            case 5 -> {
                if (!walk.arrived()) break;
                if (!inventory().equals(expected)) throw new IllegalStateException("Warehouse lost construction supplies: " + inventory());
                verifyStoredRemainders();complete = true;
                LogUtils.getLogger().info("Native workshop handoff complete: engines=7, stored={}, ticks={}", stored, level.getGameTime() - started);
            }
            default -> throw new IllegalStateException("Unknown probe workshop phase " + phase);
        }
    }

    void verifyStoredRemainders() {
        if (!contents(remainders).equals(stored)) throw new IllegalStateException("Warehouse changed manufacturing remainders or byproducts");
    }
    void cleanUp() {
        player.closeContainer();if (engines != null) engines.cleanUp();
        terrain.keySet().forEach(pos -> Clearable.tryClear(level.getBlockEntity(pos)));terrain.forEach(level::setBlockAndUpdate);
    }
}
