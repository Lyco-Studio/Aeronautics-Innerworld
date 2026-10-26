package io.github.alphaly.aeronauticsinnerworld.mixin;

import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SuperGlueEntity.class, remap = false)
abstract class SuperGlueEntityMixin {
    @Inject(method = "isValidFace", at = @At("HEAD"), cancellable = true)
    private static void innerworld$allowCutterGlue(Level level, BlockPos pos, Direction face,
            CallbackInfoReturnable<Boolean> result) {
        // Cutting faces stop piston collection, but still accept explicit glue selections.
        if (level.getBlockState(pos).is(AeronauticsInnerworld.MULTI_AXIS_CUTTER.get())) result.setReturnValue(true);
    }
}
