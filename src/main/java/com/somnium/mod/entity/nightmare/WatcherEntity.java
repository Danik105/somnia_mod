package com.somnium.mod.entity.nightmare;

import com.somnium.mod.sanity.SanityManager;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Наблюдатель (Пустота с глазами): замирает, пока на него смотрит хоть один игрок,
 * и подкрадывается, когда все отводят взгляд. Касание обжигает (урон, −6 рассудка,
 * отброс), после чего Наблюдатель отшатывается во тьму.
 *
 * Раньше базовая скорость была 0 — Наблюдатель физически не мог сдвинуться с места.
 */
public class WatcherEntity extends AbstractNightmareEntity {

    private static final double VIEW_CONE_DOT_THRESHOLD = 0.7;
    private static final double TOUCH_RANGE_SQ = 2.2 * 2.2;
    private static final int TOUCH_COOLDOWN = 100;

    private long nextTouch;

    public WatcherEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 5.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.8);
    }

    @Override
    protected void initGoals() {
        // Движение и атака — в tick(): обычный ИИ ходил бы и бил, даже когда на него смотрят
        this.goalSelector.add(5, new LookAtEntityGoal(this, PlayerEntity.class, 40.0f));
    }

    @Override
    public void tick() {
        super.tick();
        if (!(this.getWorld() instanceof ServerWorld world)) return;

        boolean watched = false;
        for (PlayerEntity player : world.getPlayers()) {
            if (!player.isSpectator() && player.squaredDistanceTo(this) < 40 * 40 && isBeingWatchedBy(player)) {
                watched = true;
                break;
            }
        }
        PlayerEntity nearest = world.getClosestPlayer(this, 40.0);
        if (watched || nearest == null || nearest.isCreative() || nearest.isSpectator()) {
            this.getNavigation().stop();
        } else if (this.age % 10 == 0) {
            this.getNavigation().startMovingTo(nearest, 1.6);
        }

        if (nearest instanceof ServerPlayerEntity player && !player.isCreative() && !player.isSpectator()
                && world.getTime() >= nextTouch && this.squaredDistanceTo(player) < TOUCH_RANGE_SQ) {
            nextTouch = world.getTime() + TOUCH_COOLDOWN;
            player.damage(world.getDamageSources().mobAttack(this), 5.0f);
            SanityManager.add(player, -6.0f);
            Vec3d away = player.getPos().subtract(this.getPos()).normalize();
            player.addVelocity(away.x * 0.9, 0.35, away.z * 0.9);
            player.velocityModified = true;
            world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM,
                    this.getSoundCategory(), 0.5f, 1.8f);
            double angle = this.getRandom().nextDouble() * Math.PI * 2;
            this.teleport(player.getX() + Math.cos(angle) * 14.0, player.getY(), player.getZ() + Math.sin(angle) * 14.0);
            this.getNavigation().stop();
        }
    }

    private boolean isBeingWatchedBy(PlayerEntity player) {
        Vec3d look = player.getRotationVec(1.0f).normalize();
        Vec3d toEntity = this.getPos().add(0, this.getHeight() * 0.6, 0).subtract(player.getEyePos()).normalize();
        return look.dotProduct(toEntity) > VIEW_CONE_DOT_THRESHOLD;
    }
}
