package io.github.alphaly.aeronauticsinnerworld.world.structure;

import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

public final class CoreMazeStructure extends Structure {
    public static final MapCodec<CoreMazeStructure> CODEC = simpleCodec(CoreMazeStructure::new);

    public CoreMazeStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        var pos = new BlockPos(context.chunkPos().getMinBlockX(), 32, context.chunkPos().getMinBlockZ());
        var facing = Direction.Plane.HORIZONTAL.getRandomDirection(context.random());
        long seed = context.random().nextLong();
        return Optional.of(new GenerationStub(pos, builder -> builder.addPiece(new CoreMazePiece(pos, facing, seed))));
    }

    @Override
    public StructureType<?> type() {
        return InnerworldStructures.CORE_MAZE.get();
    }
}
