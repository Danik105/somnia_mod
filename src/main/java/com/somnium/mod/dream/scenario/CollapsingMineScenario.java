package com.somnium.mod.dream.scenario;

import com.somnium.mod.SomniumMod;
import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Обрушающаяся шахта: процедурный лабиринт 20×20 ячеек. Выход — дверь в самой дальней
 * ячейке, Компас Выхода указывает на неё. Каждые 5 секунд за спиной игрока одна стена
 * обрушается (новый короткий путь), другая зарастает — исходный путь к двери остаётся всегда.
 */
public final class CollapsingMineScenario extends DreamScenario {

    private static final int MAZE_CELLS = 20;
    private static final int CELL_SIZE = 3;
    private static final int WALL_HEIGHT = 4;
    private static final int SHIFT_INTERVAL = 100;
    private static final int SHIFT_RADIUS = 8;
    private static final int MAX_OPEN_PASSAGES = 6;

    private MazeGenerator maze;
    private BlockPos mazeOrigin;
    private BlockPos door;
    private final List<BlockPos> opened = new ArrayList<>();
    private long nextShift;
    private long nextScream;

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        Random rnd = session.random;
        maze = new MazeGenerator(MAZE_CELLS, MAZE_CELLS, CELL_SIZE, rnd);
        maze.generate();
        int total = (MAZE_CELLS * 2 + 1) * CELL_SIZE;
        mazeOrigin = session.origin.add(-total / 2, 0, -total / 2);
        buildMaze(world, rnd);

        BlockPos spawn = maze.findSpawnPosition(mazeOrigin, 0);
        door = maze.findExitPosition(spawn, mazeOrigin, 0);
        WakeDoor.build(session, door);

        for (Identifier id : session.type.monsters()) {
            for (BlockPos pos : maze.findMonsterSpawnPositions(mazeOrigin, 0, 4)) {
                if (pos.getSquaredDistance(spawn) > 100) session.spawnMob(id, pos, rnd.nextFloat() * 360f);
            }
        }
        // Гарантированная встреча с Кричащим Шахтёром недалеко от старта
        for (int attempt = 0; attempt < 32; attempt++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            int dist = 10 + rnd.nextInt(7);
            BlockPos near = spawn.add((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist));
            if (world.getBlockState(near).isAir() && world.getBlockState(near.up()).isAir()
                    && !world.getBlockState(near.down()).isAir()) {
                session.spawnMob(SomniumMod.id("screaming_miner"), near, rnd.nextFloat() * 360f);
                break;
            }
        }
        nextShift = session.now() + SHIFT_INTERVAL;
        nextScream = session.now() + 300;
        return spawn;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        int total = (MAZE_CELLS * 2 + 1) * CELL_SIZE;
        return new BlockBox(mazeOrigin.getX() - 1, mazeOrigin.getY() - 1, mazeOrigin.getZ() - 1,
                mazeOrigin.getX() + total, mazeOrigin.getY() + WALL_HEIGHT + 3, mazeOrigin.getZ() + total);
    }

    @Override
    public int areaRadius() {
        return (MAZE_CELLS * 2 + 1) * CELL_SIZE / 2 + 8;
    }

    @Override
    public void applyEffects(ServerPlayerEntity player, int duration) {
        DreamFx.effect(player, StatusEffects.BLINDNESS, duration, 0);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        giveCompass(player, session);
        DreamHints.show(player, tr("somnium.hint.mine.compass").formatted(Formatting.AQUA), 9);
    }

    @Override
    public void tick(DreamSession session) {
        long now = session.now();
        if (now >= nextScream) {
            nextScream = now + 500 + session.random.nextInt(300);
            for (ServerPlayerEntity player : session.players()) {
                player.playSound(ModSounds.MINER_SCREAM, SoundCategory.AMBIENT, 0.5f, 0.7f);
            }
        }
        if (now >= nextShift) {
            nextShift = now + SHIFT_INTERVAL;
            ServerPlayerEntity player = session.randomPlayer();
            if (player != null) shiftWalls(session, player);
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (WakeDoor.isPassing(session.world, door, player)
                && DreamManager.requestWake(player, DreamOutcome.SURVIVED_OBJECTIVE)) {
            player.playSound(ModSounds.EXIT_PORTAL, SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
    }

    /** Лоудстоун-компас, привязанный к двери выхода (в яви исчезает вместе с вещами сна). */
    private void giveCompass(ServerPlayerEntity player, DreamSession session) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        NbtCompound nbt = compass.getOrCreateNbt();
        nbt.putBoolean("LodestoneTracked", false);
        nbt.putString("LodestoneDimension", session.world.getRegistryKey().getValue().toString());
        NbtCompound pos = new NbtCompound();
        pos.putInt("X", door.getX());
        pos.putInt("Y", door.getY());
        pos.putInt("Z", door.getZ());
        nbt.put("LodestonePos", pos);
        compass.setCustomName(tr("somnium.item.exit_compass").formatted(Formatting.AQUA));
        player.getInventory().offerOrDrop(compass);
    }

    private void buildMaze(ServerWorld world, Random rnd) {
        int grid = MAZE_CELLS * 2 + 1;
        int size = grid * CELL_SIZE;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                setBulk(world, mazeOrigin.add(x, -1, z), Blocks.STONE_BRICKS.getDefaultState());
                setBulk(world, mazeOrigin.add(x, WALL_HEIGHT, z), Blocks.DEEPSLATE.getDefaultState());
                for (int y = 0; y < WALL_HEIGHT; y++) {
                    setBulk(world, mazeOrigin.add(x, y, z), Blocks.AIR.getDefaultState());
                }
            }
        }
        for (int gx = 0; gx < grid; gx++) {
            for (int gz = 0; gz < grid; gz++) {
                if (maze.isWall(gx, gz)) {
                    fillWall(world, mazeOrigin.add(gx * CELL_SIZE, 0, gz * CELL_SIZE), rnd);
                } else if (rnd.nextFloat() < 0.15f) {
                    placeTorch(world, gx, gz);
                }
            }
        }
    }

    private void fillWall(ServerWorld world, BlockPos base, Random rnd) {
        for (int x = 0; x < CELL_SIZE; x++) {
            for (int z = 0; z < CELL_SIZE; z++) {
                for (int y = 0; y < WALL_HEIGHT; y++) {
                    Block block = (y == 0 || rnd.nextFloat() < 0.8f) ? Blocks.DEEPSLATE : Blocks.COAL_ORE;
                    setBulk(world, base.add(x, y, z), block.getDefaultState());
                }
            }
        }
    }

    /** Факел на стене прохода — только к ортогональному соседу-стене, с правильной ориентацией. */
    private void placeTorch(ServerWorld world, int gx, int gz) {
        int cx = gx * CELL_SIZE + CELL_SIZE / 2;
        int cz = gz * CELL_SIZE + CELL_SIZE / 2;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (maze.isWall(gx + dir.getOffsetX(), gz + dir.getOffsetZ())) {
                setBulk(world, mazeOrigin.add(cx + dir.getOffsetX(), 1, cz + dir.getOffsetZ()),
                        Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, dir.getOpposite()));
                return;
            }
        }
    }

    /** Одна стена за спиной игрока обрушается, один из ранее открытых проходов зарастает. */
    private void shiftWalls(DreamSession session, ServerPlayerEntity player) {
        ServerWorld world = session.world;
        int grid = MAZE_CELLS * 2 + 1;
        double yawRad = Math.toRadians(player.getYaw());
        double lookX = -Math.sin(yawRad);
        double lookZ = Math.cos(yawRad);
        int pgx = Math.floorDiv(player.getBlockX() - mazeOrigin.getX(), CELL_SIZE);
        int pgz = Math.floorDiv(player.getBlockZ() - mazeOrigin.getZ(), CELL_SIZE);

        List<int[]> candidates = new ArrayList<>();
        for (int gx = Math.max(1, pgx - SHIFT_RADIUS); gx <= Math.min(grid - 2, pgx + SHIFT_RADIUS); gx++) {
            for (int gz = Math.max(1, pgz - SHIFT_RADIUS); gz <= Math.min(grid - 2, pgz + SHIFT_RADIUS); gz++) {
                if (!maze.isWall(gx, gz)) continue;
                boolean splitsX = !maze.isWall(gx - 1, gz) && !maze.isWall(gx + 1, gz);
                boolean splitsZ = !maze.isWall(gx, gz - 1) && !maze.isWall(gx, gz + 1);
                if (!splitsX && !splitsZ) continue;
                if (isInView(player, lookX, lookZ, gx, gz)) continue;
                BlockPos base = mazeOrigin.add(gx * CELL_SIZE, 0, gz * CELL_SIZE);
                if (opened.contains(base)) continue;
                candidates.add(new int[]{gx, gz});
            }
        }
        if (!candidates.isEmpty()) {
            int[] pick = candidates.get(session.random.nextInt(candidates.size()));
            BlockPos base = mazeOrigin.add(pick[0] * CELL_SIZE, 0, pick[1] * CELL_SIZE);
            for (int x = 0; x < CELL_SIZE; x++) {
                for (int z = 0; z < CELL_SIZE; z++) {
                    for (int y = 0; y < WALL_HEIGHT - 1; y++) {
                        setBulk(world, base.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                }
            }
            opened.add(base);
            session.sound(base, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.BLOCKS, 1.0f, 0.5f);
            world.spawnParticles(ParticleTypes.EXPLOSION, base.getX() + 1.5, base.getY() + 1.5, base.getZ() + 1.5,
                    2, 1.0, 1.0, 1.0, 0.0);
        }

        for (int i = 0; i < opened.size(); i++) {
            BlockPos base = opened.get(i);
            int gx = (base.getX() - mazeOrigin.getX()) / CELL_SIZE;
            int gz = (base.getZ() - mazeOrigin.getZ()) / CELL_SIZE;
            if (isNearAnyPlayer(session, gx, gz)) continue;
            if (opened.size() <= MAX_OPEN_PASSAGES && isInView(player, lookX, lookZ, gx, gz)) continue;
            fillWall(world, base, session.random);
            opened.remove(i);
            session.sound(base, SoundEvents.BLOCK_DEEPSLATE_PLACE, SoundCategory.BLOCKS, 1.0f, 0.5f);
            break;
        }
    }

    private boolean isNearAnyPlayer(DreamSession session, int gx, int gz) {
        for (ServerPlayerEntity player : session.players()) {
            int pgx = Math.floorDiv(player.getBlockX() - mazeOrigin.getX(), CELL_SIZE);
            int pgz = Math.floorDiv(player.getBlockZ() - mazeOrigin.getZ(), CELL_SIZE);
            if (Math.abs(gx - pgx) <= 1 && Math.abs(gz - pgz) <= 1) return true;
        }
        return false;
    }

    /** В поле зрения (~100° от взгляда) или вплотную к игроку — такие места не трогаем. */
    private boolean isInView(ServerPlayerEntity player, double lookX, double lookZ, int gx, int gz) {
        double dx = mazeOrigin.getX() + gx * CELL_SIZE + CELL_SIZE / 2.0 - player.getX();
        double dz = mazeOrigin.getZ() + gz * CELL_SIZE + CELL_SIZE / 2.0 - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 2.0) return true;
        return (dx / len) * lookX + (dz / len) * lookZ > -0.17;
    }
}
