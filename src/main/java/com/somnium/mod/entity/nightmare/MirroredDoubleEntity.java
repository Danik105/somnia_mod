package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

/**
 * Mirrored Double — обитатель «Пустоши зеркал».
 * Копирует силу атаки ближайшего игрока (немного слабее оригинала, чтобы
 * бой был выигрышным, но не тривиальным) и визуально похож на игрока
 * (модель/скин подставляется на клиенте, см. TODO в client-модуле).
 * Разбитое рядом зеркало-осколок порождает ещё одного двойника — важно
 * убить оригинал, не разбивая зеркала вокруг.
 */
public class MirroredDoubleEntity extends AbstractNightmareEntity {

    public MirroredDoubleEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.24)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.0);
    }

    @Override
    public void tick() {
        super.tick();
        PlayerEntity target = this.getEntityWorld().getClosestPlayer(this, 32.0);
        if (target != null) {
            double playerDamage = target.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
            this.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE)
                    .setBaseValue(Math.max(2.0, playerDamage * 0.8));
        }
    }

    // TODO: событие "разбито зеркало рядом" -> ModEntities.MIRRORED_DOUBLE.spawn(...) клон в той же точке
    // TODO: на клиенте подменять текстуру сущности скином ближайшего игрока (кастомный EntityRenderer)
}
