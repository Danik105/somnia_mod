package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.haunt.Horror;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.effect.StatusEffects;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Падение: тот самый сон, где падаешь и не можешь проснуться. Игрок медленно летит вниз
 * сквозь чёрное небо, мимо обломков комнаты: кровати, двери, стулья. Внизу ничего нет —
 * долетев, снова оказываешься наверху. Чтобы проснуться, надо пролететь сквозь 8 светящихся
 * колец (за один или несколько заходов). Вокруг кружат фантомы.
 */
public final class EndlessFallScenario extends DreamScenario {

    private static final int TOP = 200;
    private static final int GAP = 22;
    private static final int RINGS = 8;
    private static final int NEEDED = 8;
    private static final double HOLE = 2.6;
    private static final int COLUMN = 20;

    private BlockPos top;
    private final List<BlockPos> rings = new ArrayList<>();
    private final Map<UUID, Set<Integer>> passedThisDrop = new HashMap<>();
    private final Map<UUID, Integer> passed = new HashMap<>();
    private final Map<UUID, Double> lastY = new HashMap<>();

    @Override
    public int originY(ServerWorld world, int surfaceY) {
        return TOP;
    }

    @Override
    public int areaRadius() {
        return COLUMN + 16;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        top = session.origin;
        // Площадка, с которой падаешь: кусок пола спальни
        fill(world, top.add(-2, -1, -2), top.add(2, -1, 2), Blocks.OAK_PLANKS.getDefaultState());
        BlockState bed = Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.NORTH);
        setBulk(world, top.add(1, 0, -1), bed.with(BedBlock.PART, BedPart.HEAD));
        setBulk(world, top.add(1, 0, 0), bed.with(BedBlock.PART, BedPart.FOOT));

        int x = 0;
        int z = 0;
        for (int i = 0; i < RINGS; i++) {
            x = clamp(x + session.random.nextInt(13) - 6, 9);
            z = clamp(z + session.random.nextInt(13) - 6, 9);
            BlockPos ring = top.add(x, -GAP * (i + 1) + 4, z);
            rings.add(ring);
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d >= HOLE && d < 3.6) {
                        setBulk(world, ring.add(dx, 0, dz), (dx + dz) % 2 == 0
                                ? Blocks.SEA_LANTERN.getDefaultState() : Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());
                    }
                }
            }
        }
        // Обломки сна между кольцами: на них можно приземлиться и потерять время
        for (int i = 0; i < 26; i++) {
            BlockPos at = top.add(session.random.nextInt(COLUMN * 2) - COLUMN, -10 - session.random.nextInt(GAP * RINGS), session.random.nextInt(COLUMN * 2) - COLUMN);
            if (nearRing(at, 5)) continue;
            switch (session.random.nextInt(5)) {
                case 0 -> {
                    setBulk(world, at, bed.with(BedBlock.PART, BedPart.FOOT));
                    setBulk(world, at.north(), bed.with(BedBlock.PART, BedPart.HEAD));
                }
                case 1 -> door(world, at, Blocks.OAK_DOOR, Direction.fromHorizontal(session.random.nextInt(4)), false);
                case 2 -> {
                    setBulk(world, at, Blocks.OAK_FENCE.getDefaultState());
                    setBulk(world, at.up(), Blocks.OAK_PRESSURE_PLATE.getDefaultState());
                }
                case 3 -> fill(world, at, at.add(1, 0, 1), Blocks.OAK_PLANKS.getDefaultState());
                default -> setBulk(world, at, Blocks.BOOKSHELF.getDefaultState());
            }
        }
        for (int i = 0; i < 2; i++) {
            session.spawnMob(net.minecraft.entity.EntityType.PHANTOM, top.add(session.random.nextInt(20) - 10, -60 - i * 50, 8), 0f);
        }
        return top;
    }

    private static int clamp(int v, int max) {
        return Math.max(-max, Math.min(max, v));
    }

    private boolean nearRing(BlockPos pos, int dist) {
        for (BlockPos ring : rings) if (Math.abs(ring.getY() - pos.getY()) < dist && ring.getSquaredDistance(pos) < 64) return true;
        return false;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return around(top, COLUMN + 4, -GAP * RINGS - 10, 3);
    }

    @Override
    public BlockPos joinSpawn(DreamSession session, ServerPlayerEntity player) {
        return top;
    }

    @Override
    public void applyEffects(ServerPlayerEntity player, int duration) {
        DreamFx.effect(player, StatusEffects.SLOW_FALLING, duration, 0);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamHints.show(player, tr("somnium.hint.fall.start", NEEDED).formatted(Formatting.AQUA), 10);
    }

    @Override
    public void tick(DreamSession session) {
        if (session.now() % 6 != 0) return;
        for (BlockPos ring : rings) {
            session.world.spawnParticles(ParticleTypes.END_ROD, ring.getX() + 0.5, ring.getY() + 0.5, ring.getZ() + 0.5, 2, 1.6, 0.1, 1.6, 0.0);
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        ServerWorld world = session.world;
        UUID id = player.getUuid();
        double y = player.getY();
        double prev = lastY.getOrDefault(id, y);
        lastY.put(id, y);

        for (int i = 0; i < rings.size(); i++) {
            BlockPos ring = rings.get(i);
            double ry = ring.getY() + 0.5;
            if (prev >= ry && y < ry) {
                double dx = player.getX() - (ring.getX() + 0.5);
                double dz = player.getZ() - (ring.getZ() + 0.5);
                Set<Integer> drop = passedThisDrop.computeIfAbsent(id, k -> new HashSet<>());
                if (dx * dx + dz * dz < HOLE * HOLE && drop.add(i)) {
                    int total = passed.merge(id, 1, Integer::sum);
                    player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 2.0f, 0.6f + total * 0.12f);
                    world.spawnParticles(ParticleTypes.END_ROD, ring.getX() + 0.5, ring.getY() + 0.5, ring.getZ() + 0.5, 30, 2, 0.2, 2, 0.05);
                    if (total >= NEEDED) {
                        reachedExit(session, player, player.getBlockPos(), 1000);
                        return;
                    }
                    player.sendMessage(tr("somnium.hint.fall.ring", NEEDED - total).formatted(Formatting.AQUA), true);
                }
            }
        }

        // Внизу ничего нет: снова наверху
        if (y < top.getY() - GAP * RINGS - 6) {
            passedThisDrop.remove(id);
            lastY.remove(id);
            player.teleport(world, top.getX() + 4.5, top.getY() + 12, top.getZ() + 4.5, player.getYaw(), 30f);
            player.fallDistance = 0;
            DreamFx.title(player, Text.empty(), tr("somnium.hint.fall.again").formatted(Formatting.GRAY), 5, 30, 10);
            Horror.sound(player, SoundEvents.ENTITY_PLAYER_BIG_FALL, player.getEyePos(), 1.0f, 0.5f);
            return;
        }
        // Не даём улететь из колонны: мягко тянет обратно к центру
        Vec3d off = player.getPos().subtract(top.toCenterPos()).multiply(1, 0, 1);
        if (off.length() > COLUMN) {
            player.setVelocity(player.getVelocity().add(off.normalize().multiply(-0.25)));
            player.velocityModified = true;
        }
        // Ветер и полосы воздуха снизу вверх
        if (session.now() % 2 == 0 && !player.isOnGround()) {
            world.spawnParticles(player, ParticleTypes.CLOUD, false, player.getX(), player.getY() - 6, player.getZ(),
                    0, 0, 1, 0, 0.6);
            world.spawnParticles(player, ParticleTypes.WHITE_ASH, false, player.getX(), player.getY(), player.getZ(),
                    8, 5, 5, 5, 0.0);
        }
        if (session.now() % 30 == 0 && !player.isOnGround()) {
            player.playSound(SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.AMBIENT, 0.3f, 0.6f);
        }
    }

    @Override
    public void onLeave(DreamSession session, ServerPlayerEntity player) {
        passedThisDrop.remove(player.getUuid());
        lastY.remove(player.getUuid());
    }
}
