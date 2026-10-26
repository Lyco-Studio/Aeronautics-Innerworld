package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.logging.LogUtils;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.util.FakePlayer;

final class BucketReservoir {
    static void fill(ServerLevel level, FakePlayer builder, List<BlockPos> cells) {
        var floor = new LinkedHashMap<BlockPos, BlockState>();
        var position = builder.position();float yaw = builder.getYRot(), pitch = builder.getXRot();
        var hand = builder.getMainHandItem().copy();boolean creative = builder.getAbilities().instabuild;
        try {
            builder.getAbilities().instabuild = false;
            for (var pos : cells) {
                if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("Reservoir cell is occupied: " + pos);
                var below = pos.below();
                if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                    floor.put(below, level.getBlockState(below));level.setBlockAndUpdate(below, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                }
            }
            for (var pos : cells) {
                standOnRim(level, builder, pos);
                builder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAVA_BUCKET));
                var interaction = builder.gameMode.useItem(builder, level, builder.getMainHandItem(), InteractionHand.MAIN_HAND);
                var fluid = level.getFluidState(pos);
                if (!fluid.is(FluidTags.LAVA) || !fluid.isSource() || !builder.getMainHandItem().is(Items.BUCKET))
                    throw new IllegalStateException("Native bucket did not fill the reservoir or return its empty bucket at " + pos
                            + "; fluid=" + fluid + ", hand=" + builder.getMainHandItem() + ", result=" + interaction
                            + ", eye=" + builder.getEyePosition() + ", look=" + builder.getLookAngle()
                            + ", hit=" + level.clip(new ClipContext(builder.getEyePosition(),
                                    builder.getEyePosition().add(builder.getLookAngle().scale(builder.blockInteractionRange())),
                                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, builder)).getBlockPos());
            }
            LogUtils.getLogger().info("Reservoir supplied through native buckets: lava={}, returned buckets={}, temporary floor={}",
                    cells.size(), cells.size(), floor.size());
        } finally {
            floor.forEach(level::setBlockAndUpdate);
            builder.setPos(position);builder.setYRot(yaw);builder.setXRot(pitch);
            builder.setItemInHand(InteractionHand.MAIN_HAND, hand);builder.getAbilities().instabuild = creative;
        }
    }

    private static void standOnRim(ServerLevel level, FakePlayer builder, BlockPos cell) {
        var target = Vec3.atBottomCenterOf(cell).add(0, -.001, 0);
        for (var rim : BlockPos.betweenClosed(cell.offset(-4, 0, -4), cell.offset(4, 0, 4))) {
            if (!level.getBlockState(rim).isFaceSturdy(level, rim, Direction.UP)
                    || !level.getBlockState(rim.above()).isAir() || !level.getBlockState(rim.above(2)).isAir()) continue;
            builder.setPos(Vec3.atBottomCenterOf(rim.above()));
            var delta = target.subtract(builder.getEyePosition());
            if (delta.length() > builder.blockInteractionRange() || !level.noCollision(builder)) continue;
            var hit = level.clip(new ClipContext(builder.getEyePosition(), target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, builder));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(cell.below()) || hit.getDirection() != Direction.UP) continue;
            builder.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            builder.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
            var end = builder.getEyePosition().add(builder.calculateViewVector(builder.getXRot(), builder.getYRot())
                    .scale(builder.blockInteractionRange()));
            var aimed = level.clip(new ClipContext(builder.getEyePosition(), end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, builder));
            if (aimed.getType() == HitResult.Type.BLOCK && aimed.getBlockPos().equals(cell.below()) && aimed.getDirection() == Direction.UP) return;
        }
        throw new IllegalStateException("Reservoir has no dry supported rim within bucket reach: " + cell);
    }
}
