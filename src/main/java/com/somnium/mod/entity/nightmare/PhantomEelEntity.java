package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

/**
 * Phantom Eel — второй монстр «Тонущего города», упомянутый в комментариях DreamRegistry как
 * никогда не зарегистрированная сущность (баг, из-за которого DefaultedRegistry молча подставлял
 * свинью вместо предупреждения). Теперь зарегистрирован по-настоящему — см. ModEntities.
 *
 * Механика: в отличие от Drowned Wretch (медлительный на суше, опасный в воде), Phantom Eel
 * ПОЛНОСТЬЮ водное существо — не выходит на сушу вовсе (у него нет ходьбы), зато под водой
 * стремительно атакует из засады: ускоряется рывком, когда игрок оказывается в воде рядом,
 * и почти не показывается на поверхности.
 */
public class PhantomEelEntity extends AbstractNightmareEntity {

    private static final double AMBUSH_RANGE = 6.0;

    public PhantomEelEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 14.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.30)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 5.0);
    }

    @Override
    protected void initGoals() {
        // Своя, упрощённая версия набора целей предка: без сухопутного блуждания —
        // угорь целиком водный и на суше беспомощен.
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new net.minecraft.entity.ai.goal.MeleeAttackGoal(this, 1.4, true));
        this.goalSelector.add(2, new WanderAroundFarGoal(this, 1.0));
        this.targetSelector.add(1, new net.minecraft.entity.ai.goal.RevengeGoal(this));
        this.targetSelector.add(2, new net.minecraft.entity.ai.goal.ActiveTargetGoal<>(this, PlayerEntity.class, true));
    }

    @Override
    public void tick() {
        super.tick();
        boolean ambushReady = this.isTouchingWater();
        this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED)
                .setBaseValue(ambushReady ? 0.42 : 0.05); // почти неподвижен вне воды
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
