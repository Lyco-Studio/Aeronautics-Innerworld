package io.github.alphaly.aeronauticsinnerworld.content.drill;

import com.simibubi.create.AllTags;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class MultiAxisCutterMovementBehaviour extends BlockBreakingMovementBehaviour {
    @Override
    public boolean isActive(MovementContext context) {
        return context.stall || context.relativeMotion.lengthSqr() > 1.0e-8;
    }

    @Override
    public Vec3 getActiveAreaOffset(MovementContext context) {
        var motion = context.relativeMotion;
        if (motion.lengthSqr() < 1.0e-8) {
            return Vec3.ZERO;
        }
        var direction = Direction.getNearest(motion.x, motion.y, motion.z);
        return Vec3.atLowerCornerOf(direction.getNormal()).scale(0.65);
    }

    @Override
    public boolean canBreak(Level level, BlockPos pos, BlockState state) {
        return super.canBreak(level, pos, state)
                && !state.getCollisionShape(level, pos).isEmpty()
                && !AllTags.AllBlockTags.TRACKS.matches(state);
    }
}
