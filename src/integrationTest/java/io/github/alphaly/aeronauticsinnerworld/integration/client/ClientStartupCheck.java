package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
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
    private static java.util.concurrent.CompletableFuture<Void> cleanup;
    private static RuntimeException failure;

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.clientStartupCheck")) {
            return;
        }
        if (++ticks > 1200) {
            throw new IllegalStateException("Client did not enter the runtime check world");
        }
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.getSingleplayerServer() == null) {
            return;
        }
        if (++worldTicks == 60) {
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
        if (cleanup != null) {
            if (!cleanup.isDone()) {
                return;
            }
            cleanup.join();
            if (failure != null) {
                throw failure;
            }
            LogUtils.getLogger().info("Innerworld client startup check passed: models, head renderer, vertical/horizontal movement and pause synchronization");
            client.stop();
        } else if (drillCheck != null) {
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
