package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * НОВЫЙ КОШМАР: Зеркальное отражение игрока.
 *
 * Поведение:
 * 1. Спавнится напротив игрока через "зеркало" (невидимую плоскость)
 * 2. Копирует внешний вид игрока (скин, одежда)
 * 3. Зеркально повторяет движения игрока (если игрок идёт вперёд, отражение идёт назад)
 * 4. Через некоторое время начинает "ломать зеркало" и атаковать
 * 5. Издаёт жуткие звуки (стекло, искажённые звуки игрока)
 */
public class MirrorReflectionEntity extends AbstractNightmareEntity {

    private static final int BREAK_GLASS_DELAY = 400; // 20 секунд до начала атаки (400 тиков)
    private int ticksAlive = 0;
    private boolean glassBreaking = false;
    private ServerPlayerEntity targetPlayer;
    private BlockPos mirrorPlanePos; // Центр "зеркала"

    public MirrorReflectionEntity(EntityType<? extends AbstractNightmareEntity> type, World world) {
        super(type, world);
        // ДОБАВЛЕНО: показываем ник (Custom Name Visible) для зеркального отражения
        this.setCustomNameVisible(true);
    }

    /**
     * ИСПРАВЛЕНО: НЕ добавляем стандартный AI пока моб в зеркальном режиме.
     * Первые 20 секунд моб только повторяет движения игрока (без блуждания).
     * После разбития стекла - добавляем боевой AI.
     */
    @Override
    protected void initGoals() {
        // В начале НЕ добавляем никаких AI целей - моб полностью контролируется вручную
        // через mirrorPlayerMovement(). AI будет добавлен позже в startAttacking().
    }

    public static DefaultAttributeContainer.Builder createMirrorReflectionAttributes() {
        return AbstractNightmareEntity.createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 30.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.28)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 6.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
    }

    public void setTargetPlayer(ServerPlayerEntity player) {
        this.targetPlayer = player;
        // ДОБАВЛЕНО: устанавливаем ник игрока как имя моба
        if (player != null) {
            this.setCustomName(player.getName());
        }
    }

    public void setMirrorPlane(BlockPos pos) {
        this.mirrorPlanePos = pos;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getEntityWorld().isClient()) return;

        ticksAlive++;

        // ИСПРАВЛЕНИЕ: не удаляем моба, если он еще не инициализирован (первые 30 тиков)
        // targetPlayer устанавливается через ~10 тиков после спавна, даем время на инициализацию
        if (ticksAlive > 30) {
            if (targetPlayer == null || !targetPlayer.isAlive()) {
                this.discard();
                return;
            }
        }

        // Первые 20 секунд - зеркальное отражение (молчаливое следование)
        if (ticksAlive < BREAK_GLASS_DELAY) {
            // ДОБАВЛЕНО: логирование для отладки
            if (ticksAlive % 20 == 0) { // Раз в секунду
                if (mirrorPlanePos == null) {
                    com.somnium.mod.SomniumMod.LOGGER.warn("[MirrorReflection] mirrorPlanePos is NULL!");
                } else {
                    com.somnium.mod.SomniumMod.LOGGER.info("[MirrorReflection] Following player. Mirror at X={}, Player at X={}, Mob at X={}",
                        mirrorPlanePos.getX(), targetPlayer.getX(), this.getX());
                }
            }
            mirrorPlayerMovement();

            // Звук стекла при начале "трещин" (за 1 секунду до атаки)
            if (ticksAlive == BREAK_GLASS_DELAY - 20) {
                this.getEntityWorld().playSound(null, this.getBlockPos(),
                        SoundEvents.BLOCK_GLASS_BREAK,
                        net.minecraft.sound.SoundCategory.HOSTILE,
                        2.0f, 0.5f);
            }
        }
        // После - начинаем атаку
        else if (!glassBreaking) {
            glassBreaking = true;
            startAttacking();
        }

        // ИЗМЕНЕНО: жуткие звуки только ПОСЛЕ начала атаки (не во время зеркального следования)
        if (glassBreaking && ticksAlive % 60 == 0) {
            this.getEntityWorld().playSound(null, this.getBlockPos(),
                    SoundEvents.ENTITY_ENDERMAN_SCREAM,
                    net.minecraft.sound.SoundCategory.HOSTILE,
                    1.0f, 0.3f);
        }
    }

    /**
     * ИСПРАВЛЕНО: Зеркально копирует движения игрока через стекло.
     * Моб находится ЗА СТЕКЛОМ и повторяет действия игрока зеркально,
     * пока стекло не разобьётся через 20 секунд.
     */
    private void mirrorPlayerMovement() {
        if (mirrorPlanePos == null || targetPlayer == null) return;

        Vec3d playerPos = new Vec3d(targetPlayer.getX(), targetPlayer.getY(), targetPlayer.getZ());

        // ИСПРАВЛЕНО: вычисляем зеркальную позицию относительно плоскости стекла
        // Стекло на mirrorPlanePos.X (это X координата центра стекла)
        // Если игрок на расстоянии D слева от стекла, моб на расстоянии D справа
        double mirrorX = mirrorPlanePos.getX();
        double distanceFromMirror = playerPos.x - mirrorX; // Расстояние игрока от стекла
        double mirroredX = mirrorX - distanceFromMirror; // Зеркальная позиция

        // Телепортируем отражение в зеркальную позицию
        this.setPosition(mirroredX, playerPos.y, playerPos.z);

        // ИСПРАВЛЕНО: сбрасываем velocity чтобы моб не продолжал двигаться по инерции
        this.setVelocity(0, 0, 0);

        // ИСПРАВЛЕНО: моб смотрит в зеркально противоположную сторону
        // В Minecraft yaw: 0°=юг, 90°=запад, 180°=север, 270°=восток
        // Для зеркала по оси X: mirroredYaw = -playerYaw
        float mirroredYaw = -targetPlayer.getYaw();
        this.setYaw(mirroredYaw);
        this.headYaw = mirroredYaw; // Поворот головы тоже синхронизируем
        this.bodyYaw = mirroredYaw; // И тела

        // Pitch остаётся таким же (наклон вверх/вниз не зеркалится горизонтальным зеркалом)
        this.setPitch(targetPlayer.getPitch());

        // Копируем анимацию (приседание, спринт и т.д.)
        this.setSneaking(targetPlayer.isSneaking());
        this.setSprinting(targetPlayer.isSprinting());
    }

    /**
     * Начинает агрессивное преследование игрока после "разбития зеркала"
     */
    private void startAttacking() {
        // Звук разбитого стекла
        this.getEntityWorld().playSound(null, this.getBlockPos(),
                SoundEvents.BLOCK_GLASS_BREAK,
                net.minecraft.sound.SoundCategory.HOSTILE,
                3.0f, 0.3f);

        // ДОБАВЛЕНО: накладываем эффект слепоты на игрока ПОСЛЕ звука стекла
        if (targetPlayer != null && targetPlayer.isAlive()) {
            targetPlayer.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.BLINDNESS,
                    400, // 20 секунд (остаток сна)
                    1, // уровень II (сильная слепота)
                    false, false, true
            ));
        }

        // Устанавливаем цель атаки
        this.setTarget(targetPlayer);

        // Увеличиваем скорость для рывка
        this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(0.35);

        // ДОБАВЛЕНО: теперь включаем боевой AI (ранее был отключен)
        this.goalSelector.add(0, new net.minecraft.entity.ai.goal.SwimGoal(this));
        this.goalSelector.add(1, new net.minecraft.entity.ai.goal.MeleeAttackGoal(this, 1.2, false));
        this.goalSelector.add(2, new net.minecraft.entity.ai.goal.LookAtEntityGoal(this, PlayerEntity.class, 12.0f));
        this.targetSelector.add(1, new net.minecraft.entity.ai.goal.RevengeGoal(this));
        this.targetSelector.add(2, new net.minecraft.entity.ai.goal.ActiveTargetGoal<>(this, PlayerEntity.class, true));
    }

    @Override
    public void onDeath(DamageSource damageSource) {
        super.onDeath(damageSource);

        // Звук разбитого зеркала при смерти
        if (!this.getEntityWorld().isClient()) {
            this.getEntityWorld().playSound(null, this.getBlockPos(),
                    SoundEvents.BLOCK_GLASS_BREAK,
                    net.minecraft.sound.SoundCategory.HOSTILE,
                    2.0f, 1.0f);
        }
    }
}
