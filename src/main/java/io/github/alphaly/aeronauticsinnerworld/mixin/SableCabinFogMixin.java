package io.github.alphaly.aeronauticsinnerworld.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Sable 2.0.6 compatibility workaround for liquid fog inside occluded cabins. */
@Mixin(FogRenderer.class)
abstract class SableCabinFogMixin {
    @ModifyVariable(method = {"setupColor", "setupFog"}, at = @At("STORE"), ordinal = 0)
    private static FogType innerworld$useCabinFluid(FogType fluid, @Local(argsOnly = true) Camera camera) {
        // Sable bypasses occlusion for fog, leaving Flywheel parts covered in liquid fog inside dry cabins.
        if (fluid == FogType.WATER || fluid == FogType.LAVA) return camera.getFluidInCamera();
        return fluid;
    }
}
