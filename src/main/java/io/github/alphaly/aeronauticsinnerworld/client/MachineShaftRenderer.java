package io.github.alphaly.aeronauticsinnerworld.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.OrientedRotatingVisual;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class MachineShaftRenderer<T extends KineticBlockEntity> extends KineticBlockEntityRenderer<T> {
    public MachineShaftRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    private static Direction input(KineticBlockEntity entity) {
        return entity.getBlockState().getValue(BlockStateProperties.FACING).getOpposite();
    }

    @Override
    protected SuperByteBuffer getRotatedModel(T entity, BlockState state) {
        return CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, input(entity));
    }

    @Override
    protected void renderSafe(T entity, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        // The opaque casing's center is dark; sample light at the recessed shaft opening.
        int inputLight = LevelRenderer.getLightColor(entity.getLevel(), entity.getBlockPos().relative(input(entity)));
        super.renderSafe(entity, partialTicks, pose, buffers, inputLight, overlay);
    }

    public static <T extends KineticBlockEntity> BlockEntityVisual<T> visual(VisualizationContext context, T entity, float partialTicks) {
        return new OrientedRotatingVisual<>(context, entity, partialTicks, Direction.SOUTH, input(entity),
                Models.partial(AllPartialModels.SHAFT_HALF)) {
            @Override
            public void updateLight(float partialTicks) {
                relight(entity.getBlockPos().relative(input(entity)), rotatingModel);
            }
        };
    }
}
