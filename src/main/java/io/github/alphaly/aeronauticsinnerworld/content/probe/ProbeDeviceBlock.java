package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
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

public final class ProbeDeviceBlock extends DirectionalKineticBlock implements IBE<KineticBlockEntity> {
    public enum Kind { THRUSTER, BALLAST, CABIN }

    private final Kind kind;

    public ProbeDeviceBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction direction) {
        return direction == state.getValue(FACING).getOpposite();
    }

    @Override
    public Class<KineticBlockEntity> getBlockEntityClass() {
        return KineticBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends KineticBlockEntity> getBlockEntityType() {
        return kind == Kind.CABIN ? AeronauticsInnerworld.CABIN_REGULATOR_ENTITY.get()
                : AeronauticsInnerworld.PROBE_DRIVE_ENTITY.get();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            if (level.getBlockEntity(pos) instanceof ProbeDriveBlockEntity drive) {
                drive.showStatus(player);
            } else if (level.getBlockEntity(pos) instanceof CabinRegulatorBlockEntity cabin) {
                cabin.showStatus(player);
            }
        }
        return InteractionResult.SUCCESS;
    }
}
