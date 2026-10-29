package com.somnium.mod.entity.nightmare;

import com.somnium.mod.dimension.ModDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

/**
 * Контролёр (сон "Электричка"): идёт по проходу за игроком, его не остановить и не убить.
 * Куда идти и что делать при встрече, решает сценарий сна; сам моб только ходит и смотрит.
 * Вне снов он обычный неспешный моб, которого можно победить.
 */
public class ControllerEntity extends AbstractNightmareEntity {

    public ControllerEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.2)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void initGoals() {
        if (inDream()) {
            this.goalSelector.add(5, new LookAtEntityGoal(this, PlayerEntity.class, 32.0f));
        } else {
            super.initGoals();
        }
    }

    private boolean inDream() {
        return ModDimensions.isSessionDimension(this.getWorld().getRegistryKey());
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (inDream() && !source.isOf(DamageTypes.OUT_OF_WORLD) && !source.isOf(DamageTypes.GENERIC_KILL)) {
            return false;
        }
        return super.damage(source, amount);
    }

    @Override
    public boolean isPushable() {
        return !inDream();
    }
}
