package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreConsoleBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreWarden;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import io.github.alphaly.aeronauticsinnerworld.world.structure.CoreMazePiece;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class CoreClientCheck {
    private static final boolean FULL_COMBAT = Boolean.getBoolean("innerworld.coreFullCombat");
    private static int ticks;
    private static int observedStage = -1;
    private static int frames;
    private static boolean clicked;
    private static boolean checkedBooks;
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Void> action;
    private static CompletableFuture<Void> cleanup;
    private static Scene scene;
    private static RuntimeException failure;
    private static boolean finished;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.coreClientCheck") || finished) return;
        var client = Minecraft.getInstance();
        try {
            if (++ticks > (FULL_COMBAT ? 18000 : 2400) && cleanup == null) throw new IllegalStateException("Core client timed out at stage " + observedStage);
            if (cleanup != null) {
                if (!cleanup.isDone()) return;
                cleanup.join();finished = true;
                if (failure != null) throw failure;
                Files.writeString(client.gameDirectory.toPath().resolve(FULL_COMBAT ? "core-combat-check.result" : "core-check.result"), "verified");
                LogUtils.getLogger().info(FULL_COMBAT
                        ? "Core survival combat check passed: walking maze controls, full-health active Warden, ordinary equipment, network combat, victory and supply round trip"
                        : "Core client check passed: natural maze, surveyor, Warden renderer, attack warning, dodge, damage, network cooling, melee victory, story advancement and actual core/mantle supply round trip");
                client.stop();return;
            }
            if (client.level == null || client.player == null || client.getSingleplayerServer() == null || ticks < 100) return;
            var server = client.getSingleplayerServer();
            if (setup == null) {
                var id = client.player.getUUID();setup = server.submit(() -> new Scene(server.getPlayerList().getPlayer(id)));return;
            }
            if (!setup.isDone()) return;
            scene = setup.join();
            if (FULL_COMBAT && scene.stage == 0) {
                scene.expedition.tickClient(client);
                if (action != null) {if (!action.isDone()) return;action.join();action = null;}
                action = server.submit(scene::advance);return;
            }
            if (!client.level.dimension().equals(Core.LEVEL) && scene.stage < 7) return;
            if (observedStage != scene.stage) {
                releaseControls(client);
                observedStage = scene.stage;frames = 0;clicked = false;
            }
            frames++;
            if (!checkedBooks && frames > 15) {
                BookLayoutChecks.check(client, scene.manual);BookLayoutChecks.check(client, scene.record);checkedBooks = true;
            }
            if (observedStage == 0 && !clicked && client.player.getMainHandItem().is(AeronauticsInnerworld.CORE_SURVEYOR.get())) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);clicked = true;
            }
            if (observedStage == 1 && frames == 20) {
                if (client.getBlockRenderer().getBlockModel(AeronauticsInnerworld.CORE_CONSOLE.get().defaultBlockState()) == client.getModelManager().getMissingModel())
                    throw new IllegalStateException("Core console model is missing");
                Screenshot.grab(client.gameDirectory, "core-warden-arena.png", client.getMainRenderTarget(),
                        text -> LogUtils.getLogger().info("Core arena screenshot: {}", text.getString()));
            }
            if (observedStage == 4 && !clicked && frames > 8 && client.player.getMainHandItem().isEmpty()) {
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(scene.valve), net.minecraft.core.Direction.UP, scene.valve, false));clicked = true;
            }
            if (observedStage == 5 && !clicked && frames > 10 && client.player.getMainHandItem().is(Items.DIAMOND_SWORD)
                    && client.player.getAttackStrengthScale(0) > .95f) {
                for (var entity : client.level.entitiesForRendering()) {
                    if (entity instanceof CoreWarden warden && warden.getUUID().equals(scene.warden.getUUID())) {
                        client.gameMode.attack(client.player, warden);client.player.swing(InteractionHand.MAIN_HAND);clicked = true;break;
                    }
                }
            }
            if ((observedStage == 7 && frames > 10 || observedStage == 8 && frames > 50) && !clicked) {
                DeepDrillConnection.empty(client);
                if (!client.player.getMainHandItem().isEmpty()) return;
                var pos = observedStage == 7 ? scene.transit.coreStation : scene.transit.mantleStation;
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false));clicked = true;
            }
            if (observedStage == 9 && frames > 15) {releaseControls(client);cleanup = server.submit(scene::cleanUp);return;}
            if (action != null) {
                if (!action.isDone()) return;
                action.join();action = null;
            }
            action = server.submit(scene::advance);
        } catch (Exception e) {
            if (finished || cleanup != null && cleanup.isDone()) {finished = true;throw new IllegalStateException("Core client check failed", e);}
            failure = new IllegalStateException("Core client check failed", e);
            releaseControls(client);
            cleanup = client.getSingleplayerServer().submit(() -> {if (scene != null) scene.cleanUp();});
        }
    }

    private static void releaseControls(Minecraft client) {
        if (FULL_COMBAT) CoreMazeExpedition.release(client);
    }

    private static final class Scene {
        private final ServerPlayer player;
        private final net.minecraft.server.level.ServerLevel oldLevel;
        private final Vec3 oldPosition;
        private final float oldYaw, oldPitch, oldHealth, oldSaturation, oldExperience;
        private final int oldFood, oldExperienceLevel, oldTotalExperience, oldPortalCooldown;
        private final GameType oldMode;
        private final boolean oldFlying, hadVictory;
        private final Difficulty oldDifficulty;
        private final int oldSelected;
        private final java.util.List<MobEffectInstance> oldEffects;
        private final ListTag inventory;
        private final net.minecraft.server.level.ServerLevel level;
        private final CoreConsoleBlockEntity console;
        private final net.minecraft.nbt.CompoundTag originalConsole;
        private final HashMap<BlockPos, BlockState> blocks = new HashMap<>();
        private final ItemStack manual;
        private final ItemStack record;
        private final BlockPos valve;
        private final BlockPos arena;
        private final BlockPos entrance;
        private final net.minecraft.core.Direction outward;
        private CoreMazeExpedition expedition;
        private CoreTransitFixture transit;
        private CoreWarden warden;
        private volatile int stage;
        private long since;
        private float safeHealth;

        private Scene(ServerPlayer player) {
            this.player = player;oldLevel = player.serverLevel();oldPosition = player.position();oldYaw = player.getYRot();oldPitch = player.getXRot();
            oldHealth = player.getHealth();oldMode = player.gameMode.getGameModeForPlayer();oldFlying = player.getAbilities().flying;
            oldFood = player.getFoodData().getFoodLevel();oldSaturation = player.getFoodData().getSaturationLevel();
            oldExperience = player.experienceProgress;oldExperienceLevel = player.experienceLevel;oldTotalExperience = player.totalExperience;
            oldPortalCooldown = player.getPortalCooldown();
            oldDifficulty = player.server.getWorldData().getDifficulty();oldSelected = player.getInventory().selected;
            oldEffects = player.getActiveEffects().stream().map(MobEffectInstance::new).toList();
            inventory = player.getInventory().save(new ListTag());
            var advancement = player.server.getAdvancements().get(id("exploration/core_victory"));
            hadVictory = player.getAdvancements().getOrStartProgress(advancement).isDone();
            level = player.server.getLevel(Core.LEVEL);
            var located = level.findNearestMapStructure(TagKey.create(Registries.STRUCTURE, id("core_mazes")),
                    FULL_COMBAT ? new BlockPos(16384, 33, 16384) : new BlockPos(8192, 33, 8192), 4, false);
            if (located == null) throw new IllegalStateException("Natural core maze could not be located");
            var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(id("core_maze"));
            var start = level.getChunkAt(located).getStartForStructure(structure);
            if (start == null || !start.isValid()) throw new IllegalStateException("Natural core maze has no structure start");
            var box = start.getBoundingBox();
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) level.getChunk(x, z);
            CoreConsoleBlockEntity found = null;ItemStack book = ItemStack.EMPTY;
            for (var pos : BlockPos.betweenClosed(box.minX(), box.minY() + 1, box.minZ(), box.maxX(), box.minY() + 1, box.maxZ())) {
                if (level.getBlockEntity(pos) instanceof CoreConsoleBlockEntity control) found = control;
                if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) if (chest.getItem(slot).is(Items.WRITTEN_BOOK)) book = chest.getItem(slot).copy();
                }
            }
            if (found == null || book.isEmpty()) throw new IllegalStateException("Natural core maze lacks its console or records");
            console = found;record = book;originalConsole = console.saveWithFullMetadata(level.registryAccess());
            for (var key : java.util.List.of("Nodes", "Gates", "Valves")) for (long packed : originalConsole.getLongArray(key)) {
                var pos = BlockPos.of(packed);blocks.put(pos, level.getBlockState(pos));
            }
            valve = BlockPos.of(originalConsole.getLongArray("Valves")[0]);arena = BlockPos.of(originalConsole.getLong("Arena"));
            manual = level.getRecipeManager().byKey(id("expedition_manual")).orElseThrow().value().getResultItem(level.registryAccess()).copy();
            var maze = (CoreMazePiece) start.getPieces().getFirst();entrance = maze.getEntrance();outward = maze.getOrientation().getOpposite();
            player.setGameMode(GameType.SURVIVAL);player.getAbilities().flying = false;player.onUpdateAbilities();
            player.getInventory().clearContent();player.setHealth(20);player.getFoodData().setFoodLevel(20);
            if (FULL_COMBAT) {
                player.server.setDifficulty(Difficulty.NORMAL, true);player.removeAllEffects();
                player.getFoodData().setSaturation(5);
                player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
                player.getInventory().setItem(1, new ItemStack(Items.COOKED_BEEF, 16));
                player.getInventory().selected = 0;
            }
            if (FULL_COMBAT) {
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
                player.getInventory().setItem(3, new ItemStack(AeronauticsInnerworld.CORE_SURVEYOR.get()));
            } else player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AeronauticsInnerworld.CORE_SURVEYOR.get()));
            player.teleportTo(level, entrance.getX() + .5, entrance.getY(), entrance.getZ() + .5, 0, 0);player.containerMenu.broadcastChanges();
            if (FULL_COMBAT) expedition = new CoreMazeExpedition(player, entrance);
            since = level.getGameTime();
        }

        private void advance() {
            level.resetEmptyTime();
            if (level.getGameTime() - since > (FULL_COMBAT && stage == 0 ? 12000 : 240)) throw new IllegalStateException("Core scene stalled at stage " + stage + ", player=" + player.position());
            if (FULL_COMBAT && stage == 0) {
                if (expedition.advance()) {player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);player.containerMenu.broadcastChanges();next(6);}
                return;
            }
            if (stage == 0 && player.getCooldowns().isOnCooldown(AeronauticsInnerworld.CORE_SURVEYOR.get())) {
                for (long packed : originalConsole.getLongArray("Nodes")) {
                    var pos = BlockPos.of(packed);level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(LeverBlock.POWERED, true));
                }
                if (console.getBossId() == null) {
                    if (console.hasVictory()) {
                        player.getInventory().setItem(17, new ItemStack(AeronauticsInnerworld.HARMONIC_CRYSTAL.get(), 4));
                        player.setShiftKeyDown(true);
                    }
                    console.interact(player);player.setShiftKeyDown(false);
                }
                if (console.getBossId() == null || !(level.getEntity(console.getBossId()) instanceof CoreWarden active)) return;
                warden = active;player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.teleportTo(level, arena.getX() + .5, arena.getY(), arena.getZ() + 7.5, 180, 0);next(1);
            } else if (stage == 1 && warden.getAttackType() != 0) {
                safeHealth = player.getHealth();
                player.teleportTo(level, arena.getX() + 8.5, arena.getY(), arena.getZ() + 8.5, 135, 0);next(2);
            } else if (stage == 2 && level.getGameTime() - since > 42) {
                if (player.getHealth() < safeHealth) throw new IllegalStateException("Telegraphed strike hit a player outside its warning circle");
                player.teleportTo(level, arena.getX() + 4.5, arena.getY(), arena.getZ() + .5, 90, 0);next(3);
            } else if (stage == 3 && player.getHealth() < safeHealth) {
                warden.setNoAi(true);player.setHealth(20);
                player.teleportTo(level, valve.getX() - 1.5, valve.getY(), valve.getZ() + .5, -90, 35);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);player.containerMenu.broadcastChanges();next(4);
            } else if (stage == 4 && warden.isCoreExposed()) {
                warden.setHealth(1);warden.invulnerableTime = 0;
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));player.containerMenu.broadcastChanges();
                player.teleportTo(level, warden.getX(), warden.getY(), warden.getZ() + 2, 180, 0);next(5);
            } else if (stage == 5 && !warden.isAlive() && console.getBossId() == null && console.hasVictory()) {
                var advancement = player.server.getAdvancements().get(id("exploration/core_victory"));
                if (!player.getAdvancements().getOrStartProgress(advancement).isDone()) throw new IllegalStateException("Actual melee participant did not receive story completion");
                next(6);
            } else if (stage == 6) {
                transit = new CoreTransitFixture(player, entrance, outward);next(7);
            } else if (stage == 7 && player.level().dimension().equals(io.github.alphaly.aeronauticsinnerworld.world.Mantle.LEVEL)) {
                transit.verifyCargo(player);next(8);
            } else if (stage == 8 && player.level().dimension().equals(Core.LEVEL)) {
                transit.verifyCargo(player);next(9);
            }
        }

        private void next(int stage) {this.stage = stage;since = level.getGameTime();}

        private void cleanUp() {
            if (expedition != null) expedition.cleanUp();
            if (transit != null) transit.cleanUp();
            if (warden != null) warden.discard();
            blocks.forEach(level::setBlockAndUpdate);
            level.getChunkAt(console.getBlockPos());
            if (level.getBlockEntity(console.getBlockPos()) instanceof CoreConsoleBlockEntity current) {
                current.loadWithComponents(originalConsole, level.registryAccess());current.setChanged();
            }
            for (var entity : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(arena).inflate(16))) if (entity.getItem().is(AeronauticsInnerworld.CORE_HEART.get())) entity.discard();
            for (var orb : level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, new net.minecraft.world.phys.AABB(arena).inflate(16))) orb.discard();
            if (!hadVictory) player.getAdvancements().revoke(player.server.getAdvancements().get(id("exploration/core_victory")), "defeat");
            player.teleportTo(oldLevel, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch);player.setGameMode(oldMode);
            player.getAbilities().flying = oldFlying;player.onUpdateAbilities();player.getInventory().load(inventory);
            player.getInventory().selected = oldSelected;
            if (FULL_COMBAT) {
                player.removeAllEffects();oldEffects.forEach(player::addEffect);
                player.server.setDifficulty(oldDifficulty, true);
            }
            player.setHealth(oldHealth);player.getFoodData().setFoodLevel(oldFood);player.getFoodData().setSaturation(oldSaturation);
            player.experienceProgress = oldExperience;player.experienceLevel = oldExperienceLevel;player.totalExperience = oldTotalExperience;
            player.setPortalCooldown(oldPortalCooldown);
            player.containerMenu.broadcastChanges();
        }

        private static ResourceLocation id(String path) {return ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, path);}
    }
}
