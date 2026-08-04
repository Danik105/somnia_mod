package com.somnium.mod.dream;

import net.minecraft.util.math.BlockPos;
import java.util.*;

/**
 * Генератор процедурного лабиринта для сна "Обрушающаяся шахта".
 * Использует алгоритм рекурсивного backtracking для создания идеального лабиринта
 * (каждая ячейка достижима, есть только один путь между любыми двумя точками).
 */
public class MazeGenerator {

    private final Random random;
    private final int width;  // Ширина лабиринта (количество ячеек)
    private final int height; // Высота лабиринта (количество ячеек)
    private final int cellSize; // Размер одной ячейки в блоках
    private final boolean[][] walls; // true = стена, false = проход
    private final boolean[][] visited;

    // Направления: север, восток, юг, запад
    private static final int[][] DIRECTIONS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    public MazeGenerator(int width, int height, int cellSize, Random random) {
        this.width = width;
        this.height = height;
        this.cellSize = cellSize;
        this.random = random;
        this.walls = new boolean[width * 2 + 1][height * 2 + 1];
        this.visited = new boolean[width][height];

        // Инициализируем все как стены
        for (int x = 0; x < walls.length; x++) {
            Arrays.fill(walls[x], true);
        }
    }

    /**
     * Генерирует лабиринт используя алгоритм рекурсивного backtracking
     */
    public void generate() {
        // Начинаем с случайной ячейки
        int startX = random.nextInt(width);
        int startY = random.nextInt(height);
        carvePassagesFrom(startX, startY);
    }

    private void carvePassagesFrom(int cx, int cy) {
        visited[cx][cy] = true;

        // Открываем текущую ячейку
        walls[cx * 2 + 1][cy * 2 + 1] = false;

        // Перемешиваем направления для случайности
        List<Integer> directions = Arrays.asList(0, 1, 2, 3);
        Collections.shuffle(directions, random);

        for (int dir : directions) {
            int nx = cx + DIRECTIONS[dir][0];
            int ny = cy + DIRECTIONS[dir][1];

            // Проверяем границы
            if (nx >= 0 && nx < width && ny >= 0 && ny < height && !visited[nx][ny]) {
                // Убираем стену между текущей и следующей ячейкой
                int wallX = cx * 2 + 1 + DIRECTIONS[dir][0];
                int wallY = cy * 2 + 1 + DIRECTIONS[dir][1];
                walls[wallX][wallY] = false;

                // Рекурсивно продолжаем
                carvePassagesFrom(nx, ny);
            }
        }
    }

    /**
     * Возвращает true если в данной позиции должна быть стена
     */
    public boolean isWall(int x, int z) {
        if (x < 0 || x >= walls.length || z < 0 || z >= walls[0].length) {
            return true; // За границами всегда стена
        }
        return walls[x][z];
    }

    /**
     * Находит случайную свободную позицию для спавна игрока
     */
    public BlockPos findSpawnPosition(BlockPos origin, int baseY) {
        for (int attempts = 0; attempts < 100; attempts++) {
            int cellX = random.nextInt(width);
            int cellY = random.nextInt(height);

            // ИСПРАВЛЕНО: ячейка (cellX) лежит в сетке на (cellX*2+1), а не на cellX —
            // раньше спавн/выход/мобы попадали в стены (смещение вдвое)
            int worldX = (cellX * 2 + 1) * cellSize + cellSize / 2;
            int worldZ = (cellY * 2 + 1) * cellSize + cellSize / 2;

            if (!isWall(cellX * 2 + 1, cellY * 2 + 1)) {
                return origin.add(worldX, baseY, worldZ);
            }
        }
        // Fallback: центр лабиринта (в координатах сетки (width*2+1) ячеек)
        return origin.add((width * 2 + 1) * cellSize / 2, baseY, (height * 2 + 1) * cellSize / 2);
    }

    /**
     * Находит самую дальнюю свободную позицию от точки спавна для двери
     */
    public BlockPos findExitPosition(BlockPos spawnPos, BlockPos origin, int baseY) {
        BlockPos farthest = null;
        double maxDistance = 0;

        for (int cellX = 0; cellX < width; cellX++) {
            for (int cellY = 0; cellY < height; cellY++) {
                if (!isWall(cellX * 2 + 1, cellY * 2 + 1)) {
                    // ИСПРАВЛЕНО: см. findSpawnPosition — ячейка в сетке на (cellX*2+1)
                    int worldX = (cellX * 2 + 1) * cellSize + cellSize / 2;
                    int worldZ = (cellY * 2 + 1) * cellSize + cellSize / 2;
                    BlockPos pos = origin.add(worldX, baseY, worldZ);

                    double distance = spawnPos.getSquaredDistance(pos);
                    if (distance > maxDistance) {
                        maxDistance = distance;
                        farthest = pos;
                    }
                }
            }
        }
        return farthest != null ? farthest : origin.add((width * 2 + 1) * cellSize - cellSize / 2, baseY, (height * 2 + 1) * cellSize - cellSize / 2);
    }

    /**
     * Находит случайную свободную позицию для спавна монстров
     */
    public List<BlockPos> findMonsterSpawnPositions(BlockPos origin, int baseY, int count) {
        List<BlockPos> positions = new ArrayList<>();
        Set<String> used = new HashSet<>();

        int attempts = 0;
        while (positions.size() < count && attempts < count * 10) {
            int cellX = random.nextInt(width);
            int cellY = random.nextInt(height);
            String key = cellX + "," + cellY;

            if (!isWall(cellX * 2 + 1, cellY * 2 + 1) && !used.contains(key)) {
                // ИСПРАВЛЕНО: см. findSpawnPosition — ячейка в сетке на (cellX*2+1)
                int worldX = (cellX * 2 + 1) * cellSize + cellSize / 2;
                int worldZ = (cellY * 2 + 1) * cellSize + cellSize / 2;
                positions.add(origin.add(worldX, baseY, worldZ));
                used.add(key);
            }
            attempts++;
        }
        return positions;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getCellSize() {
        return cellSize;
    }
}
