package io.github.alphaly.aeronauticsinnerworld.world.structure;

import java.util.ArrayList;
import net.minecraft.util.RandomSource;

final class CoreMazeLayout {
    static final int SIZE = 11;
    private static final int[][] ROOMS = {{2, 2, 8, 8}, {38, 2, 44, 8}, {38, 38, 44, 44}, {2, 38, 8, 44},
            {8, 8, 14, 14}, {32, 8, 38, 14}, {20, 20, 26, 26}};

    static boolean[][] passages(long seed) {
        var random = RandomSource.create(seed);
        var open = new boolean[SIZE * 4 + 3][SIZE * 4 + 3];
        var visited = new boolean[SIZE][SIZE];
        var stack = new ArrayList<int[]>();
        stack.add(new int[] {0, 0});
        visited[0][0] = true;
        while (!stack.isEmpty()) {
            var cell = stack.getLast();
            int x = cell[0], z = cell[1];
            carve(open, x * 4 + 2, z * 4 + 2, x * 4 + 4, z * 4 + 4);
            var choices = new ArrayList<int[]>();
            for (var step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + step[0], nz = z + step[1];
                if (nx >= 0 && nx < SIZE && nz >= 0 && nz < SIZE && !visited[nx][nz]) choices.add(new int[] {nx, nz});
            }
            if (choices.isEmpty()) {
                stack.removeLast();
                continue;
            }
            var next = choices.get(random.nextInt(choices.size()));
            carve(open, Math.min(x, next[0]) * 4 + 2, Math.min(z, next[1]) * 4 + 2,
                    Math.max(x, next[0]) * 4 + 4, Math.max(z, next[1]) * 4 + 4);
            visited[next[0]][next[1]] = true;
            stack.add(next);
        }
        for (var room : ROOMS) {
            carve(open, room[0], room[1], room[2], room[3]);
        }
        return open;
    }

    static boolean isRoom(int x, int z) {
        for (var room : ROOMS) if (x >= room[0] && x <= room[2] && z >= room[1] && z <= room[3]) return true;
        return false;
    }

    private static void carve(boolean[][] open, int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) open[x][z] = true;
    }

    private CoreMazeLayout() {
    }
}
