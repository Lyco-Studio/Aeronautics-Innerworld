package io.github.alphaly.aeronauticsinnerworld.world.structure;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.loot.LootTable;

public final class MantleRelayPiece extends StructurePiece {
    private static final ResourceKey<LootTable> SUPPLIES = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "chests/mantle_relay"));
    private static final ResourceKey<LootTable> ARCHIVE = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "chests/relay_archive"));

    public MantleRelayPiece(BlockPos position, Direction facing) {
        super(InnerworldStructures.RELAY_PIECE.get(), 0,
                makeBoundingBox(position.getX(), position.getY(), position.getZ(), facing, 19, 14, 23));
        setOrientation(facing);
    }

    public MantleRelayPiece(CompoundTag tag) {
        super(InnerworldStructures.RELAY_PIECE.get(), tag);
    }

    public BlockPos getEntrance() {
        return getWorldPos(8, 1, 8).immutable();
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
            BoundingBox bounds, ChunkPos chunk, BlockPos pivot) {
        var shell = Blocks.DEEPSLATE_TILES.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        generateBox(level, bounds, 0, 3, 0, 18, 13, 22, shell, air, false);
        for (int x : new int[] {0, 18}) {
            for (int z : new int[] {0, 22}) {
                generateBox(level, bounds, x, 0, z, x, 12, z, Blocks.POLISHED_BASALT.defaultBlockState(), air, false);
            }
            generateBox(level, bounds, x, 6, 6, x, 9, 12,
                    AeronauticsInnerworld.MANTLE_VIEWPORT.get().defaultBlockState(), air, false);
        }
        // A bottom entrance keeps the raised dry gallery clear without a fluid-removal system.
        generateBox(level, bounds, 6, 3, 4, 12, 3, 12, Blocks.LAVA.defaultBlockState(), Blocks.LAVA.defaultBlockState(), false);
        for (int step = 0; step < 4; step++) {
            placeBlock(level, Blocks.POLISHED_BASALT.defaultBlockState(), 5 + step, 3 - step, 8, bounds);
            placeBlock(level, Blocks.POLISHED_BASALT.defaultBlockState(), 5 + step, 3 - step, 9, bounds);
        }
        for (int x : new int[] {5, 13}) {
            for (int z = 4; z <= 12; z++) {
                if (z != 8 && z != 9) {
                    placeBlock(level, Blocks.IRON_BARS.defaultBlockState(), x, 4, z, bounds);
                }
            }
        }
        for (int x : new int[] {2, 16}) {
            for (int z : new int[] {2, 14, 20}) {
                generateBox(level, bounds, x, 4, z, x, 12, z, Blocks.CUT_COPPER.defaultBlockState(), air, false);
            }
        }
        generateBox(level, bounds, 1, 4, 16, 17, 12, 16, shell, air, false);
        BlockState hatch = AeronauticsInnerworld.PRESSURE_HATCH.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        placeBlock(level, hatch, 9, 4, 16, bounds);
        placeBlock(level, hatch, 9, 5, 16, bounds);
        for (int x : new int[] {3, 15}) {
            for (int z : new int[] {3, 13, 20}) {
                placeBlock(level, Blocks.GLOWSTONE.defaultBlockState(), x, 11, z, bounds);
            }
        }
        placeBlock(level, Blocks.CHISELED_DEEPSLATE.defaultBlockState(), 9, 4, 20, bounds);
        placeBlock(level, AeronauticsInnerworld.RELAY_BEACON.get().defaultBlockState(), 9, 5, 20, bounds);
        createChest(level, bounds, random, 3, 4, 19, SUPPLIES);
        createChest(level, bounds, random, 15, 4, 19, ARCHIVE);
    }
}
