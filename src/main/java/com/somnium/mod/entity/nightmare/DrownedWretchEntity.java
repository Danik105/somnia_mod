package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.world.World;

/**
 * Drowned Wretch — обитатель "Тонущего города".
 * Механика: получает бонус скорости и урона, если находится в воде или
 * если уровень воды в сне поднялся выше определённого порога (нагнетание паники
 * по мере затопления локации). На суше — медленный и слабый.
 */
public class DrownedWretchEntity extends AbstractNightmareEntity {

    public DrownedWretchEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 16.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.18); // на суше медленный
    }

    @Override
    public void tick() {
        super.tick();
        boolean inWater = this.isTouchingWater();
        // В воде — резко опаснее (эффект "паники утопающего")
        this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED)
                .setBaseValue(inWater ? 0.32 : 0.18);
        this.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE)
                .setBaseValue(inWater ? 6.0 : 3.0);
    }

    @Override
    public boolean canBreatheInWater() {
        return true;
    }
}
