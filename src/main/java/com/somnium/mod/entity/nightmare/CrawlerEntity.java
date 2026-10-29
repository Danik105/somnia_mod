package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/**
 * Ползун: бледная многоногая тварь, которая вылезает из темноты к игроку с низким
 * рассудком. Очень быстрая и бьёт слабо, но часто. Света не выносит: на освещённом
 * месте (уровень 11+) шипит и рассыпается. Сама уходит через минуту.
 */
public class CrawlerEntity extends AbstractNightmareEntity {

    private static final int LIGHT_LIMIT = 11;
    private static final int LIFETIME = 1200;

    private int burning;

    public CrawlerEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 14.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.38)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(this.getWorld() instanceof ServerWorld world) || this.age % 5 != 0) return;
        if (this.age > LIFETIME) {
            this.discard();
            return;
        }
        if (world.getLightLevel(this.getBlockPos()) >= LIGHT_LIMIT) {
            burning += 5;
            world.spawnParticles(ParticleTypes.SMOKE, this.getX(), this.getY() + 0.4, this.getZ(), 6, 0.4, 0.2, 0.4, 0.01);
            if (burning == 5) world.playSound(null, this.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, this.getSoundCategory(), 1.0f, 0.6f);
            if (burning >= 30) {
                world.spawnParticles(ParticleTypes.ASH, this.getX(), this.getY() + 0.3, this.getZ(), 30, 0.5, 0.3, 0.5, 0.02);
                this.discard();
            }
        } else {
            burning = 0;
        }
        if (this.age % 60 == 0) {
            world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_SPIDER_AMBIENT, this.getSoundCategory(), 0.8f, 0.5f);
        }
    }
}
