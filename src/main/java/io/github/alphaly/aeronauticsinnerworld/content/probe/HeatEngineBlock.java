package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

public final class HeatEngineBlock extends DirectionalKineticBlock implements IBE<HeatEngineBlockEntity> {
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public HeatEngineBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ACTIVE);
    }

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

}
