package io.github.alphaly.aeronauticsinnerworld.integration.client;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

final class RelayVoyagePlan {
    private final ServerLevel level;
    private final BlockPos intake;
    private final int low, high;
    private final Map<Long, Long> columns = new HashMap<>();
    private final Map<BlockPos, Boolean> volumes = new HashMap<>();
    final List<BlockPos> route;
    BlockPos obstruction;

    RelayVoyagePlan(ServerLevel level, BlockPos intake) {
        this.level = level;this.intake = intake;low = intake.getY() - 30;high = intake.getY() + 14;
        for (int x = (intake.getX() - 48) >> 4; x <= (intake.getX() + 48) >> 4; x++)
            for (int z = (intake.getZ() - 48) >> 4; z <= (intake.getZ() + 48) >> 4; z++) level.getChunk(x, z);
        var berths = new ArrayList<BlockPos>();
        for (int dy : new int[] {-6, -8, -10}) for (int dx = -12; dx <= 12; dx += 2) for (int dz = -12; dz <= 12; dz += 2)
            if (dx * dx + dz * dz >= 16 && dx * dx + dz * dz <= 144) {
                var point = intake.offset(dx, dy, dz);
                if (clear(point)) berths.add(point);
            }
        berths.sort(Comparator.comparingDouble(intake::distSqr));
        List<BlockPos> found = null;
        for (var berth : berths.subList(0, Math.min(8, berths.size()))) {
            found = search(berth);
            if (found != null) break;
        }
        if (found == null) throw new IllegalStateException("No natural obstructed route to this relay intake");
        route = List.copyOf(found);
    }

    private List<BlockPos> search(BlockPos berth) {
        var parents = new HashMap<BlockPos, BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        parents.put(berth, berth);queue.add(berth);
        BlockPos launch = null;
        while (!queue.isEmpty() && parents.size() < 50000) {
            var pos = queue.remove();
            if (Math.abs(pos.getX() - berth.getX()) >= 14 && Math.abs(pos.getZ() - berth.getZ()) >= 24
                    && parents.containsKey(pos.south(8)) && visible(pos, pos.south(8))) {
                var blocked = centerObstruction(pos, berth);
                if (blocked != null) {launch = pos;obstruction = blocked;break;}
            }
            for (var direction : Direction.values()) {
                var next = pos.relative(direction, 2);
                if (parents.containsKey(next) || next.getY() < intake.getY() - 24 || next.getY() > intake.getY() + 10
                        || Math.abs(next.getX() - intake.getX()) > 42 || Math.abs(next.getZ() - intake.getZ()) > 42
                        || !clear(next) || !clear(pos.relative(direction))) continue;
                parents.put(next, pos);queue.add(next);
            }
        }
        if (launch == null) return null;
        var path = new ArrayList<BlockPos>();path.add(launch);
        for (var pos = launch.south(8); ; pos = parents.get(pos)) {
            path.add(pos);
            if (pos.equals(berth)) break;
        }
        var smooth = new ArrayList<BlockPos>();smooth.add(launch);smooth.add(path.get(1));
        for (int i = 1; i < path.size() - 1;) {
            int next = path.size() - 1;
            while (next > i && !visible(path.get(i), path.get(next))) next--;
            if (next == i) throw new IllegalStateException("Natural voyage path lost its full-hull clearance");
            smooth.add(path.get(next));i = next;
        }
        return smooth;
    }

    private boolean visible(BlockPos from, BlockPos to) {
        int steps = Math.max(1, (int)Math.ceil(Math.sqrt(from.distSqr(to)) * 2));
        for (int i = 0; i <= steps; i++) {
            double f = i / (double)steps;
            var point = new BlockPos((int)Math.round(from.getX() + (to.getX() - from.getX()) * f),
                    (int)Math.round(from.getY() + (to.getY() - from.getY()) * f),
                    (int)Math.round(from.getZ() + (to.getZ() - from.getZ()) * f));
            if (!clear(point)) return false;
        }
        return true;
    }

    private BlockPos centerObstruction(BlockPos from, BlockPos to) {
        int steps = Math.max(1, (int)Math.ceil(Math.sqrt(from.distSqr(to)) * 4));
        for (int i = 0; i <= steps; i++) {
            double f = i / (double)steps;
            var pos = BlockPos.containing(from.getX() + .5 + (to.getX() - from.getX()) * f,
                    from.getY() + .5 + (to.getY() - from.getY()) * f,
                    from.getZ() + .5 + (to.getZ() - from.getZ()) * f);
            var state = level.getBlockState(pos);
            if (state.is(Blocks.BASALT) || state.is(AeronauticsInnerworld.HARMONIC_ORE.get())) return pos;
        }
        return null;
    }

    private boolean clear(BlockPos point) {
        return volumes.computeIfAbsent(point, pos -> {
            long mask = 255L << (pos.getY() - 3 - low);
            for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
                if (dx * dx + dz * dz > 36) continue;
                long column = columns.computeIfAbsent(BlockPos.asLong(pos.getX() + dx, 0, pos.getZ() + dz), key -> {
                    var base = BlockPos.of(key);long solid = 0;
                    for (int y = low; y <= high; y++) {
                        var block = new BlockPos(base.getX(), y, base.getZ());
                        if (!level.hasChunkAt(block) || !level.getBlockState(block).getCollisionShape(level, block).isEmpty())
                            solid |= 1L << (y - low);
                    }
                    return solid;
                });
                if ((column & mask) != 0) return false;
            }
            return true;
        });
    }
}
