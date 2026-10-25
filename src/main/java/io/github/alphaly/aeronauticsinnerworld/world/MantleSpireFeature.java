package io.github.alphaly.aeronauticsinnerworld.world;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

public final class MantleSpireFeature extends Feature<NoneFeatureConfiguration> {
    public MantleSpireFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        var level = context.level();
        if (!level.getLevel().dimension().equals(Mantle.LEVEL)) return false;
        var origin = context.origin();var random = context.random();
        // Six blocks of margin keep decoration writes inside their generating chunk.
        int centerX = (origin.getX() & -16) + 6 + random.nextInt(4);
        int centerZ = (origin.getZ() & -16) + 6 + random.nextInt(4);
        int height = 96 + random.nextInt(116);
        int width = 3 + random.nextInt(2);
        int bendX = 0, bendZ = 0, placed = 0;
        var point = new BlockPos.MutableBlockPos();
        for (int y = 1; y <= Math.min(height, 199); y++) {
            if (y % 16 == 0) {
                bendX = Math.clamp(bendX + random.nextInt(3) - 1, -2, 2);
                bendZ = Math.clamp(bendZ + random.nextInt(3) - 1, -2, 2);
            }
            int radius = Math.max(1, width - y * (width - 1) / height);
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                point.set(centerX + bendX + dx, y, centerZ + bendZ + dz);
                if (!level.getFluidState(point).is(FluidTags.LAVA) || !level.getBlockState(point).canBeReplaced()) continue;
                var state = dx == 0 && dz == 0 && y % 64 == 0
                        ? AeronauticsInnerworld.HARMONIC_ORE.get().defaultBlockState() : Blocks.BASALT.defaultBlockState();
                level.setBlock(point, state, 2);placed++;
            }
        }
        return placed > 0;
    }
}
