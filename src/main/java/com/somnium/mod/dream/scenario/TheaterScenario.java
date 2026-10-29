package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.entity.decoration.ArmorStandEntity;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Пустой театр: тёмный зал, ряды кресел, в каждом сидит безликий манекен. На сцене ходит
 * луч прожектора. Встань в него и держись: 30 секунд в свете — и зал взорвётся
 * аплодисментами, а ты проснёшься. Пока стоишь в темноте, зрители встают с мест и,
 * когда на них не смотришь, подходят ближе.
 */
public final class TheaterScenario extends DreamScenario {

    private static final int WIDTH = 31;
    private static final int DEPTH = 40;
    private static final int HEIGHT = 12;
    private static final int STAGE_Z = 27;
    private static final int STAGE_H = 2;
    private static final int NEEDED = 600;
    private static final int MOVE_EVERY = 120;

    private static final BlockState WALL = Blocks.DARK_OAK_PLANKS.getDefaultState();
    private static final BlockState VELVET = Blocks.RED_WOOL.getDefaultState();
    private static final BlockState STAGE = Blocks.SPRUCE_PLANKS.getDefaultState();
    private static final BlockState SPOT = Blocks.OCHRE_FROGLIGHT.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private BlockPos c;
    private BlockPos spot;
    private final List<BlockPos> lit = new ArrayList<>();
    private final List<ArmorStandEntity> audience = new ArrayList<>();
    private final Map<ArmorStandEntity, Vec3d> seats = new HashMap<>();
    private final Map<UUID, Integer> applause = new HashMap<>();
    private long nextMove;

    @Override
    public int areaRadius() {
        return DEPTH;
    }

    private BlockPos at(int x, int y, int z) {
        return c.add(x, y, z);
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        c = session.origin.add(-WIDTH / 2, 0, -DEPTH / 2);
        fill(world, at(-1, -1, -1), at(WIDTH, HEIGHT, DEPTH), WALL);
        fill(world, at(0, 0, 0), at(WIDTH - 1, HEIGHT - 1, DEPTH - 1), AIR);
        fill(world, at(0, -1, 0), at(WIDTH - 1, -1, STAGE_Z - 1), Blocks.RED_TERRACOTTA.getDefaultState());

        // Сцена, рампа со свечами, занавес по бокам и сверху
        fill(world, at(2, 0, STAGE_Z), at(WIDTH - 3, STAGE_H - 1, DEPTH - 1), STAGE);
        for (int x = 3; x < WIDTH - 3; x += 2) {
            setBulk(world, at(x, STAGE_H, STAGE_Z), Blocks.CANDLE.getDefaultState().with(CandleBlock.LIT, true)
                    .with(CandleBlock.CANDLES, 1 + session.random.nextInt(3)));
        }
        fill(world, at(2, STAGE_H, STAGE_Z + 1), at(3, HEIGHT - 1, DEPTH - 1), VELVET);
        fill(world, at(WIDTH - 4, STAGE_H, STAGE_Z + 1), at(WIDTH - 3, HEIGHT - 1, DEPTH - 1), VELVET);
        fill(world, at(2, HEIGHT - 3, STAGE_Z + 1), at(WIDTH - 3, HEIGHT - 1, STAGE_Z + 1), VELVET);
        fill(world, at(2, STAGE_H, DEPTH - 1), at(WIDTH - 3, HEIGHT - 1, DEPTH - 1), Blocks.BLACK_WOOL.getDefaultState());
        // Лестницы на сцену по краям
        for (int side : new int[]{4, WIDTH - 5}) {
            setBulk(world, at(side, 0, STAGE_Z - 1), Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH));
            setBulk(world, at(side, 1, STAGE_Z), Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH));
        }
        // Ложи-балконы по стенам: тёмные проёмы
        for (int z = 6; z < STAGE_Z - 2; z += 6) {
            for (int x : new int[]{0, WIDTH - 1}) {
                fill(world, at(x, 6, z), at(x, 7, z + 2), Blocks.BLACK_CONCRETE.getDefaultState());
                setBulk(world, at(x, 5, z + 1), Blocks.GOLD_BLOCK.getDefaultState());
            }
        }
        // Люстра под потолком — не горит
        BlockPos chandelier = at(WIDTH / 2, HEIGHT - 1, STAGE_Z / 2);
        fill(world, chandelier.down(2), chandelier, Blocks.CHAIN.getDefaultState());
        fill(world, chandelier.add(-1, -3, -1), chandelier.add(1, -3, 1), Blocks.IRON_BARS.getDefaultState());

        // Ряды кресел с проходом посередине, в креслах зрители
        for (int row = 0; row < 8; row++) {
            int z = 6 + row * 2;
            for (int x = 3; x < WIDTH - 3; x++) {
                if (Math.abs(x - WIDTH / 2) <= 1) continue;
                setBulk(world, at(x, 0, z), Blocks.CRIMSON_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH));
                if (session.random.nextInt(3) == 0) {
                    Vec3d seat = at(x, 0, z).toCenterPos();
                    ArmorStandEntity viewer = new ArmorStandEntity(world, seat.x, seat.y, seat.z);
                    viewer.setInvulnerable(true);
                    faceTowards(viewer, at(WIDTH / 2, STAGE_H + 1, DEPTH - 6).toCenterPos());
                    session.add(viewer);
                    audience.add(viewer);
                    seats.put(viewer, seat);
                }
            }
        }
        moveSpot(session);
        nextMove = session.now() + MOVE_EVERY;
        return at(WIDTH / 2, 0, 1);
    }

    /** Переносит пятно света в новую точку сцены. */
    private void moveSpot(DreamSession session) {
        ServerWorld world = session.world;
        for (BlockPos pos : lit) setBulk(world, pos, STAGE);
        lit.clear();
        int x = 6 + session.random.nextInt(WIDTH - 12);
        int z = STAGE_Z + 3 + session.random.nextInt(DEPTH - STAGE_Z - 6);
        spot = at(x, STAGE_H, z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = spot.add(dx, -1, dz);
                setBulk(world, pos, SPOT);
                lit.add(pos);
            }
        }
        session.sound(spot, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 2.0f, 0.4f);
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return new BlockBox(c.getX() - 1, c.getY() - 1, c.getZ() - 1, c.getX() + WIDTH, c.getY() + HEIGHT, c.getZ() + DEPTH);
    }

    @Override
    public BlockPos joinSpawn(DreamSession session, ServerPlayerEntity player) {
        return session.spawn().add(session.random.nextInt(5) - 2, 0, 0);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamHints.show(player, tr("somnium.hint.theater.start").formatted(Formatting.GOLD), 10);
    }

    private boolean inSpot(ServerPlayerEntity player) {
        return Math.abs(player.getX() - (spot.getX() + 0.5)) < 1.7 && Math.abs(player.getZ() - (spot.getZ() + 0.5)) < 1.7
                && Math.abs(player.getY() - spot.getY()) < 1.0;
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        if (now >= nextMove) {
            nextMove = now + MOVE_EVERY;
            moveSpot(session);
        }
        if (now % 4 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, spot.getX() + 0.5, spot.getY() + 4, spot.getZ() + 0.5, 3, 0.6, 3, 0.6, 0.0);
        }
        if (now % 10 != 0 || session.elapsed() < 200) return;

        // Зрители: те, на кого никто не смотрит, подходят к тем, кто стоит в темноте
        for (ArmorStandEntity viewer : audience) {
            if (viewer.isRemoved()) continue;
            boolean watched = false;
            for (ServerPlayerEntity player : session.players()) {
                if (Horror.isLookingAt(player, viewer, 0.75)) watched = true;
            }
            if (watched) continue;
            ServerPlayerEntity target = null;
            double best = 26 * 26;
            for (ServerPlayerEntity player : session.players()) {
                double d = player.squaredDistanceTo(viewer);
                if (!inSpot(player) && d < best) {
                    best = d;
                    target = player;
                }
            }
            if (target == null) {
                // Все в свете — зрители возвращаются на места
                Vec3d seat = seats.get(viewer);
                if (seat != null && viewer.squaredDistanceTo(seat) > 1) {
                    viewer.refreshPositionAndAngles(seat.x, seat.y, seat.z, 0, 0);
                    faceTowards(viewer, spot.toCenterPos());
                }
                continue;
            }
            if (best < 2.2) {
                Horror.screamer(target, Horror.FACE_PALE);
                target.damage(world.getDamageSources().magic(), 3.0f);
                SanityManager.add(target, -3.0f);
                applause.computeIfPresent(target.getUuid(), (k, v) -> Math.max(0, v - NEEDED / 5));
                Vec3d seat = seats.get(viewer);
                if (seat != null) viewer.refreshPositionAndAngles(seat.x, seat.y, seat.z, 0, 0);
                continue;
            }
            if (session.random.nextInt(6) != 0) continue;
            Vec3d dir = target.getPos().subtract(viewer.getPos()).multiply(1, 0, 1).normalize();
            Vec3d to = viewer.getPos().add(dir.multiply(1.5));
            BlockPos ground = session.groundNear((int) Math.floor(to.x), (int) Math.floor(viewer.getY() + 0.5), (int) Math.floor(to.z));
            viewer.refreshPositionAndAngles(to.x, ground.getY(), to.z, 0, 0);
            faceTowards(viewer, target.getEyePos());
            Horror.sound(target, SoundEvents.BLOCK_WOOD_STEP, to, 0.8f, 0.6f);
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (!inSpot(player)) {
            if (session.now() % 20 == 0) player.sendMessage(meter(applause.getOrDefault(player.getUuid(), 0)), true);
            return;
        }
        int value = applause.merge(player.getUuid(), 1, Integer::sum);
        if (value % 20 == 0) {
            player.sendMessage(meter(value), true);
            // Сначала один хлопок в темноте, потом всё больше
            int claps = 1 + value * 6 / NEEDED;
            for (int i = 0; i < claps; i++) {
                Vec3d from = Horror.around(player, 150 + session.random.nextDouble() * 60, 6 + session.random.nextInt(14));
                Horror.later(i * 2, () -> Horror.sound(player, SoundEvents.ENTITY_ITEM_FRAME_PLACE, from.add(0, 1, 0), 0.8f, 1.5f));
            }
        }
        if (value >= NEEDED) {
            Horror.sound(player, SoundEvents.ENTITY_PLAYER_LEVELUP, player.getEyePos(), 1.0f, 0.5f);
            reachedExit(session, player, player.getBlockPos(), 1000);
        }
    }

    private static Text meter(int value) {
        int bars = Math.min(10, value * 10 / NEEDED);
        return tr("somnium.hint.theater.applause").formatted(Formatting.GOLD).append(" ")
                .append(Text.literal("▮".repeat(bars)).formatted(Formatting.YELLOW))
                .append(Text.literal("▯".repeat(10 - bars)).formatted(Formatting.DARK_GRAY));
    }

    @Override
    public void onLeave(DreamSession session, ServerPlayerEntity player) {
        applause.remove(player.getUuid());
    }
}
