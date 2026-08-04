package com.somnium.mod.entity.nightmare;

import com.somnium.mod.sanity.SanityManager;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Mirrored Double — обитатель «Зеркальных пустошей», центральная механика "Резонанс Двойника".
 *
 * Двойник никогда не преследует игрока напрямую: он стремится занять позицию, ЗЕРКАЛЬНУЮ
 * позиции игрока через Разлом (точку симметрии, задаёт DreamManager#setMirrorCenter):
 *     doubleTarget = 2 * mirrorCenter - playerPos
 * Поэтому вдали от Разлома он недосягаем, а у самого центра неизбежно сближается с игроком —
 * это единственное окно, чтобы его ударить. Но долгий контакт опасен: если игрок и Двойник
 * остаются ближе SYNC_RADIUS дольше SYNC_LIMIT_TICKS, происходит СИНХРОНИЗАЦИЯ — игрок получает
 * урон и теряет рассудок, а Двойник "рассыпается стеклом" и пересобирается на краю арены,
 * становясь чуть быстрее. Бой — серия коротких заходов к центру: успей нанести удары и отойти
 * до разрыва связи.
 *
 * Победа/поражение обрабатывает общий конвейер снов (BOSS_KILL / смерть в сне / таймаут).
 */
public class MirroredDoubleEntity extends AbstractNightmareEntity {

    /** Дистанция "опасного контакта": ближе — идёт накопление синхронизации */
    private static final double SYNC_RADIUS = 3.0;
    /** Сколько тиков непрерывного контакта выдерживает связь до синхронизации (3 секунды) */
    private static final int SYNC_LIMIT_TICKS = 60;
    /** Тики контакта, на которых игрок получает текстовые предупреждения */
    private static final int SYNC_WARN_FIRST = 20;
    private static final int SYNC_WARN_SECOND = 40;
    /** Куда "рассыпается" Двойник после синхронизации — кольцо вокруг Разлома */
    private static final double REFORM_MIN_RADIUS = 12.0;
    private static final double REFORM_MAX_RADIUS = 18.0;
    /** Рассудок, отнимаемый каждой синхронизацией */
    private static final float SYNC_SANITY_DAMAGE = -12.0f;
    /** Базовая скорость и её прирост за каждую синхронизацию (кап — чтобы бой оставался честным) */
    private static final double BASE_SPEED = 0.24;
    private static final double SPEED_PER_SYNC = 0.02;
    private static final double MAX_SPEED = 0.32;

    /** Точка симметрии арены — центр Разлома; задаёт DreamManager при спавне */
    private BlockPos mirrorCenter;
    /** Накопленные тики непрерывного контакта с игроком */
    private int syncTicks = 0;
    /** Сколько синхронизаций уже произошло — растит урон и скорость Двойника */
    private int syncCount = 0;

    public MirroredDoubleEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, BASE_SPEED)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
    }

    /**
     * ИСПРАВЛЕНО: у Двойника нет стандартного боевого AI — он не ходит к игроку и не бьёт
     * сам. Движение полностью ручное (зеркалирование позиции), единственное "оружие" —
     * синхронизация при долгом контакте (см. tick()).
     */
    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new net.minecraft.entity.ai.goal.SwimGoal(this));
    }

    public void setMirrorCenter(BlockPos center) {
        this.mirrorCenter = center;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getEntityWorld().isClient()) return;
        if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) return;

        PlayerEntity closest = serverWorld.getClosestPlayer(this, 64.0);
        if (mirrorCenter == null || !(closest instanceof ServerPlayerEntity player)) {
            // Без центра симметрии или игрока связь затухает
            syncTicks = Math.max(0, syncTicks - 2);
            if (syncTicks == 0) this.setGlowing(false);
            return;
        }

        mirrorPlayerPosition(player);
        updateSynchronization(player, serverWorld);
    }

    /**
     * Двигает Двойника в точку, зеркальную позиции игрока через Разлом (центральная симметрия
     * в плоскости XZ). Пока игрок далеко от центра — Двойник далеко от игрока; подойти к нему
     * вплотную можно, только встав у самого Разлома.
     */
    private void mirrorPlayerPosition(ServerPlayerEntity player) {
        double centerX = mirrorCenter.getX() + 0.5;
        double centerZ = mirrorCenter.getZ() + 0.5;
        double mirroredX = 2.0 * centerX - player.getX();
        double mirroredZ = 2.0 * centerZ - player.getZ();

        // Переиздаём приказ на движение периодически и когда навигация остановилась —
        // иначе моб "замирает", дойдя до устаревшей цели
        if (this.age % 5 == 0 || this.getNavigation().isIdle()) {
            this.getNavigation().startMovingTo(mirroredX, player.getY(), mirroredZ, 1.0);
        }

        // Двойник всегда смотрит на оригинал — как отражение в зеркале
        this.getLookControl().lookAt(player);

        // Копирует "пластику" игрока: ускоряется, когда тот бежит
        this.setSprinting(player.isSprinting());
    }

    /**
     * Считает тики близкого контакта и разрывает связь, когда лимит исчерпан.
     * Контакт без прикосновения затухает вдвое быстрее, чем накапливается — короткие
     * заходы "ударил и отошёл" безопасны, стоять рядом — нет.
     */
    private void updateSynchronization(ServerPlayerEntity player, ServerWorld world) {
        double distance = this.distanceTo(player);
        if (distance <= SYNC_RADIUS) {
            syncTicks++;
        } else {
            syncTicks = Math.max(0, syncTicks - 2);
        }

        // Двойник светится, пока копит резонанс — видимый индикатор опасности
        this.setGlowing(syncTicks > 0);

        if (syncTicks == SYNC_WARN_FIRST) {
            player.sendMessage(Text.literal("§5Отражение настраивается на тебя..."), true);
        } else if (syncTicks == SYNC_WARN_SECOND) {
            player.sendMessage(Text.literal("§5§lСвязь вот-вот замкнётся — ОТОЙДИ!"), true);
        }

        // Звон стекла учащается по мере резонанса
        if (syncTicks > 0 && syncTicks % 10 == 0) {
            world.playSound(null, this.getBlockPos(),
                    SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                    SoundCategory.HOSTILE, 1.0f, 0.6f + 0.6f * ((float) syncTicks / SYNC_LIMIT_TICKS));
        }

        if (syncTicks >= SYNC_LIMIT_TICKS) {
            triggerSynchronization(player, world);
        }
    }

    /**
     * Разрыв зеркальной связи: игрок получает урон и теряет рассудок, Двойник "рассыпается"
     * и пересобирается на краю арены. Каждая синхронизация делает его чуть сильнее и быстрее.
     */
    private void triggerSynchronization(ServerPlayerEntity player, ServerWorld world) {
        syncCount++;
        syncTicks = 0;

        // Урон растёт с каждой синхронизацией — затягивать бой всё опаснее
        player.damage(world.getDamageSources().magic(), 3.0f + syncCount);
        SanityManager.get(player).addSanity(SYNC_SANITY_DAMAGE);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 100, 0));
        player.sendMessage(Text.literal("§5§lВы синхронизировались с отражением — связь рвётся болью!"), true);

        // Отброс игрока от Двойника — физическое "размыкание" связи
        Vec3d away = new Vec3d(player.getX() - this.getX(), 0, player.getZ() - this.getZ());
        if (away.lengthSquared() < 1.0E-4) {
            away = new Vec3d(this.random.nextDouble() - 0.5, 0, this.random.nextDouble() - 0.5);
        }
        away = away.normalize().multiply(1.2);
        player.addVelocity(away.x, 0.4, away.z);
        player.velocityModified = true;

        // Двойник рассыпается стеклом...
        world.playSound(null, this.getBlockPos(),
                SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 2.0f, 0.7f);
        world.spawnParticles(ParticleTypes.REVERSE_PORTAL,
                this.getX(), this.getY() + 1.0, this.getZ(), 60, 0.4, 0.8, 0.4, 0.05);

        // ...и пересобирается в случайной точке кольца вокруг Разлома
        double angle = this.random.nextDouble() * Math.PI * 2;
        double dist = REFORM_MIN_RADIUS + this.random.nextDouble() * (REFORM_MAX_RADIUS - REFORM_MIN_RADIUS);
        double newX = mirrorCenter.getX() + 0.5 + Math.cos(angle) * dist;
        double newZ = mirrorCenter.getZ() + 0.5 + Math.sin(angle) * dist;
        this.refreshPositionAndAngles(newX, this.getY(), newZ, this.random.nextFloat() * 360f, 0);
        world.spawnParticles(ParticleTypes.REVERSE_PORTAL,
                newX, this.getY() + 1.0, newZ, 40, 0.4, 0.8, 0.4, 0.05);
        world.playSound(null, this.getBlockPos(),
                SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1.0f, 0.5f);

        // Эскалация: каждая синхронизация ускоряет Двойника
        var speedAttr = this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.setBaseValue(Math.min(BASE_SPEED + SPEED_PER_SYNC * syncCount, MAX_SPEED));
        }
    }

    @Override
    public void onDeath(DamageSource damageSource) {
        super.onDeath(damageSource);

        // Зеркало разбито — звон стекла и вспышка частиц вместо обычной смерти
        if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
            serverWorld.playSound(null, this.getBlockPos(),
                    SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 2.5f, 0.5f);
            serverWorld.spawnParticles(ParticleTypes.REVERSE_PORTAL,
                    this.getX(), this.getY() + 1.0, this.getZ(), 100, 0.6, 1.0, 0.6, 0.1);
        }
    }
}
