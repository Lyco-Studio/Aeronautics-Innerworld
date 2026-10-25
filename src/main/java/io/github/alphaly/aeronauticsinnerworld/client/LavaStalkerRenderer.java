package io.github.alphaly.aeronauticsinnerworld.client;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.combat.LavaStalker;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;

public final class LavaStalkerRenderer extends MobRenderer<LavaStalker, LavaStalkerModel> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            AeronauticsInnerworld.MOD_ID, "textures/entity/lava_stalker.png");
    private static final ResourceLocation GRAZER_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            AeronauticsInnerworld.MOD_ID, "textures/entity/mantle_grazer.png");

    public LavaStalkerRenderer(EntityRendererProvider.Context context) {
        super(context, new LavaStalkerModel(context.bakeLayer(LavaStalkerModel.LAYER)), .5f);
    }

    @Override
    public ResourceLocation getTextureLocation(LavaStalker entity) {
        return entity.isNeutral() ? GRAZER_TEXTURE : TEXTURE;
    }

    @Override
    protected void scale(LavaStalker entity, PoseStack pose, float partialTick) {
        if (entity.isNeutral()) {
            pose.scale(1.25f, 1.25f, 1.25f);
        }
    }
}
