package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

/**
 * Общий предок для всех 6-7 уникальных сущностей-кошмаров.
 * Полностью новые сущности "с нуля" (не наследуют ванильные мобы),
 * но переиспользуют ванильный набор AI-целей как отправную точку —
 * каждый наследник переопределяет initGoals() под свою уникальную механику.
 */
public abstract class AbstractNightmareEntity extends HostileEntity {

    protected AbstractNightmareEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    /** Базовые атрибуты — переопределяются наследниками под конкретного монстра. */
    public static DefaultAttributeContainer.Builder createNightmareAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.0, false));
        this.goalSelector.add(2, new WanderAroundFarGoal(this, 0.8));
        this.goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 12.0f));
        this.goalSelector.add(4, new LookAroundGoal(this));
        this.targetSelector.add(1, new RevengeGoal(this));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
    }

    /**
     * Все монстры снов родом "не отсюда" — стоят на месте под прямыми солнечными лучами
     * гораздо хуже ванильных мобов (усиленный лор-эффект), но это переопределяется по вкусу
     * в конкретных наследниках (например, Наблюдателю (Watcher) свет не важен вовсе).
     */
    @Override
    public boolean isAffectedBySplashPotions() {
        return true;
    }

    /**
     * ДОБАВЛЕНО ("предметов нет"): раньше NIGHTMARE_ESSENCE негде было получить — не было ни
     * одного источника этого предмета в игре. Теперь любой монстр-кошмар, убитый игроком, роняет
     * 1-2 Эссенции Кошмара — основной крафтовый ресурс мода (Ловец Снов, Колокол Пробуждения).
     * Не роняет ничего при смерти НЕ от игрока (например, от игрового мира), чтобы не плодить
     * фарм через окружающую среду без риска.
     */
    // ПРОВЕРИТЬ ПРИ ПЕРВОЙ СБОРКЕ: сигнатура dropStack(ServerWorld, ItemStack) взята по аналогии
    // с тем, как в 1.21.x многим методам LivingEntity добавили явный параметр ServerWorld вместо
    // обращения к полю world напрямую (как и в остальном коде проекта, см. README про getTopY/
    // player.teleport и т.п.). Если в вашей сборке 26.1 метод называется иначе или принимает
    // другие аргументы — поправьте по автодополнению IDE/декомпилу.
    @Override
    public void onDeath(net.minecraft.entity.damage.DamageSource damageSource) {
        super.onDeath(damageSource);
        if (this.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld
                && damageSource.getAttacker() instanceof PlayerEntity) {
            int count = 1 + this.getRandom().nextInt(2);
            this.dropStack(new net.minecraft.item.ItemStack(
                    com.somnium.mod.registry.ModItems.NIGHTMARE_ESSENCE, count));
        }
    }
}
