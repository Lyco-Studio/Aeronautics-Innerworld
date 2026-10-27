package io.github.alphaly.aeronauticsinnerworld.world.structure;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreConsoleBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
        decoratePassages(level, bounds, open);
        decorateRooms(level, bounds);
        generateBox(level, bounds, 2, 1, 0, 4, 4, 3, air, air, false);
        for (int x : new int[] {1, 5}) {
            generateBox(level, bounds, x, 1, 0, x, 6, 0, Blocks.WAXED_CUT_COPPER.defaultBlockState(), air, false);
            placeBlock(level, Blocks.LANTERN.defaultBlockState(), x, 6, 0, bounds);
        }
        fill(level, bounds, 2, 6, 0, 4, 6, 0, AllBlocks.ANDESITE_CASING.getDefaultState());
        label(level, bounds, 2, 3, 3, Direction.EAST, "facility", "entry");
        var lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.NORTH);
        int[][] positions = {{43, 3}, {43, 43}, {3, 43}};
        long[] nodes = new long[3];
        for (int i = 0; i < 3; i++) {
            int x = positions[i][0], z = positions[i][1];
            nodes[i] = getWorldPos(x, 1, z).asLong();
            placeBlock(level, Blocks.REDSTONE_LAMP.defaultBlockState(), x, 0, z, bounds);
            placeBlock(level, lever, x, 1, z, bounds);
            placeBlock(level, lamp(), x, 5, z, bounds);
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
        decorateArena(level, bounds);
        generateBox(level, bounds, 22, 1, 43, 24, 4, 48, air, air, false);
        long[] gates = new long[9];int index = 0;
        for (int x = 22; x <= 24; x++) for (int y = 1; y <= 3; y++) {
            gates[index++] = getWorldPos(x, y, 47).asLong();
            placeBlock(level, AeronauticsInnerworld.CORE_SEAL.get().defaultBlockState(), x, y, 47, bounds);
        }
        for (int x : new int[] {14, 32}) {
            for (int z : new int[] {51, 69}) {
                generateBox(level, bounds, x, 1, z, x, 8, z, Blocks.POLISHED_BASALT.defaultBlockState(), air, false);
                placeBlock(level, lamp(), x, 8, z, bounds);
            }
        }
        for (int x : new int[] {17, 23, 29}) for (int z : new int[] {54, 60, 66}) {
            placeBlock(level, lamp(), x, 8, z, bounds);
            placeBlock(level, Blocks.CHAIN.defaultBlockState(), x, 9, z, bounds);
        }
        for (int[] light : new int[][] {{23, 56}, {23, 64}, {19, 60}, {27, 60}}) {
            placeBlock(level, lamp(), light[0], 0, light[1], bounds);
        }
        var valves = new long[] {getWorldPos(14, 1, 55).asLong(), getWorldPos(32, 1, 55).asLong()};
        placeBlock(level, lever, 14, 1, 55, bounds);placeBlock(level, lever, 32, 1, 55, bounds);
        placeBlock(level, Blocks.REDSTONE_LAMP.defaultBlockState(), 14, 0, 55, bounds);
        placeBlock(level, Blocks.REDSTONE_LAMP.defaultBlockState(), 32, 0, 55, bounds);
        generateBox(level, bounds, 35, 1, 67, 35, 3, 69, air, air, false);
        label(level, bounds, 34, 4, 68, Direction.WEST, "exit", "exit_hint");
        var consolePos = getWorldPos(21, 1, 45);
        placeBlock(level, AeronauticsInnerworld.CORE_CONSOLE.get().defaultBlockState(), 21, 1, 45, bounds);
        generateBox(level, bounds, 21, 1, 44, 21, 2, 44, air, air, false);
        if (bounds.isInside(consolePos) && level.getBlockEntity(consolePos) instanceof CoreConsoleBlockEntity console) {
            console.configure(nodes, gates, valves, getWorldPos(23, 1, 60));
        }
    }

    private void decoratePassages(WorldGenLevel level, BoundingBox bounds, boolean[][] open) {
        for (int x = 1; x < 46; x++) for (int z = 1; z < 46; z++) {
            int wear = (int) Math.floorMod(seed + x * 31L + z * 17L, 29);
            if (open[x][z]) {
                var floor = wear == 0 ? Blocks.CRACKED_DEEPSLATE_TILES : x % 4 == 3 || z % 4 == 3
                        ? Blocks.POLISHED_ANDESITE : Blocks.DEEPSLATE_BRICKS;
                placeBlock(level, floor.defaultBlockState(), x, 0, z, bounds);
                if (!CoreMazeLayout.isRoom(x, z) && x % 4 == 3 && z % 4 == 3 && (x / 4 + z / 4) % 2 == 0
                        && Math.abs(x - 19) + Math.abs(z - 19) > 12 && Math.abs(x - 31) + Math.abs(z - 31) > 12) {
                    ceilingLight(level, bounds, x, 5, z);
                }
                if (!CoreMazeLayout.isRoom(x, z) && x % 4 == 3 && z % 8 == 5) {
                    fill(level, bounds, x - 1, 5, z, x + 1, 5, z, Blocks.POLISHED_BASALT.defaultBlockState()
                            .setValue(BlockStateProperties.AXIS, Direction.Axis.X));
                }
            } else if (open[x - 1][z] || open[x + 1][z] || open[x][z - 1] || open[x][z + 1]) {
                placeBlock(level, Blocks.POLISHED_BASALT.defaultBlockState(), x, 1, z, bounds);
                var wall = wear < 3 ? Blocks.CRACKED_DEEPSLATE_BRICKS : Blocks.DEEPSLATE_BRICKS;
                fill(level, bounds, x, 2, z, x, 4, z, wall.defaultBlockState());
                placeBlock(level, Blocks.SMOOTH_BASALT.defaultBlockState(), x, 5, z, bounds);
                if (x % 8 == 1 && z % 8 == 1) {
                    fill(level, bounds, x, 2, z, x, 4, z, Blocks.WAXED_WEATHERED_CUT_COPPER.defaultBlockState());
                }
            }
        }
        for (int x : new int[] {2, 44}) {
            for (int z = 2; z <= 44; z++) placeBlock(level, pipe(Direction.Axis.Z), x, 4, z, bounds);
        }
    }

    private void decorateRooms(WorldGenLevel level, BoundingBox bounds) {
        var casing = AllBlocks.ANDESITE_CASING.getDefaultState();
        var copper = Blocks.WAXED_CUT_COPPER.defaultBlockState();
        fill(level, bounds, 7, 1, 3, 7, 2, 3, casing);
        placeBlock(level, Blocks.BARREL.defaultBlockState(), 7, 1, 5, bounds);
        ceilingLight(level, bounds, 5, 5, 5);

        placeBlock(level, Blocks.CRAFTING_TABLE.defaultBlockState(), 9, 1, 9, bounds);
        placeBlock(level, Blocks.SMITHING_TABLE.defaultBlockState(), 10, 1, 9, bounds);
        placeBlock(level, Blocks.GRINDSTONE.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR), 9, 2, 9, bounds);
        fill(level, bounds, 13, 1, 9, 13, 2, 9, casing);
        for (int x = 9; x <= 13; x++) placeBlock(level, pipe(Direction.Axis.X), x, 4, 9, bounds);
        for (int x : new int[] {9, 13}) placeBlock(level, Blocks.CHAIN.defaultBlockState(), x, 5, 9, bounds);
        ceilingLight(level, bounds, 11, 5, 11);
        // The plaques sit above head height; their backing does not occupy the central approach.
        fill(level, bounds, 9, 1, 8, 9, 3, 8, casing);
        label(level, bounds, 9, 3, 9, Direction.SOUTH, "workshop", "facility");

        for (int x : new int[] {33, 37}) {
            fill(level, bounds, x, 1, 9, x, 3, 10, Blocks.BOOKSHELF.defaultBlockState());
            placeBlock(level, casing, x, 4, 9, bounds);
        }
        placeBlock(level, Blocks.CARTOGRAPHY_TABLE.defaultBlockState(), 37, 1, 13, bounds);
        ceilingLight(level, bounds, 35, 5, 11);
        fill(level, bounds, 33, 1, 8, 33, 3, 8, casing);
        label(level, bounds, 33, 3, 9, Direction.SOUTH, "archive", "facility");

        var stone = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
        generateBox(level, bounds, 19, 6, 19, 27, 9, 27, stone, stone, false);
        fill(level, bounds, 20, 6, 20, 26, 8, 26, Blocks.AIR.defaultBlockState());
        for (int x : new int[] {20, 26}) for (int z : new int[] {20, 26}) {
            fill(level, bounds, x, 1, z, x, 2, z, Blocks.POLISHED_BASALT.defaultBlockState());
            fill(level, bounds, x, 3, z, x, 7, z, casing);
        }
        for (int x : new int[] {21, 25}) for (int z : new int[] {21, 25}) ceilingLight(level, bounds, x, 8, z);
        placeBlock(level, Blocks.CHISELED_DEEPSLATE.defaultBlockState(), 23, 0, 23, bounds);
        for (int x = 21; x <= 25; x++) placeBlock(level, copper, x, 0, 22, bounds);

        fill(level, bounds, 39, 1, 3, 40, 2, 3, casing);
        for (int x = 39; x <= 43; x++) placeBlock(level, pipe(Direction.Axis.X), x, 4, 7, bounds);
        for (int x : new int[] {39, 43}) placeBlock(level, Blocks.CHAIN.defaultBlockState(), x, 5, 7, bounds);
        label(level, bounds, 43, 3, 2, Direction.SOUTH, "node_1", "node_hint");
        ceilingLight(level, bounds, 41, 5, 5);

        for (int x : new int[] {39, 41}) {
            fill(level, bounds, x, 1, 39, x, 2, 39, AllBlocks.FLUID_TANK.getDefaultState());
            placeBlock(level, pipe(Direction.Axis.Y), x, 3, 39, bounds);
        }
        placeBlock(level, pipe(Direction.Axis.X).setValue(BlockStateProperties.WEST, false).setValue(BlockStateProperties.DOWN, true), 39, 4, 39, bounds);
        placeBlock(level, pipe(Direction.Axis.X), 40, 4, 39, bounds);
        placeBlock(level, pipe(Direction.Axis.X).setValue(BlockStateProperties.EAST, false).setValue(BlockStateProperties.DOWN, true), 41, 4, 39, bounds);
        placeBlock(level, Blocks.CHAIN.defaultBlockState(), 40, 5, 39, bounds);
        label(level, bounds, 44, 3, 43, Direction.WEST, "node_2", "node_hint");
        ceilingLight(level, bounds, 41, 5, 41);

        fill(level, bounds, 3, 1, 40, 5, 2, 40, Blocks.BOOKSHELF.defaultBlockState());
        placeBlock(level, Blocks.LECTERN.defaultBlockState(), 7, 1, 42, bounds);
        label(level, bounds, 2, 3, 43, Direction.EAST, "node_3", "node_hint");
        ceilingLight(level, bounds, 5, 5, 41);
        for (int[] node : new int[][] {{43, 3}, {43, 43}, {3, 43}}) {
            fill(level, bounds, node[0] - 1, 0, node[1] - 1, node[0] + 1, 0, node[1] + 1, copper);
        }
    }

    private void decorateArena(WorldGenLevel level, BoundingBox bounds) {
        for (int x = 12; x < 35; x++) for (int z = 47; z < 72; z++) {
            int distance = Math.max(Math.abs(x - 23), Math.abs(z - 60));
            var floor = distance == 7 ? Blocks.POLISHED_BLACKSTONE_BRICKS
                    : x == 23 || z == 60 ? Blocks.POLISHED_ANDESITE : Blocks.DEEPSLATE_BRICKS;
            placeBlock(level, floor.defaultBlockState(), x, 0, z, bounds);
        }
        for (int x : new int[] {11, 35}) {
            fill(level, bounds, x, 1, 48, x, 2, 71, Blocks.POLISHED_BASALT.defaultBlockState());
            for (int z : new int[] {50, 58, 64, 70}) {
                fill(level, bounds, x, 3, z, x, 8, z, Blocks.WAXED_WEATHERED_CUT_COPPER.defaultBlockState());
            }
        }
        for (int x : new int[] {12, 34}) {
            for (int z = 51; z <= 69; z++) placeBlock(level, pipe(Direction.Axis.Z), x, 5, z, bounds);
        }
        for (int x : new int[] {11, 35}) {
            fill(level, bounds, x, 3, 55, x, 4, 55, AllBlocks.ANDESITE_CASING.getDefaultState());
        }
        placeBlock(level, pipe(Direction.Axis.Z).setValue(BlockStateProperties.DOWN, true), 12, 5, 55, bounds);
        placeBlock(level, pipe(Direction.Axis.Y).setValue(BlockStateProperties.DOWN, false).setValue(BlockStateProperties.WEST, true), 12, 4, 55, bounds);
        placeBlock(level, pipe(Direction.Axis.Z).setValue(BlockStateProperties.DOWN, true), 34, 5, 55, bounds);
        placeBlock(level, pipe(Direction.Axis.Y).setValue(BlockStateProperties.DOWN, false).setValue(BlockStateProperties.EAST, true), 34, 4, 55, bounds);
        label(level, bounds, 12, 3, 55, Direction.EAST, "coolant_valve", "valve_hint");
        label(level, bounds, 34, 3, 55, Direction.WEST, "coolant_valve", "valve_hint");
        fill(level, bounds, 19, 1, 71, 27, 8, 71, Blocks.CHISELED_DEEPSLATE.defaultBlockState());
        for (int x : new int[] {19, 27}) {
            fill(level, bounds, x, 2, 71, x, 7, 71, Blocks.WAXED_CUT_COPPER.defaultBlockState());
        }
        fill(level, bounds, 22, 4, 72, 24, 6, 72, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        fill(level, bounds, 22, 4, 71, 24, 6, 71, AeronauticsInnerworld.MANTLE_VIEWPORT.get().defaultBlockState());
        fill(level, bounds, 21, 1, 43, 21, 3, 43, AllBlocks.ANDESITE_CASING.getDefaultState());
        label(level, bounds, 21, 3, 44, Direction.SOUTH, "control", "control_hint");
    }

    private void ceilingLight(WorldGenLevel level, BoundingBox bounds, int x, int y, int z) {
        placeBlock(level, lamp(), x, y, z, bounds);
    }

    private BlockState lamp() {
        return Blocks.WAXED_COPPER_BULB.defaultBlockState().setValue(BlockStateProperties.LIT, true);
    }

    private BlockState pipe(Direction.Axis axis) {
        return ((FluidPipeBlock) AllBlocks.FLUID_PIPE.get()).getAxisState(axis);
    }

    private void fill(WorldGenLevel level, BoundingBox bounds, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        generateBox(level, bounds, x0, y0, z0, x1, y1, z1, state, state, false);
    }

    private void label(WorldGenLevel level, BoundingBox bounds, int x, int y, int z, Direction facing, String title, String subtitle) {
        // StructurePiece's unrotated template maps increasing local Z toward north.
        var templateFacing = facing.getAxis() == Direction.Axis.Z ? facing.getOpposite() : facing;
        placeBlock(level, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, templateFacing), x, y, z, bounds);
        var pos = getWorldPos(x, y, z);
        if (bounds.isInside(pos) && level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            Component[] lines = {Component.empty(), Component.translatable("maze.aeronautics_innerworld." + title),
                    Component.translatable("maze.aeronautics_innerworld." + subtitle), Component.empty()};
            // World-generation sign entities have no Level for live text updates.
            var data = sign.saveWithoutMetadata(level.registryAccess());
            data.put("front_text", SignText.DIRECT_CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                    new SignText(lines, lines, DyeColor.LIGHT_GRAY, false)).getOrThrow());
            data.putBoolean("is_waxed", true);
            sign.loadWithComponents(data, level.registryAccess());
            sign.setChanged();
        }
    }
}
