package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.entity.nightmare.MirroredDoubleEntity;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Пустошь зеркал: "Поймай своё отражение". Через 25 секунд в пустоши появляется зеркало —
 * его нужно коснуться 3 раза, а оно ускользает в новые места. По следу идёт неуязвимый
 * Двойник и ускоряется после каждого касания; догонит — заберёт рассудок.
 * Третье касание будит всю группу (раньше просыпался только коснувшийся,
 * остальные оставались без зеркала до конца таймера).
 */
public final class MirrorWastesScenario extends DreamScenario {

    private static final int RADIUS = 60;
    private static final int MIRROR_DELAY = 500;
    private static final int MIRROR_STAND_TICKS = 200;
    private static final int TOUCHES_NEEDED = 3;
    private static final float GRAB_SANITY = -12.0f;

    private MirroredDoubleEntity doubleEntity;
    private final List<BlockPos> mirrorBlocks = new ArrayList<>();
    private BlockPos mirror;
    private long nextJump;
    private int touches;
    private long grabCooldownUntil;

    @Override
    public int areaRadius() {
        return RADIUS + 16;
    }

    @Override
    public int boundaryRadius() {
        return RADIUS + 8;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        BlockPos origin = session.origin;
        double angle = session.random.nextDouble() * Math.PI * 2;
        BlockPos start = session.groundNear(origin.getX() + (int) (Math.cos(angle) * 25), origin.getY(),
                origin.getZ() + (int) (Math.sin(angle) * 25));
        MirroredDoubleEntity entity = ModEntities.MIRRORED_DOUBLE.create(session.world);
        if (entity != null) {
            entity.refreshPositionAndAngles(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0f, 0f);
            entity.setAiDisabled(true);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setPersistent();
            entity.setSkinOwner(leader.getUuid());
            doubleEntity = session.add(entity);
        }
        return origin;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return around(session.origin, RADIUS + 2, -1, 6);
    }

    @Override
    public void applyEffects(ServerPlayerEntity player, int duration) {
        DreamFx.effect(player, StatusEffects.WEAKNESS, duration, 0);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        player.sendMessage(tr("somnium.hint.mirror_wastes.start").formatted(Formatting.GRAY), true);
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();

        if (mirror == null && session.elapsed() >= MIRROR_DELAY && touches < TOUCHES_NEEDED) {
            placeMirror(session);
            session.hint(tr("somnium.hint.mirror_wastes.appeared").formatted(Formatting.DARK_PURPLE), 6);
            for (ServerPlayerEntity player : session.players()) {
                player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.AMBIENT, 1.0f, 0.6f);
            }
        }

        if (mirror != null) {
            if (now % 5 == 0) {
                world.spawnParticles(ParticleTypes.END_ROD, mirror.getX() + 0.5, mirror.getY() + 1.5, mirror.getZ() + 0.5,
                        2, 0.1, 1.6, 0.1, 0.01);
            }
            ServerPlayerEntity toucher = null;
            for (ServerPlayerEntity player : session.players()) {
                double dx = player.getX() - (mirror.getX() + 0.5);
                double dz = player.getZ() - (mirror.getZ() + 0.5);
                if (dx * dx + dz * dz < 4.0 && Math.abs(player.getY() - mirror.getY()) < 4) {
                    toucher = player;
                    break;
                }
            }
            if (toucher != null) {
                touches++;
                session.sound(mirror, SoundEvents.BLOCK_GLASS_HIT, SoundCategory.BLOCKS, 1.0f, 0.7f);
                if (touches >= TOUCHES_NEEDED) {
                    removeMirror(world);
                    world.spawnParticles(ParticleTypes.END_ROD, toucher.getX(), toucher.getY() + 1, toucher.getZ(),
                            40, 0.5, 1.0, 0.5, 0.05);
                    session.sound(toucher.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.AMBIENT, 1.2f, 0.6f);
                    session.actionBar(tr("somnium.hint.mirror_wastes.shattered").formatted(Formatting.GOLD));
                    session.wakeAll(DreamOutcome.SURVIVED_OBJECTIVE);
                    return;
                }
                session.actionBar(tr("somnium.hint.mirror_wastes.touch", touches, TOUCHES_NEEDED).formatted(Formatting.DARK_PURPLE));
                placeMirror(session);
            } else if (now >= nextJump) {
                placeMirror(session);
                for (ServerPlayerEntity player : session.players()) {
                    player.playSound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.AMBIENT, 0.9f, 0.5f);
                }
                session.actionBar(tr("somnium.hint.mirror_wastes.jumped").formatted(Formatting.GRAY));
            }
        }

        tickDouble(session, now);
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (mirror == null || session.now() % 240 != 0) return;
        double dx = mirror.getX() - player.getX();
        double dz = mirror.getZ() - player.getZ();
        String dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");
        player.sendMessage(tr("somnium.hint.mirror_wastes.direction", tr("somnium.direction." + dir))
                .formatted(Formatting.GRAY), true);
    }

    private void tickDouble(DreamSession session, long now) {
        if (doubleEntity == null || doubleEntity.isRemoved()) return;
        ServerPlayerEntity target = session.nearestPlayer(doubleEntity.getPos());
        if (target == null) return;
        double dx = target.getX() - doubleEntity.getX();
        double dz = target.getZ() - doubleEntity.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist > 0.5 && now >= grabCooldownUntil && now % 2 == 0) {
            double speed = 0.18 + 0.07 * touches;
            double step = Math.min(speed * 2, dist - 0.4);
            BlockPos ground = session.groundNear((int) Math.floor(doubleEntity.getX() + dx / dist * step),
                    doubleEntity.getBlockY(), (int) Math.floor(doubleEntity.getZ() + dz / dist * step));
            doubleEntity.setPosition(doubleEntity.getX() + dx / dist * step, ground.getY(), doubleEntity.getZ() + dz / dist * step);
            float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
            doubleEntity.setYaw(yaw);
            doubleEntity.setBodyYaw(yaw);
            doubleEntity.setHeadYaw(yaw);
        }

        if (dist < 1.8 && now >= grabCooldownUntil) {
            grabCooldownUntil = now + 120;
            SanityManager.add(target, GRAB_SANITY);
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 100, 0));
            target.sendMessage(tr("somnium.hint.mirror_wastes.grabbed").formatted(Formatting.DARK_RED), true);
            session.sound(target.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_SCREAM, SoundCategory.HOSTILE, 0.8f, 0.5f);
            double angle = session.random.nextDouble() * Math.PI * 2;
            BlockPos away = session.groundNear((int) (target.getX() + Math.cos(angle) * 15), target.getBlockY(),
                    (int) (target.getZ() + Math.sin(angle) * 15));
            doubleEntity.setPosition(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
        }

        if (now % 300 == 0) {
            session.sound(doubleEntity.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_AMBIENT, SoundCategory.HOSTILE, 0.7f, 0.5f);
        }
    }

    /** Рама 3×4 из полированного чернита со стеклом посередине — в 12..56 блоках от центра, вдали от игроков. */
    private void placeMirror(DreamSession session) {
        ServerWorld world = session.world;
        removeMirror(world);
        BlockPos origin = session.origin;
        BlockPos base = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = session.random.nextDouble() * Math.PI * 2;
            double dist = 12 + session.random.nextDouble() * (RADIUS - 16);
            BlockPos candidate = new BlockPos(origin.getX() + (int) Math.round(Math.cos(angle) * dist), origin.getY(),
                    origin.getZ() + (int) Math.round(Math.sin(angle) * dist));
            base = candidate;
            ServerPlayerEntity nearest = session.nearestPlayer(candidate.toCenterPos());
            if (nearest == null || nearest.squaredDistanceTo(candidate.toCenterPos()) > 14 * 14) break;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                boolean frame = dx != 0 || dy == 0 || dy == 3;
                BlockPos pos = base.add(dx, dy, 0);
                set(world, pos, (frame ? Blocks.POLISHED_BLACKSTONE : Blocks.GLASS).getDefaultState());
                mirrorBlocks.add(pos);
            }
        }
        mirror = base;
        nextJump = session.now() + MIRROR_STAND_TICKS;
        world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, base.getX() + 0.5, base.getY() + 1.5, base.getZ() + 0.5,
                20, 0.6, 1.0, 0.6, 0.02);
    }

    private void removeMirror(ServerWorld world) {
        for (BlockPos pos : mirrorBlocks) {
            set(world, pos, Blocks.AIR.getDefaultState());
        }
        mirrorBlocks.clear();
        mirror = null;
    }
}
