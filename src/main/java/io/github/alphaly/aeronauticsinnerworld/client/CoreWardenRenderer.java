package io.github.alphaly.aeronauticsinnerworld.client;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreWarden;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

public final class CoreWardenRenderer extends MobRenderer<CoreWarden, CoreWardenModel> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID,
            "textures/entity/core_warden.png");

    public CoreWardenRenderer(EntityRendererProvider.Context context) {
        super(context, new CoreWardenModel(context.bakeLayer(CoreWardenModel.LAYER)), .9f);
    }

    @Override
    public ResourceLocation getTextureLocation(CoreWarden entity) {return TEXTURE;}

    @Override
    protected int getBlockLightLevel(CoreWarden entity, BlockPos pos) {
        return entity.isCoreExposed() ? 15 : super.getBlockLightLevel(entity, pos);
    }
}
