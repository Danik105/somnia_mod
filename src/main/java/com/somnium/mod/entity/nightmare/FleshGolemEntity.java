package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.world.World;

/**
 * Flesh Golem — крупный медленный "мини-босс" сна «Кровавый пир».
 * Раз в несколько секунд бьёт по площади (эффект отбрасывания) —
 * важно не толпиться рядом.
 */
public class FleshGolemEntity extends AbstractNightmareEntity {

    private int slamCooldown = 0;

    public FleshGolemEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 60.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.15)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 9.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    public void tick() {
        super.tick();
        if (slamCooldown > 0) {
            slamCooldown--;
        }
        // TODO: атака по площади (AoE slam) с анимацией и отбрасыванием игроков в радиусе 4 блока,
        // перезарядка ~8 секунд (slamCooldown = 160)
    }
}
