package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Призрачный угорь «Тонущего города». Живёт только в воде: плавает сам, без наземной
 * навигации. Кружит под водой, а заметив игрока в воде в 14 блоках, бросается рывком.
 * Укус замедляет. На суше беспомощно бьётся и быстро задыхается.
 */
public class PhantomEelEntity extends AbstractNightmareEntity {

    private static final double HUNT_RANGE = 14.0;
    private static final int LUNGE_COOLDOWN = 40;

    private int lungeTimer;
    private int airTicks;
    private Vec3d wander = Vec3d.ZERO;

    public PhantomEelEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 14.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.0);
    }

    @Override
    protected void initGoals() {
        // Движение целиком в tick(): обычный ИИ ходит по дну, а угорь плавает
    }

    @Override
    public void tick() {
        super.tick();
        if (!(this.getWorld() instanceof ServerWorld world)) return;
        boolean inWater = this.isTouchingWater();
        this.setNoGravity(inWater);

        if (!inWater) {
            // Выброшен на сушу: бьётся и задыхается
            if (this.isOnGround() && this.age % 10 == 0) {
                this.addVelocity((random.nextDouble() - 0.5) * 0.2, 0.3, (random.nextDouble() - 0.5) * 0.2);
                world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_COD_FLOP, this.getSoundCategory(), 1.0f, 0.6f);
            }
            if (++airTicks > 100 && this.age % 20 == 0) this.damage(world.getDamageSources().dryOut(), 2.0f);
            return;
        }
        airTicks = 0;

        PlayerEntity prey = world.getClosestPlayer(this.getX(), this.getY(), this.getZ(), HUNT_RANGE,
                p -> p.isTouchingWater() && !((PlayerEntity) p).isCreative() && !p.isSpectator());
        Vec3d vel = this.getVelocity().multiply(0.9);
        if (prey != null) {
            this.setTarget(prey);
            Vec3d to = prey.getEyePos().subtract(0, 0.4, 0).subtract(this.getPos());
            Vec3d dir = to.normalize();
            if (--lungeTimer <= 0 && to.lengthSquared() < 36) {
                lungeTimer = LUNGE_COOLDOWN;
                vel = dir.multiply(0.9);
                world.spawnParticles(ParticleTypes.BUBBLE, this.getX(), this.getY(), this.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
            } else {
                vel = vel.add(dir.multiply(0.04));
            }
            if (to.lengthSquared() < 1.8 && this.age % 10 == 0 && this.tryAttack(prey)) {
                prey.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1));
            }
        } else {
            if (this.age % 60 == 0 || wander == Vec3d.ZERO) {
                wander = new Vec3d(random.nextDouble() - 0.5, (random.nextDouble() - 0.5) * 0.4, random.nextDouble() - 0.5)
                        .normalize().multiply(0.02);
            }
            vel = vel.add(wander);
        }
        double max = 0.9;
        if (vel.lengthSquared() > max * max) vel = vel.normalize().multiply(max);
        this.setVelocity(vel);
        if (vel.horizontalLengthSquared() > 1.0E-4) {
            float yaw = (float) (Math.atan2(vel.z, vel.x) * 57.2957763671875) - 90.0f;
            this.setYaw(yaw);
            this.bodyYaw = yaw;
            this.headYaw = yaw;
        }
    }

    @Override
    public boolean canBreatheInWater() {
        return true;
    }

    @Override
    public boolean isPushedByFluids() {
        return false;
    }
}
