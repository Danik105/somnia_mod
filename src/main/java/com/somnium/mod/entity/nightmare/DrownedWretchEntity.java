package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

/**
 * Утопленник-переродок «Тонущего города». На суше медленный и слабый, в воде быстрый
 * и злой. Хватает жертву в воде и тянет на дно: после удара игрока резко дёргает вниз
 * и замедляет.
 */
public class DrownedWretchEntity extends AbstractNightmareEntity {

    private boolean wasInWater;

    public DrownedWretchEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 16.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.18);
    }

    @Override
    public void tick() {
        super.tick();
        boolean inWater = this.isTouchingWater();
        if (inWater == wasInWater && this.age > 1) return;
        wasInWater = inWater;
        this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(inWater ? 0.32 : 0.18);
        this.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(inWater ? 6.0 : 3.0);
    }

    @Override
    public boolean tryAttack(Entity target) {
        boolean hit = super.tryAttack(target);
        if (hit && target instanceof PlayerEntity player && player.isTouchingWater()) {
            player.addVelocity(0, -0.6, 0);
            player.velocityModified = true;
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
        }
        return hit;
    }

    @Override
    public boolean canBreatheInWater() {
        return true;
    }
}
