package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.world.World;

/**
 * Feral Villager — одичавший житель «Кровавого пира».
 * Спавнится группами (стая), становится быстрее, когда рядом ранен другой
 * Feral Villager (эффект "запаха крови" — паническая ярость стаи).
 */
public class FeralVillagerEntity extends AbstractNightmareEntity {

    public FeralVillagerEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 14.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.27)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.5);
    }

    // TODO: детектор раненых сородичей в радиусе 8 блоков -> временный баф скорости/урона
}
