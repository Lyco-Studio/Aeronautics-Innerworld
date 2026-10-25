package io.github.alphaly.aeronauticsinnerworld.world.structure;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreConsoleBlockEntity;
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
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.loot.LootTable;

public final class CoreMazePiece extends StructurePiece {
    private static final ResourceKey<LootTable> CACHE = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "chests/core_cache"));
    private final long seed;

    public CoreMazePiece(BlockPos pos, Direction facing, long seed) {
        super(InnerworldStructures.CORE_MAZE_PIECE.get(), 0,
                makeBoundingBox(pos.getX(), pos.getY(), pos.getZ(), facing, 47, 11, 73));
        setOrientation(facing);this.seed = seed;
    }

    public CoreMazePiece(CompoundTag tag) {
        super(InnerworldStructures.CORE_MAZE_PIECE.get(), tag);seed = tag.getLong("LayoutSeed");
    }

    public BlockPos getEntrance() {
        return getWorldPos(3, 1, 0).immutable();
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putLong("LayoutSeed", seed);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
            BoundingBox bounds, ChunkPos chunk, BlockPos pivot) {
        var stone = Blocks.DEEPSLATE_TILES.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        generateBox(level, bounds, 0, 0, 0, 46, 7, 46, stone, stone, false);
        var open = CoreMazeLayout.passages(seed);
        for (int x = 0; x < open.length; x++) for (int z = 0; z < open[x].length; z++) {
            if (open[x][z]) generateBox(level, bounds, x, 1, z, x, 5, z, air, air, false);
        }
        generateBox(level, bounds, 2, 1, 0, 4, 4, 3, air, air, false);
        for (int x : new int[] {1, 5}) {
            generateBox(level, bounds, x, 1, 0, x, 6, 0, Blocks.WAXED_CUT_COPPER.defaultBlockState(), air, false);
            placeBlock(level, Blocks.SHROOMLIGHT.defaultBlockState(), x, 6, 0, bounds);
        }
        var lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.NORTH);
        int[][] positions = {{43, 3}, {43, 43}, {3, 43}};
        long[] nodes = new long[3];
        for (int i = 0; i < 3; i++) {
            int x = positions[i][0], z = positions[i][1];
            nodes[i] = getWorldPos(x, 1, z).asLong();
            placeBlock(level, Blocks.WAXED_CUT_COPPER.defaultBlockState(), x, 0, z, bounds);
            placeBlock(level, lever, x, 1, z, bounds);
            placeBlock(level, Blocks.SHROOMLIGHT.defaultBlockState(), x, 5, z, bounds);
            createChest(level, bounds, random, x + 1, 1, z, CACHE);
        }
        for (int[] point : new int[][] {{19, 19}, {31, 31}}) {
            var pos = getWorldPos(point[0], 1, point[1]);
            if (bounds.isInside(pos)) {
                placeBlock(level, Blocks.SPAWNER.defaultBlockState(), point[0], 1, point[1], bounds);
                if (level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) spawner.setEntityId(EntityType.SKELETON, random);
            }
        }
        generateBox(level, bounds, 11, 0, 46, 35, 10, 72, stone, air, false);
        generateBox(level, bounds, 22, 1, 43, 24, 4, 48, air, air, false);
        long[] gates = new long[9];int index = 0;
        for (int x = 22; x <= 24; x++) for (int y = 1; y <= 3; y++) {
            gates[index++] = getWorldPos(x, y, 47).asLong();
            placeBlock(level, AeronauticsInnerworld.CORE_SEAL.get().defaultBlockState(), x, y, 47, bounds);
        }
        for (int x : new int[] {14, 32}) {
            for (int z : new int[] {51, 69}) {
                generateBox(level, bounds, x, 1, z, x, 8, z, Blocks.POLISHED_BASALT.defaultBlockState(), air, false);
                placeBlock(level, Blocks.SHROOMLIGHT.defaultBlockState(), x, 8, z, bounds);
            }
        }
        for (int x : new int[] {17, 23, 29}) for (int z : new int[] {54, 60, 66}) {
            placeBlock(level, Blocks.SHROOMLIGHT.defaultBlockState(), x, 8, z, bounds);
            placeBlock(level, Blocks.IRON_BARS.defaultBlockState(), x, 9, z, bounds);
        }
        for (int[] light : new int[][] {{23, 56}, {23, 64}, {19, 60}, {27, 60}}) {
            placeBlock(level, Blocks.SHROOMLIGHT.defaultBlockState(), light[0], 0, light[1], bounds);
        }
        var valves = new long[] {getWorldPos(14, 1, 55).asLong(), getWorldPos(32, 1, 55).asLong()};
        placeBlock(level, lever, 14, 1, 55, bounds);placeBlock(level, lever, 32, 1, 55, bounds);
        generateBox(level, bounds, 35, 1, 67, 35, 3, 69, air, air, false);
        placeBlock(level, Blocks.SHROOMLIGHT.defaultBlockState(), 34, 4, 68, bounds);
        var consolePos = getWorldPos(21, 1, 45);
        placeBlock(level, AeronauticsInnerworld.CORE_CONSOLE.get().defaultBlockState(), 21, 1, 45, bounds);
        generateBox(level, bounds, 21, 1, 44, 21, 2, 44, air, air, false);
        if (bounds.isInside(consolePos) && level.getBlockEntity(consolePos) instanceof CoreConsoleBlockEntity console) {
            console.configure(nodes, gates, valves, getWorldPos(23, 1, 60));
        }
    }
}
