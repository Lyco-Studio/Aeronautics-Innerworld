package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.client.ponder.InnerworldPonderPlugin;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.PonderTooltipHandler;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class PonderClientCheck {
    private static final List<String> SUBJECTS = List.of("multi_axis_cutter", "bore_station", "refractory_hull",
            "heat_engine", "molten_thruster", "mantle_sampler");
    private static final Map<String, Integer> TEXT_COUNTS = Map.of("drill_assembly", 6, "layer_connection", 7,
            "sealed_probe", 7, "heat_exchange", 6, "propulsion_trim", 6, "survey_sampling", 6);
    private static final Map<String, String> ENGLISH_TITLES = new HashMap<>();
    private static String oldLanguage;
    private static ItemStack oldSlot;
    private static boolean oldPause, started, finished;
    private static int ticks, stage, view, frames;
    private static CompletableFuture<Void> reload;

    public static boolean holdingPonderKey() {
        return Boolean.getBoolean("innerworld.ponderClientCheck") && !finished && stage == 1 && frames > 10;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.ponderClientCheck") || finished) return;
        var client = Minecraft.getInstance();
        try {
            if (client.player == null || client.level == null) return;
            if (!started) {
                started = true;oldLanguage = client.options.languageCode;oldPause = client.options.pauseOnLostFocus;
                oldSlot = client.player.getInventory().getItem(0).copy();client.options.pauseOnLostFocus = false;
                client.player.getInventory().setItem(0, new ItemStack(AeronauticsInnerworld.DRILL_CONTROLLER.get()));
                reload = language(client, "en_us");
            }
            if (++ticks > 5000) throw new IllegalStateException("Ponder check timed out at stage " + stage);
            if (reload != null) {if (!reload.isDone()) return;reload.join();reload = null;}
            if (client.getOverlay() != null) return;
            switch (stage) {
                case 0 -> {
                    checkScenes(false);
                    client.setScreen(new InventoryScreen(client.player));
                    stage = 1;frames = 0;
                    LogUtils.getLogger().info("Ponder check: exercising the native tooltip entry with a simulated held W key");
                }
                case 1 -> {
                    if (client.screen instanceof PonderUI ponder) {
                        require(ponder.getSubject().is(AeronauticsInnerworld.DRILL_CONTROLLER_ITEM.get()), "Wrong native Ponder subject");
                        stage = 6;view = 0;frames = 0;
                    } else {
                        frames++;
                        PonderTooltipHandler.addToTooltip(new java.util.ArrayList<>(), client.player.getInventory().getItem(0));
                    }
                }
                case 6 -> {
                    if (++frames < 10) return;
                    capture(client, "ponder-native-entry.png");stage = 2;frames = 0;
                }
                case 2, 4 -> {
                    if (frames == 0) {
                        var ui = PonderUI.of(id(SUBJECTS.get(view)));
                        client.setScreen(ui);
                        var scene = ui.getActiveScene();
                        int frame = switch (view) {case 0 -> 2;case 1 -> 4;case 2 -> 2;case 3 -> 2;case 4 -> 3;default -> 2;};
                        ui.seekToTime(scene.getKeyframeTime(frame) + 25);
                    }
                    if (++frames < 10) return;
                    capture(client, "ponder-" + SUBJECTS.get(view) + (stage == 2 ? "-en.png" : "-zh.png"));
                    if (++view == SUBJECTS.size()) {
                        view = 0;
                        if (stage == 2) {stage = 3;reload = language(client, "zh_cn");}
                        else {stage = 5;reload = language(client, oldLanguage);}
                    }
                    frames = 0;
                }
                case 3 -> {checkScenes(true);stage = 4;}
                case 5 -> {
                    restore(client);finished = true;
                    Files.writeString(client.gameDirectory.toPath().resolve("ponder-check.result"), "verified");
                    LogUtils.getLogger().info("Ponder passed: native held-key entry, 21 subjects, two indexed tags, six complete storyboards and bilingual captions");
                    client.stop();
                }
                default -> throw new IllegalStateException("Unknown Ponder check stage " + stage);
            }
        } catch (Exception error) {
            finished = true;if (started) restore(client);
            throw new IllegalStateException("Ponder check failed at stage " + stage + ", view " + view, error);
        }
    }

    private static void checkScenes(boolean chinese) {
        var registry = PonderIndex.getSceneAccess();
        var tags = PonderIndex.getTagAccess();
        var checked = new java.util.HashSet<String>();
        int subjects = 0;
        for (var tag : List.of(InnerworldPonderPlugin.DRILLING, InnerworldPonderPlugin.PROBES)) {
            require(tags.getListedTags().contains(tags.getRegisteredTag(tag)), "Unlisted Ponder tag " + tag);
            for (var item : tags.getItems(tag)) {
                subjects++;
                require(registry.doScenesExistForId(item), "Missing storyboards for " + item);
                for (var scene : registry.compile(item)) if (checked.add(scene.getId().getPath())) checkScene(scene, chinese);
            }
        }
        require(subjects == 21, "Unexpected Ponder subject count: " + subjects);
        require(checked.equals(TEXT_COUNTS.keySet()), "Incomplete storyboard set: " + checked);
        require(registry.compile(id("cabin_regulator")).size() == 2, "Regulator needs both sealing and cooling scenes");
        require(registry.compile(id("drill_controller")).size() == 2, "Controller needs both assembly and connection scenes");
    }

    private static void checkScene(PonderScene scene, boolean chinese) {
        var name = scene.getId().getPath();
        require(TEXT_COUNTS.containsKey(name), "Unexpected storyboard: " + name);
        require(!scene.getWorld().getBlockState(new net.minecraft.core.BlockPos(0, 0, 0)).isAir(), "Empty structure: " + name);
        require(scene.getKeyframeCount() >= TEXT_COUNTS.get(name), "Missing keyframes: " + name);
        var title = scene.getTitle();
        require(!title.contains(".ponder."), "Untranslated title: " + name);
        if (chinese) require(!title.equals(ENGLISH_TITLES.get(name)), "Title did not change language: " + name);
        else ENGLISH_TITLES.put(name, title);
        for (int index = 1; index <= TEXT_COUNTS.get(name); index++) {
            var key = "aeronautics_innerworld.ponder." + name + ".text_" + index;
            require(I18n.exists(key), "Missing Ponder caption " + key);
        }
        scene.begin();scene.seekToTime(scene.getTotalTime() + 1);
        require(scene.isFinished(), "Storyboard did not finish: " + name);
    }

    private static CompletableFuture<Void> language(Minecraft client, String code) {
        client.setScreen(null);client.getLanguageManager().setSelected(code);client.options.languageCode = code;
        return client.reloadResourcePacks();
    }

    private static void restore(Minecraft client) {
        client.setScreen(null);client.options.pauseOnLostFocus = oldPause;
        client.options.languageCode = oldLanguage;client.getLanguageManager().setSelected(oldLanguage);
        if (client.player != null) client.player.getInventory().setItem(0, oldSlot);
        client.options.save();
    }

    private static void capture(Minecraft client, String name) {
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> { });
    }

    private static ResourceLocation id(String path) {return ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, path);}
    private static void require(boolean condition, String message) {if (!condition) throw new IllegalStateException(message);}
}
