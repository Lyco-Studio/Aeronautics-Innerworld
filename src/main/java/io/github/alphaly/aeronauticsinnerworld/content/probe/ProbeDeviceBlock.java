package io.github.alphaly.aeronauticsinnerworld.content.probe;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.block.IBE;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.combat.PressureCannonBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class ProbeDeviceBlock extends DirectionalKineticBlock implements IBE<KineticBlockEntity> {
    public enum Kind { THRUSTER, BALLAST, CABIN, SOUNDER, SAMPLER, CANNON }

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
        return switch (kind) {
            case CABIN -> AeronauticsInnerworld.CABIN_REGULATOR_ENTITY.get();
            case SOUNDER -> AeronauticsInnerworld.ECHO_SOUNDER_ENTITY.get();
            case SAMPLER -> AeronauticsInnerworld.MANTLE_SAMPLER_ENTITY.get();
            case CANNON -> AeronauticsInnerworld.PRESSURE_CANNON_ENTITY.get();
            default -> AeronauticsInnerworld.PROBE_DRIVE_ENTITY.get();
        };
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof EchoSounderBlockEntity sounder) {
            sounder.setHome(pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!level.isClientSide && !replacement.is(this)) {
            var entity = level.getBlockEntity(pos);
            var inventory = entity instanceof MantleSamplerBlockEntity sampler ? sampler.inventory
                    : entity instanceof PressureCannonBlockEntity cannon ? cannon.ammunition : null;
            if (inventory != null) {
                for (int i = 0; i < inventory.getSlots(); i++) {
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), inventory.extractItem(i, 64, false));
                }
            }
        }
        super.onRemove(state, level, pos, replacement, moving);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if ((stack.is(Items.COMPASS) || stack.is(Items.RECOVERY_COMPASS))
                && level.getBlockEntity(pos) instanceof EchoSounderBlockEntity sounder) {
            if (!level.isClientSide) sounder.useCompass(player, stack);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.is(AeronauticsInnerworld.PRESSURE_BOLT.get()) && level.getBlockEntity(pos) instanceof PressureCannonBlockEntity cannon) {
            if (!level.isClientSide) {
                cannon.reload(player, stack);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            if (level.getBlockEntity(pos) instanceof ProbeDriveBlockEntity drive) {
                if (player.isShiftKeyDown()) drive.cycleTrimMode(player);
                else drive.showStatus(player);
            } else if (level.getBlockEntity(pos) instanceof CabinRegulatorBlockEntity cabin) {
                cabin.showStatus(player);
            } else if (level.getBlockEntity(pos) instanceof EchoSounderBlockEntity sounder) {
                sounder.interact(player);
            } else if (level.getBlockEntity(pos) instanceof MantleSamplerBlockEntity sampler) {
                sampler.interact(player);
            } else if (level.getBlockEntity(pos) instanceof PressureCannonBlockEntity cannon) {
                cannon.interact(player);
            }
        }
        return InteractionResult.SUCCESS;
    }
}
