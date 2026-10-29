package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.haunt.WorldEvents;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallSignBlock;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
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
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Закулисье: бесконечные жёлтые комнаты. Сырой ковролин, обои цвета старой бумаги, гудящие
 * лампы дневного света. Комнаты 6×6 соединены случайными проходами (лабиринт с лишними
 * дырами, есть большие залы). Часть комнат без света — там ждут Улыбаки. Время от времени
 * свет вокруг игроков гаснет, и тогда Улыбаки выходят на охоту. Выход — дверь с табличкой
 * "ВЫХОД" в самой дальней комнате; её слышно издалека.
 */
public final class BackroomsScenario extends DreamScenario {

    private static final int CELLS = 15;
    private static final int CELL = 6;
    private static final int HEIGHT = 4;
    private static final double DARK_CELL = 0.22;
    private static final int SMILERS = 5;

    private static final BlockState WALL = Blocks.SMOOTH_SANDSTONE.getDefaultState();
    private static final BlockState WALL_LOW = Blocks.SANDSTONE.getDefaultState();
    private static final BlockState CARPET = Blocks.YELLOW_TERRACOTTA.getDefaultState();
    private static final BlockState CARPET_WET = Blocks.BROWN_TERRACOTTA.getDefaultState();
    private static final BlockState CEILING = Blocks.MUSHROOM_STEM.getDefaultState();
    private static final BlockState LAMP = Blocks.SEA_LANTERN.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private BlockPos c;
    private boolean[][] wallEast;
    private boolean[][] wallSouth;
    private boolean[][] dark;
    private final List<BlockPos> lamps = new ArrayList<>();
    private BlockPos exitDoor;
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
        return CELLS * CELL;
    }

    @Override
    public BlockPos joinSpawn(DreamSession session, ServerPlayerEntity player) {
        return session.spawn();
    }

    private BlockPos at(int x, int y, int z) {
        return c.add(x, y, z);
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        c = session.origin.add(-CELLS * CELL / 2, 0, -CELLS * CELL / 2);
        generateLayout(session);

        int size = CELLS * CELL;
        for (int x = 0; x <= size; x++) {
            for (int z = 0; z <= size; z++) {
                setBulk(world, at(x, -1, z), session.random.nextInt(16) == 0 ? CARPET_WET : CARPET);
                setBulk(world, at(x, HEIGHT, z), CEILING);
                boolean wall = isWall(x, z);
                for (int y = 0; y < HEIGHT; y++) {
                    setBulk(world, at(x, y, z), wall ? (y == 0 ? WALL_LOW : WALL) : AIR);
                }
            }
        }
        for (int i = 0; i < CELLS; i++) {
            for (int j = 0; j < CELLS; j++) {
                if (dark[i][j]) continue;
                for (int[] off : new int[][]{{2, 2}, {4, 4}}) {
                    BlockPos lamp = at(i * CELL + off[0], HEIGHT, j * CELL + off[1]);
                    setBulk(world, lamp, LAMP);
                    lamps.add(lamp);
                }
            }
        }

        int mid = CELLS / 2;
        int[] exit = farthestCell(mid, mid);
        buildExit(world, exit[0], exit[1]);

        // Улыбаки в тёмных комнатах
        List<int[]> darkCells = new ArrayList<>();
        for (int i = 0; i < CELLS; i++) for (int j = 0; j < CELLS; j++) {
            if (dark[i][j] && Math.abs(i - mid) + Math.abs(j - mid) > 3) darkCells.add(new int[]{i, j});
        }
        Collections.shuffle(darkCells, session.random);
        for (int k = 0; k < Math.min(SMILERS, darkCells.size()); k++) {
            int[] cell = darkCells.get(k);
            session.spawnMob(ModEntities.SMILER, at(cell[0] * CELL + 3, 0, cell[1] * CELL + 3), 0f);
        }
        nextBlackout = session.now() + 600 + session.random.nextInt(400);
        nextScare = session.now() + 500;
        return at(mid * CELL + 3, 0, mid * CELL + 3);
    }

    /** Лабиринт поиском в глубину, потом убираем 45% лишних стен, чтобы были петли и залы. */
    private void generateLayout(DreamSession session) {
        wallEast = new boolean[CELLS][CELLS];
        wallSouth = new boolean[CELLS][CELLS];
        dark = new boolean[CELLS][CELLS];
        for (int i = 0; i < CELLS; i++) for (int j = 0; j < CELLS; j++) {
            wallEast[i][j] = true;
            wallSouth[i][j] = true;
            dark[i][j] = session.random.nextDouble() < DARK_CELL;
        }
        dark[CELLS / 2][CELLS / 2] = false;
        boolean[][] seen = new boolean[CELLS][CELLS];
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
                if (ni >= 0 && nj >= 0 && ni < CELLS && nj < CELLS && !seen[ni][nj]) next.add(new int[]{ni, nj, d[0], d[1]});
            }
            if (next.isEmpty()) {
                stack.pop();
                continue;
            }
            int[] n = next.get(session.random.nextInt(next.size()));
            openBetween(cur[0], cur[1], n[0], n[1]);
            seen[n[0]][n[1]] = true;
            stack.push(new int[]{n[0], n[1]});
        }
        for (int i = 0; i < CELLS; i++) for (int j = 0; j < CELLS; j++) {
            if (session.random.nextDouble() < 0.45) wallEast[i][j] = false;
            if (session.random.nextDouble() < 0.45) wallSouth[i][j] = false;
        }
    }

    private void openBetween(int i1, int j1, int i2, int j2) {
        if (i1 != i2) wallEast[Math.min(i1, i2)][j1] = false;
        else wallSouth[i1][Math.min(j1, j2)] = false;
    }

    /** Блок (x, z) — стена? Внешний контур, столбы на углах и сохранившиеся перегородки. */
    private boolean isWall(int x, int z) {
        int size = CELLS * CELL;
        if (x == 0 || z == 0 || x == size || z == size) return true;
        boolean onX = x % CELL == 0;
        boolean onZ = z % CELL == 0;
        if (onX && onZ) return true;
        if (onX) {
            int i = x / CELL - 1;
            int j = z / CELL;
            return wallEast[i][j] && (z % CELL) != 3; // в стене бывает дверной проём
        }
        if (onZ) {
            int i = x / CELL;
            int j = z / CELL - 1;
            return wallSouth[i][j] && (x % CELL) != 3;
        }
        return false;
    }

    private int[] farthestCell(int si, int sj) {
        int best = 0;
        int[] result = {si, sj};
        for (int i = 0; i < CELLS; i++) for (int j = 0; j < CELLS; j++) {
            int d = Math.abs(i - si) + Math.abs(j - sj);
            if (d > best) {
                best = d;
                result = new int[]{i, j};
            }
        }
        return result;
    }

    /** Дверь посреди комнаты в куске стены, над ней красная табличка, комната освещена. */
    private void buildExit(ServerWorld world, int i, int j) {
        dark[i][j] = false;
        int x0 = i * CELL + 3;
        int z0 = j * CELL + 3;
        for (int dx = -1; dx <= 1; dx++) {
            for (int y = 0; y < HEIGHT; y++) setBulk(world, at(x0 + dx, y, z0), WALL);
        }
        exitDoor = at(x0, 0, z0);
        setBulk(world, exitDoor, AIR);
        setBulk(world, exitDoor.up(), AIR);
        door(world, exitDoor, Blocks.DARK_OAK_DOOR, Direction.SOUTH, true);
        setBulk(world, exitDoor.north(), Blocks.WHITE_CONCRETE.getDefaultState());
        setBulk(world, exitDoor.north().up(), Blocks.WHITE_CONCRETE.getDefaultState());
        sign(world, exitDoor.up(2).south(), Blocks.DARK_OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.SOUTH),
                Text.empty(), tr("somnium.backrooms.exit").formatted(Formatting.RED, Formatting.BOLD));
        BlockPos lamp = at(x0, HEIGHT, z0 + 1);
        setBulk(world, lamp, LAMP);
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        int size = CELLS * CELL;
        return new BlockBox(c.getX() - 1, c.getY() - 2, c.getZ() - 1, c.getX() + size + 1, c.getY() + HEIGHT + 1, c.getZ() + size + 1);
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
            DreamHints.show(player, tr("somnium.hint.backrooms.start").formatted(Formatting.GRAY), 10);
        });
    }

    @Override
    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        // Табличку не редактировать, дверь не закрывать
        BlockPos pos = hit.getBlockPos();
        if (exitDoor != null && (pos.equals(exitDoor) || pos.equals(exitDoor.up()))) return ActionResult.SUCCESS;
        return session.world.getBlockState(pos).getBlock() instanceof net.minecraft.block.AbstractSignBlock
                ? ActionResult.SUCCESS : ActionResult.PASS;
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        if (now % 80 == 0) session.sound(exitDoor, ModSounds.EXIT_PORTAL, SoundCategory.AMBIENT, 4.0f, 0.7f);

        if (!blackout && now >= nextBlackout) {
            blackout = true;
            blackoutEnd = now + 100;
            for (BlockPos lamp : lamps) {
                ServerPlayerEntity near = session.nearestPlayer(lamp.toCenterPos());
                if (near != null && near.squaredDistanceTo(lamp.toCenterPos()) < 30 * 30) {
                    set(world, lamp, CEILING);
                    blackedOut.add(lamp);
                }
            }
            session.actionBar(tr("somnium.hint.backrooms.blackout").formatted(Formatting.DARK_GRAY));
            for (ServerPlayerEntity player : session.players()) {
                player.playSound(SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 1.0f, 0.5f);
            }
        } else if (blackout && now >= blackoutEnd) {
            blackout = false;
            for (BlockPos lamp : blackedOut) set(world, lamp, LAMP);
            blackedOut.clear();
            nextBlackout = now + 700 + session.random.nextInt(500);
        }

        // Мигающая лампа рядом с кем-нибудь
        if (!blackout && now % 30 == 0 && !lamps.isEmpty()) {
            ServerPlayerEntity player = session.randomPlayer();
            if (player != null) {
                BlockPos lamp = lamps.get(session.random.nextInt(lamps.size()));
                if (lamp.getSquaredDistance(player.getBlockPos()) < 15 * 15) {
                    set(world, lamp, CEILING);
                    Horror.later(6, () -> set(world, lamp, LAMP));
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
        if (session.now() % 40 == 0) player.playSound(SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.AMBIENT, 0.35f, 0.6f);
        if (exitDoor != null && player.squaredDistanceTo(exitDoor.toCenterPos()) < 1.2
                && DreamManager.requestWake(player, DreamOutcome.SURVIVED_OBJECTIVE)) {
            session.world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1, player.getZ(), 30, 0.5, 1, 0.5, 0.05);
        }
    }
}
