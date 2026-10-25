package io.github.alphaly.aeronauticsinnerworld.client;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreWarden;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public final class CoreWardenModel extends HierarchicalModel<CoreWarden> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "core_warden"), "main");
    private final ModelPart root;

    public CoreWardenModel(ModelPart root) {this.root = root;}

    public static LayerDefinition layer() {
        var mesh = new MeshDefinition();var root = mesh.getRoot().addOrReplaceChild("machine", CubeListBuilder.create(), PartPose.offset(0, 24, 0));
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-10, -10, -6, 20, 20, 12), PartPose.offset(0, -30, 0));
        root.addOrReplaceChild("core", CubeListBuilder.create().texOffs(0, 70).addBox(-4, -6, -7, 8, 8, 2), PartPose.offset(0, -30, 0));
        root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 40).addBox(-6, -10, -5, 12, 10, 10), PartPose.offset(0, -40, 0));
        for (int sign : new int[] {-1, 1}) {
            root.addOrReplaceChild(sign < 0 ? "left_arm" : "right_arm", CubeListBuilder.create().texOffs(64, 0)
                    .addBox(-4, 0, -5, 8, 28, 10), PartPose.offset(sign * 15, -38, 0));
            root.addOrReplaceChild(sign < 0 ? "left_leg" : "right_leg", CubeListBuilder.create().texOffs(64, 40)
                    .addBox(-4, 0, -4, 8, 18, 8), PartPose.offset(sign * 6, -18, 0));
        }
        return LayerDefinition.create(mesh, 128, 128);
    }

    @Override
    public ModelPart root() {return root;}

    @Override
    public void setupAnim(CoreWarden entity, float limbSwing, float limbSwingAmount, float age, float headYaw, float headPitch) {
        root.getAllParts().forEach(ModelPart::resetPose);
        var machine = root.getChild("machine");
        machine.getChild("head").yRot = headYaw * Mth.DEG_TO_RAD * .5f;
        float walk = Mth.sin(limbSwing * .6f) * .6f * limbSwingAmount;
        machine.getChild("left_leg").xRot = walk;machine.getChild("right_leg").xRot = -walk;
        machine.getChild("left_arm").xRot = entity.getAttackType() == 0 ? -walk : -2.2f;
        machine.getChild("right_arm").xRot = entity.getAttackType() == 0 ? walk : -2.2f;
        if (entity.isCoreExposed()) machine.getChild("core").z = -1 + Mth.sin(age * .4f) * .3f;
    }
}
