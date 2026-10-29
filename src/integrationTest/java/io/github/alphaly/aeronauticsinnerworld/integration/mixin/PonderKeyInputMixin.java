package io.github.alphaly.aeronauticsinnerworld.integration.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.alphaly.aeronauticsinnerworld.integration.client.PonderClientCheck;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputConstants.class)
abstract class PonderKeyInputMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void innerworld$ponderKey(long window, int key, CallbackInfoReturnable<Boolean> result) {
        if (key == GLFW.GLFW_KEY_W && PonderClientCheck.holdingPonderKey()) result.setReturnValue(true);
    }
}
