package io.github.alphaly.aeronauticsinnerworld.client;

import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.TooltipModifier;
import dev.engine_room.flywheel.lib.visualization.SimpleEntityVisualizer;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.createmod.catnip.lang.FontHelper;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.registries.BuiltInRegistries;
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
        event.enqueueWork(() -> {
            SimpleEntityVisualizer.builder(AeronauticsInnerworld.DRILL_HEAD_ENTITY.get())
                    .factory(ContraptionVisual::new).neverSkipVanillaRender().apply();
            SimpleBlockEntityVisualizer.builder(AeronauticsInnerworld.DRILL_CONTROLLER_ENTITY.get())
                    .factory(MachineShaftRenderer::visual).apply();
            SimpleBlockEntityVisualizer.builder(AeronauticsInnerworld.PROBE_DRIVE_ENTITY.get())
                    .factory(MachineShaftRenderer::visual).apply();
            SimpleBlockEntityVisualizer.builder(AeronauticsInnerworld.CABIN_REGULATOR_ENTITY.get())
                    .factory(MachineShaftRenderer::visual).apply();
            SimpleBlockEntityVisualizer.builder(AeronauticsInnerworld.ECHO_SOUNDER_ENTITY.get())
                    .factory(MachineShaftRenderer::visual).apply();
            SimpleBlockEntityVisualizer.builder(AeronauticsInnerworld.MANTLE_SAMPLER_ENTITY.get())
                    .factory(MachineShaftRenderer::visual).apply();
            SimpleBlockEntityVisualizer.builder(AeronauticsInnerworld.PRESSURE_CANNON_ENTITY.get())
                    .factory(MachineShaftRenderer::visual).apply();
            for (var item : BuiltInRegistries.ITEM) {
                if (BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(AeronauticsInnerworld.MOD_ID)) {
                    TooltipModifier.REGISTRY.register(item, new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE));
                }
            }
        });
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(AeronauticsInnerworld.DRILL_HEAD_ENTITY.get(), ContraptionEntityRenderer::new);
        event.registerEntityRenderer(AeronauticsInnerworld.LAVA_STALKER.get(), LavaStalkerRenderer::new);
        event.registerEntityRenderer(AeronauticsInnerworld.MANTLE_GRAZER.get(), LavaStalkerRenderer::new);
        event.registerEntityRenderer(AeronauticsInnerworld.CORE_WARDEN.get(), CoreWardenRenderer::new);
        event.registerEntityRenderer(AeronauticsInnerworld.PRESSURE_BOLT_ENTITY.get(), context -> new ThrownItemRenderer<>(context, .6f, true));
        event.registerBlockEntityRenderer(AeronauticsInnerworld.DRILL_CONTROLLER_ENTITY.get(), MachineShaftRenderer::new);
        event.registerBlockEntityRenderer(AeronauticsInnerworld.PROBE_DRIVE_ENTITY.get(), MachineShaftRenderer::new);
        event.registerBlockEntityRenderer(AeronauticsInnerworld.CABIN_REGULATOR_ENTITY.get(), MachineShaftRenderer::new);
        event.registerBlockEntityRenderer(AeronauticsInnerworld.ECHO_SOUNDER_ENTITY.get(), MachineShaftRenderer::new);
        event.registerBlockEntityRenderer(AeronauticsInnerworld.MANTLE_SAMPLER_ENTITY.get(), MachineShaftRenderer::new);
        event.registerBlockEntityRenderer(AeronauticsInnerworld.PRESSURE_CANNON_ENTITY.get(), MachineShaftRenderer::new);
    }

    @SubscribeEvent
    public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(LavaStalkerModel.LAYER, LavaStalkerModel::layer);
        event.registerLayerDefinition(CoreWardenModel.LAYER, CoreWardenModel::layer);
    }
}
