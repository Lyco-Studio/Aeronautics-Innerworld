package io.github.alphaly.aeronauticsinnerworld.client;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.combat.LavaStalker;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public final class LavaStalkerRenderer extends MobRenderer<LavaStalker, LavaStalkerModel> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            AeronauticsInnerworld.MOD_ID, "textures/entity/lava_stalker.png");

    public LavaStalkerRenderer(EntityRendererProvider.Context context) {
        super(context, new LavaStalkerModel(context.bakeLayer(LavaStalkerModel.LAYER)), .5f);
    }

    @Override
    public ResourceLocation getTextureLocation(LavaStalker entity) {
        return TEXTURE;
    }
}
