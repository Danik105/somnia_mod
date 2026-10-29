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
import net.minecraft.block.DoorBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
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
 * Подъезд ("Лестница в никуда", id сна falling_planks): лестничная клетка старого дома над
 * пустотой. Стены как в настоящем подъезде: до плеча зелёная масляная краска (линия идёт
 * вдоль ступеней), выше побелка. На этажах двери квартир и лампочка, на межэтажных
 * площадках окно в темноту и батарея. Марши достраиваются вверх по мере подъёма и
 * обрушаются за спиной, ряды ступеней впереди трещат и осыпаются волной (через 4,5 с
 * "заживают"), треснувшие ступени ломаются под ногами. Сорвался в пролёт — приходишь в себя
 * на последней надёжной ступени. Выход — люк в потолке последнего этажа.
 *
 * В группе марши строятся по самому высокому игроку, а рушатся за самым низким.
 */
public final class StairwellScenario extends DreamScenario {

    private static final int FLOORS = 8;
    private static final int FINAL_STAGE = FLOORS * 4;
    private static final int BUILD_AHEAD = 3;
    private static final int COLLAPSE_BEHIND = 3;
    private static final int ROTTEN_START_FLOOR = 2;
    private static final double ROTTEN_CHANCE = 0.15;
    private static final int ROTTEN_BREAK_TICKS = 15;
    private static final double FALL_CATCH_DEPTH = 5.0;
    private static final float FALL_DAMAGE = 4.0f;
    private static final float FALL_SANITY = -4.0f;
    private static final float ROTTEN_SANITY = -2.0f;
    private static final int CRUMBLE_START_DELAY = 8;
    private static final int CRUMBLE_WARN_TICKS = 2;
    private static final int CRUMBLE_ROW_INTERVAL = 4;
    private static final int CRUMBLE_RESTORE_TICKS = 90;

    private static final Block STEP = Blocks.STONE_STAIRS;
    private static final Block RISER = Blocks.STONE;
    private static final Block CRACKED_STEP = Blocks.COBBLESTONE_STAIRS;
    private static final Block TILE = Blocks.ANDESITE;
    private static final Block TILE_BORDER = Blocks.RED_TERRACOTTA;
    private static final Block PAINT = Blocks.WAXED_OXIDIZED_CUT_COPPER;
    private static final Block PAINT_WORN = Blocks.WAXED_WEATHERED_CUT_COPPER;
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
        buildWalls(world, center.getY() - 8, center.getY() + 10);
        ceilingY = center.getY() + 10;
        wallTopY = ceilingY;
        fillCeiling(world, ceilingY, WHITEWASH.getDefaultState());
        for (int stage = 0; stage <= 3; stage++) buildStage(session, stage);
        built = 4;
        return center;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return new BlockBox(center.getX() - 4, center.getY() - 9, center.getZ() - 14,
                center.getX() + 4, center.getY() + FLOORS * 8 + 8, center.getZ() + 2);
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

    private int stageOf(ServerPlayerEntity player) {
        double relY = player.getY() - center.getY();
        int floor = Math.max(0, (int) Math.floor(relY / 8.0));
        double r = relY - floor * 8;
        int part = r < 0.75 ? 0 : r < 4.25 ? 1 : r < 4.75 ? 2 : 3;
        return Math.min(floor * 4 + part, FINAL_STAGE);
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

        // Достройка вперёд и подъём временного потолка
        // Стены поднимаем до постройки маршей: окна, двери и лампы встраиваются в готовую стену
        int target = Math.min(highest + BUILD_AHEAD + 1, FINAL_STAGE + 1);
        if (hatch == null) {
            int needed = center.getY() + Math.min(target, FINAL_STAGE) * 2 + 4;
            if (needed > ceilingY) {
                fillCeiling(world, ceilingY, Blocks.AIR.getDefaultState());
                buildWalls(world, wallTopY + 1, needed);
                fillCeiling(world, needed, WHITEWASH.getDefaultState());
                wallTopY = needed;
                ceilingY = needed;
            }
        }
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
        if (!collapseQueue.isEmpty() && now % 2 == 0) {
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

        // "Заживление" обвалившихся рядов (кроме уже обрушенных фрагментов)
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
        if (now % 15 == 0) {
            int shown = 0;
            for (BlockPos pos : rotten) {
                if (shown >= 5) break;
                if (pos.getSquaredDistance(player.getBlockPos()) < 200) {
                    world.spawnParticles(player, ParticleTypes.WITCH, false, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                            1, 0.15, 0.05, 0.15, 0.0);
                    shown++;
                }
            }
        }

        // Последняя надёжная ступень и мягкая ловля падения (треснувшие не запоминаем)
        int dz = support.getZ() - center.getZ();
        if (player.isOnGround() && Math.abs(support.getX() - center.getX()) <= 3 && dz >= -13 && dz <= 1
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

        if (stage < 2 && hatch == null && now % 120 == 0) {
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

    /** Ряды ступеней прямо перед игроком трещат и осыпаются волной. */
    private void tickCrumble(DreamSession session, PlayerState state, int stage, BlockPos support, long now) {
        ServerWorld world = session.world;
        int part = stage % 4;
        if (stage < FINAL_STAGE && (part == 1 || part == 3)) {
            if (state.lastFlightStage != stage) {
                state.lastFlightStage = stage;
                state.nextCrumbleTick = now + CRUMBLE_START_DELAY;
            }
            if (state.crumble == null && now >= state.nextCrumbleTick) {
                Crumble next = pickCrumbleRow(world, stage, support, now);
                if (next != null) {
                    state.crumble = next;
                    for (int i = 0; i < next.positions().size(); i++) {
                        BlockPos pos = next.positions().get(i);
                        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, next.states().get(i)),
                                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 4, 0.25, 0.15, 0.25, 0.02);
                    }
                    session.sound(next.positions().get(0), SoundEvents.BLOCK_STONE_HIT, SoundCategory.BLOCKS, 1.0f, 0.5f);
                } else {
                    state.nextCrumbleTick = now + 6;
                }
            }
        } else {
            state.lastFlightStage = -1;
        }
        Crumble pending = state.crumble;
        if (pending != null && now >= pending.breakTick()) {
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
            state.nextCrumbleTick = now + CRUMBLE_ROW_INTERVAL;
        }
    }

    /** Ближайший по высоте ряд целых ступеней впереди по маршу (ряд под ногами не трогаем). */
    @Nullable
    private Crumble pickCrumbleRow(ServerWorld world, int stage, BlockPos support, long now) {
        if (stage >= stageBlocks.size()) return null;
        boolean north = stage % 4 == 1;
        int supZ = support.getZ();
        Map<Integer, List<BlockPos>> rows = new HashMap<>();
        Map<Integer, Integer> rowY = new HashMap<>();
        for (BlockPos pos : stageBlocks.get(stage)) {
            if (!world.getBlockState(pos).isOf(STEP)) continue;
            rows.computeIfAbsent(pos.getZ(), k -> new ArrayList<>()).add(pos);
            rowY.put(pos.getZ(), pos.getY());
        }
        Integer bestZ = null;
        int bestY = Integer.MAX_VALUE;
        for (int z : rows.keySet()) {
            boolean ahead = north ? z < supZ : z > supZ;
            if (!ahead) continue;
            int y = rowY.get(z);
            if (bestZ == null || y < bestY || (y == bestY && Math.abs(z - supZ) < Math.abs(bestZ - supZ))) {
                bestY = y;
                bestZ = z;
            }
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
            BlockPos rail = new BlockPos(center.getX(), stair.getY() + 1, stair.getZ());
            if (world.getBlockState(rail).isOf(Blocks.IRON_BARS)) {
                positions.add(rail);
                states.add(world.getBlockState(rail));
            }
        }
        return new Crumble(positions, states, stage, now + CRUMBLE_WARN_TICKS);
    }

    // ----- постройка -----

    /**
     * Стены клетки 7×15 вокруг center. Два блока над полом или ступенью у стены — зелёная
     * краска (с потёртостями), выше побелка с грязными пятнами. Линия краски повторяет
     * наклон марша, как в настоящем подъезде.
     */
    private void buildWalls(ServerWorld world, int y0, int y1) {
        for (int y = y0; y <= y1; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -13; z <= 1; z++) {
                    if (Math.abs(x) != 3 && z != -13 && z != 1) continue;
                    BlockPos pos = new BlockPos(center.getX() + x, y, center.getZ() + z);
                    setBulk(world, pos, wallState(x, y, z, pos));
                }
            }
        }
    }

    private BlockState wallState(int x, int y, int z, BlockPos pos) {
        int f = Math.floorDiv(y - (center.getY() - 1), 8);
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
        int base = center.getY() - 1 + floor * 8;
        if (z <= -10) return base + 4;
        if (z >= -1) return base;
        if (x < 0) return base + 1 + (-2 - z) / 2;
        return base + 5 + (z + 9) / 2;
    }

    private void fillCeiling(ServerWorld world, int y, BlockState state) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -12; z <= 0; z++) {
                setBulk(world, new BlockPos(center.getX() + x, y, center.getZ() + z), state);
            }
        }
    }

    /**
     * Фрагмент подъезда. Этаж f (+8 высоты): 0 — площадка этажа; 1 — марш на север вдоль
     * западной стены; 2 — междуэтажная площадка с окном в тьму; 3 — марш на юг вдоль восточной.
     * Пол площадки — на блок ниже уровня ног, ступени поднимаются по +0,5 без прыжков.
     */
    private void buildStage(DreamSession session, int stage) {
        ServerWorld world = session.world;
        List<BlockPos> blocks = new ArrayList<>();
        int floor = stage / 4;
        int part = stage % 4;
        int baseY = center.getY() - 1 + floor * 8;

        if (part == 0) {
            buildFloorLanding(world, baseY, blocks);
            // Две квартиры: за дверью глухая чернота, над ними голая лампочка
            for (int x : new int[]{-2, 2}) buildDoor(world, baseY + 1, x);
            setBulk(world, new BlockPos(center.getX(), baseY + 3, center.getZ() + 1), LAMP.getDefaultState());
        } else if (part == 1 || part == 3) {
            boolean first = part == 1;
            int marchBaseY = first ? baseY : baseY + 4;
            int startZ = first ? -2 : -9;
            int x1 = first ? -2 : 1;
            int x2 = first ? -1 : 2;
            Direction uphill = first ? Direction.NORTH : Direction.SOUTH;
            for (int i = 0; i < 8; i++) {
                int z = first ? startZ - i : startZ + i;
                int y = marchBaseY + 1 + i / 2;
                BlockHalf half = i % 2 == 0 ? BlockHalf.BOTTOM : BlockHalf.TOP;
                boolean isRotten = floor >= ROTTEN_START_FLOOR && session.random.nextDouble() < ROTTEN_CHANCE;
                Block step = isRotten ? CRACKED_STEP : STEP;
                for (int x : new int[]{x1, x2}) {
                    BlockPos pos = new BlockPos(center.getX() + x, y, center.getZ() + z);
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
                BlockPos rail = new BlockPos(center.getX(), y + 1, center.getZ() + z);
                set(world, rail, Blocks.IRON_BARS.getDefaultState()
                        .with(PaneBlock.NORTH, true).with(PaneBlock.SOUTH, true));
                blocks.add(rail);
            }
        } else {
            int landY = baseY + 4;
            for (int x = -2; x <= 2; x++) {
                for (int z = -12; z <= -10; z++) {
                    BlockPos pos = new BlockPos(center.getX() + x, landY, center.getZ() + z);
                    boolean border = z == -10 || Math.abs(x) == 2;
                    set(world, pos, (border ? TILE_BORDER : TILE).getDefaultState());
                    blocks.add(pos);
                }
            }
            // Окно в темноту и чугунная батарея под ним
            for (int x = -1; x <= 0; x++) {
                for (int y = landY + 2; y <= landY + 3; y++) {
                    setBulk(world, new BlockPos(center.getX() + x, y, center.getZ() - 13), Blocks.GLASS_PANE.getDefaultState()
                            .with(PaneBlock.EAST, true).with(PaneBlock.WEST, true));
                }
                BlockPos radiator = new BlockPos(center.getX() + x, landY + 1, center.getZ() - 12);
                setBulk(world, radiator, Blocks.IRON_TRAPDOOR.getDefaultState()
                        .with(TrapdoorBlock.FACING, Direction.SOUTH).with(TrapdoorBlock.OPEN, true));
                blocks.add(radiator);
            }
            setBulk(world, new BlockPos(center.getX() + (floor % 2 == 0 ? -2 : 1), landY + 4, center.getZ() - 13),
                    LAMP.getDefaultState());
        }
        stageBlocks.add(blocks);
    }

    /** Площадка этажа 5×2: плитка, у края марша красный бордюр. */
    private void buildFloorLanding(ServerWorld world, int y, List<BlockPos> blocks) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -1; z <= 0; z++) {
                BlockPos pos = new BlockPos(center.getX() + x, y, center.getZ() + z);
                set(world, pos, (z == -1 ? TILE_BORDER : TILE).getDefaultState());
                blocks.add(pos);
            }
        }
    }

    /** Дверь квартиры в южной стене; за ней чёрный блок, чтобы открытая дверь вела в никуда. */
    private void buildDoor(ServerWorld world, int y, int x) {
        BlockPos lower = new BlockPos(center.getX() + x, y, center.getZ() + 1);
        BlockState door = Blocks.DARK_OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.NORTH);
        setBulk(world, lower, door.with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        setBulk(world, lower.up(), door.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        setBulk(world, lower.south(), Blocks.BLACK_CONCRETE.getDefaultState());
        setBulk(world, lower.south().up(), Blocks.BLACK_CONCRETE.getDefaultState());
    }

    /** Площадка последнего этажа, глухой потолок и люк со светом пробуждения за ним. */
    private void buildFinal(DreamSession session) {
        ServerWorld world = session.world;
        int landingY = center.getY() - 1 + FLOORS * 8;
        List<BlockPos> blocks = new ArrayList<>();
        buildFloorLanding(world, landingY, blocks);
        stageBlocks.add(blocks);
        int finalCeiling = landingY + 5;
        fillCeiling(world, ceilingY, Blocks.AIR.getDefaultState());
        buildWalls(world, wallTopY + 1, finalCeiling);
        fillCeiling(world, finalCeiling, WHITEWASH.getDefaultState());
        wallTopY = finalCeiling;
        ceilingY = finalCeiling;
        hatch = new BlockPos(center.getX(), finalCeiling, center.getZ());
        set(world, hatch, Blocks.SPRUCE_TRAPDOOR.getDefaultState()
                .with(TrapdoorBlock.HALF, BlockHalf.BOTTOM).with(TrapdoorBlock.OPEN, false));
        set(world, hatch.up(), Blocks.SEA_LANTERN.getDefaultState());
        setBulk(world, new BlockPos(center.getX(), landingY + 3, center.getZ() + 1), LAMP.getDefaultState());
    }
}
