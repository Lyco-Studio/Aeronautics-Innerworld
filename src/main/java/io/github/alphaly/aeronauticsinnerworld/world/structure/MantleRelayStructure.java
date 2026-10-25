package io.github.alphaly.aeronauticsinnerworld.world.structure;

import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

public final class MantleRelayStructure extends Structure {
    public static final MapCodec<MantleRelayStructure> CODEC = simpleCodec(MantleRelayStructure::new);

    public MantleRelayStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        var position = new BlockPos(context.chunkPos().getMinBlockX(), 165 + context.random().nextInt(8),
                context.chunkPos().getMinBlockZ());
        var facing = Direction.Plane.HORIZONTAL.getRandomDirection(context.random());
        return Optional.of(new GenerationStub(position, builder -> builder.addPiece(new MantleRelayPiece(position, facing))));
    }

    @Override
    public StructureType<?> type() {
        return InnerworldStructures.MANTLE_RELAY.get();
    }
}
