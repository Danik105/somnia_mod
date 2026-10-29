package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Кошмар — босс, который приходит при рассудке 5 и ниже. Каждые 15 секунд меняет облик:
 *  - Наблюдатель: замирает, пока на него смотрят, и рвётся вперёд, когда отворачиваешься;
 *  - Тень: невидим, пока на него не смотришь, удар накрывает темнотой;
 *  - Голем: медленный, но раз в 3 секунды бьёт по земле, раскидывая всех рядом.
 * Смена облика видна по вспышке и полосе босса.
 */
public class NightmareAmalgamEntity extends AbstractNightmareEntity {

    private enum Phase { WATCHER, SHADE, GOLEM }

    private static final int PHASE_DURATION_TICKS = 300;
    private static final int SLAM_INTERVAL = 60;
    private static final double SLAM_RADIUS = 4.5;
    private static final double VIEW_DOT = 0.75;

    private final ServerBossBar bossBar = new ServerBossBar(Text.translatable("entity.somnium.nightmare_amalgam"),
            BossBar.Color.PURPLE, BossBar.Style.NOTCHED_10);
    private Phase phase = Phase.WATCHER;
    private int phaseTimer;
    private int slamTimer;

    public NightmareAmalgamEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        this.experiencePoints = 40;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 120.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 7.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.7)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 40.0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(this.getWorld() instanceof ServerWorld world)) return;

        if (++phaseTimer >= PHASE_DURATION_TICKS) {
            phaseTimer = 0;
            phase = Phase.values()[(phase.ordinal() + 1) % Phase.values().length];
            this.setInvisible(false);
            world.spawnParticles(ParticleTypes.SQUID_INK, this.getX(), this.getBodyY(0.5), this.getZ(), 40, 0.6, 1.0, 0.6, 0.05);
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, this.getX(), this.getBodyY(0.5), this.getZ(), 30, 0.6, 1.0, 0.6, 0.1);
            world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_VILLAGER_CONVERTED, this.getSoundCategory(), 1.5f, 0.5f);
        }

        boolean watched = isWatched(world);
        var speed = this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        switch (phase) {
            case WATCHER -> {
                speed.setBaseValue(watched ? 0.0 : 0.38);
                if (watched) {
                    this.getNavigation().stop();
                    this.setVelocity(0, Math.min(0, this.getVelocity().y), 0);
                }
            }
            case SHADE -> {
                speed.setBaseValue(0.3);
                this.setInvisible(!watched);
            }
            case GOLEM -> {
                speed.setBaseValue(0.18);
                if (++slamTimer >= SLAM_INTERVAL && this.getTarget() != null
                        && this.getTarget().squaredDistanceTo(this) < SLAM_RADIUS * SLAM_RADIUS) {
                    slamTimer = 0;
                    slam(world);
                }
            }
        }
        bossBar.setName(Text.translatable("entity.somnium.nightmare_amalgam")
                .append(" · ").append(Text.translatable("somnium.amalgam.phase." + phase.name().toLowerCase())));
        bossBar.setPercent(this.getHealth() / this.getMaxHealth());
    }

    private void slam(ServerWorld world) {
        world.spawnParticles(ParticleTypes.EXPLOSION, this.getX(), this.getY() + 0.2, this.getZ(), 6, 1.5, 0.1, 1.5, 0.0);
        world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, this.getSoundCategory(), 1.6f, 0.5f);
        for (PlayerEntity player : world.getEntitiesByClass(PlayerEntity.class, this.getBoundingBox().expand(SLAM_RADIUS),
                p -> !p.isCreative() && !p.isSpectator())) {
            player.damage(world.getDamageSources().mobAttack(this), 6.0f);
            Vec3d away = player.getPos().subtract(this.getPos()).normalize();
            player.addVelocity(away.x * 1.2, 0.6, away.z * 1.2);
            player.velocityModified = true;
        }
    }

    private boolean isWatched(ServerWorld world) {
        for (PlayerEntity player : world.getPlayers()) {
            if (player.isSpectator() || player.squaredDistanceTo(this) > 40 * 40) continue;
            Vec3d look = player.getRotationVec(1.0f);
            Vec3d to = this.getPos().add(0, this.getHeight() * 0.6, 0).subtract(player.getEyePos()).normalize();
            if (look.dotProduct(to) > VIEW_DOT && player.canSee(this)) return true;
        }
        return false;
    }

    @Override
    public boolean tryAttack(Entity target) {
        boolean hit = super.tryAttack(target);
        if (hit && phase == Phase.SHADE && target instanceof ServerPlayerEntity player) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 100, 0));
        }
        return hit;
    }

    @Override
    public void onStartedTrackingBy(ServerPlayerEntity player) {
        super.onStartedTrackingBy(player);
        bossBar.addPlayer(player);
    }

    @Override
    public void onStoppedTrackingBy(ServerPlayerEntity player) {
        super.onStoppedTrackingBy(player);
        bossBar.removePlayer(player);
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Phase", phase.ordinal());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        phase = Phase.values()[Math.floorMod(nbt.getInt("Phase"), Phase.values().length)];
    }
}
