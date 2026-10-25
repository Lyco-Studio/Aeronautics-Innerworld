package io.github.alphaly.aeronauticsinnerworld.content.core;

import com.mojang.serialization.MapCodec;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class CoreConsoleBlock extends Block implements EntityBlock {
    private static final MapCodec<CoreConsoleBlock> CODEC = simpleCodec(CoreConsoleBlock::new);

    public CoreConsoleBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CoreConsoleBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != AeronauticsInnerworld.CORE_CONSOLE_ENTITY.get() ? null
                : (world, pos, blockState, entity) -> ((CoreConsoleBlockEntity) entity).tick();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof CoreConsoleBlockEntity console) {
            console.interact(serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }
}
