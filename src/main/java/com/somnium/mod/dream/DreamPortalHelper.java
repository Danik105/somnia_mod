package com.somnium.mod.dream;

import com.somnium.mod.block.DreamPortalBlock;
import com.somnium.mod.registry.ModBlocks;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Портал в мир снов: логика рамки из блоков сноведений.
 * Рамка — как минимальный портал в ад: внутренность 2×3, окантовка из блоков
 * сноведений (углы необязательны). Поджог зажигалкой внутри рамки заполняет её
 * плёнкой портала; разрушение рамки осыпает плёнку целиком.
 * Также умеет достраивать "встречный" портал и платформу в мире снов.
 */
public final class DreamPortalHelper {

    private DreamPortalHelper() {}

    /** Внутренняя ширина и высота портала (как минимальный портал в ад). */
    private static final int INNER_W = 2;
    private static final int INNER_H = 3;

    /**
     * Пытается зажечь портал так, чтобы airPos (клетка, где встанет огонь) оказалась
     * внутри рамки из блоков сноведений. Возвращает true, если портал зажёгся.
     */
    public static boolean tryIgnite(ServerWorld world, BlockPos airPos) {
        for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
            BlockPos bottomLeft = findFrameBottomLeft(world, airPos, axis, true);
            if (bottomLeft != null) {
                for (int dx = 0; dx < INNER_W; dx++) {
                    for (int dy = 0; dy < INNER_H; dy++) {
                        BlockPos cell = offset(bottomLeft, axis, dx, dy);
                        world.setBlockState(cell, ModBlocks.DREAM_PORTAL.getDefaultState()
                                .with(DreamPortalBlock.AXIS, axis));
                    }
                }
                world.playSound(null, airPos, SoundEvents.BLOCK_PORTAL_TRIGGER,
                        SoundCategory.BLOCKS, 1.0f, 0.9f);
                return true;
            }
        }
        return false;
    }

    /**
     * Проверяет, что рамка вокруг уже зажжённого блока портала цела.
     * Используется при соседних обновлениях: сломал блок рамки — портал осыпается.
     */
    public static boolean isFrameIntact(ServerWorld world, BlockPos portalPos, Direction.Axis axis) {
        return findFrameBottomLeft(world, portalPos, axis, false) != null;
    }

    /** Осыпает весь портал: обходим соседние блоки портала волной и убираем их. */
    public static void clearPortal(ServerWorld world, BlockPos start) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty() && seen.size() < 64) {
            BlockPos pos = queue.poll();
            if (!seen.add(pos)) continue;
            if (world.getBlockState(pos).getBlock() != ModBlocks.DREAM_PORTAL) continue;
            world.setBlockState(pos, Blocks.AIR.getDefaultState());
            for (Direction dir : Direction.values()) {
                queue.add(pos.offset(dir));
            }
        }
        world.playSound(null, start, SoundEvents.BLOCK_GLASS_BREAK,
                SoundCategory.BLOCKS, 0.8f, 0.5f);
    }

    /**
     * Ищет нижний-левый угол внутренности рамки, содержащей клетку pos.
     * Для поджога (forIgnite=true) внутренние клетки должны быть пустыми (воздух/огонь),
     * для проверки целостности — блоками портала. Углы рамки необязательны, как в аду.
     */
    private static BlockPos findFrameBottomLeft(ServerWorld world, BlockPos pos, Direction.Axis axis,
                                                boolean forIgnite) {
        for (int dx = 0; dx < INNER_W; dx++) {
            for (int dy = 0; dy < INNER_H; dy++) {
                BlockPos bottomLeft = offset(pos, axis, -dx, -dy);
                if (isValidFrame(world, bottomLeft, axis, forIgnite)) {
                    return bottomLeft;
                }
            }
        }
        return null;
    }

    private static boolean isValidFrame(ServerWorld world, BlockPos bottomLeft, Direction.Axis axis,
                                        boolean forIgnite) {
        // Внутренность
        for (int dx = 0; dx < INNER_W; dx++) {
            for (int dy = 0; dy < INNER_H; dy++) {
                BlockPos cell = offset(bottomLeft, axis, dx, dy);
                var state = world.getBlockState(cell);
                boolean ok = forIgnite
                        ? (state.isAir() || state.getBlock() == Blocks.FIRE)
                        : state.getBlock() == ModBlocks.DREAM_PORTAL;
                if (!ok) return false;
            }
        }
        // Окантовка: низ, верх и обе колонны — из блоков сноведений; углы необязательны
        for (int dx = -1; dx <= INNER_W; dx++) {
            // углы пропускаем
            if (!isFrameBlock(world, offset(bottomLeft, axis, dx, -1), dx == -1 || dx == INNER_W)) {
                return false;
            }
            if (!isFrameBlock(world, offset(bottomLeft, axis, dx, INNER_H), dx == -1 || dx == INNER_W)) {
                return false;
            }
        }
        for (int dy = 0; dy < INNER_H; dy++) {
            if (world.getBlockState(offset(bottomLeft, axis, -1, dy)).getBlock() != ModBlocks.DREAM_BLOCK) {
                return false;
            }
            if (world.getBlockState(offset(bottomLeft, axis, INNER_W, dy)).getBlock() != ModBlocks.DREAM_BLOCK) {
                return false;
            }
        }
        return true;
    }

    /** Угол рамки (corner=true) может быть любым блоком; остальное — только блоки сноведений. */
    private static boolean isFrameBlock(ServerWorld world, BlockPos pos, boolean corner) {
        return corner || world.getBlockState(pos).getBlock() == ModBlocks.DREAM_BLOCK;
    }

    /** Смещение вдоль оси портала: dx — поперёк плоскости портала, dy — вверх. */
    private static BlockPos offset(BlockPos pos, Direction.Axis axis, int dx, int dy) {
        return axis == Direction.Axis.X ? pos.add(dx, dy, 0) : pos.add(0, dy, dx);
    }

    // =====================================================================
    // "Встречная" сторона: платформа и портал в мире снов (строятся один раз)
    // =====================================================================

    /** Позиция платформы в мире снов (её центр). */
    public static final BlockPos DREAM_PLATFORM = new BlockPos(0, 5, 0);

    /** Строит платформу и стоячий портал в мире снов, если их ещё нет. */
    public static void ensureDreamPortalBuilt(ServerWorld dreamWorld) {
        if (dreamWorld.getBlockState(DREAM_PLATFORM).getBlock() == ModBlocks.DREAM_BLOCK) {
            return; // уже построено
        }
        // Платформа 7×7 из блоков сноведений
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                dreamWorld.setBlockState(new BlockPos(x, 5, z), ModBlocks.DREAM_BLOCK.getDefaultState());
            }
        }
        // Стоячая рамка вдоль оси X: внутренность x 1..2, y 7..9, z 0; окантовка вокруг
        for (int x = 0; x <= 3; x++) {
            dreamWorld.setBlockState(new BlockPos(x, 6, 0), ModBlocks.DREAM_BLOCK.getDefaultState());
            dreamWorld.setBlockState(new BlockPos(x, 10, 0), ModBlocks.DREAM_BLOCK.getDefaultState());
        }
        for (int y = 7; y <= 9; y++) {
            dreamWorld.setBlockState(new BlockPos(0, y, 0), ModBlocks.DREAM_BLOCK.getDefaultState());
            dreamWorld.setBlockState(new BlockPos(3, y, 0), ModBlocks.DREAM_BLOCK.getDefaultState());
        }
        for (int x = 1; x <= 2; x++) {
            for (int y = 7; y <= 9; y++) {
                dreamWorld.setBlockState(new BlockPos(x, y, 0),
                        ModBlocks.DREAM_PORTAL.getDefaultState()
                                .with(DreamPortalBlock.AXIS, Direction.Axis.X));
            }
        }
    }

    /** Куда телепортировать прибывшего в мир снов: перед порталом, на платформе. */
    public static double[] dreamArrivalPoint() {
        return new double[] {1.5, 6.0, 2.5};
    }
}
