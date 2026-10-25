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
import net.minecraft.client.renderer.entity.ThrownItemRenderer;

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
        event.registerEntityRenderer(AeronauticsInnerworld.LAVA_STALKER.get(), LavaStalkerRenderer::new);
        event.registerEntityRenderer(AeronauticsInnerworld.PRESSURE_BOLT_ENTITY.get(), context -> new ThrownItemRenderer<>(context, .6f, true));
    }

    @SubscribeEvent
    public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(LavaStalkerModel.LAYER, LavaStalkerModel::layer);
    }
}
