package io.github.alphaly.aeronauticsinnerworld.client;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.combat.LavaStalker;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public final class LavaStalkerModel extends HierarchicalModel<LavaStalker> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "lava_stalker"), "main");
    private final ModelPart root;
    private final ModelPart tail;
    private final ModelPart leftFin;
    private final ModelPart rightFin;

    public LavaStalkerModel(ModelPart root) {
        this.root = root;
        tail = root.getChild("tail");
        leftFin = root.getChild("left_fin");
        rightFin = root.getChild("right_fin");
    }

    public static LayerDefinition layer() {
        var mesh = new MeshDefinition();
        var root = mesh.getRoot();
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-7, -3, -8, 14, 6, 16), PartPose.offset(0, 18, 0));
        var head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 24).addBox(-5, -2, -6, 10, 5, 6), PartPose.offset(0, 18, -8));
        head.addOrReplaceChild("eyes", CubeListBuilder.create().texOffs(48, 32).addBox(-5.5f, -1, -5, 1, 2, 2)
                .texOffs(48, 32).addBox(4.5f, -1, -5, 1, 2, 2), PartPose.ZERO);
        root.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(0, 40).addBox(-4, -1, 0, 8, 2, 8), PartPose.offset(0, 18, 8));
        root.addOrReplaceChild("left_fin", CubeListBuilder.create().texOffs(32, 40).addBox(-5, -.5f, -3, 5, 1, 8), PartPose.offset(-7, 18, 0));
        root.addOrReplaceChild("right_fin", CubeListBuilder.create().texOffs(32, 40).mirror().addBox(0, -.5f, -3, 5, 1, 8), PartPose.offset(7, 18, 0));
        root.addOrReplaceChild("crest", CubeListBuilder.create().texOffs(40, 50).addBox(-1, -2, -4, 2, 3, 8), PartPose.offset(0, 14, 0));
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(LavaStalker entity, float limbSwing, float limbSwingAmount, float age, float headYaw, float headPitch) {
        tail.yRot = Mth.sin(age * .35f) * .35f;
        leftFin.zRot = -.15f + Mth.sin(age * .25f) * .18f;
        rightFin.zRot = -leftFin.zRot;
        root.getChild("body").zRot = entity.isWindingUp() ? Mth.sin(age * 1.5f) * .12f : 0;
        root.getChild("head").xRot = headPitch * Mth.DEG_TO_RAD * .3f;
    }
}
