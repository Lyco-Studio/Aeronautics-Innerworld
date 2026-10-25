package io.github.alphaly.aeronauticsinnerworld.world.structure;

import java.util.ArrayList;
import net.minecraft.util.RandomSource;

final class CoreMazeLayout {
    static final int SIZE = 11;

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
        return open;
    }

    private static void carve(boolean[][] open, int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) open[x][z] = true;
    }

    private CoreMazeLayout() {
    }
}
