package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * ДОБАВЛЕНО (моб-наблюдатель): сущность с механикой "Weeping Angel" из Doctor Who.
 *
 * Поведение:
 * - Когда игрок смотрит на моба (в пределах угла обзора) → моб ЗАМИРАЕТ (не двигается, статуя)
 * - Когда игрок НЕ смотрит на моба → моб БЫСТРО приближается к игроку
 * - При касании/атаке → наносит урон
 *
 * Технические детали:
 * - Проверка "смотрит ли игрок" делается через raycast от глаз игрока в направлении взгляда
 * - Если луч пересекает bounding box моба → игрок смотрит на него
 * - В замороженном состоянии моб неподвижен, в активном - очень быстр
 */
public class StalkerEntity extends AbstractNightmareEntity {

    /** Насколько быстро моб двигается когда игрок НЕ смотрит (множитель скорости) */
    private static final double STALKING_SPEED = 0.5;
    /** Насколько далеко моб может "видеть" игрока для преследования */
    private static final double DETECTION_RANGE = 32.0;
    /** Как часто проверять состояние "смотрит ли игрок" (каждые N тиков) */
    private static final int CHECK_INTERVAL = 5;

    /** Текущее состояние: true = заморожен (игрок смотрит), false = активен (преследует) */
    private boolean frozen = false;
    /** Счётчик тиков до следующей проверки состояния */
    private int ticksUntilCheck = 0;

    public StalkerEntity(EntityType<? extends HostileEntity> entityType, World world) {
        super(entityType, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 30.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 8.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, STALKING_SPEED) // быстрый когда активен
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, DETECTION_RANGE)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.5); // трудно отбросить
    }

    @Override
    protected void initGoals() {
        // ВАЖНО: стандартные AI цели НЕ добавляем - у Stalker особая логика движения,
        // которая целиком реализована в tick() методе ниже.
        this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 16.0f));
        this.goalSelector.add(8, new LookAroundGoal(this));

        // Цель атаки остаётся стандартной (касание = урон)
        this.targetSelector.add(1, new RevengeGoal(this));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getEntityWorld().isClient()) return; // вся логика на сервере

        ticksUntilCheck--;
        if (ticksUntilCheck <= 0) {
            ticksUntilCheck = CHECK_INTERVAL;
            updateFrozenState();
        }

        // Если заморожен - полностью останавливаем движение
        if (frozen) {
            this.setVelocity(Vec3d.ZERO);
            this.velocityDirty = true;
        } else {
            // Если не заморожен - преследуем ближайшего игрока
            stalkNearestPlayer();
        }
    }

    /**
     * Обновляет состояние frozen: проверяет, смотрит ли хотя бы один игрок на этого моба.
     * Если хотя бы один смотрит → заморожен, иначе → активен.
     */
    private void updateFrozenState() {
        boolean anyPlayerLooking = false;

        // Ищем всех игроков в радиусе DETECTION_RANGE
        var nearbyPlayers = this.getEntityWorld().getEntitiesByClass(
                ServerPlayerEntity.class,
                this.getBoundingBox().expand(DETECTION_RANGE),
                player -> player.isAlive() && !player.isSpectator()
        );

        for (ServerPlayerEntity player : nearbyPlayers) {
            if (isPlayerLookingAt(player)) {
                anyPlayerLooking = true;
                break;
            }
        }

        frozen = anyPlayerLooking;
    }

    /**
     * Проверяет, смотрит ли конкретный игрок на этого моба.
     *
     * Логика: raycast от глаз игрока в направлении взгляда. Если луч пересекает
     * bounding box моба в пределах дистанции обзора → игрок смотрит на моба.
     */
    private boolean isPlayerLookingAt(ServerPlayerEntity player) {
        Vec3d playerEyes = player.getEyePos();
        Vec3d playerLook = player.getRotationVector(); // направление взгляда

        double distance = playerEyes.distanceTo(this.getBlockPos().toCenterPos());
        if (distance > DETECTION_RANGE) return false;

        // Raycast: луч от глаз игрока в направлении взгляда на длину = дистанция до моба + запас
        Vec3d rayEnd = playerEyes.add(playerLook.multiply(distance + 2.0));

        // Проверяем пересечение луча с bounding box моба
        Box mobBox = this.getBoundingBox().expand(0.5); // небольшой запас для удобства
        var hit = mobBox.raycast(playerEyes, rayEnd);

        return hit.isPresent();
    }

    /**
     * Преследует ближайшего игрока когда НЕ заморожен.
     * Движется ПРЯМО к игроку, игнорируя препятствия (как призрак).
     */
    private void stalkNearestPlayer() {
        var target = this.getTarget();
        PlayerEntity player;
        if (!(target instanceof PlayerEntity)) {
            // Нет цели - ищем ближайшего игрока
            player = this.getEntityWorld().getClosestPlayer(this, DETECTION_RANGE);
            if (player != null) {
                this.setTarget(player);
            }
        } else {
            player = (PlayerEntity) target;
        }

        if (player == null || !player.isAlive()) return;

        // Вектор направления к игроку
        Vec3d toPlayer = player.getBlockPos().toCenterPos().subtract(this.getBlockPos().toCenterPos()).normalize();

        // Устанавливаем скорость движения прямо к игроку
        this.setVelocity(toPlayer.multiply(STALKING_SPEED));
        this.velocityDirty = true;

        // Поворачиваем голову в сторону игрока
        this.getLookControl().lookAt(player, 30.0f, 30.0f);
    }

    /**
     * Stalker не получает урон пока заморожен (игрок смотрит на него).
     */
    public boolean damageWithWorld(net.minecraft.server.world.ServerWorld world, DamageSource source, float amount) {
        if (frozen) {
            // Заморожен = неуязвим (каменная статуя)
            return false;
        }
        return super.damage(source, amount);
    }

    /**
     * Stalker не может быть отброшен назад - стоит как статуя когда заморожен,
     * и движется неумолимо когда активен.
     */
    @Override
    public void takeKnockback(double strength, double x, double z) {
        if (frozen) return; // заморожен = нет отбрасывания
        // Даже в активном состоянии - сильно снижаем отбрасывание
        super.takeKnockback(strength * 0.3, x, z);
    }

    /**
     * Визуальная подсказка: когда заморожен, моб светится (glowing effect),
     * чтобы игрок понимал что он "активен" как статуя.
     */
    @Override
    public boolean isGlowing() {
        return frozen;
    }
}
