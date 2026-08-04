package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

/**
 * Lurking Shade — обитатель «Бесконечного леса теней».
 * Ключевая механика: становится невидимой (эффект Invisibility), пока
 * ближайший игрок смотрит прямо на неё (угол обзора < ~30°), и снова
 * проявляется и ускоряется, когда игрок отворачивается — заставляя
 * бояться обернуться, но и не позволяя вечно "держать её на мушке".
 */
public class LurkingShadeEntity extends AbstractNightmareEntity {

    private static final double VIEW_CONE_DOT_THRESHOLD = 0.85; // ~косинус 30°

    public LurkingShadeEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 18.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.20)
                // ИСПРАВЛЕНО (баланс): было 6.0 (3 сердца за удар) — тень невидима под взглядом
                // и быстра в темноте, поэтому ударов не избежать; 2.0 (1 сердце) оставляет
                // опасность, но перестаёт быть "несправедливой" расправой
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0);
    }

    @Override
    public void tick() {
        super.tick();
        PlayerEntity nearest = this.getEntityWorld().getClosestPlayer(this, 24.0);

        // Грейс-период 12 секунд от входа в сон: тень не целится в игрока, пока он осматривается
        if (nearest instanceof net.minecraft.server.network.ServerPlayerEntity spe
                && this.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld
                && com.somnium.mod.dream.DreamManager.isInDreamEntryGrace(
                        spe.getUuid(), serverWorld.getServer().getTicks())) {
            this.setTarget(null);
        }

        boolean watched = nearest != null && isBeingWatchedBy(nearest);

        this.setInvisible(!watched);
        this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED)
                .setBaseValue(watched ? 0.05 : 0.32); // замирает под взглядом, иначе рвётся вперёд
    }

    @Override
    public boolean tryAttack(net.minecraft.entity.Entity target) {
        // Грейс-период: даже случайный контакт в первые 12 секунд сна не наносит урона
        if (target instanceof net.minecraft.server.network.ServerPlayerEntity spe
                && this.getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld
                && com.somnium.mod.dream.DreamManager.isInDreamEntryGrace(
                        spe.getUuid(), serverWorld.getServer().getTicks())) {
            return false;
        }
        boolean hit = super.tryAttack(target);
        // ДОБАВЛЕНО (способность "Прикосновение тьмы"): удар тени накрывает жертву
        // кромешным мраком на 5 секунд — в лесу теней это почти смертный приговор.
        if (hit && target instanceof net.minecraft.server.network.ServerPlayerEntity spe2) {
            spe2.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.DARKNESS, 100, 0));
            this.getEntityWorld().playSound(null, this.getBlockPos(),
                    net.minecraft.sound.SoundEvents.ENTITY_WARDEN_SONIC_BOOM,
                    this.getSoundCategory(), 0.4f, 1.6f);
        }
        return hit;
    }

    private boolean isBeingWatchedBy(PlayerEntity player) {
        var lookVec = player.getRotationVec(1.0f).normalize();
        var toEntity = this.getPos().subtract(player.getEyePos()).normalize();
        return lookVec.dotProduct(toEntity) > VIEW_CONE_DOT_THRESHOLD;
    }
}
