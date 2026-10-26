package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class ProbeFabrication {
    private record Step(String recipe, int batches, int count) {
        ResourceLocation id() {return ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, recipe);}
        Item output() {return BuiltInRegistries.ITEM.get(id());}
    }

    private static final List<Step> STEPS = List.of(new Step("refractory_hull", 18, 144), new Step("pressure_hatch", 2, 4),
            new Step("mantle_viewport", 1, 4), new Step("multi_axis_cutter", 1, 1), new Step("cabin_regulator", 1, 1),
            new Step("echo_sounder", 1, 1), new Step("mantle_sampler", 1, 1), new Step("molten_thruster", 2, 2),
            new Step("mantle_ballast", 2, 2));
    private final ServerPlayer player;
    private final ServerLevel level;
    final BlockPos bench;
    private final long started;
    private volatile int step;
    private int observed = -1, frames;
    private boolean placed, taken;
    private String approach;
    volatile boolean complete;

    ProbeFabrication(ServerPlayer player, ServerLevel level, BlockPos bench) {
        this.player = player;this.level = level;this.bench = bench;started = level.getGameTime();
    }

    static Map<Item, Integer> ingredients() {
        return Map.ofEntries(Map.entry(Items.IRON_INGOT, 92), Map.entry(Items.NETHER_BRICK, 72),
                Map.entry(AllItems.COPPER_SHEET.get(), 25), Map.entry(AllBlocks.COGWHEEL.asItem(), 2),
                Map.entry(Items.GLASS, 2), Map.entry(AllItems.ELECTRON_TUBE.get(), 2), Map.entry(AllBlocks.SHAFT.asItem(), 7),
                Map.entry(Items.QUARTZ, 1), Map.entry(Items.CHEST, 1), Map.entry(AllBlocks.ANDESITE_CASING.asItem(), 3),
                Map.entry(AllItems.PROPELLER.get(), 2), Map.entry(AllBlocks.FLUID_TANK.asItem(), 2));
    }

    void tickClient(Minecraft client) {
        ProbeConstruction.release(client);
        if (observed != step) {observed = step;frames = 0;placed = false;taken = false;}
        if (step >= STEPS.size()) return;
        if (++frames > 200) {
            Screenshot.grab(client.gameDirectory, "probe-fabrication-stalled.png", client.getMainRenderTarget(), message -> { });
            throw new IllegalStateException("Probe crafting input timed out at " + STEPS.get(step).recipe
                + ", placed=" + placed + ", taken=" + taken + ", menu=" + client.player.containerMenu.getClass().getSimpleName()
                + ", slots=" + client.player.containerMenu.slots.stream().limit(10).map(slot -> slot.getItem().toString()).toList()
                + ", approach=" + approach);
        }
        if (!(client.player.containerMenu instanceof CraftingMenu menu)) {
            if (frames % 10 != 0) return;
            if (client.level.dimension() != level.dimension() || client.player.position().distanceTo(Vec3.atCenterOf(bench)) > 8) return;
            client.player.getInventory().selected = 0;
            var target = Vec3.atCenterOf(bench).add(0, .49, 0);var delta = target.subtract(client.player.getEyePosition());
            client.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            client.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
            var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), target, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, client.player));
            approach = "eye=" + client.player.getEyePosition() + ", bench=" + bench + ", hit=" + hit.getBlockPos()
                    + ", state=" + client.level.getBlockState(hit.getBlockPos()) + ", chunk=" + client.level.hasChunkAt(bench)
                    + ", height=" + client.level.getMinBuildHeight() + ".." + client.level.getMaxBuildHeight()
                    + ", screen=" + client.screen;
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(bench)
                    || hit.getLocation().distanceTo(client.player.getEyePosition()) > 4.5) return;
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);return;
        }
        var current = STEPS.get(step);
        if (!placed) {
            var recipe = client.level.getRecipeManager().byKey(current.id()).orElseThrow();
            LogUtils.getLogger().info("Placing native probe recipe: {}, known={}", current.recipe, client.player.getRecipeBook().contains(recipe));
            client.gameMode.handlePlaceRecipe(menu.containerId, recipe, current.batches > 1);placed = true;return;
        }
        if (taken || !menu.getSlot(0).getItem().is(current.output())) return;
        for (int slot = 1; slot <= 9; slot++) {
            var input = menu.getSlot(slot).getItem();
            if (!input.isEmpty() && input.getCount() != current.batches) return;
        }
        client.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, client.player);taken = true;
    }

    void advance() {
        if (complete) return;
        var current = STEPS.get(step);
        if (level.getGameTime() - started > 2000) throw new IllegalStateException("Probe fabrication timed out at " + current.recipe);
        int count = player.getInventory().countItem(current.output());
        if (count < current.count) return;
        if (count != current.count || !(player.containerMenu instanceof CraftingMenu menu) || !menu.getCarried().isEmpty())
            throw new IllegalStateException("Native workbench produced an unexpected batch for " + current.recipe + ": " + count);
        for (int slot = 0; slot <= 9; slot++) if (!menu.getSlot(slot).getItem().isEmpty()) return;
        LogUtils.getLogger().info("Native probe fabrication: {} x{}", current.recipe, count);
        if (++step < STEPS.size()) return;
        for (var ingredient : ingredients().keySet()) if (player.getInventory().countItem(ingredient) != 0)
            throw new IllegalStateException("Probe fabrication left a prepared ingredient unused: " + ingredient);
        if (player.getInventory().countItem(AeronauticsInnerworld.REFRACTORY_HULL.asItem()) != 117
                || player.getInventory().countItem(AeronauticsInnerworld.MULTI_AXIS_CUTTER.asItem()) != 0)
            throw new IllegalStateException("Device crafting did not consume its hulls and cutter");
        complete = true;
        LogUtils.getLogger().info("Native probe fabrication complete: hulls=144, hulls used in devices=27, ticks={}", level.getGameTime() - started);
    }

    void verifyRemainder() {
        if (player.getInventory().countItem(AeronauticsInnerworld.REFRACTORY_HULL.asItem()) != 5
                || player.getInventory().countItem(AeronauticsInnerworld.MANTLE_VIEWPORT.asItem()) != 2
                || player.getInventory().countItem(AeronauticsInnerworld.PRESSURE_HATCH.asItem()) != 1)
            throw new IllegalStateException("Probe build lost the native crafting batch remainder");
    }
}
