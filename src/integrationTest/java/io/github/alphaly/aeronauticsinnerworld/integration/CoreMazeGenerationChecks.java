package io.github.alphaly.aeronauticsinnerworld.integration;

import io.github.alphaly.aeronauticsinnerworld.content.core.CoreConsoleBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import io.github.alphaly.aeronauticsinnerworld.world.structure.CoreMazePiece;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("aeronautics_innerworld_maze")
@PrefixGameTestTemplate(false)
public final class CoreMazeGenerationChecks {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void keepsControlsAndCachesReachableAcrossRotatedChunks(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Core.LEVEL);
        var generator = level.getChunkSource().getGenerator();
        int index = 0;
        for (var facing : Direction.Plane.HORIZONTAL) {
            var piece = new CoreMazePiece(new BlockPos(4096 + index * 128, 32, 4096), facing, 9137L + index++ * 71);
            var box = piece.getBoundingBox();
            // Generate separate chunks in reverse order to exercise every clipped detail and rotated support.
            for (int x = box.maxX() >> 4; x >= box.minX() >> 4; x--) for (int z = box.maxZ() >> 4; z >= box.minZ() >> 4; z--) {
                level.getChunk(x, z);
                var chunkBounds = new BoundingBox(x * 16, level.getMinBuildHeight(), z * 16,
                        x * 16 + 15, level.getMaxBuildHeight() - 1, z * 16 + 15);
                piece.postProcess(level, level.structureManager(), generator, RandomSource.create(37), chunkBounds,
                        new ChunkPos(x, z), piece.getEntrance());
            }
            CoreConsoleBlockEntity console = null;
            var caches = new HashSet<BlockPos>();
            boolean labelled = false;
            for (var pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
                var entity = level.getBlockEntity(pos);
                if (entity instanceof CoreConsoleBlockEntity control) console = control;
                if (entity instanceof ChestBlockEntity) caches.add(pos.immutable());
                if (entity instanceof SignBlockEntity) {
                    labelled = true;
                    var state = level.getBlockState(pos);
                    var back = pos.relative(state.getValue(WallSignBlock.FACING).getOpposite());
                    helper.assertTrue(level.getBlockState(back).isFaceSturdy(level, back, state.getValue(WallSignBlock.FACING)),
                            "A rotated facility sign has no backing: " + facing + " / " + pos);
                }
            }
            helper.assertTrue(console != null && labelled && !caches.isEmpty(), "Maze generation lost controls, labels or caches");
            var reached = reachable(level, box, piece.getEntrance());
            var data = console.saveWithFullMetadata(level.registryAccess());
            for (long packed : data.getLongArray("Nodes")) helper.assertTrue(reached.contains(BlockPos.of(packed)),
                    "A decorated node is unreachable: " + facing);
            helper.assertTrue(approachable(reached, console.getBlockPos()), "The maintenance console has no approach: " + facing);
            for (var cache : caches) helper.assertTrue(approachable(reached, cache), "A supply cache is blocked by decoration: " + facing);
            for (long packed : data.getLongArray("Gates")) level.removeBlock(BlockPos.of(packed), false);
            reached = reachable(level, box, piece.getEntrance());
            for (long packed : data.getLongArray("Valves")) helper.assertTrue(reached.contains(BlockPos.of(packed)),
                    "A decorated cooling valve is unreachable: " + facing);
            var exit = piece.getEntrance().relative(facing, 68)
                    .relative(facing.getAxis() == Direction.Axis.Z ? Direction.EAST : Direction.SOUTH, 32);
            helper.assertTrue(reached.contains(exit), "The service exit is unreachable: " + facing);
            var arena = BlockPos.of(data.getLong("Arena"));
            helper.assertTrue(reached.contains(arena) && level.getBlockState(arena.above(2)).isAir(), "The Warden spawn is obstructed");
        }
        helper.succeed();
    }

    private static Set<BlockPos> reachable(net.minecraft.server.level.ServerLevel level, BoundingBox bounds, BlockPos from) {
        var reached = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        reached.add(from);queue.add(from);
        while (!queue.isEmpty()) {
            var pos = queue.remove();
            for (var direction : Direction.Plane.HORIZONTAL) {
                var next = pos.relative(direction);
                if (!bounds.isInside(next) || reached.contains(next)
                        || !level.getBlockState(next).getCollisionShape(level, next).isEmpty()
                        || !level.getBlockState(next.above()).getCollisionShape(level, next.above()).isEmpty()
                        || !level.getBlockState(next.below()).isFaceSturdy(level, next.below(), Direction.UP)) continue;
                reached.add(next);queue.add(next);
            }
        }
        return reached;
    }

    private static boolean approachable(Set<BlockPos> reached, BlockPos pos) {
        return Direction.Plane.HORIZONTAL.stream().anyMatch(direction -> reached.contains(pos.relative(direction)));
    }
}
