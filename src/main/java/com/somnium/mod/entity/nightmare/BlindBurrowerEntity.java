package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Слепой крот «Шахты-лабиринта». Глаз у него нет, ищет на слух: слышит шаги в 18 блоках,
 * бег в 28. Кто крадётся (Shift), того он не слышит, если не подойти вплотную. Потеряв
 * жертву, идёт к месту, где слышал её последний раз, и принюхивается. Крик шахтёра
 * сразу выдаёт ему игрока.
 */
public class BlindBurrowerEntity extends AbstractNightmareEntity {

    private static final double HEAR_WALK = 18.0;
    private static final double HEAR_SPRINT = 28.0;
    private static final double FEEL_RANGE = 2.5;
    private static final int WARN_COOLDOWN = 400;

    @Nullable private Vec3d lastHeard;
    private int sniffTicks;
    private long nextWarn;

    public BlindBurrowerEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 30.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 7.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void initGoals() {
        // Без зрения: цель выбирает tick() по звуку, а не ActiveTargetGoal по линии взгляда
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.15, true));
        this.goalSelector.add(3, new WanderAroundFarGoal(this, 0.6));
    }

    @Override
    public void tick() {
        super.tick();
        if (!(this.getWorld() instanceof ServerWorld world) || this.age % 5 != 0) return;

        PlayerEntity heard = null;
        double best = Double.MAX_VALUE;
        for (PlayerEntity player : world.getPlayers()) {
            if (player.isCreative() || player.isSpectator() || !player.isAlive()) continue;
            double dist = player.distanceTo(this);
            if (dist < best && canHear(player, dist)) {
                best = dist;
                heard = player;
            }
        }

        if (heard != null) {
            if (this.getTarget() != heard) {
                world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, this.getSoundCategory(), 0.7f, 1.6f);
                if (heard instanceof ServerPlayerEntity sp && world.getTime() >= nextWarn) {
                    nextWarn = world.getTime() + WARN_COOLDOWN;
                    sp.sendMessage(Text.translatable("somnium.hint.burrower.heard").formatted(Formatting.GRAY), true);
                }
            }
            this.setTarget(heard);
            lastHeard = heard.getPos();
            sniffTicks = 0;
            return;
        }

        // Жертва затихла: идём на последний звук и принюхиваемся
        if (this.getTarget() instanceof PlayerEntity target && target.distanceTo(this) > FEEL_RANGE) {
            this.setTarget(null);
        }
        if (lastHeard != null) {
            if (this.getPos().squaredDistanceTo(lastHeard) > 2.0) {
                this.getNavigation().startMovingTo(lastHeard.x, lastHeard.y, lastHeard.z, 1.0);
            } else {
                sniffTicks += 5;
                world.spawnParticles(ParticleTypes.POOF, this.getX(), this.getEyeY(), this.getZ(), 2, 0.3, 0.1, 0.3, 0.01);
                if (sniffTicks % 20 == 0) {
                    world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_SNIFFER_SNIFFING, this.getSoundCategory(), 1.0f, 0.6f);
                }
                if (sniffTicks >= 80) lastHeard = null;
            }
        }
    }

    /** Крадущегося слышно только вплотную, идущего в 18 блоках, бегущего в 28. */
    private boolean canHear(PlayerEntity player, double dist) {
        if (dist <= FEEL_RANGE) return true;
        if (player.isSneaking()) return false;
        Vec3d v = player.getVelocity();
        boolean moving = v.x * v.x + v.z * v.z > 0.0004 || !player.isOnGround();
        if (!moving) return false;
        return dist <= (player.isSprinting() ? HEAR_SPRINT : HEAR_WALK);
    }

    /** Крик шахтёра выдаёт жертву, даже если она крадётся. */
    @Override
    public void setTarget(@Nullable net.minecraft.entity.LivingEntity target) {
        super.setTarget(target);
        if (target != null) lastHeard = target.getPos();
    }
}
