package com.somnium.mod.entity.nightmare;

import com.somnium.mod.entity.SkinOwner;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Зеркальное отражение игрока (Зеркальная комната). Первые 20 секунд стоит за стеклом
 * и зеркально повторяет движения своего игрока, потом стекло разбивается и отражение
 * нападает, ослепляя жертву. Носит скин и имя своего игрока.
 */
public class MirrorReflectionEntity extends AbstractNightmareEntity implements SkinOwner {

    private static final TrackedData<Optional<UUID>> SKIN_OWNER =
            DataTracker.registerData(MirrorReflectionEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final int BREAK_GLASS_DELAY = 400;

    private int ticksAlive = 0;
    private boolean attacking = false;
    @Nullable private ServerPlayerEntity targetPlayer;
    @Nullable private BlockPos mirrorPlanePos;

    public MirrorReflectionEntity(EntityType<? extends AbstractNightmareEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createMirrorReflectionAttributes() {
        return AbstractNightmareEntity.createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 30.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.28)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 6.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(SKIN_OWNER, Optional.empty());
    }

    @Override
    public Optional<UUID> getSkinOwner() {
        return this.dataTracker.get(SKIN_OWNER);
    }

    /** Боевой ИИ включается только после разбития стекла (startAttacking). */
    @Override
    protected void initGoals() {}

    public void setTargetPlayer(@Nullable ServerPlayerEntity player) {
        this.targetPlayer = player;
        if (player != null) {
            this.setCustomName(player.getName());
            this.setCustomNameVisible(true);
            this.dataTracker.set(SKIN_OWNER, Optional.of(player.getUuid()));
            if (attacking) this.setTarget(player);
        }
    }

    public boolean isTarget(ServerPlayerEntity player) {
        return targetPlayer == player;
    }

    public void setMirrorPlane(BlockPos pos) {
        this.mirrorPlanePos = pos;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient()) return;
        ticksAlive++;

        // Без своего игрока отражению незачем существовать (игрок проснулся, перезапуск сервера)
        if (ticksAlive > 30 && (targetPlayer == null || targetPlayer.isRemoved()
                || targetPlayer.getWorld() != this.getWorld())) {
            this.discard();
            return;
        }
        if (ticksAlive < BREAK_GLASS_DELAY) {
            mirrorPlayerMovement();
            if (ticksAlive == BREAK_GLASS_DELAY - 20) {
                this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK,
                        SoundCategory.HOSTILE, 2.0f, 0.5f);
            }
        } else if (!attacking) {
            attacking = true;
            startAttacking();
        }
        if (attacking && ticksAlive % 60 == 0) {
            this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_SCREAM,
                    SoundCategory.HOSTILE, 1.0f, 0.3f);
        }
    }

    /** Зеркальная позиция относительно плоскости стекла (ось X), зеркальный поворот головы. */
    private void mirrorPlayerMovement() {
        if (mirrorPlanePos == null || targetPlayer == null) return;
        double mirrorX = mirrorPlanePos.getX() + 0.5;
        double mirroredX = mirrorX - (targetPlayer.getX() - mirrorX);
        this.setPosition(mirroredX, targetPlayer.getY(), targetPlayer.getZ());
        this.setVelocity(0, 0, 0);
        float mirroredYaw = -targetPlayer.getYaw();
        this.setYaw(mirroredYaw);
        this.headYaw = mirroredYaw;
        this.bodyYaw = mirroredYaw;
        this.setPitch(targetPlayer.getPitch());
        this.setSneaking(targetPlayer.isSneaking());
        this.setSprinting(targetPlayer.isSprinting());
    }

    private void startAttacking() {
        this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 3.0f, 0.3f);
        if (targetPlayer != null) {
            targetPlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0, false, false, true));
            this.setTarget(targetPlayer);
        }
        this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(0.35);
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.2, false));
        this.goalSelector.add(2, new LookAtEntityGoal(this, PlayerEntity.class, 12.0f));
        this.targetSelector.add(1, new RevengeGoal(this));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
    }

    @Override
    public void onDeath(net.minecraft.entity.damage.DamageSource source) {
        super.onDeath(source);
        if (!this.getWorld().isClient()) {
            this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 2.0f, 1.0f);
        }
    }
}
