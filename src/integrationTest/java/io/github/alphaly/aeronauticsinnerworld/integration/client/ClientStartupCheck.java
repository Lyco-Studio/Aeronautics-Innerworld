package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class ClientStartupCheck {
    private static int ticks;
    private static int worldTicks;
    private static DrillControllerClientCheck drillCheck;
    private static CompletableFuture<Void> cleanup;
    private static RuntimeException failure;

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.clientStartupCheck")) {
            return;
        }
        var client = Minecraft.getInstance();
        if (++ticks > 3000 && failure == null && cleanup == null) {
            failure = new IllegalStateException("Client check timed out: "
                    + (drillCheck == null ? "world not entered" : drillCheck.describe()) + ", paused=" + client.isPaused());
            cleanup = drillCheck == null ? CompletableFuture.completedFuture(null)
                    : drillCheck.cleanUp(client);
        }
        if (cleanup != null) {
            if (!cleanup.isDone()) {
                return;
            }
            cleanup.join();
            if (failure != null) {
                throw failure;
            }
            LogUtils.getLogger().info("Innerworld client startup check passed: models, head renderer, layer transfer, excavation and pause synchronization");
            client.stop();
            return;
        }
        if (client.level == null || client.player == null || client.getSingleplayerServer() == null) {
            return;
        }
        worldTicks++;
        if (worldTicks == 60) {
            var state = AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().defaultBlockState();
            var model = client.getBlockRenderer().getBlockModel(state);
            if (model == client.getModelManager().getMissingModel()) {
                throw new IllegalStateException("Cutter model was not loaded");
            }
            for (var controllerState : AeronauticsInnerworld.DRILL_CONTROLLER.get().getStateDefinition().getPossibleStates()) {
                if (client.getBlockRenderer().getBlockModel(controllerState) == client.getModelManager().getMissingModel()) {
                    throw new IllegalStateException("Controller model was not loaded");
                }
            }
            drillCheck = new DrillControllerClientCheck(client);
        }
        if (drillCheck != null) {
            try {
                if (drillCheck.tick(client)) {
                    Screenshot.grab(client.gameDirectory, "drill-controller-check.png", client.getMainRenderTarget(),
                            message -> LogUtils.getLogger().info("Drill check screenshot: {}", message.getString()));
                    cleanup = drillCheck.cleanUp(client);
                }
            } catch (RuntimeException exception) {
                failure = exception;
                cleanup = drillCheck.cleanUp(client);
            }
        }
    }
}
