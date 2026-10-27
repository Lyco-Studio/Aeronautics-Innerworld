package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class HeatEngineBlock extends DirectionalKineticBlock implements IBE<HeatEngineBlockEntity> {
    public HeatEngineBlock(Properties properties) {super(properties);}

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {return state.getValue(FACING).getAxis();}

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction direction) {
        return direction == state.getValue(FACING).getOpposite();
    }

    @Override
    public Class<HeatEngineBlockEntity> getBlockEntityClass() {return HeatEngineBlockEntity.class;}

    @Override
    public BlockEntityType<? extends HeatEngineBlockEntity> getBlockEntityType() {
        return AeronauticsInnerworld.HEAT_ENGINE_ENTITY.get();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof HeatEngineBlockEntity engine) engine.showStatus(player);
        return InteractionResult.SUCCESS;
    }
}
