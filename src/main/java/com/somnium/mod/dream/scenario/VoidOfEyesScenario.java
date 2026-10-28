package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.entity.nightmare.WatcherEntity;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Пустота с глазами: во тьме открываются глаза, Наблюдатели сжимают кольцо и
 * подкрадываются, когда на них не смотрят. Два выхода:
 *  - дойти до Двери пробуждения (Наблюдатели мешают);
 *  - "Перехватить взгляд": смотреть Наблюдателю точно в глаза 3 секунды — он сгорает;
 *    5 сгоревших на всю группу — сон отпускает всех.
 */
public final class VoidOfEyesScenario extends DreamScenario {

    private static final int DISPEL_NEEDED = 5;
    private static final int STARE_TO_DISPEL = 60;
    private static final int DOOR_MIN = 50;
    private static final int DOOR_MAX = 70;

    private BlockPos door;
    private int dispelled;
    private final Map<UUID, Integer> stare = new HashMap<>();
    private final Set<UUID> staredThisTick = new HashSet<>();

    @Override
    public int areaRadius() {
        return 100;
    }

    @Override
    public int boundaryRadius() {
        return 90;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        BlockPos origin = session.origin;
        double angle = session.random.nextDouble() * Math.PI * 2;
        int dist = DOOR_MIN + session.random.nextInt(DOOR_MAX - DOOR_MIN + 1);
        door = origin.add((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist));
        WakeDoor.build(session, door);
        for (int i = 0; i < 3; i++) spawnWatcher(session, origin, 14, 24);
        return origin;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return new BlockBox(door.getX() - 2, door.getY() - 1, door.getZ() - 2,
                door.getX() + 2, door.getY() + 3, door.getZ() + 2);
    }

    @Override
    public void applyEffects(ServerPlayerEntity player, int duration) {
        DreamFx.effect(player, StatusEffects.SLOW_FALLING, duration, 0);
        DreamFx.effect(player, StatusEffects.SLOWNESS, duration, 0);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamHints.show(player, tr("somnium.hint.void.start", DISPEL_NEEDED).formatted(Formatting.DARK_PURPLE), 12);
    }

    @Override
    public void tick(DreamSession session) {
        long now = session.now();
        List<WatcherEntity> watchers = watchers(session, session.origin, 160);
        if (now % 20 == 0) {
            for (UUID id : new HashSet<>(stare.keySet())) {
                if (staredThisTick.contains(id)) continue;
                int left = stare.get(id) - 4;
                if (left <= 0) stare.remove(id);
                else stare.put(id, left);
            }
        }
        staredThisTick.clear();

        int wanted = 3 + Math.max(0, session.players().size() - 1);
        if (session.elapsed() > 400 && watchers.size() < wanted && now % 120 == 0) {
            ServerPlayerEntity target = session.randomPlayer();
            if (target != null) {
                WatcherEntity fresh = spawnWatcher(session, target.getBlockPos(), 14, 24);
                if (fresh != null) {
                    session.sound(fresh.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 0.7f, 0.4f);
                }
            }
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        ServerWorld world = session.world;
        long now = session.now();
        long elapsed = session.elapsed();

        if (WakeDoor.isPassing(world, door, player)
                && DreamManager.requestWake(player, DreamOutcome.SURVIVED_OBJECTIVE)) {
            player.playSound(ModSounds.EXIT_PORTAL, SoundCategory.PLAYERS, 1.0f, 1.0f);
            return;
        }

        // Первые 30 секунд: во тьме вокруг одна за другой открываются пары глаз
        if (elapsed < 600 && now % 40 == 0) {
            int pairs = 2 + session.random.nextInt(2);
            for (int p = 0; p < pairs; p++) {
                double angle = session.random.nextDouble() * Math.PI * 2;
                double dist = 8.0 + session.random.nextDouble() * 7.0;
                double ex = player.getX() + Math.cos(angle) * dist;
                double ez = player.getZ() + Math.sin(angle) * dist;
                double ey = player.getY() + 1.5 + (session.random.nextDouble() - 0.5);
                world.spawnParticles(player, ParticleTypes.END_ROD, false, ex - 0.22, ey, ez, 1, 0.02, 0.02, 0.02, 0.0);
                world.spawnParticles(player, ParticleTypes.END_ROD, false, ex + 0.22, ey, ez, 1, 0.02, 0.02, 0.02, 0.0);
                if (session.random.nextInt(3) == 0) {
                    DreamFx.soundTo(player, SoundEvents.ENTITY_ENDERMAN_AMBIENT, SoundCategory.AMBIENT, ex, ey, ez, 0.6f, 0.3f);
                }
            }
        }
        if (elapsed < 1200 && now % 100 == 0) {
            player.playSound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.AMBIENT, 0.9f,
                    (float) (0.6 + Math.min(0.6, elapsed / 2400.0)));
        }

        List<WatcherEntity> watchers = watchers(session, player.getBlockPos(), 48);

        // Кольцо сжимается: каждые 15 секунд дальние Наблюдатели оказываются ближе
        if (now % 300 == 0) {
            double radius = Math.max(9.0, 26.0 - (elapsed / 300.0) * 2.5);
            for (WatcherEntity watcher : watchers) {
                if (watcher.squaredDistanceTo(player) < radius * radius) continue;
                double angle = session.random.nextDouble() * Math.PI * 2;
                BlockPos ground = session.groundNear((int) (player.getX() + Math.cos(angle) * radius), player.getBlockY(),
                        (int) (player.getZ() + Math.sin(angle) * radius));
                watcher.teleport(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
                watcher.lookAtEntity(player, 360f, 360f);
                session.sound(ground, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 0.5f, 0.4f);
            }
        }

        // Перехват взгляда: узкий конус ~8° прямо в Наблюдателя
        Vec3d look = player.getRotationVec(1.0f).normalize();
        WatcherEntity target = null;
        for (WatcherEntity watcher : watchers) {
            if (watcher.squaredDistanceTo(player) > 32 * 32) continue;
            Vec3d to = watcher.getPos().add(0, watcher.getHeight() * 0.7, 0).subtract(player.getEyePos()).normalize();
            if (look.dotProduct(to) > 0.99 && player.canSee(watcher)) {
                target = watcher;
                break;
            }
        }
        if (target == null) return;

        staredThisTick.add(target.getUuid());
        int value = stare.merge(target.getUuid(), 1, Integer::sum);
        if (value % 10 == 0) {
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, target.getX(), target.getY() + 1.8, target.getZ(),
                    4, 0.25, 0.4, 0.25, 0.01);
            player.sendMessage(tr("somnium.hint.void.stare", Math.min(100, value * 100 / STARE_TO_DISPEL))
                    .formatted(Formatting.DARK_PURPLE), true);
        }
        if (value < STARE_TO_DISPEL) return;

        stare.remove(target.getUuid());
        world.spawnParticles(ParticleTypes.END_ROD, target.getX(), target.getY() + 1.3, target.getZ(), 60, 0.5, 1.0, 0.5, 0.15);
        world.spawnParticles(ParticleTypes.SOUL, target.getX(), target.getY() + 1.3, target.getZ(), 20, 0.4, 0.8, 0.4, 0.05);
        session.sound(target.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_SCREAM, SoundCategory.HOSTILE, 1.5f, 1.6f);
        session.sound(target.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXTINGUISH_FIRE, SoundCategory.HOSTILE, 1.0f, 0.8f);
        target.discard();
        dispelled++;
        if (dispelled >= DISPEL_NEEDED) {
            session.wakeAll(DreamOutcome.SURVIVED_OBJECTIVE);
        } else {
            session.actionBar(tr("somnium.hint.void.dispelled", DISPEL_NEEDED - dispelled).formatted(Formatting.DARK_PURPLE));
        }
    }

    private static List<WatcherEntity> watchers(DreamSession session, BlockPos center, int radius) {
        return session.world.getEntitiesByClass(WatcherEntity.class, new Box(center).expand(radius, 24, radius),
                w -> w.isAlive() && session.owns(w));
    }

    private static WatcherEntity spawnWatcher(DreamSession session, BlockPos center, double minR, double maxR) {
        BlockPos pos = session.ringPos(center, minR, maxR);
        return session.spawnMob(ModEntities.WATCHER, pos, session.random.nextFloat() * 360f) instanceof WatcherEntity w ? w : null;
    }
}
