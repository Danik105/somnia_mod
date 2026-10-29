package com.somnium.mod.entity.nightmare;

import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.registry.ModItems;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

/**
 * Общий предок монстров-кошмаров: базовый набор ИИ (наследники переопределяют под
 * свою механику), "грейс" первых 12 секунд сна и дроп Эссенции Кошмара.
 */
public abstract class AbstractNightmareEntity extends HostileEntity {

    protected AbstractNightmareEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createNightmareAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.0, false));
        this.goalSelector.add(2, new WanderAroundFarGoal(this, 0.8));
        this.goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 12.0f));
        this.goalSelector.add(4, new LookAroundGoal(this));
        this.targetSelector.add(1, new RevengeGoal(this));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
    }

    /** Первые 12 секунд сна кошмары не бьют — игрок должен успеть осмотреться. */
    @Override
    public boolean tryAttack(Entity target) {
        if (target instanceof ServerPlayerEntity player && player.getServer() != null
                && DreamManager.isInDreamEntryGrace(player.getUuid(), player.getServer().getTicks())) {
            return false;
        }
        return super.tryAttack(target);
    }

    /** Убитый игроком кошмар роняет 1-2 Эссенции Кошмара — основной ресурс мода. */
    @Override
    public void onDeath(DamageSource damageSource) {
        super.onDeath(damageSource);
        if (!this.getWorld().isClient() && damageSource.getAttacker() instanceof PlayerEntity) {
            this.dropStack(new ItemStack(ModItems.NIGHTMARE_ESSENCE, 1 + this.getRandom().nextInt(2)));
        }
    }
}
