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

    // РЕАЛИЗОВАНО ("мясной кувалда"): когда жертва подходит ближе 4,5 блоков,
    // голем бьёт оземь — все игроки в радиусе 4 блоков получают урон, подлетают
    // вверх и отлетают прочь. Перезарядка ~8 секунд, за 1,5 секунды до удара —
    // предупреждающий рык и частицы, чтобы был шанс отбежать.
    private boolean slamWarned = false;

    @Override
    public void tick() {
        super.tick();
        if (this.getEntityWorld().isClient()) return;
        if (!(this.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw)) return;

        if (slamCooldown > 0) {
            slamCooldown--;
            // Предупреждение за 30 тиков до удара
            if (slamCooldown == 30 && !slamWarned && this.getTarget() != null) {
                slamWarned = true;
                sw.playSound(null, this.getBlockPos(),
                        net.minecraft.sound.SoundEvents.ENTITY_RAVAGER_ROAR,
                        this.getSoundCategory(), 1.0f, 0.6f);
                sw.spawnParticles(net.minecraft.particle.ParticleTypes.CRIT,
                        this.getX(), this.getY() + 2.5, this.getZ(), 12, 0.7, 0.5, 0.7, 0.1);
            }
        }

        if (slamCooldown <= 0 && this.getTarget() != null
                && this.squaredDistanceTo(this.getTarget()) < 4.5 * 4.5) {
            slamWarned = false;
            slamCooldown = 160;

            for (net.minecraft.entity.player.PlayerEntity player : sw.getEntitiesByClass(
                    net.minecraft.entity.player.PlayerEntity.class,
                    this.getBoundingBox().expand(4.0), p -> !p.isCreative() && !p.isSpectator())) {
                player.damage(sw.getDamageSources().mobAttack(this), 6.0f);
                net.minecraft.util.math.Vec3d away = player.getPos().subtract(this.getPos()).normalize();
                player.addVelocity(away.x * 1.2, 0.6, away.z * 1.2);
                player.velocityModified = true;
            }
            sw.playSound(null, this.getBlockPos(),
                    net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EXPLODE,
                    this.getSoundCategory(), 1.2f, 0.5f);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.EXPLOSION,
                    this.getX(), this.getY() + 0.5, this.getZ(), 3, 1.2, 0.3, 1.2, 0.0);
            sw.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(
                            net.minecraft.particle.ParticleTypes.BLOCK,
                            net.minecraft.block.Blocks.NETHERRACK.getDefaultState()),
                    this.getX(), this.getY() + 0.2, this.getZ(), 40, 1.5, 0.2, 1.5, 0.1);
        }
    }
}
