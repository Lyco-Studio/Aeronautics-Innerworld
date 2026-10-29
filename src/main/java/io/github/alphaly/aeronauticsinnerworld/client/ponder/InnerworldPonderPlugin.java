package io.github.alphaly.aeronauticsinnerworld.client.ponder;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

import static io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld.*;

public final class InnerworldPonderPlugin implements PonderPlugin {
    public static final ResourceLocation DRILLING = id("drilling");
    public static final ResourceLocation PROBES = id("mantle_probes");

    @Override
    public String getModId() {return AeronauticsInnerworld.MOD_ID;}

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        var scenes = helper.withKeyFunction((ItemLike item) -> BuiltInRegistries.ITEM.getKey(item.asItem()));
        scenes.forComponents(DRILL_CONTROLLER.get(), MULTI_AXIS_CUTTER.get(), DRILL_REMOTE.get())
                .addStoryBoard("drill_assembly", InnerworldPonderScenes::drillAssembly, DRILLING);
        scenes.forComponents(DRILL_CONTROLLER.get(), DRILL_REMOTE.get(), BORE_STATION.get(), DEEP_BORE_COUPLER.get())
                .addStoryBoard("layer_connection", InnerworldPonderScenes::layerConnection, DRILLING);
        scenes.forComponents(REFRACTORY_HULL.get(), MANTLE_VIEWPORT.get(), PRESSURE_HATCH.get(),
                        CABIN_REGULATOR.get(), DAMAGED_HULL.get(), EMERGENCY_PATCH.get(), REPAIR_KIT.get())
                .addStoryBoard("sealed_probe", InnerworldPonderScenes::sealedProbe, PROBES);
        scenes.forComponents(HEAT_ENGINE.get(), CABIN_REGULATOR.get(), COOLANT_CELL.get(), STABILIZED_COOLANT_CELL.get())
                .addStoryBoard("heat_exchange", InnerworldPonderScenes::heatExchange, PROBES);
        scenes.forComponents(MOLTEN_THRUSTER.get(), MANTLE_BALLAST.get())
                .addStoryBoard("propulsion_trim", InnerworldPonderScenes::propulsionTrim, PROBES);
        scenes.forComponents(ECHO_SOUNDER.get(), MANTLE_SAMPLER.get(), HARMONIC_ORE.get(), RAW_HARMONIC.get())
                .addStoryBoard("survey_sampling", InnerworldPonderScenes::surveySampling, PROBES);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(DRILLING).title("Drilling between layers")
                .description("Build a drill head, excavate a base and connect the three layers")
                .item(DRILL_CONTROLLER.get()).addToIndex().register();
        helper.registerTag(PROBES).title("Mantle probes")
                .description("Assemble, cool and operate a probe in the mantle lava sea")
                .item(ECHO_SOUNDER.get()).addToIndex().register();
        var tags = helper.withKeyFunction((ItemLike item) -> BuiltInRegistries.ITEM.getKey(item.asItem()));
        for (var item : new ItemLike[] {DRILL_CONTROLLER.get(), MULTI_AXIS_CUTTER.get(), DRILL_REMOTE.get(),
                BORE_STATION.get(), DEEP_BORE_COUPLER.get()}) tags.addTagToComponent(item, DRILLING);
        for (var item : new ItemLike[] {REFRACTORY_HULL.get(), MANTLE_VIEWPORT.get(), PRESSURE_HATCH.get(),
                CABIN_REGULATOR.get(), DAMAGED_HULL.get(), EMERGENCY_PATCH.get(), REPAIR_KIT.get(),
                HEAT_ENGINE.get(), COOLANT_CELL.get(), STABILIZED_COOLANT_CELL.get(), MOLTEN_THRUSTER.get(),
                MANTLE_BALLAST.get(), ECHO_SOUNDER.get(), MANTLE_SAMPLER.get(), HARMONIC_ORE.get(), RAW_HARMONIC.get()}) {
            tags.addTagToComponent(item, PROBES);
        }
    }

    private static ResourceLocation id(String path) {return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);}
}
