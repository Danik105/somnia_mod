package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
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

            // РЕАЛИЗОВАНО ("эхо шахты"): крик слышат все Blind Burrower'ы поблизости —
            // они тут же узнают, где жертва, и бросаются к ней.
            if (this.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw
                    && this.getTarget() instanceof PlayerEntity prey) {
                for (BlindBurrowerEntity burrower : sw.getEntitiesByClass(
                        BlindBurrowerEntity.class, this.getBoundingBox().expand(24.0), e -> true)) {
                    burrower.setTarget(prey);
                }

                // РЕАЛИЗОВАНО ("обвал"): от крика с потолка над жертвой сыплется гравий —
                // в шахте кричать опасно. Роняем 3-5 блоков с небольшим разбросом.
                int clumps = 3 + this.getRandom().nextInt(3);
                for (int i = 0; i < clumps; i++) {
                    int dx = this.getRandom().nextInt(5) - 2;
                    int dz = this.getRandom().nextInt(5) - 2;
                    // Самая верхняя воздушная полость над жертвой — гравий упадёт сам
                    for (int dy = 8; dy >= 3; dy--) {
                        net.minecraft.util.math.BlockPos pos = prey.getBlockPos().add(dx, dy, dz);
                        if (sw.getBlockState(pos).isAir()) {
                            sw.setBlockState(pos, net.minecraft.block.Blocks.GRAVEL.getDefaultState());
                            break;
                        }
                    }
                }
                sw.spawnParticles(net.minecraft.particle.ParticleTypes.CLOUD,
                        prey.getX(), prey.getY() + 2.5, prey.getZ(), 8, 0.6, 0.4, 0.6, 0.02);
            }
        }
    }
}
