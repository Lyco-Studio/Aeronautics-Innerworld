package io.github.alphaly.aeronauticsinnerworld.content.drill;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class DrillControllerBlock extends DirectionalKineticBlock implements IBE<DrillControllerBlockEntity> {
    public DrillControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING).getOpposite();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && getBlockEntity(level, pos) instanceof DrillControllerBlockEntity controller) {
            controller.toggleWork();
            showStatus(player, controller);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        if (!context.getLevel().isClientSide) {
            var controller = getBlockEntity(context.getLevel(), context.getClickedPos());
            if (controller != null) {
                if (controller.cycleDirection()) {
                    showStatus(context.getPlayer(), controller);
                } else if (context.getPlayer() != null) {
                    context.getPlayer().displayClientMessage(Component.translatable(
                            "message.aeronautics_innerworld.pause_before_turning"), true);
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        var controller = getBlockEntity(context.getLevel(), context.getClickedPos());
        if (controller == null || !controller.running) {
            return super.onSneakWrenched(state, context);
        }
        if (!context.getLevel().isClientSide) {
            controller.tryPlaceHead();
            showStatus(context.getPlayer(), controller);
        }
        return InteractionResult.SUCCESS;
    }

    private static void showStatus(Player player, DrillControllerBlockEntity controller) {
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.aeronautics_innerworld.controller_status",
                    Component.translatable("direction.aeronautics_innerworld." + controller.getMovementDirection().getName()),
                    Component.translatable("status.aeronautics_innerworld." + controller.getStatus())), true);
        }
    }

    @Override
    public Class<DrillControllerBlockEntity> getBlockEntityClass() {
        return DrillControllerBlockEntity.class;
    }

    @Override
    public BlockEntityType<DrillControllerBlockEntity> getBlockEntityType() {
        return AeronauticsInnerworld.DRILL_CONTROLLER_ENTITY.get();
    }
}
