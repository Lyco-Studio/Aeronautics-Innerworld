package io.github.alphaly.aeronauticsinnerworld.world.structure;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.loot.LootTable;

public final class DeepWorksPiece extends StructurePiece {
    private static final ResourceKey<LootTable> RECOVERY = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "chests/deep_works"));
    private static final ResourceKey<LootTable> SUPPLIES = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "chests/mantle_relay"));

    public DeepWorksPiece(BlockPos pos, Direction facing) {
        super(InnerworldStructures.DEEP_WORKS_PIECE.get(), 0,
                makeBoundingBox(pos.getX(), pos.getY(), pos.getZ(), facing, 25, 16, 29));
        setOrientation(facing);
    }

    public DeepWorksPiece(CompoundTag tag) {
        super(InnerworldStructures.DEEP_WORKS_PIECE.get(), tag);
    }

    public BlockPos getEntrance() {
        return getWorldPos(9, 1, 6).immutable();
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
            BoundingBox bounds, ChunkPos chunk, BlockPos pivot) {
        var shell = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        generateBox(level, bounds, 0, 3, 0, 24, 15, 28, shell, air, false);
        generateBox(level, bounds, 7, 3, 2, 17, 3, 10, Blocks.LAVA.defaultBlockState(), Blocks.LAVA.defaultBlockState(), false);
        for (int step = 0; step < 4; step++) {
            generateBox(level, bounds, 6 + step, 3 - step, 6, 6 + step, 3 - step, 7,
                    Blocks.POLISHED_BASALT.defaultBlockState(), air, false);
        }
        for (int x : new int[] {1, 23}) {
            generateBox(level, bounds, x, 7, 4, x, 10, 9,
                    AeronauticsInnerworld.MANTLE_VIEWPORT.get().defaultBlockState(), air, false);
        }
        for (int x : new int[] {3, 21}) {
            for (int z : new int[] {3, 12, 25}) {
                generateBox(level, bounds, x, 4, z, x, 13, z, Blocks.CUT_COPPER.defaultBlockState(), air, false);
                placeBlock(level, Blocks.SHROOMLIGHT.defaultBlockState(), x, 13, z, bounds);
            }
        }
        // Raised side galleries remain dry around the damaged processing floor.
        generateBox(level, bounds, 4, 3, 15, 20, 3, 23, Blocks.MAGMA_BLOCK.defaultBlockState(), air, false);
        generateBox(level, bounds, 4, 3, 18, 20, 3, 19, Blocks.LAVA.defaultBlockState(), Blocks.LAVA.defaultBlockState(), false);
        generateBox(level, bounds, 1, 4, 12, 23, 13, 12, shell, air, false);
        var hatch = AeronauticsInnerworld.PRESSURE_HATCH.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        for (int x : new int[] {2, 22}) {
            placeBlock(level, hatch, x, 4, 12, bounds);
            placeBlock(level, hatch, x, 5, 12, bounds);
        }
        for (int z = 14; z <= 23; z++) {
            placeBlock(level, Blocks.IRON_BARS.defaultBlockState(), 3, 4, z, bounds);
            placeBlock(level, Blocks.IRON_BARS.defaultBlockState(), 21, 4, z, bounds);
        }
        var spawnerPos = getWorldPos(12, 4, 22);
        if (bounds.isInside(spawnerPos)) {
            placeBlock(level, Blocks.SPAWNER.defaultBlockState(), 12, 4, 22, bounds);
            if (level.getBlockEntity(spawnerPos) instanceof SpawnerBlockEntity spawner) {
                spawner.setEntityId(EntityType.MAGMA_CUBE, random);
            }
        }
        placeBlock(level, AeronauticsInnerworld.RELAY_BEACON.get().defaultBlockState(), 12, 5, 26, bounds);
        createChest(level, bounds, random, 2, 4, 10, SUPPLIES);
        createChest(level, bounds, random, 12, 4, 26, RECOVERY);
    }
}
