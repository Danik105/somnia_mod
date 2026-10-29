package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.haunt.WorldEvents;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModSounds;
import com.somnium.mod.entity.nightmare.CrawlerEntity;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.ChainBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.WallMountLocation;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Закулисье, два уровня.
 *
 * Уровень 0 — жёлтые комнаты: полосатые обои с подтёками, тёмный плинтус, потолок из
 * плиток (кое-где плитки нет — там чернота), гудящие лампы по две. Комнаты разные: пустые,
 * с колонной, с брошенной офисной мебелью, промокшие, с опущенным потолком. На стенах
 * розетки, решётки вентиляции, записки тех, кто был здесь раньше. В тёмных комнатах ждут
 * Улыбаки, свет время от времени гаснет. Дверь «ВЫХОД» в дальней комнате ведёт не наружу,
 * а ниже — на уровень 1.
 *
 * Уровень 1 — жилая зона: бетонные залы в два раза выше, колонны, лужи, трубы под
 * потолком, штабеля ящиков. Света меньше, в темноте Ползуны. В ящиках снабжения бывает
 * миндальная вода. Выход — дверь с зелёной табличкой.
 *
 * Миндальная вода (бутылки на полу и в ящиках) лечит и успокаивает.
 */
public final class BackroomsScenario extends DreamScenario {

    private static final int L1_DROP = 24;
    private static final String ALMOND_TAG = "somnium_almond_water";

    // ----- уровень 0 -----
    private static final BlockState WALLPAPER = Blocks.SMOOTH_SANDSTONE.getDefaultState();
    private static final BlockState WALLPAPER_SEAM = Blocks.CUT_SANDSTONE.getDefaultState();
    private static final BlockState WALLPAPER_STAIN = Blocks.SANDSTONE.getDefaultState();
    private static final BlockState BASEBOARD = Blocks.STRIPPED_OAK_WOOD.getDefaultState();
    private static final BlockState CARPET = Blocks.YELLOW_TERRACOTTA.getDefaultState();
    private static final BlockState CARPET_WET = Blocks.BROWN_TERRACOTTA.getDefaultState();
    private static final BlockState CARPET_WORN = Blocks.ORANGE_TERRACOTTA.getDefaultState();
    private static final BlockState TILE = Blocks.MUSHROOM_STEM.getDefaultState();
    private static final BlockState TILE_FRAME = Blocks.WHITE_TERRACOTTA.getDefaultState();
    private static final BlockState LAMP0 = Blocks.OCHRE_FROGLIGHT.getDefaultState();
    private static final BlockState DROP_CEILING = Blocks.SMOOTH_SANDSTONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP);

    // ----- уровень 1 -----
    private static final BlockState CONCRETE_LOW = Blocks.GRAY_CONCRETE.getDefaultState();
    private static final BlockState CONCRETE = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
    private static final BlockState CONCRETE_DIRTY = Blocks.LIGHT_GRAY_CONCRETE_POWDER.getDefaultState();
    private static final BlockState SLAB_FLOOR = Blocks.POLISHED_ANDESITE.getDefaultState();
    private static final BlockState CEIL1 = Blocks.GRAY_CONCRETE.getDefaultState();
    private static final BlockState LAMP1 = Blocks.SEA_LANTERN.getDefaultState();

    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    /** Одна сетка комнат: стены между ячейками, тёмные ячейки, типы комнат. */
    private static final class Grid {
        final int cells;
        final int cell;
        final int height;
        final BlockPos c;
        final boolean[][] wallEast;
        final boolean[][] wallSouth;
        final boolean[][] dark;
        final int[][] kind;

        Grid(BlockPos center, int cells, int cell, int height) {
            this.cells = cells;
            this.cell = cell;
            this.height = height;
            this.c = center.add(-cells * cell / 2, 0, -cells * cell / 2);
            wallEast = new boolean[cells][cells];
            wallSouth = new boolean[cells][cells];
            dark = new boolean[cells][cells];
            kind = new int[cells][cells];
        }

        int size() {
            return cells * cell;
        }

        BlockPos at(int x, int y, int z) {
            return c.add(x, y, z);
        }

        BlockPos cellCenter(int i, int j) {
            return at(i * cell + cell / 2, 0, j * cell + cell / 2);
        }

        /** Лабиринт поиском в глубину, потом убираем часть стен — получаются петли и залы. */
        void layout(Random random, double darkChance, double removeChance) {
            for (int i = 0; i < cells; i++) for (int j = 0; j < cells; j++) {
                wallEast[i][j] = true;
                wallSouth[i][j] = true;
                dark[i][j] = random.nextDouble() < darkChance;
            }
            boolean[][] seen = new boolean[cells][cells];
            ArrayDeque<int[]> stack = new ArrayDeque<>();
            stack.push(new int[]{0, 0});
            seen[0][0] = true;
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!stack.isEmpty()) {
                int[] cur = stack.peek();
                List<int[]> next = new ArrayList<>();
                for (int[] d : dirs) {
                    int ni = cur[0] + d[0];
                    int nj = cur[1] + d[1];
                    if (ni >= 0 && nj >= 0 && ni < cells && nj < cells && !seen[ni][nj]) next.add(new int[]{ni, nj});
                }
                if (next.isEmpty()) {
                    stack.pop();
                    continue;
                }
                int[] n = next.get(random.nextInt(next.size()));
                if (cur[0] != n[0]) wallEast[Math.min(cur[0], n[0])][cur[1]] = false;
                else wallSouth[cur[0]][Math.min(cur[1], n[1])] = false;
                seen[n[0]][n[1]] = true;
                stack.push(n);
            }
            for (int i = 0; i < cells; i++) for (int j = 0; j < cells; j++) {
                if (random.nextDouble() < removeChance) wallEast[i][j] = false;
                if (random.nextDouble() < removeChance) wallSouth[i][j] = false;
            }
        }

        /** Колонка (x, z) — стена? doorTop: до какой высоты в стене открыт дверной проём. */
        boolean isWall(int x, int z, int y, int doorFrom, int doorTo, int doorTop) {
            int size = size();
            if (x == 0 || z == 0 || x == size || z == size) return true;
            boolean onX = x % cell == 0;
            boolean onZ = z % cell == 0;
            if (onX && onZ) return true;
            if (onX) {
                int along = z % cell;
                return wallEast[x / cell - 1][z / cell] && !(along >= doorFrom && along <= doorTo && y < doorTop);
            }
            if (onZ) {
                int along = x % cell;
                return wallSouth[x / cell][z / cell - 1] && !(along >= doorFrom && along <= doorTo && y < doorTop);
            }
            return false;
        }

        int[] farthest(int si, int sj) {
            int best = -1;
            int[] result = {si, sj};
            for (int i = 0; i < cells; i++) for (int j = 0; j < cells; j++) {
                int d = Math.abs(i - si) + Math.abs(j - sj);
                if (d > best) {
                    best = d;
                    result = new int[]{i, j};
                }
            }
            return result;
        }
    }

    private Grid l0;
    private Grid l1;
    private BlockPos exit0;
    private BlockPos exit1;
    private BlockPos spawn1;
    private final Map<BlockPos, BlockState> lamps = new HashMap<>();
    private final Set<BlockPos> supplyCrates = new HashSet<>();
    private final List<BlockPos> darkCells1 = new ArrayList<>();
    private final Set<UUID> onLevel1 = new HashSet<>();
    private long nextBlackout;
    private long blackoutEnd;
    private boolean blackout;
    private final List<BlockPos> blackedOut = new ArrayList<>();
    private long nextScare;

    @Override
    public int originY(ServerWorld world, int surfaceY) {
        return 100;
    }

    @Override
    public int areaRadius() {
        return 110;
    }

    @Override
    public BlockPos joinSpawn(DreamSession session, ServerPlayerEntity player) {
        return session.spawn();
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        l0 = new Grid(session.origin, 15, 6, 4);
        l0.layout(session.random, 0.22, 0.45);
        l1 = new Grid(session.origin.down(L1_DROP), 11, 9, 6);
        l1.layout(session.random, 0.45, 0.35);
        int mid0 = l0.cells / 2;
        l0.dark[mid0][mid0] = false;
        BlockPos spawn = buildLevel0(session);
        buildLevel1(session);
        nextBlackout = session.now() + 600 + session.random.nextInt(400);
        nextScare = session.now() + 500;
        return spawn;
    }

    // =====================================================================
    // Уровень 0
    // =====================================================================

    private BlockPos buildLevel0(DreamSession session) {
        ServerWorld world = session.world;
        Random random = session.random;
        Grid g = l0;
        int size = g.size();
        for (int i = 0; i < g.cells; i++) for (int j = 0; j < g.cells; j++) {
            int roll = random.nextInt(100);
            g.kind[i][j] = roll < 58 ? 0 : roll < 70 ? 1 : roll < 81 ? 2 : roll < 89 ? 3 : 4;
        }
        g.kind[g.cells / 2][g.cells / 2] = 0;

        for (int x = 0; x <= size; x++) {
            for (int z = 0; z <= size; z++) {
                int i = Math.min(x / g.cell, g.cells - 1);
                int j = Math.min(z / g.cell, g.cells - 1);
                boolean wet = g.kind[i][j] == 3;
                BlockState floor = random.nextInt(wet ? 3 : 18) == 0 ? CARPET_WET : random.nextInt(40) == 0 ? CARPET_WORN : CARPET;
                setBulk(world, g.at(x, -1, z), floor);
                // Потолок из плиток в рамке; изредка плитки нет и видно черноту
                boolean frame = x % 2 == 0 || z % 2 == 0;
                boolean missing = !frame && random.nextInt(110) == 0;
                setBulk(world, g.at(x, g.height, z), missing ? AIR : frame ? TILE_FRAME : TILE);
                setBulk(world, g.at(x, g.height + 1, z), Blocks.BLACK_CONCRETE.getDefaultState());
                boolean seam = random.nextInt(9) == 0;
                for (int y = 0; y < g.height; y++) {
                    BlockState state = AIR;
                    if (g.isWall(x, z, y, 3, 3, 3)) {
                        if (y == 0) state = BASEBOARD;
                        else if (random.nextInt(22) == 0) state = WALLPAPER_STAIN;
                        else state = seam ? WALLPAPER_SEAM : WALLPAPER;
                    }
                    setBulk(world, g.at(x, y, z), state);
                }
            }
        }

        for (int i = 0; i < g.cells; i++) {
            for (int j = 0; j < g.cells; j++) {
                decorateRoom0(session, i, j);
            }
        }
        decorateWalls(session, g);

        // Бутылки миндальной воды на полу
        for (int k = 0; k < 7; k++) {
            int i = random.nextInt(g.cells);
            int j = random.nextInt(g.cells);
            BlockPos pos = g.cellCenter(i, j).add(random.nextInt(3) - 1, 0, random.nextInt(3) - 1);
            if (world.getBlockState(pos).isAir()) dropAlmond(session, pos);
        }

        int mid = g.cells / 2;
        int[] exit = g.farthest(mid, mid);
        buildExit0(world, exit[0], exit[1]);

        // Улыбаки в тёмных комнатах
        List<int[]> darkCells = new ArrayList<>();
        for (int i = 0; i < g.cells; i++) for (int j = 0; j < g.cells; j++) {
            if (g.dark[i][j] && Math.abs(i - mid) + Math.abs(j - mid) > 3) darkCells.add(new int[]{i, j});
        }
        Collections.shuffle(darkCells, random);
        for (int k = 0; k < Math.min(6, darkCells.size()); k++) {
            session.spawnMob(ModEntities.SMILER, g.cellCenter(darkCells.get(k)[0], darkCells.get(k)[1]), 0f);
        }
        return g.cellCenter(mid, mid);
    }

    private void decorateRoom0(DreamSession session, int i, int j) {
        ServerWorld world = session.world;
        Random random = session.random;
        Grid g = l0;
        int x0 = i * g.cell;
        int z0 = j * g.cell;
        switch (g.kind[i][j]) {
            case 1 -> {
                // Колонна посреди комнаты
                for (int y = 0; y < g.height; y++) {
                    setBulk(world, g.at(x0 + 3, y, z0 + 3), y == 0 ? BASEBOARD : WALLPAPER);
                }
            }
            case 2 -> {
                // Офис, из которого все ушли: стол, перевёрнутый стул, шкаф, мусор
                BlockPos desk = g.at(x0 + 2, 0, z0 + 2);
                setBulk(world, desk, Blocks.OAK_FENCE.getDefaultState());
                setBulk(world, desk.east(), Blocks.OAK_FENCE.getDefaultState());
                setBulk(world, desk.up(), Blocks.OAK_PRESSURE_PLATE.getDefaultState());
                setBulk(world, desk.east().up(), Blocks.OAK_PRESSURE_PLATE.getDefaultState());
                setBulk(world, desk.south(), Blocks.OAK_STAIRS.getDefaultState()
                        .with(StairsBlock.FACING, random.nextBoolean() ? Direction.SOUTH : Direction.EAST));
                if (random.nextBoolean()) setBulk(world, g.at(x0 + 4, 0, z0 + 4), Blocks.BARREL.getDefaultState());
                if (random.nextInt(3) == 0) {
                    setBulk(world, g.at(x0 + 4, 0, z0 + 1), Blocks.BOOKSHELF.getDefaultState());
                    setBulk(world, g.at(x0 + 4, 1, z0 + 1), Blocks.BOOKSHELF.getDefaultState());
                }
                if (random.nextBoolean()) setBulk(world, g.at(x0 + 1, 0, z0 + 4), Blocks.WHITE_CARPET.getDefaultState());
            }
            case 3 -> {
                // Промокшая комната: потолок протекает
                setBulk(world, g.at(x0 + 3, g.height, z0 + 3), Blocks.BROWN_MUSHROOM_BLOCK.getDefaultState());
            }
            case 4 -> {
                // Опущенный потолок: на блок ниже, давит
                for (int x = 1; x < g.cell; x++) {
                    for (int z = 1; z < g.cell; z++) setBulk(world, g.at(x0 + x, g.height - 1, z0 + z), DROP_CEILING);
                }
            }
            default -> {
                if (random.nextInt(12) == 0) setBulk(world, g.at(x0 + 1 + random.nextInt(4), 0, z0 + 1 + random.nextInt(4)),
                        Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.fromHorizontal(random.nextInt(4))));
            }
        }
        if (g.dark[i][j]) return;
        // Лампы дневного света по две
        boolean alongX = random.nextBoolean();
        for (int[] off : new int[][]{{2, 2}, {4, 4}}) {
            int y = g.kind[i][j] == 4 ? g.height - 1 : g.height;
            BlockPos a = g.at(x0 + off[0], y, z0 + off[1]);
            BlockPos b = alongX ? a.west() : a.north();
            for (BlockPos lamp : new BlockPos[]{a, b}) {
                setBulk(world, lamp, LAMP0);
                lamps.put(lamp, LAMP0);
            }
        }
    }

    /** Розетки, решётки вентиляции и записки на стенах (общий проход для обоих уровней). */
    private void decorateWalls(DreamSession session, Grid g) {
        ServerWorld world = session.world;
        Random random = session.random;
        int notes = g == l0 ? 14 : 8;
        int size = g.size();
        for (int x = 1; x < size; x++) {
            for (int z = 1; z < size; z++) {
                BlockPos floor = g.at(x, 0, z);
                if (!world.getBlockState(floor).isAir()) continue;
                for (Direction d : SIDES) {
                    BlockPos wall = floor.offset(d);
                    if (!isWallBlock(world.getBlockState(wall.up()))) continue;
                    Direction out = d.getOpposite();
                    int roll = random.nextInt(260);
                    if (roll < 3) {
                        // Розетка
                        setBulk(world, floor.up(), Blocks.POLISHED_BLACKSTONE_BUTTON.getDefaultState()
                                .with(ButtonBlock.FACE, WallMountLocation.WALL).with(ButtonBlock.FACING, out));
                    } else if (roll < 5 && world.getBlockState(floor.up(g.height - 1)).isAir()) {
                        // Решётка вентиляции под потолком
                        setBulk(world, floor.up(g.height - 1), Blocks.IRON_TRAPDOOR.getDefaultState()
                                .with(TrapdoorBlock.FACING, out).with(TrapdoorBlock.OPEN, true));
                    } else if (roll < 7 && notes > 0 && world.getBlockState(floor.up(2)).isAir()) {
                        notes--;
                        sign(world, floor.up(2), (g == l0 ? Blocks.BIRCH_WALL_SIGN : Blocks.SPRUCE_WALL_SIGN).getDefaultState()
                                        .with(WallSignBlock.FACING, out),
                                tr("somnium.backrooms.note." + (1 + random.nextInt(14))).formatted(
                                        random.nextInt(4) == 0 ? Formatting.DARK_RED : Formatting.BLACK));
                    }
                    break;
                }
            }
        }
    }

    private static boolean isWallBlock(BlockState state) {
        return state.isOf(Blocks.SMOOTH_SANDSTONE) || state.isOf(Blocks.CUT_SANDSTONE) || state.isOf(Blocks.SANDSTONE)
                || state.isOf(Blocks.LIGHT_GRAY_CONCRETE) || state.isOf(Blocks.GRAY_CONCRETE);
    }

    /** Дверь посреди комнаты в куске стены, над ней красная табличка, комната освещена. */
    private void buildExit0(ServerWorld world, int i, int j) {
        Grid g = l0;
        g.dark[i][j] = false;
        int x0 = i * g.cell + 3;
        int z0 = j * g.cell + 3;
        fill(world, g.at(x0 - 2, 0, z0 - 2), g.at(x0 + 2, g.height - 1, z0 + 2), AIR);
        for (int dx = -1; dx <= 1; dx++) {
            for (int y = 0; y < g.height; y++) setBulk(world, g.at(x0 + dx, y, z0), y == 0 ? BASEBOARD : WALLPAPER);
        }
        exit0 = g.at(x0, 0, z0);
        door(world, exit0, Blocks.DARK_OAK_DOOR, Direction.SOUTH, true);
        setBulk(world, exit0.north(), Blocks.BLACK_CONCRETE.getDefaultState());
        setBulk(world, exit0.north().up(), Blocks.BLACK_CONCRETE.getDefaultState());
        sign(world, exit0.up(2).south(), Blocks.DARK_OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.SOUTH),
                Text.empty(), tr("somnium.backrooms.exit").formatted(Formatting.RED, Formatting.BOLD));
        BlockPos lamp = g.at(x0, g.height, z0 + 1);
        setBulk(world, lamp, LAMP0);
        lamps.put(lamp, LAMP0);
    }

    // =====================================================================
    // Уровень 1
    // =====================================================================

    private void buildLevel1(DreamSession session) {
        ServerWorld world = session.world;
        Random random = session.random;
        Grid g = l1;
        int size = g.size();
        for (int x = 0; x <= size; x++) {
            for (int z = 0; z <= size; z++) {
                // Пол из плит, лужи (вода в выемке, течь ей некуда), грязные пятна
                boolean puddle = x > 0 && z > 0 && x < size && z < size && random.nextInt(70) == 0;
                setBulk(world, g.at(x, -2, z), CONCRETE_LOW);
                setBulk(world, g.at(x, -1, z), puddle ? Blocks.WATER.getDefaultState()
                        : random.nextInt(25) == 0 ? CONCRETE_DIRTY : SLAB_FLOOR);
                setBulk(world, g.at(x, g.height, z), CEIL1);
                for (int y = 0; y < g.height; y++) {
                    BlockState state = AIR;
                    if (g.isWall(x, z, y, 3, 5, 4)) state = y < 2 ? CONCRETE_LOW : CONCRETE;
                    setBulk(world, g.at(x, y, z), state);
                }
                // Трубы вдоль потолка
                if (z % g.cell == 2 && !g.isWall(x, z, g.height - 1, 3, 5, 4)) {
                    setBulk(world, g.at(x, g.height - 1, z), Blocks.CHAIN.getDefaultState().with(ChainBlock.AXIS, Direction.Axis.X));
                }
            }
        }
        for (int i = 0; i < g.cells; i++) {
            for (int j = 0; j < g.cells; j++) {
                int x0 = i * g.cell;
                int z0 = j * g.cell;
                BlockPos center = g.cellCenter(i, j);
                int roll = random.nextInt(10);
                if (roll < 3) {
                    // Колонна 2×2 с жёлто-чёрной полосой внизу
                    for (int y = 0; y < g.height; y++) {
                        BlockState state = y == 0 ? (y + i + j) % 2 == 0 ? Blocks.YELLOW_CONCRETE.getDefaultState()
                                : Blocks.BLACK_CONCRETE.getDefaultState() : CONCRETE;
                        fill(world, center.add(0, y, 0), center.add(1, y, 1), state);
                    }
                } else if (roll < 6) {
                    // Штабель ящиков, один из них — ящик снабжения
                    int cx = x0 + 2 + random.nextInt(3);
                    int cz = z0 + 2 + random.nextInt(3);
                    for (int k = 0; k < 4; k++) {
                        BlockPos crate = g.at(cx + (k % 2), 0, cz + (k / 2));
                        setBulk(world, crate, random.nextBoolean() ? Blocks.SPRUCE_PLANKS.getDefaultState()
                                : Blocks.STRIPPED_SPRUCE_WOOD.getDefaultState());
                        if (random.nextBoolean()) setBulk(world, crate.up(), Blocks.SPRUCE_PLANKS.getDefaultState());
                    }
                    if (random.nextInt(3) == 0) {
                        BlockPos supply = g.at(cx + 2, 0, cz);
                        setBulk(world, supply, Blocks.BARREL.getDefaultState());
                        supplyCrates.add(supply);
                    }
                } else if (roll == 6) {
                    // Строительные леса до потолка
                    fill(world, g.at(x0 + 1, 0, z0 + 1), g.at(x0 + 1, g.height - 2, z0 + 1), Blocks.SCAFFOLDING.getDefaultState());
                }
                if (g.dark[i][j]) {
                    darkCells1.add(center);
                } else {
                    BlockPos lamp = center.up(g.height);
                    setBulk(world, lamp, LAMP1);
                    lamps.put(lamp, LAMP1);
                    // Лампа висит на цепи — тень от неё качается в голове
                    setBulk(world, lamp.down().east(2), Blocks.CHAIN.getDefaultState());
                    setBulk(world, lamp.down(2).east(2), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
                }
            }
        }
        decorateWalls(session, g);

        int mid = g.cells / 2;
        g.dark[mid][mid] = false;
        spawn1 = g.cellCenter(mid, mid).add(-2, 0, -2);
        fill(world, spawn1, spawn1.up(2), AIR);
        int[] exit = g.farthest(mid, mid);
        buildExit1(world, exit[0], exit[1]);

        // Ползуны в темноте и пара Улыбак
        Collections.shuffle(darkCells1, random);
        for (int k = 0; k < Math.min(5, darkCells1.size()); k++) {
            if (darkCells1.get(k).getSquaredDistance(spawn1) > 20 * 20) {
                session.spawnMob(k < 2 ? ModEntities.SMILER : ModEntities.CRAWLER, darkCells1.get(k).add(3, 0, 3), 0f);
            }
        }
    }

    /** Дверь на лестничную клетку: стальная рама, зелёная табличка, полосы на полу. */
    private void buildExit1(ServerWorld world, int i, int j) {
        Grid g = l1;
        BlockPos center = g.cellCenter(i, j);
        fill(world, center.add(-3, 0, -3), center.add(3, g.height - 1, 3), AIR);
        fill(world, center.add(-2, 0, 0), center.add(2, 3, 4), Blocks.IRON_BLOCK.getDefaultState());
        fill(world, center.add(-1, 0, 1), center.add(1, 2, 3), AIR);
        exit1 = center.add(0, 0, 1);
        setBulk(world, center, AIR);
        setBulk(world, center.up(), AIR);
        setBulk(world, center.add(0, 2, 2), Blocks.SEA_LANTERN.getDefaultState());
        sign(world, center.add(0, 2, -1), Blocks.SPRUCE_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.NORTH),
                Text.empty(), tr("somnium.backrooms.exit").formatted(Formatting.GREEN, Formatting.BOLD));
        for (int x = -2; x <= 2; x++) {
            setBulk(world, center.add(x, -1, -1), (x % 2 == 0) ? Blocks.YELLOW_CONCRETE.getDefaultState() : Blocks.BLACK_CONCRETE.getDefaultState());
        }
        BlockPos lamp = center.add(0, g.height, -2);
        setBulk(world, lamp, LAMP1);
        lamps.put(lamp, LAMP1);
    }

    // =====================================================================
    // Миндальная вода
    // =====================================================================

    private static ItemStack almondWater() {
        ItemStack stack = PotionUtil.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        stack.setCustomName(tr("somnium.item.almond_water").formatted(Formatting.YELLOW));
        stack.getOrCreateNbt().putBoolean(ALMOND_TAG, true);
        return stack;
    }

    private static void dropAlmond(DreamSession session, BlockPos pos) {
        ItemEntity item = new ItemEntity(session.world, pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5, almondWater(), 0, 0, 0);
        item.setPickupDelay(0);
        item.setNeverDespawn();
        session.add(item);
    }

    @Override
    public void onFinishUsing(DreamSession session, ServerPlayerEntity player, ItemStack stack) {
        if (stack.getNbt() == null || !stack.getNbt().getBoolean(ALMOND_TAG)) return;
        player.heal(6.0f);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 0));
        SanityManager.add(player, 5.0f);
        player.sendMessage(tr("somnium.hint.backrooms.almond").formatted(Formatting.YELLOW), true);
    }

    // =====================================================================
    // Сон
    // =====================================================================

    @Override
    public BlockBox buildBounds(DreamSession session) {
        BlockBox a = new BlockBox(l0.c.getX() - 1, l0.c.getY() - 2, l0.c.getZ() - 1,
                l0.c.getX() + l0.size() + 1, l0.c.getY() + l0.height + 2, l0.c.getZ() + l0.size() + 1);
        BlockBox b = new BlockBox(l1.c.getX() - 1, l1.c.getY() - 3, l1.c.getZ() - 1,
                l1.c.getX() + l1.size() + 1, l1.c.getY() + l1.height + 1, l1.c.getZ() + l1.size() + 1);
        return a.encompass(b);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        // Катсцена: провалился сквозь пол реальности
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 50, 0, false, false, false));
        Horror.letterbox(player, true);
        Horror.freeze(player, 60);
        Horror.sound(player, SoundEvents.BLOCK_BEACON_AMBIENT, player.getEyePos(), 1.5f, 0.6f);
        DreamFx.title(player, Text.empty(), tr("somnium.backrooms.noclip").formatted(Formatting.GRAY), 10, 40, 10);
        Horror.later(60, () -> {
            Horror.letterbox(player, false);
            DreamFx.title(player, tr("somnium.backrooms.level0").formatted(Formatting.YELLOW), tr("somnium.backrooms.level0.sub")
                    .formatted(Formatting.GRAY), 10, 50, 20);
            DreamHints.show(player, tr("somnium.hint.backrooms.start").formatted(Formatting.GRAY), 10);
        });
    }

    @Override
    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        if (exit0 != null && (pos.equals(exit0) || pos.equals(exit0.up()))) return ActionResult.SUCCESS;
        if (supplyCrates.remove(pos)) {
            session.sound(pos, SoundEvents.BLOCK_BARREL_OPEN, SoundCategory.BLOCKS, 1.0f, 0.8f);
            if (session.random.nextInt(3) != 0) {
                dropAlmond(session, pos.up());
                player.sendMessage(tr("somnium.hint.backrooms.supply").formatted(Formatting.YELLOW), true);
            } else {
                player.sendMessage(tr("somnium.hint.backrooms.supply_empty").formatted(Formatting.GRAY), true);
            }
            return ActionResult.SUCCESS;
        }
        BlockState state = session.world.getBlockState(pos);
        return state.getBlock() instanceof AbstractSignBlock || state.isOf(Blocks.BARREL) || state.getBlock() instanceof ButtonBlock
                || state.isOf(Blocks.IRON_TRAPDOOR) ? ActionResult.SUCCESS : ActionResult.PASS;
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        // Звон выхода: раз в 12 секунд, каждому только дверь его уровня, тише издалека
        if (now % 240 == 0) {
            for (ServerPlayerEntity player : session.players()) {
                boolean deep = onLevel1.contains(player.getUuid());
                BlockPos exit = deep ? exit1 : exit0;
                if (exit == null) continue;
                double dist = Math.sqrt(player.getBlockPos().getSquaredDistance(exit));
                if (dist > 60) continue;
                float volume = (float) Math.max(0.15, 1.0 - dist / 60.0);
                DreamFx.soundTo(player, ModSounds.EXIT_PORTAL, SoundCategory.AMBIENT,
                        exit.getX() + 0.5, exit.getY() + 1, exit.getZ() + 0.5, volume, deep ? 0.9f : 0.7f);
            }
        }

        if (!blackout && now >= nextBlackout) {
            blackout = true;
            blackoutEnd = now + 100 + session.random.nextInt(60);
            for (BlockPos lamp : lamps.keySet()) {
                ServerPlayerEntity near = session.nearestPlayer(lamp.toCenterPos());
                if (near != null && near.squaredDistanceTo(lamp.toCenterPos()) < 30 * 30) {
                    set(world, lamp, lamp.getY() < l0.c.getY() ? CEIL1 : TILE);
                    blackedOut.add(lamp);
                }
            }
            session.actionBar(tr("somnium.hint.backrooms.blackout").formatted(Formatting.DARK_GRAY));
            for (ServerPlayerEntity player : session.players()) {
                player.playSound(SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 1.0f, 0.5f);
                player.playSound(SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.BLOCKS, 0.6f, 0.5f);
            }
        } else if (blackout && now >= blackoutEnd) {
            blackout = false;
            for (BlockPos lamp : blackedOut) set(world, lamp, lamps.get(lamp));
            blackedOut.clear();
            nextBlackout = now + 700 + session.random.nextInt(500);
            for (ServerPlayerEntity player : session.players()) {
                player.playSound(SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 0.4f, 1.6f);
            }
        }

        // Мигающая лампа рядом с кем-нибудь, с треском
        if (!blackout && now % 30 == 0 && !lamps.isEmpty()) {
            ServerPlayerEntity player = session.randomPlayer();
            if (player != null) {
                for (int attempt = 0; attempt < 6; attempt++) {
                    BlockPos lamp = new ArrayList<>(lamps.keySet()).get(session.random.nextInt(lamps.size()));
                    if (lamp.getSquaredDistance(player.getBlockPos()) < 15 * 15) {
                        BlockState lit = lamps.get(lamp);
                        set(world, lamp, lamp.getY() < l0.c.getY() ? CEIL1 : TILE);
                        Horror.later(4 + session.random.nextInt(6), () -> set(world, lamp, lit));
                        DreamFx.soundTo(player, SoundEvents.BLOCK_BEEHIVE_WORK, SoundCategory.BLOCKS,
                                lamp.getX() + 0.5, lamp.getY(), lamp.getZ() + 0.5, 0.6f, 1.8f);
                        break;
                    }
                }
            }
        }

        // Протечки: капли с потолка в промокших комнатах
        if (now % 20 == 0) {
            for (ServerPlayerEntity player : session.players()) {
                if (onLevel1.contains(player.getUuid())) continue;
                world.spawnParticles(player, ParticleTypes.DRIPPING_WATER, false, player.getX(), l0.c.getY() + l0.height - 0.05,
                        player.getZ(), 3, 4, 0, 4, 0.0);
            }
        }

        // Ползуны уровня 1 живут минуту — подселяем новых в темноту
        if (now % 300 == 0 && !onLevel1.isEmpty()) {
            ServerPlayerEntity player = session.randomPlayer();
            if (player != null && onLevel1.contains(player.getUuid())
                    && world.getEntitiesByClass(CrawlerEntity.class, new Box(player.getBlockPos()).expand(40), session::owns).size() < 3) {
                for (BlockPos cell : darkCells1) {
                    double d = cell.getSquaredDistance(player.getBlockPos());
                    if (d > 14 * 14 && d < 30 * 30) {
                        session.spawnMob(ModEntities.CRAWLER, cell.add(3, 0, 3), 0f);
                        break;
                    }
                }
            }
        }

        // Страшные моменты: шаги, шёпот, кто-то смотрит из темноты
        if (now >= nextScare) {
            nextScare = now + 500 + session.random.nextInt(400);
            String[] scares = {"footsteps", "whisper", "eyes_in_dark", "breathing", "far_scream", "laughter", "heartbeat"};
            for (ServerPlayerEntity player : session.players()) {
                WorldEvents.fire(scares[session.random.nextInt(scares.length)], player);
            }
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        long now = session.now();
        boolean deep = onLevel1.contains(player.getUuid());
        if (now % 160 == 0) {
            // Гул ламп наверху, гул труб внизу: редко и тихо, чтобы не сливался в один звук
            if (deep) player.playSound(SoundEvents.BLOCK_PORTAL_AMBIENT, SoundCategory.AMBIENT, 0.1f, 0.5f);
            else player.playSound(SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.AMBIENT, 0.2f, 0.55f + session.random.nextFloat() * 0.1f);
        }
        if (!deep && exit0 != null && player.squaredDistanceTo(exit0.toCenterPos()) < 1.2) {
            descend(session, player);
            return;
        }
        if (deep) reachedExit(session, player, exit1.add(0, 0, 1), 1.5);
    }

    /** Дверь «ВЫХОД» уровня 0 ведёт глубже: провал на уровень 1. */
    private void descend(DreamSession session, ServerPlayerEntity player) {
        onLevel1.add(player.getUuid());
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 40, 0, false, false, false));
        player.teleport(session.world, spawn1.getX() + 0.5, spawn1.getY(), spawn1.getZ() + 0.5, player.getYaw(), 0f);
        player.fallDistance = 0;
        Horror.freeze(player, 30);
        Horror.sound(player, SoundEvents.BLOCK_IRON_DOOR_CLOSE, player.getEyePos(), 1.0f, 0.5f);
        Horror.sound(player, SoundEvents.AMBIENT_CAVE.value(), player.getEyePos(), 1.0f, 0.5f);
        DreamFx.title(player, tr("somnium.backrooms.level1").formatted(Formatting.GRAY), tr("somnium.backrooms.level1.sub")
                .formatted(Formatting.DARK_GRAY), 10, 50, 20);
        Horror.later(40, () -> DreamHints.show(player, tr("somnium.hint.backrooms.level1").formatted(Formatting.GRAY), 10));
    }

    @Override
    public void onLeave(DreamSession session, ServerPlayerEntity player) {
        onLevel1.remove(player.getUuid());
    }
}
