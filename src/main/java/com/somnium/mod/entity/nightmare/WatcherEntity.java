package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

/**
 * Watcher — обитатель «Пустоты с глазами». Классическая SCP-подобная механика:
 * замирает на месте (не двигается, не атакует), пока хотя бы один игрок смотрит
 * на него, но стремительно приближается и атакует в момент, когда все игроки
 * отводят взгляд. Заставляет пятиться назад, не отрывая взгляда — очень
 * атмосферная и напряжённая механика "не моргай".
 */
public class WatcherEntity extends AbstractNightmareEntity {

    private static final double VIEW_CONE_DOT_THRESHOLD = 0.7;

    public WatcherEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0) // "ходит" только рывками, см. tick()
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 10.0);
    }

    @Override
    public void tick() {
        super.tick();
        boolean watchedByAnyone = this.getEntityWorld().getPlayers().stream()
                .filter(p -> p.squaredDistanceTo(this) < 40 * 40)
                .anyMatch(this::isBeingWatchedBy);

        if (!watchedByAnyone) {
            // Рывок к ближайшему игроку, пока никто не смотрит
            PlayerEntity nearest = this.getEntityWorld().getClosestPlayer(this, 40.0);
            if (nearest != null) {
                this.getNavigation().startMovingTo(nearest, 1.6);
            }
        } else {
            this.getNavigation().stop();
        }
    }

    private boolean isBeingWatchedBy(PlayerEntity player) {
        var lookVec = player.getRotationVec(1.0f).normalize();
        var toEntity = this.getPos().subtract(player.getEyePos()).normalize();
        return lookVec.dotProduct(toEntity) > VIEW_CONE_DOT_THRESHOLD;
    }
}
