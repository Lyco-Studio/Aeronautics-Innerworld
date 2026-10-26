package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import dev.simulated_team.simulated.index.SimItems;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class EngineWorkshop {
    private final ServerPlayer player;
    private final ServerLevel level;
    private final BlockPos depot, pressCrank, saw, sawCrank, hopper, bench, pressStand, sawStand;
    private final BoundingBox bounds;
    private final Map<BlockPos, BlockState> original = new LinkedHashMap<>();
    private final long started;
    private volatile CoreMazeWalk walk;
    private volatile BlockPos target;
    private volatile Item input;
    private volatile int stage;
    private int observed = -1, frames, sequence, attempts;
    private long since;
    private boolean clicked, filled;
    private boolean viewsChecked;
    private int ironRemaining;
    volatile boolean complete;

    EngineWorkshop(ServerPlayer player, BlockPos origin, BlockPos bench, BoundingBox bounds) {
        this.player = player;level = player.serverLevel();this.bench = bench;this.bounds = bounds;
        depot = origin;pressCrank = depot.above(2).east();saw = origin.east(4);sawCrank = saw.east();hopper = saw.above();
        pressStand = depot.east(2).south();sawStand = saw.east(2).south();
        set(depot, AllBlocks.DEPOT.getDefaultState());
        set(depot.above(2), AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        set(pressCrank, AllBlocks.HAND_CRANK.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        set(saw, AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        set(saw.north(), AllBlocks.DEPOT.getDefaultState());set(saw.south(), AllBlocks.DEPOT.getDefaultState());
        set(sawCrank, AllBlocks.HAND_CRANK.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        set(hopper, Blocks.HOPPER.defaultBlockState());
        started = level.getGameTime();beginPlate();
    }

    private void set(BlockPos pos, BlockState state) {
        if (level.getBlockEntity(pos) != null) throw new IllegalStateException("Engine workshop overlaps existing equipment");
        original.put(pos, level.getBlockState(pos));level.setBlockAndUpdate(pos, state);
    }
    private DepotBlockEntity depot() {return (DepotBlockEntity) level.getBlockEntity(depot);}
    private BlockPos output() {return saw.offset(BlockPos.containing(((SawBlockEntity) level.getBlockEntity(saw)).getItemMovementVec()));}
    private int count(Item item) {return player.getInventory().countItem(item);}
    int ironRemaining() {return ironRemaining;}
    private void next(int value, BlockPos stand, BlockPos block) {
        stage = value;since = level.getGameTime();target = block;
        walk = new CoreMazeWalk(level, bounds, player.blockPosition(), stand, false);
        LogUtils.getLogger().info("Client engine stage {}: sequence={}, attempt={}, target={}", value, sequence, attempts, block);
    }
    private void beginPlate() {input = Items.IRON_INGOT;next(0, pressStand, depot);}
    private void beginSaw() {input = sequence == 0 ? AllItems.IRON_SHEET.get() : SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get();next(3, sawStand, hopper);}

    void tickClient(Minecraft client) {
        if (complete) return;
        if (observed != stage) {
            observed = stage;frames = 0;clicked = false;filled = false;ProbeConstruction.release(client);
            if (client.player.containerMenu != client.player.inventoryMenu) client.player.closeContainer();
        }
        if (!walk.arrived()) {walk.tickClient(client);return;}
        ProbeConstruction.release(client);frames++;
        if (stage == 0) {
            if (!clicked && frames == 5) {single(client, input);use(client, depot);clicked = true;}
        } else if (stage == 1 || stage == 4) {
            if (frames % 8 == 0) {DeepDrillConnection.empty(client);use(client, target);}
        } else if (stage == 2 || stage == 5) {
            if (!clicked && frames == 5) {DeepDrillConnection.empty(client);use(client, target);clicked = true;}
        } else if (stage == 3) feed(client);
        else if (stage == 6) craft(client);
    }

    private static void single(Minecraft client, Item item) {
        int source = -1;
        for (int slot = 0; slot < 36; slot++) if (client.player.getInventory().getItem(slot).is(item)) {source = slot;break;}
        if (source < 0) throw new IllegalStateException("Engine workshop exhausted " + item);
        if (client.player.getInventory().getItem(source).getCount() == 1 && source < 9) {client.player.getInventory().selected = source;return;}
        DeepDrillConnection.empty(client);int hotbar = client.player.getInventory().selected;
        int inventorySlot = source < 9 ? 36 + source : source;
        int destination = 36 + hotbar;
        client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, inventorySlot, 0, ClickType.PICKUP, client.player);
        client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, destination, 1, ClickType.PICKUP, client.player);
        client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, inventorySlot, 0, ClickType.PICKUP, client.player);
    }

    private void feed(Minecraft client) {
        if (!(client.player.containerMenu instanceof HopperMenu menu)) {
            if (frames % 10 == 0) {single(client, input);use(client, hopper);}return;
        }
        if (clicked) return;
        for (int slot = 5; slot < menu.slots.size(); slot++) if (menu.getSlot(slot).getItem().is(input) && menu.getSlot(slot).getItem().getCount() == 1) {
            client.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, client.player);clicked = true;return;
        }
        throw new IllegalStateException("Engine hopper lost its single prepared workpiece");
    }

    private void craft(Minecraft client) {
        if (!(client.player.containerMenu instanceof CraftingMenu menu)) {
            if (frames % 10 == 0) {DeepDrillConnection.empty(client);use(client, bench);}return;
        }
        if (!filled) {
            Map.of(2, AllItems.IRON_SHEET.get(), 5, SimItems.ENGINE_ASSEMBLY.get(), 8, Items.BLAST_FURNACE).forEach((slot, item) -> {
                int source = -1;
                for (int i = 10; i < 46; i++) if (menu.getSlot(i).getItem().is(item)) {source = i;break;}
                if (source < 0) throw new IllegalStateException("Engine crafting lost " + item);
                client.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, client.player);
                client.gameMode.handleInventoryMouseClick(menu.containerId, slot, 1, ClickType.PICKUP, client.player);
                client.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, client.player);
            });filled = true;return;
        }
        if (!clicked && menu.getSlot(0).getItem().is(SimBlocks.PORTABLE_ENGINES.get(DyeColor.RED).asItem())) {
            client.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, client.player);clicked = true;
        }
    }

    private void use(Minecraft client, BlockPos pos) {
        var hit = hit(client.level, client.player, client.player.getEyePosition(), pos);
        var delta = hit.getLocation().subtract(client.player.getEyePosition());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        client.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
    }

    private static BlockHitResult hit(Level level, Player player, Vec3 eye, BlockPos pos) {
        boolean onDepot = level.getBlockEntity(pos) instanceof DepotBlockEntity;
        var shape = level.getBlockState(pos).getShape(level, pos);
        var points = onDepot ? java.util.List.of(new Vec3(pos.getX() + .5, pos.getY() + shape.bounds().maxY - .01, pos.getZ() + .5))
                : shape.toAabbs().stream().map(box -> box.getCenter().add(Vec3.atLowerCornerOf(pos))).toList();
        for (var point : points) {
            var hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && (!onDepot || hit.getDirection() == Direction.UP)
                    && hit.getLocation().distanceTo(eye) <= 4.5) return hit;
        }
        throw new IllegalStateException("Engine workshop has no visible input face: target=" + pos + ", eye=" + eye);
    }

    boolean advance() {
        if (complete) return true;
        if (!viewsChecked) {
            Map.of(depot, pressStand, pressCrank, pressStand, sawCrank, sawStand, hopper, sawStand,
                    saw.north(), saw.north().east(), saw.south(), saw.south().east(), bench, bench.south())
                    .forEach((block, stand) -> hit(level, player, Vec3.atBottomCenterOf(stand).add(0, 1.62, 0), block));
            viewsChecked = true;
            LogUtils.getLogger().info("Client engine workshop checked all seven native interaction approaches");
        }
        walk.advance(player.position());
        if (!player.isAlive() || level.getGameTime() - started > 16000 || level.getGameTime() - since > (stage == 1 || stage == 4 ? 1200 : 300))
            throw new IllegalStateException("Engine workshop stalled: stage=" + stage + ", sequence=" + sequence + ", attempts=" + attempts
                    + ", player=" + player.position() + ", depot=" + depot().getHeldItem() + ", arrived=" + walk.arrived());
        if (stage == 0 && depot().getHeldItem().is(input)) next(1, pressStand, pressCrank);
        else if (stage == 1) {
            var result = depot().getHeldItem();
            if (input == Items.IRON_INGOT ? result.is(AllItems.IRON_SHEET.get()) : advanced(result, sequence + 1)) next(2, pressStand, depot);
        } else if (stage == 2 && depot().getHeldItem().isEmpty()) {
            if (input == Items.IRON_INGOT) {
                if (count(AllItems.IRON_SHEET.get()) < 2) beginPlate();
                else {sequence = 0;attempts++;beginSaw();}
            } else {
                sequence++;
                if (sequence < 16) {LogUtils.getLogger().info("Client engine attempt {} completed round {}", attempts, sequence / 2);beginSaw();}
                else if (count(SimItems.ENGINE_ASSEMBLY.get()) > 0) next(6, bench.south(), bench);
                else {
                    LogUtils.getLogger().info("Client engine attempt {} produced a native failure result; retrying", attempts);
                    if (count(AllItems.IRON_SHEET.get()) < 2) beginPlate();else {sequence = 0;attempts++;beginSaw();}
                }
            }
        } else if (stage == 3 && !((SawBlockEntity) level.getBlockEntity(saw)).inventory.isEmpty()) next(4, sawStand, sawCrank);
        else if (stage == 4) {
            var pos = output();var held = ((DepotBlockEntity) level.getBlockEntity(pos)).getHeldItem();
            if (advanced(held, sequence + 1)) next(5, pos.east(), pos);
        } else if (stage == 5 && ((DepotBlockEntity) level.getBlockEntity(target)).getHeldItem().isEmpty()) {
            sequence++;input = SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get();next(0, pressStand, depot);
        } else if (stage == 6 && count(SimBlocks.PORTABLE_ENGINES.get(DyeColor.RED).asItem()) == 1) {
            if (count(SimItems.ENGINE_ASSEMBLY.get()) != 0 || count(Items.BLAST_FURNACE) != 0)
                throw new IllegalStateException("Native engine crafting failed to consume its assembly and furnace");
            ironRemaining = count(Items.IRON_INGOT);complete = true;player.closeContainer();
            LogUtils.getLogger().info("Client hand-powered engine complete: attempts={}, iron remaining={}, ticks={}", attempts, ironRemaining, level.getGameTime() - started);
            return true;
        }
        return false;
    }

    private boolean advanced(net.minecraft.world.item.ItemStack stack, int expected) {
        if (stack.isEmpty()) return false;
        if (expected == 16) return !stack.is(SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get());
        var data = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
        if (stack.is(SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get()) && data != null && data.step() == expected) return true;
        if (stack.is(input)) return false;
        throw new IllegalStateException("Client engine sequence lost step " + expected + ": " + stack + ", data=" + data);
    }

    void cleanUp() {
        player.closeContainer();original.keySet().forEach(pos -> Clearable.tryClear(level.getBlockEntity(pos)));original.forEach(level::setBlockAndUpdate);
    }
}
