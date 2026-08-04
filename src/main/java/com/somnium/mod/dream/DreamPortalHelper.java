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

        // ДОБАВЛЕНО ("мир слишком пустой"): сонный сад вокруг платформы — искажённые
        // деревья со светящимися сердцевинами, аметистовые шпили, фонари и заросли корней.
        generateDreamGarden(dreamWorld);
    }

    // =====================================================================
    // ДОБАВЛЕНО ("мир слишком пустой, нужны структуры и деревья"):
    // процедурный "сонный сад" — детерминированный (seed фиксирован), строится один раз
    // вместе с платформой. Всё в радиусе 10–48 блоков от центра, на поверхности (y=5).
    // =====================================================================

    /** Верхушка земли в мире снов (слои: 1 бедрок + 3 чернокамня + 1 искажённый нилиум). */
    private static final int GROUND_TOP = 4; // блок земли; ставим декор начиная с y=5

    private static void generateDreamGarden(ServerWorld world) {
        java.util.Random rnd = new java.util.Random(20260805L);

        // 1) Искажённые "деревья снов": ноготковый ствол + купол из искажённого нароста
        //    со светящимся грибом-сердцевиной. 18 штук в кольце 10..46 блоков.
        for (int t = 0; t < 18; t++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 10.0 + rnd.nextDouble() * 36.0;
            int cx = (int) Math.round(Math.cos(angle) * dist);
            int cz = (int) Math.round(Math.sin(angle) * dist);
            if (!isGround(world, cx, cz)) continue;
            buildDreamTree(world, cx, cz, rnd);
        }

        // 2) Аметистовые шпили — скопления кристаллов высотой 2..5
        for (int s = 0; s < 12; s++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 8.0 + rnd.nextDouble() * 34.0;
            int cx = (int) Math.round(Math.cos(angle) * dist);
            int cz = (int) Math.round(Math.sin(angle) * dist);
            if (!isGround(world, cx, cz)) continue;
            int h = 2 + rnd.nextInt(4);
            for (int y = 1; y <= h; y++) {
                world.setBlockState(new BlockPos(cx, GROUND_TOP + y, cz),
                        Blocks.AMETHYST_BLOCK.getDefaultState(), 2);
            }
            world.setBlockState(new BlockPos(cx, GROUND_TOP + h + 1, cz),
                    Blocks.LARGE_AMETHYST_BUD.getDefaultState(), 2);
        }

        // 3) Фонари снов: столбик из чернокамня + end rod, светят над тропой к платформе
        for (int l = 0; l < 10; l++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 7.0 + rnd.nextDouble() * 30.0;
            int cx = (int) Math.round(Math.cos(angle) * dist);
            int cz = (int) Math.round(Math.sin(angle) * dist);
            if (!isGround(world, cx, cz)) continue;
            world.setBlockState(new BlockPos(cx, GROUND_TOP + 1, cz), Blocks.BLACKSTONE_WALL.getDefaultState(), 2);
            world.setBlockState(new BlockPos(cx, GROUND_TOP + 2, cz), Blocks.END_ROD.getDefaultState(), 2);
        }

        // 4) Заросли искажённых корней и незерских ростков — пятнами по 3..7 штук
        for (int patch = 0; patch < 26; patch++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 6.0 + rnd.nextDouble() * 40.0;
            int px = (int) Math.round(Math.cos(angle) * dist);
            int pz = (int) Math.round(Math.sin(angle) * dist);
            int count = 3 + rnd.nextInt(5);
            for (int i = 0; i < count; i++) {
                int x = px + rnd.nextInt(5) - 2;
                int z = pz + rnd.nextInt(5) - 2;
                if (!isGround(world, x, z)) continue;
                if (!world.getBlockState(new BlockPos(x, GROUND_TOP + 1, z)).isAir()) continue;
                var plant = rnd.nextBoolean()
                        ? Blocks.WARPED_ROOTS.getDefaultState()
                        : Blocks.NETHER_SPROUTS.getDefaultState();
                world.setBlockState(new BlockPos(x, GROUND_TOP + 1, z), plant, 2);
            }
        }
    }

    /** Под платформой и порталом ничего не сажаем: там уже блоки сноведений. */
    private static boolean isGround(ServerWorld world, int x, int z) {
        return world.getBlockState(new BlockPos(x, GROUND_TOP, z)).getBlock() == Blocks.WARPED_NYLIUM
                && world.getBlockState(new BlockPos(x, GROUND_TOP + 1, z)).isAir();
    }

    /** Одно дерево снов: ствол 4..7, купол из искажённого нароста, гриб-светильник внутри. */
    private static void buildDreamTree(ServerWorld world, int cx, int cz, java.util.Random rnd) {
        int trunkH = 4 + rnd.nextInt(4);
        for (int y = 1; y <= trunkH; y++) {
            world.setBlockState(new BlockPos(cx, GROUND_TOP + y, cz),
                    Blocks.WARPED_STEM.getDefaultState(), 2);
        }
        // Купол: сфера радиуса 2..3 вокруг вершины ствола (без нижнего яруса — не душит ствол)
        int topY = GROUND_TOP + trunkH;
        int radius = 2 + rnd.nextInt(2);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = 0; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > radius * radius + 0.5) continue;
                    if (rnd.nextDouble() < 0.18) continue; // рваные края — "сонное" рыхление
                    BlockPos pos = new BlockPos(cx + dx, topY + dy, cz + dz);
                    if (!world.getBlockState(pos).isAir()
                            && world.getBlockState(pos).getBlock() != Blocks.WARPED_STEM) continue;
                    world.setBlockState(pos, Blocks.WARPED_WART_BLOCK.getDefaultState(), 2);
                }
            }
        }
        // Сердцевина-светильник внутри купола
        world.setBlockState(new BlockPos(cx, topY, cz), Blocks.SHROOMLIGHT.getDefaultState(), 2);
    }

    /** Куда телепортировать прибывшего в мир снов: перед порталом, на платформе. */
    public static double[] dreamArrivalPoint() {
        return new double[] {1.5, 6.0, 2.5};
    }
}
