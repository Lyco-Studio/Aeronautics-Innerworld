package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.PressureHatchBlock;
import io.github.alphaly.aeronauticsinnerworld.integration.ProbeTestRig;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class DockReturnProcessing {
    private final ServerPlayer player;
    private final ProbeTestRig rig;
    private final int samples;
    private final BlockPos bench, furnace;
    private final Map<BlockPos, BlockState> original = new LinkedHashMap<>();
    private final long started;
    private volatile int stage;
    private int observed = -1, frames, beforeCoolant;
    private boolean released, filled, taken;

    DockReturnProcessing(ServerPlayer player, ProbeTestRig rig, int samples) {
        this.player = player;this.rig = rig;this.samples = samples;started = rig.level.getGameTime();
        bench = rig.source(new BlockPos(-2, 1, 2));furnace = bench.above();
        set(rig.source(new BlockPos(-1, -1, 2)), Blocks.STONE.defaultBlockState());
        set(rig.source(new BlockPos(-1, 0, 2)), Blocks.STONE.defaultBlockState());
        set(bench.below(2), Blocks.STONE.defaultBlockState());set(bench.below(), Blocks.STONE.defaultBlockState());
        set(bench, Blocks.CRAFTING_TABLE.defaultBlockState());set(furnace, Blocks.FURNACE.defaultBlockState());
        if (count(AeronauticsInnerworld.RAW_HARMONIC.get()) != 0 || count(AeronauticsInnerworld.HARMONIC_CRYSTAL.get()) != 0
                || count(AeronauticsInnerworld.STABILIZED_COOLANT_CELL.get()) != 0)
            throw new IllegalStateException("Return processing started with unearned expedition products");
        player.getInventory().add(new ItemStack(AeronauticsInnerworld.COOLANT_CELL.get(), 2));
        player.getInventory().add(new ItemStack(Items.COAL, 4));player.containerMenu.broadcastChanges();
        LogUtils.getLogger().info("Dock return processing begins with {} natural samples still in the same boat", samples);
    }

    private void set(BlockPos pos, BlockState state) {
        if (rig.level.getBlockEntity(pos) != null) throw new IllegalStateException("Return station overlaps existing equipment");
        original.put(pos.immutable(), rig.level.getBlockState(pos));rig.level.setBlockAndUpdate(pos, state);
    }

    private int count(Item item) {return player.getInventory().countItem(item);}

    void tickClient(Minecraft client) {
        ProbeConstruction.release(client);
        if (!released) {
            if (client.player.getMainHandItem().is(com.simibubi.create.AllItems.LINKED_CONTROLLER.get())) client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            released = true;
        }
        if (observed != stage) {observed = stage;frames = 0;filled = false;taken = false;}
        frames++;
        if (stage == 0) {
            emptyHand(client);
            if (frames % 10 == 0) useShip(client, ProbeTestRig.SAMPLER);
        } else if (stage == 1) {
            emptyHand(client);
            if (frames % 10 == 0) useShip(client, ProbeTestRig.BOARDING_HATCH);
        } else if (stage == 2 || stage == 3) {
            if (stage == 2 && walk(client, bench.getZ() - .5)) return;
            if (!(client.player.containerMenu instanceof FurnaceMenu menu)) {
                if (frames % 10 == 0) {emptyHand(client);useFixed(client, furnace);}return;
            }
            if (stage == 2 && frames % 6 == 0) {
                if (!menu.getSlot(0).getItem().is(AeronauticsInnerworld.RAW_HARMONIC.get())) quickMove(client, menu, AeronauticsInnerworld.RAW_HARMONIC.get(), 3);
                else if (menu.getSlot(1).getItem().isEmpty()) quickMove(client, menu, Items.COAL, 3);
            } else if (stage == 3 && !taken && menu.getSlot(0).getItem().isEmpty()
                    && menu.getSlot(2).getItem().is(AeronauticsInnerworld.HARMONIC_CRYSTAL.get()) && menu.getSlot(2).getItem().getCount() == samples) {
                client.gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, client.player);taken = true;
            }
        } else if (stage == 4) {
            if (client.player.containerMenu instanceof FurnaceMenu) client.player.closeContainer();
            if (!(client.player.containerMenu instanceof CraftingMenu menu)) {
                if (frames % 10 == 0) {emptyHand(client);useFixed(client, bench);}return;
            }
            if (!filled) {
                ingredient(client, menu, AeronauticsInnerworld.HARMONIC_CRYSTAL.get(), 1);
                ingredient(client, menu, AeronauticsInnerworld.COOLANT_CELL.get(), 2, 3);filled = true;return;
            }
            if (!taken && menu.getSlot(0).getItem().is(AeronauticsInnerworld.STABILIZED_COOLANT_CELL.get()) && menu.getSlot(0).getItem().getCount() == 2) {
                client.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, client.player);taken = true;
            }
        } else if (stage == 5) {
            if (client.player.containerMenu instanceof CraftingMenu) client.player.closeContainer();
            if (walk(client, rig.world(new BlockPos(2, 1, 2)).z)) return;
            equip(client, AeronauticsInnerworld.STABILIZED_COOLANT_CELL.get());
            if (frames % 10 == 0) useShip(client, ProbeTestRig.CABIN);
        }
    }

    private static boolean walk(Minecraft client, double z) {
        double delta = z - client.player.getZ();
        if (Math.abs(delta) < .15) return false;
        client.player.setYRot(delta > 0 ? 0 : 180);client.player.setXRot(0);
        client.options.keyUp.setDown(true);return true;
    }

    private static void quickMove(Minecraft client, AbstractContainerMenu menu, Item item, int first) {
        for (int slot = first; slot < menu.slots.size(); slot++) if (menu.getSlot(slot).getItem().is(item)) {
            client.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, client.player);return;
        }
    }

    private static void ingredient(Minecraft client, CraftingMenu menu, Item item, int... targets) {
        int source = -1;
        for (int slot = 10; slot < 46; slot++) if (menu.getSlot(slot).getItem().is(item)) {source = slot;break;}
        if (source < 0) throw new IllegalStateException("Missing returned processing ingredient " + item);
        client.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, client.player);
        for (int target : targets) client.gameMode.handleInventoryMouseClick(menu.containerId, target, 1, ClickType.PICKUP, client.player);
        if (!menu.getCarried().isEmpty()) client.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, client.player);
    }

    private static void emptyHand(Minecraft client) {
        for (int slot = 0; slot < 9; slot++) if (client.player.getInventory().getItem(slot).isEmpty()) {client.player.getInventory().selected = slot;return;}
        throw new IllegalStateException("Returned cargo left no empty hand slot");
    }

    private static void equip(Minecraft client, Item item) {
        for (int slot = 0; slot < 36; slot++) if (client.player.getInventory().getItem(slot).is(item)) {
            if (slot < 9) client.player.getInventory().selected = slot;
            else {client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, slot, 0, ClickType.SWAP, client.player);client.player.getInventory().selected = 0;}
            return;
        }
    }

    private static void look(Minecraft client, Vec3 target) {
        var delta = target.subtract(client.player.getEyePosition());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        client.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
    }

    private void useShip(Minecraft client, BlockPos relative) {
        var world = rig.world(relative).add(0, .49, 0);look(client, world);
        if (world.distanceTo(client.player.getEyePosition()) > 4.5) throw new IllegalStateException("Returned ship device is out of reach: " + relative);
        var local = rig.local(relative);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(local).add(0, .49, 0), Direction.UP, local, false));
    }

    private static void useFixed(Minecraft client, BlockPos pos) {
        var target = Vec3.atCenterOf(pos).add(0, 0, -.49);look(client, target);
        var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || hit.getLocation().distanceTo(client.player.getEyePosition()) > 4.5)
            throw new IllegalStateException("Return processing station is obstructed: target=" + pos + ", hit=" + hit.getType() + "/" + hit.getBlockPos() + ", eye=" + client.player.getEyePosition());
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
    }

    boolean advance() {
        if (!player.isAlive() || player.isInLava() || player.getHealth() < player.getMaxHealth() || rig.level.getGameTime() - started > 6000)
            throw new IllegalStateException("Return processing failed at stage " + stage + ", health=" + player.getHealth());
        var oven = (AbstractFurnaceBlockEntity) rig.level.getBlockEntity(furnace);
        switch (stage) {
            case 0 -> {
                for (int slot = 0; slot < rig.sampler().inventory.getSlots(); slot++)
                    if (!rig.sampler().inventory.getStackInSlot(slot).isEmpty()) return false;
                if (count(AeronauticsInnerworld.RAW_HARMONIC.get()) != samples) throw new IllegalStateException("Natural samples changed during native unloading");
                next(1);
            }
            case 1 -> {if (rig.level.getBlockState(rig.local(ProbeTestRig.BOARDING_HATCH)).getValue(PressureHatchBlock.OPEN)) next(2);}
            case 2 -> {
                int processing = oven.getItem(0).is(AeronauticsInnerworld.RAW_HARMONIC.get()) ? oven.getItem(0).getCount() : 0;
                int produced = oven.getItem(2).is(AeronauticsInnerworld.HARMONIC_CRYSTAL.get()) ? oven.getItem(2).getCount() : 0;
                if (processing + produced == samples && oven.getItem(1).is(Items.COAL) && count(AeronauticsInnerworld.RAW_HARMONIC.get()) == 0) next(3);
            }
            case 3 -> {if (count(AeronauticsInnerworld.HARMONIC_CRYSTAL.get()) == samples && oven.getItem(0).isEmpty() && oven.getItem(2).isEmpty()) next(4);}
            case 4 -> {
                if (count(AeronauticsInnerworld.STABILIZED_COOLANT_CELL.get()) != 2) return false;
                if (count(AeronauticsInnerworld.HARMONIC_CRYSTAL.get()) != samples - 1 || count(AeronauticsInnerworld.COOLANT_CELL.get()) != 0)
                    throw new IllegalStateException("Native return workbench did not consume the recovered crystal and two cells");
                beforeCoolant = rig.cabin().getCoolant();next(5);
            }
            case 5 -> {
                if (count(AeronauticsInnerworld.STABILIZED_COOLANT_CELL.get()) != 1) return false;
                if (rig.cabin().getCoolant() != beforeCoolant + 240) throw new IllegalStateException("Newly processed coolant did not replenish the same probe");
                for (var key : java.util.List.of("sample", "crystal")) {
                    var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "expedition/" + key);
                    if (!player.getAdvancements().getOrStartProgress(player.server.getAdvancements().get(id)).isDone())
                        throw new IllegalStateException("Natural expedition did not record task progression: " + key);
                }
                LogUtils.getLogger().info("Natural expedition processing complete: raw={}, crystals remaining={}, stabilized cells remaining=1, coolant={}, ticks={}",
                        samples, samples - 1, rig.cabin().getCoolant(), rig.level.getGameTime() - started);
                return true;
            }
            default -> throw new IllegalStateException("Unknown return processing stage " + stage);
        }
        return false;
    }

    private void next(int value) {stage = value;LogUtils.getLogger().info("Native return processing stage {}", value);}

    void cleanUp() {
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        original.keySet().forEach(pos -> Clearable.tryClear(rig.level.getBlockEntity(pos)));
        original.forEach(rig.level::setBlockAndUpdate);
    }
}
