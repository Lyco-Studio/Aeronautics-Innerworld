package io.github.alphaly.aeronauticsinnerworld.world.structure;

import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

public final class DeepWorksStructure extends Structure {
    public static final MapCodec<DeepWorksStructure> CODEC = simpleCodec(DeepWorksStructure::new);

    public DeepWorksStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        var pos = new BlockPos(context.chunkPos().getMinBlockX(), 28 + context.random().nextInt(12),
                context.chunkPos().getMinBlockZ());
        var facing = Direction.Plane.HORIZONTAL.getRandomDirection(context.random());
        return Optional.of(new GenerationStub(pos, builder -> builder.addPiece(new DeepWorksPiece(pos, facing))));
    }

    @Override
    public StructureType<?> type() {
        return InnerworldStructures.DEEP_WORKS.get();
    }
}
