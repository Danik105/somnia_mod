package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Подъезд ("Лестница в никуда", id сна falling_planks): лестничная клетка панельного дома над
 * пустотой, 9 этажей. Пропорции как в настоящем подъезде: этаж 4 блока, над головой днище
 * следующего пролёта, между пролётами узкий колодец с решёткой. Стены до плеча в зелёной
 * краске (линия идёт вдоль ступеней), выше побелка. На этаже три квартиры, лампочка и номер
 * этажа, на межэтажной площадке окно в темноту и батарея.
 *
 * Пролёты достраиваются вверх по мере подъёма и обваливаются на этаж позади. На пролётах
 * иногда трещит ступень впереди: полторы секунды трещин, потом ряд осыпается и через 3,5 с
 * зарастает (можно переждать или перепрыгнуть). С 3-го этажа попадаются треснувшие ступени,
 * которые ломаются, если на них стоять. Сорвался в пролёт — приходишь в себя на последней
 * надёжной ступени. Выход — чердачный люк над лестницей на 9-м этаже.
 *
 * В группе пролёты строятся по самому высокому игроку, а рушатся за самым низким.
 */
public final class StairwellScenario extends DreamScenario {

    private static final int FLOORS = 8;
    private static final int FLOOR_HEIGHT = 4;
    private static final int FINAL_STAGE = FLOORS * 4;
    private static final int BUILD_AHEAD = 3;
    private static final int COLLAPSE_BEHIND = 4;
    private static final int COLLAPSE_INTERVAL = 3;
    private static final int ROTTEN_START_FLOOR = 2;
    private static final double ROTTEN_CHANCE = 0.12;
    private static final int ROTTEN_BREAK_TICKS = 20;
    private static final double FALL_CATCH_DEPTH = 6.0;
    private static final float FALL_DAMAGE = 4.0f;
    private static final float FALL_SANITY = -4.0f;
    private static final float ROTTEN_SANITY = -2.0f;
    private static final int CRUMBLE_START_FLOOR = 1;
    private static final int CRUMBLE_WARN_TICKS = 30;
    private static final int CRUMBLE_RESTORE_TICKS = 70;
    private static final int CRUMBLE_MIN_INTERVAL = 60;
    private static final int CRUMBLE_MAX_INTERVAL = 130;

    // Клетка: внутри x -2..2, z -7..1; стены x = ±3, z = -8 (окна) и z = 2 (квартиры)
    private static final int NORTH_WALL = -8;
    private static final int SOUTH_WALL = 2;
    private static final double[] PART_BASE = {0.0, 0.5, 2.0, 2.5};

    private static final Block STEP = Blocks.STONE_STAIRS;
    private static final Block RISER = Blocks.STONE;
    private static final Block CRACKED_STEP = Blocks.COBBLESTONE_STAIRS;
    private static final Block TILE = Blocks.ANDESITE;
    private static final Block TILE_BORDER = Blocks.RED_TERRACOTTA;
    private static final Block PAINT = Blocks.WAXED_OXIDIZED_COPPER;
    private static final Block PAINT_WORN = Blocks.WAXED_WEATHERED_COPPER;
    private static final Block WHITEWASH = Blocks.CALCITE;
    private static final Block WHITEWASH_DIRTY = Blocks.DIORITE;
    private static final Block LAMP = Blocks.OCHRE_FROGLIGHT;
    private static final Set<Block> SAFE = Set.of(STEP, RISER, TILE, TILE_BORDER);

    private record Crumble(List<BlockPos> positions, List<BlockState> states, int stage, long breakTick) {}

    private record Broken(BlockState state, long restoreTick, int stage) {}

    private static final class PlayerState {
        @Nullable Vec3d lastSafe;
        int rottenStand;
        int lastFlightStage = -1;
        @Nullable Crumble crumble;
        long nextCrumbleTick;
    }

    private BlockPos center;
    private final List<List<BlockPos>> stageBlocks = new ArrayList<>();
    private int built;
    private int collapseNext;
    private final ArrayDeque<BlockPos> collapseQueue = new ArrayDeque<>();
    private final Set<BlockPos> rotten = new HashSet<>();
    private final Map<BlockPos, Broken> broken = new HashMap<>();
    @Nullable private BlockPos hatch;
    private int ceilingY;
    private int wallTopY;
    @Nullable private Long wakeTick;
    private boolean finalAnnounced;
    private final Map<UUID, PlayerState> players = new HashMap<>();

    @Override
    public int originY(ServerWorld world, int surfaceY) {
        return 64;
    }

    @Override
    public int areaRadius() {
        return 24;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        center = session.origin;
        wallTopY = center.getY() - 9;
        ceilingY = wallTopY;
        raiseShaft(world, ceilingFor(4));
        for (int stage = 0; stage <= 3; stage++) buildStage(session, stage);
        built = 4;
        return center;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return new BlockBox(center.getX() - 4, center.getY() - 9, center.getZ() + NORTH_WALL - 1,
                center.getX() + 4, center.getY() + FLOORS * FLOOR_HEIGHT + 8, center.getZ() + SOUTH_WALL + 1);
    }

    @Override
    public void applyEffects(ServerPlayerEntity player, int duration) {
        DreamFx.effect(player, StatusEffects.WEAKNESS, duration, 0);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        players.put(player.getUuid(), new PlayerState());
        DreamHints.show(player, tr("somnium.hint.stairwell.start").formatted(Formatting.GRAY), 8);
    }

    @Override
    public void onLeave(DreamSession session, ServerPlayerEntity player) {
        players.remove(player.getUuid());
    }

    private int base(int floor) {
        return center.getY() - 1 + floor * FLOOR_HEIGHT;
    }

    /** Стадия под игроком: этаж * 4 + часть (0 площадка, 1 пролёт на север, 2 межэтажная, 3 пролёт на юг). */
    private int stageOf(ServerPlayerEntity player) {
        int dx = (int) Math.floor(player.getX()) - center.getX();
        int dz = (int) Math.floor(player.getZ()) - center.getZ();
        int part = dz >= -1 ? 0 : dz <= -6 ? 2 : dx > 0 ? 3 : 1;
        double relY = player.getY() - center.getY();
        int floor = (int) Math.floor((relY - PART_BASE[part] + 1.0) / FLOOR_HEIGHT);
        floor = Math.max(0, Math.min(floor, FLOORS));
        return Math.min(floor * 4 + part, FINAL_STAGE);
    }

    /** Высота временного потолка, когда построены стадии 0..target-1. */
    private int ceilingFor(int target) {
        int topFloor = (Math.min(target, FINAL_STAGE) - 1) / 4;
        return base(topFloor) + 6;
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        List<ServerPlayerEntity> list = session.players();
        if (list.isEmpty()) return;
        int highest = 0;
        int lowest = Integer.MAX_VALUE;
        for (ServerPlayerEntity player : list) {
            int stage = stageOf(player);
            highest = Math.max(highest, stage);
            lowest = Math.min(lowest, stage);
        }

        // Стены поднимаем до постройки маршей: окна, двери и лампы встраиваются в готовую стену
        int target = Math.min(highest + BUILD_AHEAD + 1, FINAL_STAGE + 1);
        if (hatch == null && target <= FINAL_STAGE) raiseShaft(world, ceilingFor(target));
        while (built < target) {
            if (built == FINAL_STAGE) buildFinal(session);
            else buildStage(session, built);
            built++;
        }

        // Обрушение за спиной самого низкого игрока
        int collapseUntil = Math.min(lowest - COLLAPSE_BEHIND, FINAL_STAGE);
        while (collapseNext < collapseUntil && collapseNext < stageBlocks.size()) {
            collapseQueue.addAll(stageBlocks.get(collapseNext));
            collapseNext++;
        }
        if (!collapseQueue.isEmpty() && now % COLLAPSE_INTERVAL == 0) {
            BlockPos falling = collapseQueue.poll();
            BlockState was = world.getBlockState(falling);
            set(world, falling, Blocks.AIR.getDefaultState());
            rotten.remove(falling);
            ServerPlayerEntity near = session.nearestPlayer(falling.toCenterPos());
            if (near != null && near.squaredDistanceTo(falling.toCenterPos()) < 500 && !was.isAir()) {
                session.sound(falling, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 0.9f, 0.7f);
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, was),
                        falling.getX() + 0.5, falling.getY() + 0.5, falling.getZ() + 0.5, 10, 0.25, 0.25, 0.25, 0.05);
            }
        }

        // "Заживление" осыпавшихся рядов (кроме уже обрушенных фрагментов)
        if (!broken.isEmpty()) {
            for (var entry : new ArrayList<>(broken.entrySet())) {
                if (now < entry.getValue().restoreTick()) continue;
                BlockPos pos = entry.getKey();
                broken.remove(pos);
                if (entry.getValue().stage() >= collapseNext && world.getBlockState(pos).isAir()) {
                    set(world, pos, entry.getValue().state());
                    world.spawnParticles(ParticleTypes.SOUL, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5,
                            3, 0.2, 0.1, 0.2, 0.01);
                }
            }
        }

        // Люк: открыли ПКМ — свет и пробуждение всей группы
        if (hatch != null) {
            if (wakeTick == null) {
                BlockState state = world.getBlockState(hatch);
                boolean opened = state.isAir() || (state.isOf(Blocks.SPRUCE_TRAPDOOR) && state.get(TrapdoorBlock.OPEN));
                if (opened) {
                    wakeTick = now + 25;
                    session.actionBar(tr("somnium.hint.stairwell.light").formatted(Formatting.GOLD));
                    session.sound(hatch, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 0.8f);
                    world.spawnParticles(ParticleTypes.END_ROD, hatch.getX() + 0.5, hatch.getY() - 1.5, hatch.getZ() + 0.5,
                            30, 0.3, 1.2, 0.3, 0.02);
                }
            } else if (now >= wakeTick) {
                session.wakeAll(DreamOutcome.SURVIVED_OBJECTIVE);
            }
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        ServerWorld world = session.world;
        long now = session.now();
        PlayerState state = players.computeIfAbsent(player.getUuid(), id -> new PlayerState());
        int stage = stageOf(player);
        BlockPos support = BlockPos.ofFloored(player.getX(), player.getY() - 0.5, player.getZ());

        tickCrumble(session, state, stage, support, now);

        // Треснувшие ступени: хруст и пролом под ногами
        if (rotten.contains(support) && player.isOnGround()) {
            state.rottenStand++;
            if (state.rottenStand == 1 || state.rottenStand % 5 == 0) {
                session.sound(support, SoundEvents.BLOCK_GRAVEL_HIT, SoundCategory.BLOCKS, 0.9f, 0.5f);
            }
            if (state.rottenStand >= ROTTEN_BREAK_TICKS) {
                rotten.remove(support);
                state.rottenStand = 0;
                set(world, support, Blocks.AIR.getDefaultState());
                session.sound(support, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1.0f, 0.6f);
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, CRACKED_STEP.getDefaultState()),
                        support.getX() + 0.5, support.getY() + 0.5, support.getZ() + 0.5, 20, 0.3, 0.3, 0.3, 0.08);
                SanityManager.add(player, ROTTEN_SANITY);
                player.sendMessage(tr("somnium.hint.stairwell.rotten").formatted(Formatting.GRAY), true);
            }
        } else {
            state.rottenStand = 0;
        }

        // Последняя надёжная ступень и мягкая ловля падения (треснувшие не запоминаем)
        int dz = support.getZ() - center.getZ();
        if (player.isOnGround() && Math.abs(support.getX() - center.getX()) <= 2 && dz >= NORTH_WALL + 1 && dz <= SOUTH_WALL - 1
                && !rotten.contains(support)) {
            if (SAFE.contains(world.getBlockState(support).getBlock())) {
                state.lastSafe = player.getPos();
            }
        }
        if (state.lastSafe != null && player.getY() < state.lastSafe.y - FALL_CATCH_DEPTH) {
            BlockPos safe = BlockPos.ofFloored(state.lastSafe.x, state.lastSafe.y - 0.5, state.lastSafe.z);
            if (world.getBlockState(safe).isAir()) {
                state.lastSafe = null; // опора обрушилась — ловить некуда
            } else {
                player.teleport(world, state.lastSafe.x, state.lastSafe.y, state.lastSafe.z, player.getYaw(), player.getPitch());
                player.fallDistance = 0;
                player.damage(world.getDamageSources().fall(), FALL_DAMAGE);
                SanityManager.add(player, FALL_SANITY);
                player.sendMessage(tr("somnium.hint.stairwell.fell").formatted(Formatting.GRAY), true);
                session.sound(player.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.8f, 0.6f);
            }
        }
        if (player.getY() < center.getY() - 20) {
            DreamManager.requestWake(player, DreamOutcome.DIED_IN_DREAM);
            return;
        }

        if (stage < 2 && hatch == null && now % 200 == 0) {
            player.sendMessage(tr("somnium.hint.stairwell.keep_moving").formatted(Formatting.GRAY), true);
        }
        if (stage >= FINAL_STAGE && !finalAnnounced) {
            finalAnnounced = true;
            session.actionBar(tr("somnium.hint.stairwell.last_floor").formatted(Formatting.GRAY));
            session.sound(player.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.AMBIENT, 1.0f, 0.8f);
        }
        if (hatch != null && wakeTick == null && hatch.getSquaredDistance(player.getBlockPos()) < 36 && now % 60 == 0) {
            DreamHints.show(player, tr("somnium.hint.stairwell.hatch").formatted(Formatting.GRAY), 6);
        }
    }

    /**
     * На пролёте ряд ступеней впереди (через ряд от игрока) трещит полторы секунды и осыпается.
     * Между такими обвалами 3-6 секунд, ряд прямо под ногами и следующий за ним не трогаем.
     */
    private void tickCrumble(DreamSession session, PlayerState state, int stage, BlockPos support, long now) {
        ServerWorld world = session.world;
        int part = stage % 4;
        boolean onFlight = stage < FINAL_STAGE && stage / 4 >= CRUMBLE_START_FLOOR && (part == 1 || part == 3);
        if (onFlight) {
            if (state.lastFlightStage != stage) {
                state.lastFlightStage = stage;
                state.nextCrumbleTick = Math.max(state.nextCrumbleTick, now + 10);
            }
            if (state.crumble == null && now >= state.nextCrumbleTick) {
                Crumble next = pickCrumbleRow(world, stage, support, now);
                if (next != null) {
                    state.crumble = next;
                    session.sound(next.positions().get(0), SoundEvents.BLOCK_STONE_HIT, SoundCategory.BLOCKS, 1.0f, 0.5f);
                } else {
                    state.nextCrumbleTick = now + 10;
                }
            }
        } else {
            state.lastFlightStage = -1;
        }
        Crumble pending = state.crumble;
        if (pending == null) return;
        if (now < pending.breakTick()) {
            // Предупреждение: трещины, пыль, хруст
            if (now % 6 == 0) {
                for (int i = 0; i < pending.positions().size(); i++) {
                    BlockPos pos = pending.positions().get(i);
                    world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, pending.states().get(i)),
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 3, 0.25, 0.05, 0.25, 0.01);
                }
                session.sound(pending.positions().get(0), SoundEvents.BLOCK_GRAVEL_HIT, SoundCategory.BLOCKS, 0.8f, 0.6f);
            }
            return;
        }
        state.crumble = null;
        for (int i = 0; i < pending.positions().size(); i++) {
            BlockPos pos = pending.positions().get(i);
            if (world.getBlockState(pos).isAir()) continue;
            set(world, pos, Blocks.AIR.getDefaultState());
            broken.put(pos, new Broken(pending.states().get(i), now + CRUMBLE_RESTORE_TICKS, pending.stage()));
        }
        BlockPos first = pending.positions().get(0);
        session.sound(first, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1.0f, 0.6f);
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, RISER.getDefaultState()),
                first.getX() + 0.5, first.getY() + 0.5, first.getZ() + 0.5, 20, 0.5, 0.3, 0.5, 0.06);
        state.nextCrumbleTick = now + CRUMBLE_MIN_INTERVAL
                + session.random.nextInt(CRUMBLE_MAX_INTERVAL - CRUMBLE_MIN_INTERVAL);
    }

    /** Целый ряд ступеней впереди по пролёту не ближе чем через ряд от игрока. */
    @Nullable
    private Crumble pickCrumbleRow(ServerWorld world, int stage, BlockPos support, long now) {
        if (stage >= stageBlocks.size()) return null;
        boolean north = stage % 4 == 1;
        int supZ = support.getZ();
        Map<Integer, List<BlockPos>> rows = new HashMap<>();
        for (BlockPos pos : stageBlocks.get(stage)) {
            if (!world.getBlockState(pos).isOf(STEP)) continue;
            rows.computeIfAbsent(pos.getZ(), k -> new ArrayList<>()).add(pos);
        }
        Integer bestZ = null;
        for (int z : rows.keySet()) {
            int ahead = north ? supZ - z : z - supZ;
            if (ahead < 2) continue;
            if (bestZ == null || Math.abs(z - supZ) < Math.abs(bestZ - supZ)) bestZ = z;
        }
        if (bestZ == null) return null;
        List<BlockPos> positions = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        for (BlockPos stair : rows.get(bestZ)) {
            positions.add(stair);
            states.add(world.getBlockState(stair));
            BlockPos riser = stair.down();
            if (world.getBlockState(riser).isOf(RISER)) {
                positions.add(riser);
                states.add(world.getBlockState(riser));
            }
        }
        return new Crumble(positions, states, stage, now + CRUMBLE_WARN_TICKS);
    }

    // ----- постройка -----

    /** Поднимает стены и временный потолок до высоты top. */
    private void raiseShaft(ServerWorld world, int top) {
        if (top <= ceilingY) return;
        fillCeiling(world, ceilingY, Blocks.AIR.getDefaultState());
        buildWalls(world, wallTopY + 1, top);
        fillCeiling(world, top, WHITEWASH.getDefaultState());
        wallTopY = top;
        ceilingY = top;
    }

    /**
     * Стены клетки. Два блока над полом или ступенью у стены — зелёная краска (с потёртостями),
     * выше побелка с грязными пятнами. Линия краски повторяет наклон пролёта.
     */
    private void buildWalls(ServerWorld world, int y0, int y1) {
        for (int y = y0; y <= y1; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = NORTH_WALL; z <= SOUTH_WALL; z++) {
                    if (Math.abs(x) != 3 && z != NORTH_WALL && z != SOUTH_WALL) continue;
                    BlockPos pos = new BlockPos(center.getX() + x, y, center.getZ() + z);
                    setBulk(world, pos, wallState(x, y, z, pos));
                }
            }
        }
    }

    private BlockState wallState(int x, int y, int z, BlockPos pos) {
        int f = Math.floorDiv(y - (center.getY() - 1), FLOOR_HEIGHT);
        int above = Integer.MAX_VALUE;
        for (int floor = f - 1; floor <= f; floor++) {
            if (floor < 0) continue;
            int h = y - wallFloorY(x, z, floor);
            if (h >= 1) above = Math.min(above, h);
        }
        long hash = pos.asLong() * 0x9E3779B97F4A7C15L;
        boolean stain = ((hash >>> 40) & 15) == 0;
        if (above <= 2) return (stain ? PAINT_WORN : PAINT).getDefaultState();
        return (stain ? WHITEWASH_DIRTY : WHITEWASH).getDefaultState();
    }

    /** Высота пола (ступени, площадки) у стены в точке (x, z) на этаже floor. */
    private int wallFloorY(int x, int z, int floor) {
        int base = base(floor);
        if (z >= -1) return base;
        if (z <= -6) return base + 2;
        if (x < 0) return base + 1 + (-2 - z) / 2;
        return base + 3 + (z + 5) / 2;
    }

    private void fillCeiling(ServerWorld world, int y, BlockState state) {
        for (int x = -2; x <= 2; x++) {
            for (int z = NORTH_WALL + 1; z <= SOUTH_WALL - 1; z++) {
                setBulk(world, new BlockPos(center.getX() + x, y, center.getZ() + z), state);
            }
        }
    }

    /**
     * Фрагмент подъезда. Этаж f (+4 высоты): 0 — площадка этажа (z -1..1); 1 — пролёт на север
     * вдоль западной стены; 2 — межэтажная площадка с окном (z -7..-6); 3 — пролёт на юг вдоль
     * восточной. Пол площадки на блок ниже уровня ног, ступени поднимаются по 0,5 без прыжков.
     */
    private void buildStage(DreamSession session, int stage) {
        ServerWorld world = session.world;
        List<BlockPos> blocks = new ArrayList<>();
        int floor = stage / 4;
        int part = stage % 4;
        int b = base(floor);

        if (part == 0) {
            buildFloorLanding(world, b, blocks);
            // Три квартиры: за дверью глухая чернота, над средней голая лампочка
            for (int x : new int[]{-2, 0, 2}) buildApartmentDoor(world, b + 1, x);
            setBulk(world, at(0, b + 3, SOUTH_WALL), LAMP.getDefaultState());
            floorNumber(world, b, floor);
        } else if (part == 1 || part == 3) {
            boolean first = part == 1;
            int marchBase = first ? b : b + 2;
            int x1 = first ? -2 : 1;
            int x2 = first ? -1 : 2;
            Direction uphill = first ? Direction.NORTH : Direction.SOUTH;
            for (int i = 0; i < 4; i++) {
                int z = first ? -2 - i : -5 + i;
                int y = marchBase + 1 + i / 2;
                BlockHalf half = i % 2 == 0 ? BlockHalf.BOTTOM : BlockHalf.TOP;
                boolean isRotten = floor >= ROTTEN_START_FLOOR && i > 0 && session.random.nextDouble() < ROTTEN_CHANCE;
                Block step = isRotten ? CRACKED_STEP : STEP;
                for (int x : new int[]{x1, x2}) {
                    BlockPos pos = at(x, y, z);
                    set(world, pos, step.getDefaultState().with(StairsBlock.FACING, uphill).with(StairsBlock.HALF, half));
                    blocks.add(pos);
                    if (isRotten) {
                        rotten.add(pos);
                    } else {
                        BlockPos riser = pos.down();
                        if (world.getBlockState(riser).isAir()) {
                            set(world, riser, RISER.getDefaultState());
                            blocks.add(riser);
                        }
                    }
                }
                BlockPos rail = at(0, y + 1, z);
                set(world, rail, Blocks.IRON_BARS.getDefaultState()
                        .with(PaneBlock.NORTH, true).with(PaneBlock.SOUTH, true));
                blocks.add(rail);
            }
        } else {
            int landY = b + 2;
            for (int x = -2; x <= 2; x++) {
                for (int z = -7; z <= -6; z++) {
                    BlockPos pos = at(x, landY, z);
                    set(world, pos, (z == -6 ? TILE_BORDER : TILE).getDefaultState());
                    blocks.add(pos);
                }
            }
            // Окно в темноту и чугунная батарея под ним
            for (int x = -1; x <= 0; x++) {
                for (int y = landY + 2; y <= landY + 3; y++) {
                    setBulk(world, at(x, y, NORTH_WALL), Blocks.GLASS_PANE.getDefaultState()
                            .with(PaneBlock.EAST, true).with(PaneBlock.WEST, true));
                }
                BlockPos radiator = at(x, landY + 1, NORTH_WALL + 1);
                setBulk(world, radiator, Blocks.IRON_TRAPDOOR.getDefaultState()
                        .with(TrapdoorBlock.FACING, Direction.SOUTH).with(TrapdoorBlock.OPEN, true));
                blocks.add(radiator);
            }
            setBulk(world, at(floor % 2 == 0 ? -2 : 1, landY + 3, NORTH_WALL), LAMP.getDefaultState());
        }
        stageBlocks.add(blocks);
    }

    private BlockPos at(int dx, int y, int dz) {
        return new BlockPos(center.getX() + dx, y, center.getZ() + dz);
    }

    /** Площадка этажа 5×3: плитка, у края пролёта красный бордюр. */
    private void buildFloorLanding(ServerWorld world, int y, List<BlockPos> blocks) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos pos = at(x, y, z);
                set(world, pos, (z == -1 ? TILE_BORDER : TILE).getDefaultState());
                blocks.add(pos);
            }
        }
    }

    /** Дверь квартиры в южной стене; за ней чёрный блок, чтобы открытая дверь вела в никуда. */
    private void buildApartmentDoor(ServerWorld world, int y, int x) {
        BlockPos lower = at(x, y, SOUTH_WALL);
        door(world, lower, Blocks.DARK_OAK_DOOR, Direction.NORTH, false);
        setBulk(world, lower.south(), Blocks.BLACK_CONCRETE.getDefaultState());
        setBulk(world, lower.south().up(), Blocks.BLACK_CONCRETE.getDefaultState());
    }

    /** Номер этажа на восточной стене над площадкой. */
    private void floorNumber(ServerWorld world, int b, int floor) {
        sign(world, at(2, b + 3, 0), Blocks.BIRCH_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.WEST),
                Text.empty(), Text.literal(String.valueOf(floor + 1)).formatted(Formatting.BOLD), tr("somnium.stairwell.floor"));
    }

    /** Площадка 9-го этажа, лестница к чердачному люку и свет пробуждения за ним. */
    private void buildFinal(DreamSession session) {
        ServerWorld world = session.world;
        int landingY = base(FLOORS);
        int finalCeiling = landingY + 4;
        raiseShaft(world, finalCeiling);
        List<BlockPos> blocks = new ArrayList<>();
        buildFloorLanding(world, landingY, blocks);
        stageBlocks.add(blocks);
        for (int x : new int[]{-2, 2}) buildApartmentDoor(world, landingY + 1, x);
        setBulk(world, at(1, landingY + 3, SOUTH_WALL), LAMP.getDefaultState());
        floorNumber(world, landingY, FLOORS);
        for (int y = landingY + 1; y <= landingY + 3; y++) {
            setBulk(world, at(0, y, 1), Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, Direction.NORTH));
        }
        hatch = at(0, finalCeiling, 1);
        set(world, hatch, Blocks.SPRUCE_TRAPDOOR.getDefaultState()
                .with(TrapdoorBlock.HALF, BlockHalf.BOTTOM).with(TrapdoorBlock.OPEN, false));
        set(world, hatch.up(), Blocks.SEA_LANTERN.getDefaultState());
    }
}
