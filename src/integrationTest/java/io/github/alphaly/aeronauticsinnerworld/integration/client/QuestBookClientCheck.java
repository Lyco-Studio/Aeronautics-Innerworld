package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.google.common.collect.ImmutableSet;
import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class QuestBookClientCheck {
    private static final ResourceLocation BOOK = id("expedition");
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Void> work, cleanup;
    private static CompletableFuture<Void> reload;
    private static Scene scene;
    private static Object api;
    private static Class<?> apiType;
    private static int ticks, view, frames;
    private static boolean placed, taken, captured, oldPause, finished;
    private static Language originalLanguage;
    private static String oldLanguage;
    private static RuntimeException failure;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.questBookClientCheck") || finished) return;
        var client = Minecraft.getInstance();
        if (!captured) {captured = true;oldPause = client.options.pauseOnLostFocus;client.options.pauseOnLostFocus = false;originalLanguage = Language.getInstance();oldLanguage = client.options.languageCode;}
        try {
            if (cleanup != null) {
                if (!cleanup.isDone()) return;
                cleanup.join();finished = true;Language.inject(originalLanguage);client.getLanguageManager().setSelected(oldLanguage);
                client.options.languageCode = oldLanguage;client.options.pauseOnLostFocus = oldPause;client.options.save();
                if (failure != null) throw failure;
                Files.writeString(client.gameDirectory.toPath().resolve("quest-book-check.result"), "verified");
                LogUtils.getLogger().info("Quest journal passed: native crafting, bilingual tree pages, completion sync and one-time milestone rewards");
                client.stop();return;
            }
            if (++ticks > 1800) throw new IllegalStateException("Quest journal timed out: view=" + view);
            if (client.player == null || client.level == null || client.getSingleplayerServer() == null || ticks < 100) return;
            if (setup == null) {
                checkLayout(client);
                apiType = Class.forName("vazkii.patchouli.api.PatchouliAPI$IPatchouliAPI");
                api = Class.forName("vazkii.patchouli.api.PatchouliAPI").getMethod("get").invoke(null);
                var player = client.player.getUUID();setup = client.getSingleplayerServer().submit(() -> new Scene(client.getSingleplayerServer().getPlayerList().getPlayer(player)));return;
            }
            if (!setup.isDone()) return;
            scene = setup.join();
            if (work != null) {if (!work.isDone()) return;work.join();work = null;}
            if (reload != null) {if (!reload.isDone() || client.getOverlay() != null) return;reload.join();reload = null;open(view == 9 ? "reference/industry" : "tree", view == 9 ? 2 : 0);frames = 0;}
            if (client.getOverlay() != null) return;
            if (scene.stage == 0) {craft(client);work = client.getSingleplayerServer().submit(scene::advance);return;}
            frames++;
            if (frames % 10 == 0) client.getToasts().clear();
            if (view == 0) {
                if (client.player.containerMenu instanceof CraftingMenu) client.player.closeContainer();
                equipJournal(client);
                if (frames % 10 == 0) client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                if (BOOK.equals(apiType.getMethod("getOpenBookGui").invoke(api))) {view = 1;frames = 0;}
            } else if (frames >= 20) {
                require(client.screen != null && client.screen.getClass().getSimpleName().startsWith("GuiBook"), "Patchouli did not open a usable book screen");
                switch (view) {
                    case 1 -> {capture(client, "quest-journal-landing.png");reload = language(client, "en_us");}
                    case 2 -> {capture(client, "quest-tree-en.png");reload = language(client, "zh_cn");}
                    case 3 -> {capture(client, "quest-tree-zh.png");work = client.getSingleplayerServer().submit(scene::recoverMatrix);}
                    case 4 -> {
                        work = client.getSingleplayerServer().submit(scene::advance);
                        if (scene.stage != 2) return;
                        open("missions/deep_works", 0);
                    }
                    case 5 -> {capture(client, "quest-completed-zh.png");open("tree", 1);}
                    case 6 -> {capture(client, "quest-deep-tree-zh.png");work = client.getSingleplayerServer().submit(scene::repeatReceipt);}
                    case 7 -> {
                        work = client.getSingleplayerServer().submit(scene::advance);
                        if (scene.stage != 3) return;
                        open("reference/industry", 2);
                    }
                    case 8 -> {capture(client, "quest-industry-zh.png");reload = language(client, "en_us");}
                    case 9 -> {
                        capture(client, "quest-industry-en.png");
                        cleanup = client.getSingleplayerServer().submit(scene::cleanUp);return;
                    }
                    default -> throw new IllegalStateException("Unknown journal view " + view);
                }
                view++;frames = 0;
            }
        } catch (Exception error) {
            if (finished) throw new IllegalStateException("Quest journal check failed", error);
            failure = new IllegalStateException("Quest journal check failed at view " + view, error);
            cleanup = client.getSingleplayerServer().submit(() -> {if (scene != null) scene.cleanUp();});
        }
    }

    private static void open(String entry, int page) throws ReflectiveOperationException {
        apiType.getMethod("openBookEntry", ResourceLocation.class, ResourceLocation.class, int.class).invoke(api, BOOK, id(entry), page);
    }

    private static CompletableFuture<Void> language(Minecraft client, String locale) {
        client.setScreen(null);
        client.getLanguageManager().setSelected(locale);client.options.languageCode = locale;
        return client.reloadResourcePacks();
    }

    private static void checkLayout(Minecraft client) {
        try {
            for (var locale : List.of("en_us", "zh_cn")) {
                Language.inject(ClientLanguage.loadFrom(client.getResourceManager(), List.of("en_us", locale), false));
                for (var key : List.of("drill", "mantle", "probe", "sample", "crystal", "defense", "protection", "relay", "deep_works", "deep_drill", "core", "victory")) {
                    var text = Component.translatable("book.aeronautics_innerworld.tasks." + key + ".text");
                    require(client.font.wordWrapHeight(text, 114) <= 128, "Quest text exceeds its page: " + locale + " " + key);
                    var label = Component.translatable("book.aeronautics_innerworld.tasks." + key + ".label").getString().replaceAll("\\$\\([^)]*\\)", "");
                    require(client.font.width(label) <= 40, "Tree label exceeds its node: " + locale + " " + key);
                }
                for (int page = 1; page <= 34; page++) require(client.font.wordWrapHeight(Component.translatable("book.aeronautics_innerworld.page." + page), 114) <= 128,
                        "Reference text exceeds its page: " + locale + " " + page);
            }
        } finally {
            Language.inject(originalLanguage);
        }
    }

    private static void capture(Minecraft client, String name) {
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> { });
    }

    private static void equipJournal(Minecraft client) {
        var journal = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("patchouli", "guide_book"));
        for (int slot = 0; slot < 36; slot++) if (client.player.getInventory().getItem(slot).is(journal)) {
            if (slot < 9) client.player.getInventory().selected = slot;
            else {client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, slot, 0, ClickType.SWAP, client.player);client.player.getInventory().selected = 0;}
            return;
        }
    }

    private static void craft(Minecraft client) {
        if (!(client.player.containerMenu instanceof CraftingMenu menu)) {
            if (ticks % 10 != 0) return;
            var target = Vec3.atCenterOf(scene.bench);var delta = target.subtract(client.player.getEyePosition());
            client.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            client.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
            var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            require(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(scene.bench)
                    && hit.getLocation().distanceTo(client.player.getEyePosition()) <= 4.5, "Journal workbench is not reachable");
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);return;
        }
        if (!placed) {
            client.gameMode.handlePlaceRecipe(menu.containerId, client.level.getRecipeManager().byKey(id("expedition_taskbook")).orElseThrow(), false);placed = true;return;
        }
        if (!taken && menu.getSlot(0).getItem().is(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("patchouli", "guide_book")))) {
            client.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, client.player);taken = true;
        }
    }

    private static ResourceLocation id(String path) {return ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, path);}
    private static void require(boolean condition, String message) {if (!condition) throw new IllegalStateException(message);}

    private static final class Scene {
        private final ServerPlayer player;
        private final TeamPlayerState old;
        private final int oldSlot, oldExperience, oldLevel, oldScore;
        private final float oldProgress;
        private final BlockPos bench;
        private final Map<BlockPos, BlockState> terrain = new HashMap<>();
        private final Map<AdvancementHolder, Set<String>> progress = new HashMap<>();
        private final AdvancementHolder matrix;
        private final int baselineExperience;
        private long repeated;
        private volatile int stage;

        Scene(ServerPlayer player) {
            this.player = player;old = TeamPlayerState.capture(player);oldSlot = player.getInventory().selected;
            oldExperience = player.totalExperience;oldLevel = player.experienceLevel;oldProgress = player.experienceProgress;oldScore = player.getScore();
            var level = player.serverLevel();var base = player.blockPosition().offset(16, 16, 0);
            while (!level.isEmptyBlock(base) && base.getY() < level.getMaxBuildHeight() - 5) base = base.above(4);
            for (var pos : BlockPos.betweenClosed(base.offset(-1, -1, -1), base.offset(3, 3, 3))) {
                require(level.getBlockEntity(pos) == null, "Journal scene overlaps existing equipment");terrain.put(pos.immutable(), level.getBlockState(pos));
                level.setBlockAndUpdate(pos, pos.getY() == base.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            bench = base.offset(1, 0, 1);level.setBlockAndUpdate(bench, Blocks.CRAFTING_TABLE.defaultBlockState());
            for (var key : List.of("root", "mantle", "probe", "sample", "crystal", "relay", "deep_works")) {
                var advancement = player.server.getAdvancements().get(id("expedition/" + key));
                var current = player.getAdvancements().getOrStartProgress(advancement);
                progress.put(advancement, ImmutableSet.copyOf(current.getCompletedCriteria()));
                if (key.equals("deep_works")) for (var criterion : ImmutableSet.copyOf(current.getCompletedCriteria())) player.getAdvancements().revoke(advancement, criterion);
                else for (var criterion : advancement.value().criteria().keySet()) player.getAdvancements().award(advancement, criterion);
            }
            matrix = player.server.getAdvancements().get(id("expedition/deep_works"));baselineExperience = player.totalExperience;
            player.setGameMode(GameType.SURVIVAL);player.clearFire();player.getInventory().clearContent();player.getInventory().selected = 0;
            player.getInventory().setItem(1, new ItemStack(Items.BOOK));player.getInventory().setItem(2, new ItemStack(Items.COPPER_INGOT));
            player.getInventory().setItem(3, new ItemStack(Items.REDSTONE));
            player.teleportTo(level, base.getX() + .5, base.getY(), base.getZ() + .5, 0, 0);player.containerMenu.broadcastChanges();
        }

        void advance() {
            if (stage == 0) {
                var journal = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("patchouli", "guide_book"));
                var stack = player.getInventory().items.stream().filter(item -> item.is(journal)).findFirst().orElse(ItemStack.EMPTY);
                if (stack.isEmpty()) return;
                require(BOOK.equals(stack.get(BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.fromNamespaceAndPath("patchouli", "book")))), "Crafted journal has the wrong book component");
                for (var ingredient : List.of(Items.BOOK, Items.COPPER_INGOT, Items.REDSTONE)) require(player.getInventory().countItem(ingredient) == 0, "Journal recipe retained an ingredient");
                stage = 1;
            } else if (stage == 1 && player.getAdvancements().getOrStartProgress(matrix).isDone()) {
                verifyReward();stage = 2;
            } else if (stage == 2 && repeated != 0 && player.level().getGameTime() - repeated >= 20) {
                verifyReward();stage = 3;
            }
        }

        void recoverMatrix() {
            player.getInventory().add(new ItemStack(AeronauticsInnerworld.PRESSURE_MATRIX.get()));player.containerMenu.broadcastChanges();
        }

        void repeatReceipt() {
            int slot = player.getInventory().findSlotMatchingItem(new ItemStack(AeronauticsInnerworld.PRESSURE_MATRIX.get()));
            var stack = player.getInventory().removeItem(slot, 1);player.containerMenu.broadcastChanges();
            require(stack.getCount() == 1, "Completed task lost its recovered matrix");
            player.getInventory().add(stack);player.containerMenu.broadcastChanges();repeated = player.level().getGameTime();
        }

        private void verifyReward() {
            require(player.totalExperience == baselineExperience + 50
                    && player.getInventory().countItem(AeronauticsInnerworld.STABILIZED_COOLANT_CELL.get()) == 2,
                    "Milestone rewards were missing or granted more than once");
        }

        void cleanUp() {
            if (player.containerMenu != player.inventoryMenu) player.closeContainer();
            progress.forEach((advancement, criteria) -> {
                for (var criterion : ImmutableSet.copyOf(player.getAdvancements().getOrStartProgress(advancement).getCompletedCriteria())) player.getAdvancements().revoke(advancement, criterion);
                for (var criterion : criteria) player.getAdvancements().award(advancement, criterion);
            });
            terrain.forEach(player.serverLevel()::setBlockAndUpdate);old.restore();player.getInventory().selected = oldSlot;
            player.totalExperience = oldExperience;player.experienceLevel = oldLevel;player.experienceProgress = oldProgress;
            player.setScore(oldScore);
        }
    }
}
