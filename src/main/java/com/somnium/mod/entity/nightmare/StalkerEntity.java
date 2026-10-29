package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Сталкер ("Плачущий ангел"): пока на него смотрит хоть один игрок — каменеет
 * (неподвижен, неуязвим, светится), стоит отвести взгляд — рвётся к жертве.
 * Появляется при Разрыве (рассудок ниже 10).
 *
 * Исправлено: неуязвимость в застывшем виде раньше висела на методе, который никто
 * не вызывал, а подсветка не синхронизировалась с клиентом.
 */
public class StalkerEntity extends AbstractNightmareEntity {

    private static final TrackedData<Boolean> FROZEN =
            DataTracker.registerData(StalkerEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final double SPEED = 0.45;
    private static final double DETECTION_RANGE = 32.0;
    private static final int CHECK_INTERVAL = 5;

    public StalkerEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 30.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 8.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, SPEED)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, DETECTION_RANGE)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.5);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(FROZEN, false);
    }

    public boolean isFrozen() {
        return this.dataTracker.get(FROZEN);
    }

    @Override
    protected void initGoals() {
        this.targetSelector.add(1, new RevengeGoal(this));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient()) return;
        if (this.age % CHECK_INTERVAL == 0) {
            this.dataTracker.set(FROZEN, isWatched());
        }
        if (isFrozen()) {
            this.setVelocity(0, Math.min(0, this.getVelocity().y), 0);
            this.getNavigation().stop();
            return;
        }
        PlayerEntity target = this.getTarget() instanceof PlayerEntity p ? p : this.getWorld().getClosestPlayer(this, DETECTION_RANGE);
        if (target == null || !target.isAlive() || target.isCreative() || target.isSpectator()) return;
        this.setTarget(target);
        if (this.age % 5 == 0) this.getNavigation().startMovingTo(target, 1.4);
        if (this.squaredDistanceTo(target) < 2.5 && this.age % 20 == 0) this.tryAttack(target);
        this.getLookControl().lookAt(target, 30.0f, 30.0f);
    }

    private boolean isWatched() {
        for (PlayerEntity player : this.getWorld().getPlayers()) {
            if (!player.isAlive() || player.isSpectator() || player.squaredDistanceTo(this) > DETECTION_RANGE * DETECTION_RANGE) continue;
            Vec3d eyes = player.getEyePos();
            Vec3d end = eyes.add(player.getRotationVector().multiply(DETECTION_RANGE));
            Box box = this.getBoundingBox().expand(0.5);
            if (box.raycast(eyes, end).isPresent() && player.canSee(this)) return true;
        }
        return false;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (isFrozen() && !source.isOf(DamageTypes.OUT_OF_WORLD) && !source.isOf(DamageTypes.GENERIC_KILL)) {
            return false; // каменная статуя
        }
        return super.damage(source, amount);
    }

    @Override
    public void takeKnockback(double strength, double x, double z) {
        if (!isFrozen()) super.takeKnockback(strength * 0.3, x, z);
    }

    @Override
    public boolean isGlowing() {
        return isFrozen() || super.isGlowing();
    }
}
