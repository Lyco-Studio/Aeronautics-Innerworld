package io.github.alphaly.aeronauticsinnerworld.world;

import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = AeronauticsInnerworld.MOD_ID)
public final class MantleExposure {
    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 20 != 0) {
            return;
        }
        var data = player.getPersistentData();
        if (!player.level().dimension().equals(Mantle.LEVEL) || player.isCreative() || player.isSpectator()) {
            data.remove("InnerworldHeatExposure");
            return;
        }
        var eye = player.getEyePosition();
        boolean exposed = player.level().getFluidState(BlockPos.containing(eye)).is(FluidTags.LAVA)
                && !WaterOcclusionContainer.getContainer(player.level()).isOccluded(eye);
        int seconds = Math.max(0, data.getInt("InnerworldHeatExposure") + (exposed ? 1 : -2));
        data.putInt("InnerworldHeatExposure", Math.min(10, seconds));
        if (seconds >= 3) {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.heat_exposure"), true);
        }
        if (seconds >= 5) {
            var type = ResourceKey.create(Registries.DAMAGE_TYPE,
                    ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "mantle_heat"));
            player.hurt(new DamageSource(player.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type)), 2);
        }
    }
}
