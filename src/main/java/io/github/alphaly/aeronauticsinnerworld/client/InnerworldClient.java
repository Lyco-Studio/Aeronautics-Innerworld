package io.github.alphaly.aeronauticsinnerworld.client;

import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleEntityVisualizer;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = AeronauticsInnerworld.MOD_ID, value = Dist.CLIENT)
public final class InnerworldClient {
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> SimpleEntityVisualizer.builder(AeronauticsInnerworld.DRILL_HEAD_ENTITY.get())
                .factory(ContraptionVisual::new).neverSkipVanillaRender().apply());
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(AeronauticsInnerworld.DRILL_HEAD_ENTITY.get(), ContraptionEntityRenderer::new);
    }
}
