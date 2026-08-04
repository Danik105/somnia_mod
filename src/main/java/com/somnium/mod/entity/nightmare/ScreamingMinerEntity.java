package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.world.World;

/**
 * Screaming Miner — обитатель «Шахты-лабиринта».
 * Издаёт громкий крик при обнаружении игрока, который слышен по всей
 * шахте и призывает Blind Burrower'ов к позиции игрока (звуковая приманка).
 */
public class ScreamingMinerEntity extends AbstractNightmareEntity {

    private int screamCooldown = 0;

    public ScreamingMinerEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 22.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.23)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.5);
    }

    @Override
    public void tick() {
        super.tick();
        if (screamCooldown > 0) {
            screamCooldown--;
        } else if (this.getTarget() != null) {
            this.getEntityWorld().playSound(null, this.getBlockPos(),
                    com.somnium.mod.registry.ModSounds.MINER_SCREAM, this.getSoundCategory(), 2.5f, 1.0f);
            screamCooldown = 100; // раз в 5 секунд
            // TODO: оповестить всех ScreamingMinerEntity/BlindBurrowerEntity в радиусе о позиции цели
        }
    }
}
