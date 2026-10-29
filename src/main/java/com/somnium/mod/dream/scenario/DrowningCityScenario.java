package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.block.BellBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.Attachment;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Тонущий город: руины за крепостной стеной медленно уходят под воду.
 * Выход — колокол на вершине затопленного собора (воздушный карман над максимальным
 * уровнем воды). Внутрь собора ведут арки со всех сторон, наверх — винтовая лестница
 * и пузырьковая колонна. Вне собора глубина тянет на дно.
 *
 * Вода заливается только внутри городской стены (~5,8 тыс. блоков на слой вместо
 * ~103 тыс. раньше) и порциями, поэтому подъём воды больше не лагает сервер.
 */
public final class DrowningCityScenario extends DreamScenario {

    private static final int CITY_RADIUS = 44;
    /** Насколько выше origin поднимается вода (origin.y = 7 → максимум Y=80). */
    private static final int WATER_HEIGHT = 73;
    private static final int RISE_INTERVAL = 80;
    private static final int FILL_BATCH = 3000;
    private static final int PULL_START_TICKS = 100;

    private BlockPos cathedral;
    private BlockPos bellPos;
    private int waterTop;
    private int waterLevel;
    private final List<long[]> columns = new ArrayList<>();
    private final ArrayDeque<Long> fillQueue = new ArrayDeque<>();
    private final Map<UUID, Vec3d> lastPos = new HashMap<>();
    private boolean warnedHigh;

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        BlockPos origin = session.origin;
        Random rnd = session.random;
        waterTop = origin.getY() + WATER_HEIGHT;
        waterLevel = origin.getY() - 1;

        buildCityWall(world, origin, rnd);

        double angle = rnd.nextDouble() * Math.PI * 2;
        int dist = 24 + rnd.nextInt(7);
        cathedral = origin.add((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist));
        buildRuins(world, origin, rnd);
        buildCathedral(world, rnd);

        for (int dx = -CITY_RADIUS; dx <= CITY_RADIUS; dx++) {
            for (int dz = -CITY_RADIUS; dz <= CITY_RADIUS; dz++) {
                if (dx * dx + dz * dz <= (CITY_RADIUS - 1) * (CITY_RADIUS - 1)) {
                    columns.add(new long[]{origin.getX() + dx, origin.getZ() + dz});
                }
            }
        }
        spawnMonsters(session, origin, 2, 8, 16);
        return origin;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return around(session.origin, CITY_RADIUS + 2, -1, WATER_HEIGHT + 8);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamHints.show(player, tr("somnium.hint.drowning.cathedral").formatted(Formatting.AQUA), 12);
        DreamFx.sendWeather(player, 1.0f, 0.0f);
    }

    @Override
    public void applyEffects(ServerPlayerEntity player, int duration) {
        DreamFx.effect(player, StatusEffects.SLOWNESS, duration, 1);
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();

        int target = Math.min(session.origin.getY() + (int) (session.elapsed() / RISE_INTERVAL), waterTop);
        if (fillQueue.isEmpty() && waterLevel < target) {
            waterLevel++;
            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (long[] column : columns) {
                pos.set((int) column[0], waterLevel, (int) column[1]);
                if (world.getBlockState(pos).isAir()) fillQueue.add(pos.asLong());
            }
            if ((waterLevel - session.origin.getY()) % 5 == 0) {
                for (ServerPlayerEntity player : session.players()) {
                    player.playSound(ModSounds.WATER_RISE, SoundCategory.AMBIENT, 1.2f, 0.8f);
                }
            }
            if (!warnedHigh && waterLevel >= waterTop - 10) {
                warnedHigh = true;
                session.hint(tr("somnium.hint.drowning.water_high").formatted(Formatting.RED, Formatting.BOLD), 6);
            }
        }
        BlockState water = Blocks.WATER.getDefaultState();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int i = 0; i < FILL_BATCH && !fillQueue.isEmpty(); i++) {
            pos.set(fillQueue.poll());
            if (world.getBlockState(pos).isAir()) world.setBlockState(pos, water, BULK);
        }

        if (now % 80 == 0) {
            for (int y = bellPos.getY() + 5; y <= bellPos.getY() + 28; y += 2) {
                world.spawnParticles(ParticleTypes.END_ROD, bellPos.getX() + 0.5, y, bellPos.getZ() + 0.5,
                        2, 0.15, 0.4, 0.15, 0.0);
            }
            session.sound(bellPos, SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.AMBIENT, 2.0f, 1.2f);
        }
        if (now % 100 == 0) {
            for (ServerPlayerEntity player : session.players()) DreamFx.sendWeather(player, 1.0f, 0.0f);
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        Vec3d pos = player.getPos();
        Vec3d prev = lastPos.put(player.getUuid(), pos);

        boolean insideTower = Math.abs(player.getBlockX() - cathedral.getX()) <= 5
                && Math.abs(player.getBlockZ() - cathedral.getZ()) <= 5;
        long elapsed = session.elapsed();
        // Глубина тянет на дно. Скорость игрока сервер не знает, поэтому сохраняем его
        // собственное горизонтальное движение и добавляем только рывок вниз.
        if (elapsed >= PULL_START_TICKS && !insideTower && prev != null && elapsed % 4 == 0
                && (player.isSubmergedInWater() || player.isTouchingWater())) {
            double pull = Math.min(0.15 + (elapsed - PULL_START_TICKS) / 20.0 * 0.005, 0.35);
            Vec3d motion = pos.subtract(prev);
            player.setVelocity(motion.x, Math.min(motion.y, 0.05) - pull, motion.z);
            player.velocityModified = true;
            if (elapsed % 60 == 0) {
                player.sendMessage(tr("somnium.hint.drowning.pull").formatted(Formatting.DARK_RED, Formatting.BOLD), true);
            }
        }

        double dx = player.getX() - (bellPos.getX() + 0.5);
        double dz = player.getZ() - (bellPos.getZ() + 0.5);
        double dy = player.getY() - bellPos.getY();
        if (dx * dx + dz * dz <= 12.25 && dy >= -1.0 && dy <= 4.0
                && DreamManager.requestWake(player, DreamOutcome.SURVIVED_OBJECTIVE)) {
            session.sound(bellPos, SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 2.0f, 1.0f);
            player.playSound(ModSounds.EXIT_PORTAL, SoundCategory.PLAYERS, 1.0f, 1.0f);
            session.world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1, player.getZ(),
                    40, 0.6, 1.0, 0.6, 0.05);
        }
    }

    @Override
    public void onLeave(DreamSession session, ServerPlayerEntity player) {
        lastPos.remove(player.getUuid());
    }

    // ----- постройка -----

    private void buildCityWall(ServerWorld world, BlockPos origin, Random rnd) {
        int top = waterTop + 2;
        for (int dx = -CITY_RADIUS - 2; dx <= CITY_RADIUS + 2; dx++) {
            for (int dz = -CITY_RADIUS - 2; dz <= CITY_RADIUS + 2; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < CITY_RADIUS - 0.5 || d >= CITY_RADIUS + 1.5) continue;
                boolean inner = d < CITY_RADIUS + 0.5;
                int columnTop = top + (((dx + dz) & 3) == 0 ? 1 : 0); // зубцы
                for (int y = origin.getY(); y <= columnTop; y++) {
                    BlockPos pos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                    BlockState state;
                    if (inner && (y - origin.getY()) % 12 == 6 && ((dx * 7 + dz * 3) & 7) == 0) {
                        state = Blocks.SEA_LANTERN.getDefaultState();
                    } else {
                        int roll = rnd.nextInt(100);
                        state = (roll < 50 ? Blocks.STONE_BRICKS : roll < 70 ? Blocks.MOSSY_STONE_BRICKS
                                : roll < 90 ? Blocks.CRACKED_STONE_BRICKS : Blocks.DARK_PRISMARINE).getDefaultState();
                    }
                    world.setBlockState(pos, state, BULK);
                }
            }
        }
    }

    /** Полуразрушенные дома: стены с проломами, окна, местами крыши и фонари. */
    private void buildRuins(ServerWorld world, BlockPos origin, Random rnd) {
        int houses = 11 + rnd.nextInt(4);
        for (int attempt = 0, built = 0; attempt < 60 && built < houses; attempt++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = 9 + rnd.nextDouble() * 27;
            int cx = origin.getX() + (int) Math.round(Math.cos(angle) * dist);
            int cz = origin.getZ() + (int) Math.round(Math.sin(angle) * dist);
            if (Math.hypot(cx - cathedral.getX(), cz - cathedral.getZ()) < 11) continue;
            int hw = 2 + rnd.nextInt(2);
            int hd = 2 + rnd.nextInt(2);
            int height = 4 + rnd.nextInt(6);
            boolean roof = rnd.nextBoolean();
            for (int x = -hw; x <= hw; x++) {
                for (int z = -hd; z <= hd; z++) {
                    boolean wall = Math.abs(x) == hw || Math.abs(z) == hd;
                    for (int y = 0; y <= height; y++) {
                        BlockPos pos = new BlockPos(cx + x, origin.getY() + y, cz + z);
                        if (y == height) {
                            if (roof && rnd.nextInt(100) < 75) {
                                world.setBlockState(pos, Blocks.STONE_BRICK_SLAB.getDefaultState(), BULK);
                            }
                            continue;
                        }
                        if (!wall) continue;
                        if (rnd.nextInt(100) < 18) continue; // пролом
                        boolean door = z == hd && x == 0 && y <= 1;
                        boolean window = y == 2 && ((x + z) & 1) == 0;
                        if (door || (window && rnd.nextBoolean())) continue;
                        int roll = rnd.nextInt(100);
                        BlockState state = (roll < 40 ? Blocks.COBBLESTONE : roll < 65 ? Blocks.MOSSY_COBBLESTONE
                                : roll < 85 ? Blocks.STONE_BRICKS : Blocks.CRACKED_STONE_BRICKS).getDefaultState();
                        world.setBlockState(pos, state, BULK);
                    }
                }
            }
            if (rnd.nextInt(3) == 0) {
                world.setBlockState(new BlockPos(cx, origin.getY(), cz), Blocks.SEA_LANTERN.getDefaultState(), BULK);
            }
            built++;
        }
    }

    /**
     * Собор: башня 9×9 от песка до воздушного кармана над максимальным уровнем воды,
     * арки 3×3 со всех сторон, винтовая лестница из плит без прыжков, пузырьковая
     * колонна на песке душ, лаз в полу колокольни и колокол в центре.
     */
    private void buildCathedral(ServerWorld world, Random rnd) {
        int cx = cathedral.getX();
        int cz = cathedral.getZ();
        int baseY = cathedral.getY();
        int chamberFloorY = waterTop;
        int wallTopY = chamberFloorY + 5;
        BlockState air = Blocks.AIR.getDefaultState();

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                boolean wall = Math.abs(dx) == 4 || Math.abs(dz) == 4;
                for (int y = baseY; y <= wallTopY; y++) {
                    BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                    if (wall) {
                        if (y > baseY + 2 && y < chamberFloorY - 2 && rnd.nextInt(100) < 6) continue;
                        int roll = rnd.nextInt(100);
                        BlockState state = (roll < 55 ? Blocks.STONE_BRICKS : roll < 75 ? Blocks.CRACKED_STONE_BRICKS
                                : roll < 95 ? Blocks.MOSSY_STONE_BRICKS : Blocks.PRISMARINE).getDefaultState();
                        world.setBlockState(pos, state, BULK);
                    } else if (y == chamberFloorY || y == wallTopY) {
                        world.setBlockState(pos, Blocks.STONE_BRICKS.getDefaultState(), BULK);
                    } else {
                        world.setBlockState(pos, air, BULK);
                    }
                }
            }
        }
        for (int[] corner : new int[][]{{-3, -3}, {-3, 3}, {3, -3}, {3, 3}}) {
            world.setBlockState(new BlockPos(cx + corner[0], chamberFloorY + 1, cz + corner[1]),
                    Blocks.SEA_LANTERN.getDefaultState(), BULK);
        }
        bellPos = new BlockPos(cx, chamberFloorY + 1, cz);
        world.setBlockState(bellPos, Blocks.BELL.getDefaultState().with(BellBlock.ATTACHMENT, Attachment.FLOOR), BULK);

        // Арки на всех четырёх сторонах + фонари над ними
        for (int side = 0; side < 4; side++) {
            boolean alongX = side < 2;
            int sign = side % 2 == 0 ? 1 : -1;
            for (int t = -1; t <= 1; t++) {
                for (int y = baseY; y <= baseY + 2; y++) {
                    BlockPos arch = alongX ? new BlockPos(cx + sign * 4, y, cz + t) : new BlockPos(cx + t, y, cz + sign * 4);
                    world.setBlockState(arch, air, BULK);
                }
                BlockPos lamp = alongX ? new BlockPos(cx + sign * 4, baseY + 3, cz + t * 2)
                        : new BlockPos(cx + t * 2, baseY + 3, cz + sign * 4);
                world.setBlockState(lamp, Blocks.SEA_LANTERN.getDefaultState(), BULK);
            }
        }
        // Светящиеся углы башни — видно издалека
        for (int y = baseY + 5; y <= wallTopY - 2; y += 12) {
            for (int[] corner : new int[][]{{-4, -4}, {-4, 4}, {4, -4}, {4, 4}}) {
                world.setBlockState(new BlockPos(cx + corner[0], y, cz + corner[1]), Blocks.SEA_LANTERN.getDefaultState(), BULK);
            }
        }
        // Лаз 2×2 в полу колокольни над концом лестницы
        for (int hx = -3; hx <= -2; hx++) {
            for (int hz = -3; hz <= -2; hz++) {
                world.setBlockState(new BlockPos(cx + hx, chamberFloorY, cz + hz), air, BULK);
            }
        }
        // Винтовая лестница: следующая плита на каждом Y по периметру шахты 7×7
        List<int[]> ring = new ArrayList<>();
        for (int i = -3; i <= 3; i++) ring.add(new int[]{i, -3});
        for (int i = -2; i <= 3; i++) ring.add(new int[]{3, i});
        for (int i = 2; i >= -3; i--) ring.add(new int[]{i, 3});
        for (int i = 2; i >= -2; i--) ring.add(new int[]{-3, i});
        for (int y = baseY; y < chamberFloorY; y++) {
            int[] step = ring.get((y - baseY) % ring.size());
            BlockState slab = (rnd.nextInt(100) < 70 ? Blocks.STONE_BRICK_SLAB : Blocks.MOSSY_STONE_BRICK_SLAB).getDefaultState();
            world.setBlockState(new BlockPos(cx + step[0], y, cz + step[1]), slab, BULK);
        }
        // Пузырьковая колонна, когда шахту зальёт
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlockState(new BlockPos(cx + dx, baseY - 1, cz + dz), Blocks.SOUL_SAND.getDefaultState(), BULK);
            }
        }
    }
}
