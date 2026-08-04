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

    // РЕАЛИЗОВАНО ("запах крови"): если в радиусе 8 блоков есть раненый сородич
    // (или кто-то из стаи уже в ярости), вся стая впадает в паническую ярость —
    // скорость и урон растут, вокруг летят частицы гнева. Утихает, когда раненых не осталось.
    private boolean enraged = false;

    @Override
    public void tick() {
        super.tick();
        if (this.getEntityWorld().isClient()) return;

        if (this.age % 20 == 0) {
            boolean bloodNearby = this.getEntityWorld().getEntitiesByClass(
                    FeralVillagerEntity.class, this.getBoundingBox().expand(8.0),
                    other -> other != this && other.getHealth() < other.getMaxHealth()).size() > 0;

            boolean hurt = this.getHealth() < this.getMaxHealth();
            boolean shouldRage = bloodNearby || hurt;
            if (shouldRage != enraged) {
                enraged = shouldRage;
                this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED)
                        .setBaseValue(enraged ? 0.38 : 0.27);
                this.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE)
                        .setBaseValue(enraged ? 5.0 : 3.5);
                if (enraged && this.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
                    sw.playSound(null, this.getBlockPos(),
                            net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_NO,
                            this.getSoundCategory(), 1.2f, 0.5f);
                }
            }
        }

        // Частицы ярости у взбешённого
        if (enraged && this.age % 10 == 0
                && this.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.ANGRY_VILLAGER,
                    this.getX(), this.getY() + 2.1, this.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
        }
    }
}
