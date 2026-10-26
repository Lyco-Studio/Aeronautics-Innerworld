package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.google.common.collect.ImmutableSet;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllItems;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.PressureHatchBlock;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.world.structure.DeepWorksPiece;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class DeepUpgradeClientCheck {
    private static int ticks, stage = -1, frames;
    private static boolean captured, oldPause, finished, filled, taken;
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Void> step, cleanup;
    private static Scene scene;
    private static RuntimeException failure;
    private static boolean drilling() {return Boolean.getBoolean("innerworld.deepUpgradeDrill");}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.deepUpgradeClientCheck") || finished) return;
        var client = Minecraft.getInstance();
        if (!captured) {captured = true;oldPause = client.options.pauseOnLostFocus;client.options.pauseOnLostFocus = false;}
        if (client.screen instanceof PauseScreen) client.setScreen(null);
        try {
            if (++ticks > (drilling() ? 8400 : 4200) && cleanup == null) throw new IllegalStateException("Deep recovery timed out at stage " + stage);
            if (cleanup != null) {
                if (!cleanup.isDone()) return;
                cleanup.join();finished = true;client.options.pauseOnLostFocus = oldPause;client.options.save();
                if (failure != null) throw failure;
                Files.writeString(client.gameDirectory.toPath().resolve("deep-upgrade-check.result"), drilling() ? "drill-verified" : "verified");
                LogUtils.getLogger().info("Deep upgrade passed: native wet entry, archive cargo, walking return and coupler crafting");
                client.stop();return;
            }
            if (client.player == null || client.level == null || client.getSingleplayerServer() == null || ticks < 100) return;
            var server = client.getSingleplayerServer();
            if (setup == null) {var id = client.player.getUUID();setup = server.submit(() -> new Scene(server.getPlayerList().getPlayer(id)));return;}
            if (!setup.isDone()) return;
            scene = setup.join();
            if (stage != scene.stage) {ProbeConstruction.release(client);stage = scene.stage;frames = 0;}
            frames++;
            if (stage == 0) {
                var offset = Vec3.atBottomCenterOf(scene.landing).add(0, .5, 0).subtract(client.player.position());
                look(client, client.player.getEyePosition().add(offset));client.player.setXRot(-25);
                client.options.keyUp.setDown(offset.horizontalDistanceSqr() > .02);client.options.keyJump.setDown(offset.y > .05);
            } else if (stage >= 1 && stage <= 3) {
                if (stage == 3 && client.player.containerMenu instanceof ChestMenu) client.player.closeContainer();
                if (!fight(client)) scene.walk.tickClient(client);
                if (stage == 2 && client.player.containerMenu instanceof ChestMenu menu && frames % 5 == 0) {
                    for (int slot = 0; slot < menu.getRowCount() * 9; slot++) if (recovered(menu.getSlot(slot).getItem())) {
                        client.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, client.player);break;
                    }
                }
            } else if (stage == 4) craft(client);
            else if (stage == 6) {if (!fight(client)) scene.drill.tickClient(client);}
            else if (stage == 5) {
                ProbeConstruction.release(client);
                if (frames == 10) Screenshot.grab(client.gameDirectory, "deep-recovery-upgrade.png", client.getMainRenderTarget(),
                        message -> LogUtils.getLogger().info("Deep recovery screenshot: {}", message.getString()));
                if (frames > 15) {cleanup = server.submit(scene::cleanUp);return;}
            }
            if (step != null) {if (!step.isDone()) return;step.join();step = null;}
            step = server.submit(scene::advance);
        } catch (Exception error) {
            ProbeConstruction.release(client);client.setScreen(null);
            if (finished || cleanup != null && cleanup.isDone()) {finished = true;client.options.pauseOnLostFocus = oldPause;throw new IllegalStateException("Deep upgrade check failed", error);}
            failure = new IllegalStateException("Deep upgrade check failed", error);
            cleanup = client.getSingleplayerServer().submit(() -> {if (scene != null) scene.cleanUp();});
        }
    }

    private static boolean recovered(ItemStack item) {
        return item.is(AeronauticsInnerworld.RELIC_ALLOY.get()) || item.is(AeronauticsInnerworld.PRESSURE_MATRIX.get())
                || item.is(AllItems.PRECISION_MECHANISM.get()) || item.is(Items.WRITTEN_BOOK);
    }

    private static void look(Minecraft client, Vec3 point) {
        var offset = point.subtract(client.player.getEyePosition());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-offset.x, offset.z)));
        client.player.setXRot((float)-Math.toDegrees(Math.atan2(offset.y, offset.horizontalDistance())));
    }

    private static boolean fight(Minecraft client) {
        for (var entity : client.level.entitiesForRendering()) if (entity instanceof MagmaCube cube && cube.isAlive()
                && cube.distanceTo(client.player) < 3.2 && client.player.hasLineOfSight(cube)) {
            ProbeConstruction.release(client);look(client, cube.getBoundingBox().getCenter());client.player.getInventory().selected = 0;
            if (client.player.getAttackStrengthScale(0) > .95f) {
                if (client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);
                client.gameMode.attack(client.player, cube);client.player.swing(InteractionHand.MAIN_HAND);
            } else if (!client.player.isUsingItem()) client.gameMode.useItem(client.player, InteractionHand.OFF_HAND);
            return true;
        }
        return false;
    }

    private static void craft(Minecraft client) {
        ProbeConstruction.release(client);
        if (!(client.player.containerMenu instanceof CraftingMenu menu)) {
            if (frames % 10 != 0) return;
            client.player.getInventory().selected = 2;
            var point = Vec3.atCenterOf(scene.bench).add(0, .49, 0);look(client, point);
            var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(scene.bench) || hit.getLocation().distanceTo(client.player.getEyePosition()) > 4.5)
                throw new IllegalStateException("Deep recovery workbench is obstructed: " + hit.getBlockPos());
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);return;
        }
        if (!filled) {
            ingredient(client, menu, AeronauticsInnerworld.RELIC_ALLOY.get(), 1, 3, 7, 9);
            ingredient(client, menu, AeronauticsInnerworld.HARMONIC_CRYSTAL.get(), 2, 8);
            ingredient(client, menu, AllItems.PRECISION_MECHANISM.get(), 4, 6);
            ingredient(client, menu, AeronauticsInnerworld.PRESSURE_MATRIX.get(), 5);filled = true;
        } else if (!taken && menu.getSlot(0).getItem().is(AeronauticsInnerworld.DEEP_BORE_COUPLER.get().asItem())) {
            client.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, client.player);taken = true;
        }
    }

    private static void ingredient(Minecraft client, CraftingMenu menu, Item item, int... targets) {
        int source = -1;
        for (int slot = 10; slot < 46; slot++) if (menu.getSlot(slot).getItem().is(item)) {source = slot;break;}
        if (source < 0) throw new IllegalStateException("Missing recovered upgrade ingredient: " + item);
        client.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, client.player);
        for (int target : targets) client.gameMode.handleInventoryMouseClick(menu.containerId, target, 1, ClickType.PICKUP, client.player);
        if (!menu.getCarried().isEmpty()) client.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, client.player);
    }

    private static ResourceLocation id(String path) {return ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, path);}

    private static final class Scene {
        private final ServerPlayer player;
        private final TeamPlayerState old;
        private final ServerLevel level;
        private final Difficulty difficulty;
        private final boolean spawning;
        private final int selected, experience, experienceLevel, score;
        private final float experienceProgress;
        private final BoundingBox bounds;
        private final BlockPos intake, landing, hatch, chest;
        private final Map<BlockPos, BlockState> hatches = new HashMap<>();
        private final Map<BlockPos, CompoundTag> containers = new HashMap<>(), spawners = new HashMap<>();
        private final Map<AdvancementHolder, Set<String>> progress = new HashMap<>();
        private final Set<UUID> entities = new HashSet<>();
        private final int alloy, mechanisms;
        private final long started;
        private volatile int stage;
        private volatile CoreMazeWalk walk;
        private BlockPos bench;
        private BlockState previousBench;
        private float minimumHealth;
        private volatile DeepDrillConnection drill;

        Scene(ServerPlayer player) {
            this.player = player;old = TeamPlayerState.capture(player);selected = player.getInventory().selected;
            experience = player.totalExperience;experienceLevel = player.experienceLevel;experienceProgress = player.experienceProgress;score = player.getScore();
            difficulty = player.server.getWorldData().getDifficulty();
            if (!player.server.getWorldData().worldGenOptions().generateStructures()) throw new IllegalStateException("Select a development world with natural structures enabled");
            level = player.server.getLevel(Mantle.LEVEL);spawning = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
            var located = level.findNearestMapStructure(TagKey.create(Registries.STRUCTURE, id("deep_works")), new BlockPos(-4096, 40, -4096), 4, false);
            if (located == null) throw new IllegalStateException("No natural deep works near the recovery region");
            var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(id("deep_works"));
            var start = level.getChunkAt(located).getStartForStructure(structure);
            if (start == null || !start.isValid() || !(start.getPieces().getFirst() instanceof DeepWorksPiece piece)) throw new IllegalStateException("Deep recovery has no natural intake");
            bounds = start.getBoundingBox();intake = piece.getEntrance();
            for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) level.getChunk(x, z);
            BlockPos dry = null, supplies = null;double nearest = Double.MAX_VALUE;
            for (var pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                var state = level.getBlockState(pos);
                if (pos.getY() == intake.getY() + 3 && state.isAir() && level.isEmptyBlock(pos.above())
                        && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                        && !level.getBlockState(pos.below()).is(Blocks.MAGMA_BLOCK) && pos.distSqr(intake) < nearest) {dry = pos.immutable();nearest = pos.distSqr(intake);}
                if (state.is(AeronauticsInnerworld.PRESSURE_HATCH.get())) hatches.put(pos.immutable(), state);
                if (level.getBlockEntity(pos) instanceof ChestBlockEntity box) {
                    containers.put(pos.immutable(), box.saveWithFullMetadata(level.registryAccess()));
                    if (count(box, AeronauticsInnerworld.PRESSURE_MATRIX.get()) > 0) supplies = pos.immutable();
                }
                if (level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) spawners.put(pos.immutable(), spawner.saveWithFullMetadata(level.registryAccess()));
            }
            if (dry == null || supplies == null || !level.getFluidState(intake).is(FluidTags.LAVA)) throw new IllegalStateException("Deep recovery has no wet intake, dry landing or matrix archive");
            landing = dry;chest = supplies;
            hatch = hatches.keySet().stream().filter(pos -> pos.getY() == landing.getY()).min(java.util.Comparator.comparingDouble(pos -> pos.distSqr(landing))).orElseThrow();
            var cargo = (ChestBlockEntity) level.getBlockEntity(chest);
            if (level.getBlockState(chest).getMenuProvider(level, chest) == null) throw new IllegalStateException("Natural archive chest is blocked above");
            alloy = count(cargo, AeronauticsInnerworld.RELIC_ALLOY.get());mechanisms = count(cargo, AllItems.PRECISION_MECHANISM.get());
            if (alloy < 4 || mechanisms < 1 || count(cargo, AeronauticsInnerworld.PRESSURE_MATRIX.get()) != 2) throw new IllegalStateException("Natural archive cannot supply its upgrade materials");
            for (var key : List.of("relay", "crystal", "deep_works", "deep_drill", "core")) {
                var advancement = player.server.getAdvancements().get(id("expedition/" + key));
                var criteria = ImmutableSet.copyOf(player.getAdvancements().getOrStartProgress(advancement).getCompletedCriteria());progress.put(advancement, criteria);
                for (var criterion : criteria) player.getAdvancements().revoke(advancement, criterion);
            }
            level.getEntities(null, new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX() + 1, bounds.maxY() + 1, bounds.maxZ() + 1).inflate(16)).forEach(entity -> entities.add(entity.getUUID()));
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, player.server);player.server.setDifficulty(Difficulty.NORMAL, true);
            player.setGameMode(GameType.SURVIVAL);player.getAbilities().flying = false;player.onUpdateAbilities();
            player.getInventory().clearContent();player.getInventory().selected = 2;player.removeAllEffects();player.clearFire();player.setHealth(player.getMaxHealth());minimumHealth = player.getHealth();
            player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 3600));player.getPersistentData().putInt("InnerworldHeatExposure", 0);
            var food = new CompoundTag();new net.minecraft.world.food.FoodData().addAdditionalSaveData(food);player.getFoodData().readAdditionalSaveData(food);
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            player.getInventory().setItem(3, new ItemStack(AeronauticsInnerworld.HARMONIC_CRYSTAL.get(), 2));
            player.getInventory().setItem(4, AllItems.PRECISION_MECHANISM.asStack());
            var position = Vec3.atBottomCenterOf(intake);player.teleportTo(level, position.x, position.y, position.z, 0, -25);player.containerMenu.broadcastChanges();
            started = level.getGameTime();LogUtils.getLogger().info("Deep recovery starts: intake={}, landing={}, hatch={}, archive={}, alloy={}, mechanisms={}", intake, landing, hatch, chest, alloy, mechanisms);
        }

        private int count(Item item) {return player.getInventory().countItem(item);}
        private static int count(ChestBlockEntity chest, Item item) {
            int total = 0;for (int slot = 0; slot < chest.getContainerSize(); slot++) if (chest.getItem(slot).is(item)) total += chest.getItem(slot).getCount();return total;
        }

        void advance() {
            level.resetEmptyTime();minimumHealth = Math.min(minimumHealth, player.getHealth());
            if (!player.isAlive() || player.getHealth() < 6 || level.getGameTime() - started > (drilling() ? 8100 : 3600))
                throw new IllegalStateException("Deep recovery failed: stage=" + stage + ", position=" + player.position() + ", health=" + player.getHealth());
            if (stage == 0 && player.getY() >= landing.getY() && player.position().subtract(Vec3.atBottomCenterOf(landing)).horizontalDistanceSqr() < .3
                    && level.getFluidState(BlockPos.containing(player.getEyePosition())).isEmpty()) {
                walk = new CoreMazeWalk(level, bounds, player.blockPosition(), hatch);next(1);
            } else if (stage >= 1 && stage <= 3) {
                walk.advance(player.position());
                if (stage == 1 && level.getBlockState(hatch).getValue(PressureHatchBlock.OPEN)) {
                    walk = new CoreMazeWalk(level, bounds, player.blockPosition(), chest);next(2);
                } else if (stage == 2 && count(AeronauticsInnerworld.PRESSURE_MATRIX.get()) == 2 && count(AeronauticsInnerworld.RELIC_ALLOY.get()) == alloy
                        && count(AllItems.PRECISION_MECHANISM.get()) == mechanisms + 1 && count(Items.WRITTEN_BOOK) == 1) {
                    var box = (ChestBlockEntity) level.getBlockEntity(chest);
                    for (int slot = 0; slot < box.getContainerSize(); slot++) if (recovered(box.getItem(slot))) throw new IllegalStateException("Deep archive duplicated its recovered cargo");
                    walk = new CoreMazeWalk(level, bounds, player.blockPosition(), landing, false);next(3);
                } else if (stage == 3 && walk.arrived()) {prepareBench();next(4);}
            } else if (stage == 4 && count(AeronauticsInnerworld.DEEP_BORE_COUPLER.get().asItem()) == 1) {
                if (count(AeronauticsInnerworld.PRESSURE_MATRIX.get()) != 1 || count(AeronauticsInnerworld.RELIC_ALLOY.get()) != alloy - 4
                        || count(AllItems.PRECISION_MECHANISM.get()) != mechanisms - 1 || count(AeronauticsInnerworld.HARMONIC_CRYSTAL.get()) != 0)
                    throw new IllegalStateException("Deep upgrade did not consume its recovered ingredients exactly once");
                for (var key : List.of("deep_works", "deep_drill")) if (!player.getAdvancements().getOrStartProgress(player.server.getAdvancements().get(id("expedition/" + key))).isDone())
                    throw new IllegalStateException("Deep recovery task did not complete: " + key);
                LogUtils.getLogger().info("Deep recovery complete: alloy remaining={}, matrices remaining=1, mechanisms remaining={}, couplers=1, minimum health={}, ticks={}", alloy - 4, mechanisms - 1, minimumHealth, level.getGameTime() - started);
                if (drilling()) {player.closeContainer();drill = new DeepDrillConnection(player, landing, bounds);next(6);} else next(5);
            } else if (stage == 6 && drill.advance()) {
                next(5);
            }
        }

        private void prepareBench() {
            for (var direction : Direction.Plane.HORIZONTAL) {
                var pos = landing.relative(direction, 2);
                if (!bounds.isInside(pos) || !level.isEmptyBlock(pos) || !level.isEmptyBlock(pos.above())
                        || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP) || level.getBlockState(pos.below()).is(Blocks.MAGMA_BLOCK)) continue;
                bench = pos;previousBench = level.getBlockState(pos);level.setBlockAndUpdate(pos, Blocks.CRAFTING_TABLE.defaultBlockState());return;
            }
            throw new IllegalStateException("Recovered materials have no dry workbench space");
        }

        private void next(int value) {stage = value;LogUtils.getLogger().info("Native deep recovery stage {}", value);}

        void cleanUp() {
            if (drill != null) drill.cleanUp();
            player.closeContainer();
            if (bench != null) level.setBlockAndUpdate(bench, previousBench);
            hatches.forEach(level::setBlockAndUpdate);
            containers.forEach((pos, tag) -> {var entity = level.getBlockEntity(pos);entity.loadWithComponents(tag, level.registryAccess());entity.setChanged();});
            spawners.forEach((pos, tag) -> {var entity = level.getBlockEntity(pos);entity.loadWithComponents(tag, level.registryAccess());entity.setChanged();});
            for (var entity : level.getEntities(null, new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX() + 1, bounds.maxY() + 1, bounds.maxZ() + 1).inflate(16)))
                if (!entities.contains(entity.getUUID()) && (entity instanceof MagmaCube || entity instanceof ItemEntity)) entity.discard();
            progress.forEach((advancement, criteria) -> {
                for (var criterion : ImmutableSet.copyOf(player.getAdvancements().getOrStartProgress(advancement).getCompletedCriteria())) player.getAdvancements().revoke(advancement, criterion);
                for (var criterion : criteria) player.getAdvancements().award(advancement, criterion);
            });
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(spawning, player.server);player.server.setDifficulty(difficulty, true);
            old.restore();player.getInventory().selected = selected;player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(selected));
            player.totalExperience = experience;player.experienceLevel = experienceLevel;player.experienceProgress = experienceProgress;player.setScore(score);
        }
    }
}
