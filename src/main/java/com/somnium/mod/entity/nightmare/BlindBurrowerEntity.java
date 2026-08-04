package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.world.World;

/**
 * Blind Burrower — слепой крот-гигант «Шахты-лабиринта».
 * Не видит игрока напрямую (нет обычного line-of-sight таргетинга),
 * а реагирует на звук шагов/крик шахтёра и роет напрямик через блоки
 * к последней услышанной позиции — источник страха: он "знает", где вы.
 */
public class BlindBurrowerEntity extends AbstractNightmareEntity {

    public BlindBurrowerEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 30.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.28)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 7.0);
    }

    @Override
    protected void initGoals() {
        super.initGoals();
        // TODO: заменить стандартный targetSelector на кастомную "SoundTrackingGoal",
        // которая не использует зрение (canSee), а идёт к последней "услышанной" точке
        // и умеет ломать слабые блоки (землю/камень) на пути.
    }
}
