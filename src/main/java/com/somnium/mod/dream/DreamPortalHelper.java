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
            // Платформа есть (мир создан раньше), но сад мог появиться позже — проверяем его всегда
            generateDreamGarden(dreamWorld);
            return;
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

    /**
     * Маркер "сад уже построен" — ПОД центром платформы (невидим и не ломается).
     * ВАЖНО: маркер отдельный от платформы — миры, созданные до появления сада
     * (платформа уже есть), всё равно получат сад при следующем входе.
     */
    private static final BlockPos GARDEN_MARKER = new BlockPos(0, GROUND_TOP, 0);

    /**
     * Маркер v3 ("мир снов пустой — нужны деревни, структуры, мобы всех видов"):
     * миры, получившие сад v2, достраиваются до v3 при следующем входе.
     * Лежит под платформой рядом с маркером v2 — невидим и не ломается.
     */
    private static final BlockPos GARDEN_MARKER_V3 = new BlockPos(1, GROUND_TOP, 0);

    private static void generateDreamGarden(ServerWorld world) {
        if (world.getBlockState(GARDEN_MARKER).getBlock() == Blocks.SHROOMLIGHT) {
            // Сад v2 уже есть — но контент v3 (деревня и т.д.) мог появиться позже
            generateDreamGardenV3(world);
            return;
        }
        world.setBlockState(GARDEN_MARKER, Blocks.SHROOMLIGHT.getDefaultState(), 2);

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

        // ДОБАВЛЕНО v2 ("в мире снов ничего нет — структуры, мобы, руды"):

        // 5) Руины арок из чернокамня с плачущим обсидианом — остатки чьих-то снов
        for (int a = 0; a < 5; a++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 16.0 + rnd.nextDouble() * 26.0;
            int cx = (int) Math.round(Math.cos(angle) * dist);
            int cz = (int) Math.round(Math.sin(angle) * dist);
            if (!isGround(world, cx, cz)) continue;
            buildRuinedArch(world, cx, cz, rnd);
        }

        // 6) Обсидиановые обелиски высотой 5..9 со светящимся наконечником
        for (int o = 0; o < 5; o++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 12.0 + rnd.nextDouble() * 30.0;
            int cx = (int) Math.round(Math.cos(angle) * dist);
            int cz = (int) Math.round(Math.sin(angle) * dist);
            if (!isGround(world, cx, cz)) continue;
            int h = 5 + rnd.nextInt(5);
            for (int y = 1; y <= h; y++) {
                world.setBlockState(new BlockPos(cx, GROUND_TOP + y, cz),
                        rnd.nextDouble() < 0.2 ? Blocks.CRYING_OBSIDIAN.getDefaultState()
                                : Blocks.OBSIDIAN.getDefaultState(), 2);
            }
            world.setBlockState(new BlockPos(cx, GROUND_TOP + h + 1, cz),
                    Blocks.SEA_LANTERN.getDefaultState(), 2);
        }

        // 7) Парящие острова — куски земли снов, оторвавшиеся и зависшие в небе
        for (int i = 0; i < 4; i++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 14.0 + rnd.nextDouble() * 24.0;
            int cx = (int) Math.round(Math.cos(angle) * dist);
            int cz = (int) Math.round(Math.sin(angle) * dist);
            buildFloatingIsland(world, cx, 13 + rnd.nextInt(6), cz, rnd);
        }

        // 8) Сонные жилы — небольшие воронки с оголённой рудой сноведений (активность: добыча)
        for (int v = 0; v < 10; v++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 9.0 + rnd.nextDouble() * 32.0;
            int cx = (int) Math.round(Math.cos(angle) * dist);
            int cz = (int) Math.round(Math.sin(angle) * dist);
            if (!isGround(world, cx, cz)) continue;
            buildDreamOreVein(world, cx, cz, rnd);
        }

        // 9) Обитатели мира снов: пара эндерменов-скитальцев, белые кролики и все́и —
        //    мир мирный, но не мёртвый
        spawnAmbientMob(world, net.minecraft.entity.EntityType.ENDERMAN,
                20 + rnd.nextInt(15), 20 + rnd.nextInt(15), rnd);
        spawnAmbientMob(world, net.minecraft.entity.EntityType.ENDERMAN,
                -(18 + rnd.nextInt(12)), 16 + rnd.nextInt(15), rnd);
        for (int r = 0; r < 4; r++) {
            var rabbit = spawnAmbientMob(world, net.minecraft.entity.EntityType.RABBIT,
                    8 + rnd.nextInt(30) - 15, 8 + rnd.nextInt(30) - 15, rnd);
            if (rabbit instanceof net.minecraft.entity.passive.RabbitEntity rabbitEntity) {
                rabbitEntity.setVariant(net.minecraft.entity.passive.RabbitEntity.RabbitType.WHITE);
            }
        }
        spawnAmbientMob(world, net.minecraft.entity.EntityType.ALLAY, 10, -12, rnd);
        spawnAmbientMob(world, net.minecraft.entity.EntityType.ALLAY, -14, 8, rnd);

        // Свежий мир — сразу достраиваем и контент v3
        generateDreamGardenV3(world);
    }

    // =====================================================================
    // ДОБАВЛЕНО v3 ("мир снов пустой — добавь деревни, структуры, мобов всех видов"):
    // деревня снов с колодцем, домами и фермой, каменный круг, разрушенные башни,
    // второе, более густое кольцо леса и стая обитателей — от коров и кур до
    // кошмарных скитальцев на отшибе. Детерминировано (свой seed), строится один раз.
    // =====================================================================

    private static void generateDreamGardenV3(ServerWorld world) {
        if (world.getBlockState(GARDEN_MARKER_V3).getBlock() == Blocks.SHROOMLIGHT) {
            return; // контент v3 уже построен
        }
        world.setBlockState(GARDEN_MARKER_V3, Blocks.SHROOMLIGHT.getDefaultState(), 2);

        java.util.Random rnd = new java.util.Random(20260807L);

        // 1) ДЕРЕВНЯ СНОВ: колодец, 5 домов, тропы, ферма, жители и коты
        buildDreamVillage(world, 48, -20, rnd);

        // 2) Каменный круг — древнее место силы снов
        buildStoneCircle(world, -40, 30, rnd);

        // 3) Две разрушенные башни на горизонте
        buildRuinedTower(world, -52, -34, rnd);
        buildRuinedTower(world, 30, 48, rnd);

        // 4) Второе, дальнее и более густое кольцо леса (14 деревьев, 30..70 блоков)
        for (int t = 0; t < 14; t++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 30.0 + rnd.nextDouble() * 40.0;
            int cx = (int) Math.round(Math.cos(angle) * dist);
            int cz = (int) Math.round(Math.sin(angle) * dist);
            if (!isGround(world, cx, cz)) continue;
            buildDreamTree(world, cx, cz, rnd);
        }

        // 5) Ещё 8 аметистовых шпилей в дальнем кольце
        for (int s = 0; s < 8; s++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 40.0 + rnd.nextDouble() * 30.0;
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

        // 6) Ещё 6 фонарей на дальних тропах
        for (int l = 0; l < 6; l++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 30.0 + rnd.nextDouble() * 25.0;
            int cx = (int) Math.round(Math.cos(angle) * dist);
            int cz = (int) Math.round(Math.sin(angle) * dist);
            if (!isGround(world, cx, cz)) continue;
            world.setBlockState(new BlockPos(cx, GROUND_TOP + 1, cz), Blocks.BLACKSTONE_WALL.getDefaultState(), 2);
            world.setBlockState(new BlockPos(cx, GROUND_TOP + 2, cz), Blocks.END_ROD.getDefaultState(), 2);
        }

        // 7) Ещё 20 пятен корней и ростков — чтобы земля не была голой
        for (int patch = 0; patch < 20; patch++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 10.0 + rnd.nextDouble() * 50.0;
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

        // 8) МОБЫ ВСЕХ ВИДОВ. Мирная живность бродит по лугам снов (12..40 блоков от центра):
        net.minecraft.entity.EntityType<?>[] pasture = {
            net.minecraft.entity.EntityType.COW, net.minecraft.entity.EntityType.SHEEP,
            net.minecraft.entity.EntityType.PIG, net.minecraft.entity.EntityType.CHICKEN,
            net.minecraft.entity.EntityType.COW, net.minecraft.entity.EntityType.SHEEP,
            net.minecraft.entity.EntityType.PIG, net.minecraft.entity.EntityType.CHICKEN,
            net.minecraft.entity.EntityType.CHICKEN, net.minecraft.entity.EntityType.FOX,
            net.minecraft.entity.EntityType.COW, net.minecraft.entity.EntityType.FOX,
        };
        for (net.minecraft.entity.EntityType<?> type : pasture) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 12.0 + rnd.nextDouble() * 28.0;
            spawnAmbientMob(world, type,
                    (int) Math.round(Math.cos(angle) * dist),
                    (int) Math.round(Math.sin(angle) * dist), rnd);
        }
        // Ещё кролики, всеи и эндермены-скитальцы
        for (int r = 0; r < 4; r++) {
            var rabbit = spawnAmbientMob(world, net.minecraft.entity.EntityType.RABBIT,
                    10 + rnd.nextInt(40) - 20, 10 + rnd.nextInt(40) - 20, rnd);
            if (rabbit instanceof net.minecraft.entity.passive.RabbitEntity rabbitEntity) {
                rabbitEntity.setVariant(net.minecraft.entity.passive.RabbitEntity.RabbitType.WHITE);
            }
        }
        spawnAmbientMob(world, net.minecraft.entity.EntityType.ALLAY, 24, 6, rnd);
        spawnAmbientMob(world, net.minecraft.entity.EntityType.ALLAY, -8, 26, rnd);
        spawnAmbientMob(world, net.minecraft.entity.EntityType.ENDERMAN, 34, -30, rnd);
        spawnAmbientMob(world, net.minecraft.entity.EntityType.ENDERMAN, -30, -22, rnd);

        // 9) Кошмарные скитальцы на ОТШИБЕ (45..70 блоков) — мир снов не безопасен
        //    на окраинах, но деревню и платформу они не достают
        for (int n = 0; n < 2; n++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 45.0 + rnd.nextDouble() * 25.0;
            spawnAmbientMob(world, com.somnium.mod.registry.ModEntities.WATCHER,
                    (int) Math.round(Math.cos(angle) * dist),
                    (int) Math.round(Math.sin(angle) * dist), rnd);
        }
        for (int n = 0; n < 2; n++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 45.0 + rnd.nextDouble() * 25.0;
            spawnAmbientMob(world, com.somnium.mod.registry.ModEntities.FERAL_VILLAGER,
                    (int) Math.round(Math.cos(angle) * dist),
                    (int) Math.round(Math.sin(angle) * dist), rnd);
        }
        double shadeAngle = rnd.nextDouble() * Math.PI * 2;
        spawnAmbientMob(world, com.somnium.mod.registry.ModEntities.LURKING_SHADE,
                (int) Math.round(Math.cos(shadeAngle) * 55.0),
                (int) Math.round(Math.sin(shadeAngle) * 55.0), rnd);
    }

    /**
     * Деревня снов: центральный колодец с крышей, пять искажённых домов с лампами,
     * кроватями и бочками, тропы из полированного чернокамня, ферма адского нароста,
     * жители и коты. Красивое, живое место — "цивилизация" мира снов.
     */
    private static void buildDreamVillage(ServerWorld world, int cx, int cz, java.util.Random rnd) {
        // --- Колодец: чаша из чернокаменного кирпича, вода, столбики и крыша ---
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean center = dx == 0 && dz == 0;
                world.setBlockState(new BlockPos(cx + dx, GROUND_TOP, cz + dz),
                        center ? Blocks.WATER.getDefaultState()
                               : Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState(), 2);
                if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
                    // Угловые столбики и крыша колодца
                    world.setBlockState(new BlockPos(cx + dx, GROUND_TOP + 1, cz + dz),
                            Blocks.BLACKSTONE_WALL.getDefaultState(), 2);
                    world.setBlockState(new BlockPos(cx + dx, GROUND_TOP + 2, cz + dz),
                            Blocks.BLACKSTONE_WALL.getDefaultState(), 2);
                    world.setBlockState(new BlockPos(cx + dx, GROUND_TOP + 3, cz + dz),
                            Blocks.POLISHED_BLACKSTONE_BRICK_SLAB.getDefaultState(), 2);
                }
            }
        }
        world.setBlockState(new BlockPos(cx, GROUND_TOP + 3, cz),
                Blocks.SHROOMLIGHT.getDefaultState(), 2);

        // --- Дома вокруг колодца (смещения от центра деревни) ---
        int[][] houses = { {-14, 0}, {14, 2}, {0, -14}, {2, 14}, {-9, 11} };
        for (int[] h : houses) {
            buildDreamHouse(world, cx + h[0], cz + h[1], rnd);
            // Тропа от колодца к дому — ломаная из полированного чернокамня
            buildPath(world, cx, cz, cx + h[0], cz + h[1]);
        }

        // --- Ферма адского нароста за домом ---
        int fx = cx + 14, fz = cz - 10;
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                world.setBlockState(new BlockPos(fx + dx, GROUND_TOP, fz + dz),
                        Blocks.SOUL_SAND.getDefaultState(), 2);
                world.setBlockState(new BlockPos(fx + dx, GROUND_TOP + 1, fz + dz),
                        Blocks.NETHER_WART.getDefaultState(), 2);
            }
        }
        for (int dx = -1; dx <= 5; dx++) {
            world.setBlockState(new BlockPos(fx + dx, GROUND_TOP + 1, fz - 1),
                    Blocks.BLACKSTONE_WALL.getDefaultState(), 2);
            world.setBlockState(new BlockPos(fx + dx, GROUND_TOP + 1, fz + 3),
                    Blocks.BLACKSTONE_WALL.getDefaultState(), 2);
        }

        // --- Фонарные столбы деревни ---
        for (int[] lamp : new int[][]{ {-7, 0}, {7, 1}, {0, -7}, {1, 7} }) {
            world.setBlockState(new BlockPos(cx + lamp[0], GROUND_TOP + 1, cz + lamp[1]),
                    Blocks.BLACKSTONE_WALL.getDefaultState(), 2);
            world.setBlockState(new BlockPos(cx + lamp[0], GROUND_TOP + 2, cz + lamp[1]),
                    Blocks.SHROOMLIGHT.getDefaultState(), 2);
        }

        // --- Жители деревни снов и коты ---
        for (int v = 0; v < 5; v++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 3.0 + rnd.nextDouble() * 8.0;
            spawnAmbientMob(world, net.minecraft.entity.EntityType.VILLAGER,
                    cx + (int) Math.round(Math.cos(angle) * dist),
                    cz + (int) Math.round(Math.sin(angle) * dist), rnd);
        }
        spawnAmbientMob(world, net.minecraft.entity.EntityType.CAT, cx + 3, cz + 2, rnd);
        spawnAmbientMob(world, net.minecraft.entity.EntityType.CAT, cx - 4, cz - 3, rnd);
    }

    /** Один дом снов 5×5: искажённые стены на столбах-стволах, плоская крыша со светогрибом. */
    private static void buildDreamHouse(ServerWorld world, int hx, int hz, java.util.Random rnd) {
        // Пол
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                world.setBlockState(new BlockPos(hx + dx, GROUND_TOP, hz + dz),
                        Blocks.WARPED_PLANKS.getDefaultState(), 2);
            }
        }
        // Стены (2 высоты) с угловыми столбами и дверным проёмом на южной стороне
        for (int y = 1; y <= 2; y++) {
            for (int dx = 0; dx < 5; dx++) {
                for (int dz = 0; dz < 5; dz++) {
                    boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    if (!edge) continue;
                    boolean corner = (dx == 0 || dx == 4) && (dz == 0 || dz == 4);
                    // Дверной проём 1×2 — по центру южной стены
                    if (dz == 0 && dx == 2) continue;
                    world.setBlockState(new BlockPos(hx + dx, GROUND_TOP + y, hz + dz),
                            (corner ? Blocks.WARPED_STEM : Blocks.WARPED_PLANKS).getDefaultState(), 2);
                }
            }
        }
        // Крыша: плиты 5×5, над ними куполок 3×3 и светогриб-шпиль
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                world.setBlockState(new BlockPos(hx + dx, GROUND_TOP + 3, hz + dz),
                        Blocks.WARPED_SLAB.getDefaultState(), 2);
            }
        }
        for (int dx = 1; dx <= 3; dx++) {
            for (int dz = 1; dz <= 3; dz++) {
                world.setBlockState(new BlockPos(hx + dx, GROUND_TOP + 4, hz + dz),
                        Blocks.WARPED_PLANKS.getDefaultState(), 2);
            }
        }
        world.setBlockState(new BlockPos(hx + 2, GROUND_TOP + 5, hz + 2),
                Blocks.SHROOMLIGHT.getDefaultState(), 2);

        // Интерьер: свет, верстак, бочка, кровать (приглушённая — во снах не спят)
        world.setBlockState(new BlockPos(hx + 2, GROUND_TOP + 2, hz + 4),
                Blocks.SHROOMLIGHT.getDefaultState(), 2);
        world.setBlockState(new BlockPos(hx + 1, GROUND_TOP + 1, hz + 3),
                Blocks.CRAFTING_TABLE.getDefaultState(), 2);
        world.setBlockState(new BlockPos(hx + 3, GROUND_TOP + 1, hz + 3),
                Blocks.BARREL.getDefaultState(), 2);
        // Кровать вдоль задней стены (головка + изножье)
        world.setBlockState(new BlockPos(hx + 1, GROUND_TOP + 1, hz + 1),
                Blocks.RED_BED.getDefaultState()
                        .with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.HEAD)
                        .with(net.minecraft.block.BedBlock.FACING, net.minecraft.util.math.Direction.NORTH), 2);
        world.setBlockState(new BlockPos(hx + 1, GROUND_TOP + 1, hz + 2),
                Blocks.RED_BED.getDefaultState()
                        .with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.FOOT)
                        .with(net.minecraft.block.BedBlock.FACING, net.minecraft.util.math.Direction.NORTH), 2);
    }

    /** Ломаная тропа между двумя точками: сначала вдоль X, потом вдоль Z. */
    private static void buildPath(ServerWorld world, int x0, int z0, int x1, int z1) {
        int x = x0;
        while (x != x1) {
            world.setBlockState(new BlockPos(x, GROUND_TOP, z0),
                    Blocks.POLISHED_BLACKSTONE.getDefaultState(), 2);
            x += Integer.signum(x1 - x);
        }
        int z = z0;
        while (z != z1) {
            world.setBlockState(new BlockPos(x1, GROUND_TOP, z),
                    Blocks.POLISHED_BLACKSTONE.getDefaultState(), 2);
            z += Integer.signum(z1 - z);
        }
    }

    /** Каменный круг: 8 столбов-менгиров по кругу, пара поваленных, в центре — руда снов. */
    private static void buildStoneCircle(ServerWorld world, int cx, int cz, java.util.Random rnd) {
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            int px = cx + (int) Math.round(Math.cos(angle) * 6);
            int pz = cz + (int) Math.round(Math.sin(angle) * 6);
            if (!isGround(world, px, pz)) continue;
            if (rnd.nextInt(8) < 2) {
                // Поваленный менгир — лежит на земле
                world.setBlockState(new BlockPos(px, GROUND_TOP + 1, pz),
                        Blocks.POLISHED_BASALT.getDefaultState(), 2);
                world.setBlockState(new BlockPos(px + 1, GROUND_TOP + 1, pz),
                        Blocks.POLISHED_BASALT.getDefaultState(), 2);
                continue;
            }
            int h = 3 + rnd.nextInt(3);
            for (int y = 1; y <= h; y++) {
                world.setBlockState(new BlockPos(px, GROUND_TOP + y, pz),
                        rnd.nextDouble() < 0.15 ? Blocks.CRYING_OBSIDIAN.getDefaultState()
                                : Blocks.POLISHED_BASALT.getDefaultState(), 2);
            }
        }
        // Центр круга — оголённая руда сноведений на светящейся подложке
        world.setBlockState(new BlockPos(cx, GROUND_TOP, cz), Blocks.SHROOMLIGHT.getDefaultState(), 2);
        world.setBlockState(new BlockPos(cx, GROUND_TOP + 1, cz),
                com.somnium.mod.registry.ModBlocks.DREAM_ORE.getDefaultState(), 2);
    }

    /** Разрушенная башня: полый цилиндр радиуса 3 с рваным верхом и дверным проёмом. */
    private static void buildRuinedTower(ServerWorld world, int cx, int cz, java.util.Random rnd) {
        int height = 9 + rnd.nextInt(5);
        for (int y = 1; y <= height; y++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d < 2.4 || d > 3.4) continue; // только стенка
                    // Рваный верх: верхние 3 яруса местами обвалены
                    if (y > height - 3 && rnd.nextDouble() < 0.45) continue;
                    // Дверной проём 2 высоты с юга
                    if (y <= 2 && dz == 3 && Math.abs(dx) <= 0) continue;
                    int roll = rnd.nextInt(100);
                    world.setBlockState(new BlockPos(cx + dx, GROUND_TOP + y, cz + dz),
                            (roll < 60 ? Blocks.POLISHED_BLACKSTONE_BRICKS
                                    : roll < 80 ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS
                                    : Blocks.BLACKSTONE).getDefaultState(), 2);
                }
            }
        }
        // Свет внутри руины
        world.setBlockState(new BlockPos(cx, GROUND_TOP + 1, cz),
                Blocks.SHROOMLIGHT.getDefaultState(), 2);
    }

    /** Разрушенная арка: две колонны разной высоты, перемычка местами обвалилась. */
    private static void buildRuinedArch(ServerWorld world, int cx, int cz, java.util.Random rnd) {
        int h1 = 4 + rnd.nextInt(3);
        int h2 = 3 + rnd.nextInt(3);
        boolean alongX = rnd.nextBoolean();
        for (int y = 1; y <= h1; y++) {
            set(world, cx, GROUND_TOP + y, cz, Blocks.POLISHED_BLACKSTONE);
        }
        for (int y = 1; y <= h2; y++) {
            set(world, cx + (alongX ? 4 : 0), GROUND_TOP + y, cz + (alongX ? 0 : 4), Blocks.POLISHED_BLACKSTONE);
        }
        // Перемычка между верхушками с пропусками (обвал)
        for (int i = 1; i < 4; i++) {
            if (rnd.nextDouble() < 0.3) continue; // обвалившийся кусок
            int x = cx + (alongX ? i : 0);
            int z = cz + (alongX ? 0 : i);
            set(world, x, GROUND_TOP + Math.max(h1, h2), z,
                    rnd.nextDouble() < 0.25 ? Blocks.CRYING_OBSIDIAN : Blocks.POLISHED_BLACKSTONE);
        }
        // Обломки у основания
        for (int i = 0; i < 4; i++) {
            int x = cx + rnd.nextInt(6) - 1;
            int z = cz + rnd.nextInt(6) - 1;
            if (isGround(world, x, z)) set(world, x, GROUND_TOP + 1, z, Blocks.BLACKSTONE);
        }
    }

    /** Парящий остров: 5x5 искажённого нилиума на чернокамне, сверху дерево или кристалл. */
    private static void buildFloatingIsland(ServerWorld world, int cx, int cy, int cz, java.util.Random rnd) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlockState(new BlockPos(cx + dx, cy, cz + dz), Blocks.WARPED_NYLIUM.getDefaultState(), 2);
                if (Math.abs(dx) + Math.abs(dz) < 3) {
                    world.setBlockState(new BlockPos(cx + dx, cy - 1, cz + dz), Blocks.BLACKSTONE.getDefaultState(), 2);
                }
                if (Math.abs(dx) + Math.abs(dz) < 2) {
                    world.setBlockState(new BlockPos(cx + dx, cy - 2, cz + dz), Blocks.BLACKSTONE.getDefaultState(), 2);
                }
            }
        }
        // Сталактит снизу и украшение сверху
        world.setBlockState(new BlockPos(cx, cy - 3, cz), Blocks.POINTED_DRIPSTONE.getDefaultState(), 2);
        if (rnd.nextBoolean()) {
            int h = 3 + rnd.nextInt(2);
            for (int y = 1; y <= h; y++) {
                world.setBlockState(new BlockPos(cx, cy + y, cz), Blocks.WARPED_STEM.getDefaultState(), 2);
            }
            world.setBlockState(new BlockPos(cx, cy + h + 1, cz), Blocks.SHROOMLIGHT.getDefaultState(), 2);
        } else {
            world.setBlockState(new BlockPos(cx, cy + 1, cz), Blocks.AMETHYST_BLOCK.getDefaultState(), 2);
            world.setBlockState(new BlockPos(cx, cy + 2, cz), Blocks.MEDIUM_AMETHYST_BUD.getDefaultState(), 2);
        }
        // Световой след под островом
        world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                cx + 0.5, cy - 1.5, cz + 0.5, 12, 1.2, 1.2, 1.2, 0.0);
    }

    /** Воронка с оголённой рудой сноведений: выбоина 3x3, на дне и стенках — руда. */
    private static void buildDreamOreVein(ServerWorld world, int cx, int cz, java.util.Random rnd) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // Снимаем нилиум и верхний чернокамень
                world.setBlockState(new BlockPos(cx + dx, GROUND_TOP, cz + dz), Blocks.AIR.getDefaultState(), 2);
                if (Math.abs(dx) + Math.abs(dz) < 2) {
                    world.setBlockState(new BlockPos(cx + dx, GROUND_TOP - 1, cz + dz), Blocks.AIR.getDefaultState(), 2);
                }
            }
        }
        int ores = 2 + rnd.nextInt(3);
        for (int i = 0; i < ores; i++) {
            int dx = rnd.nextInt(3) - 1;
            int dz = rnd.nextInt(3) - 1;
            int y = GROUND_TOP - (rnd.nextBoolean() ? 2 : 1);
            world.setBlockState(new BlockPos(cx + dx, y, dz + cz), ModBlocks.DREAM_ORE.getDefaultState(), 2);
        }
    }

    /** Спавн мирного обитателя на поверхности, если место свободно. */
    private static net.minecraft.entity.Entity spawnAmbientMob(ServerWorld world,
            net.minecraft.entity.EntityType<?> type, int x, int z, java.util.Random rnd) {
        BlockPos ground = new BlockPos(x, GROUND_TOP, z);
        if (world.getBlockState(ground).getBlock() != Blocks.WARPED_NYLIUM) return null;
        var entity = type.create(world);
        if (entity == null) return null;
        entity.refreshPositionAndAngles(x + 0.5, GROUND_TOP + 1, z + 0.5, rnd.nextFloat() * 360.0f, 0.0f);
        if (entity instanceof net.minecraft.entity.mob.MobEntity mob) mob.setPersistent();
        world.spawnEntity(entity);
        return entity;
    }

    private static void set(ServerWorld world, int x, int y, int z, net.minecraft.block.Block block) {
        world.setBlockState(new BlockPos(x, y, z), block.getDefaultState(), 2);
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
