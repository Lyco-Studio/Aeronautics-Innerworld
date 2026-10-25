package io.github.alphaly.aeronauticsinnerworld.content.drill;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.TranslatingContraption;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

public final class DrillContraption extends TranslatingContraption {
    private BlockPos assemblyController;

    public DrillContraption() {
    }

    public DrillContraption(BlockPos assemblyController) {
        this.assemblyController = assemblyController;
    }

    @Override
    protected boolean isAnchoringBlockAt(BlockPos pos) {
        return pos.equals(assemblyController);
    }

    @Override
    public boolean assemble(Level level, BlockPos pos) throws AssemblyException {
        if (!searchMovedStructure(level, pos, null) || getBlocks().isEmpty()) {
            return false;
        }
        if (getBlocks().values().stream().noneMatch(block ->
                block.state().is(AeronauticsInnerworld.MULTI_AXIS_CUTTER) || AllBlocks.MECHANICAL_DRILL.has(block.state()))) {
            throw new AssemblyException(Component.translatable("message.aeronautics_innerworld.missing_cutter"));
        }
        startMoving(level);
        return true;
    }

    @Override
    public ContraptionType getType() {
        return AeronauticsInnerworld.DRILL_CONTRAPTION.get();
    }
}
