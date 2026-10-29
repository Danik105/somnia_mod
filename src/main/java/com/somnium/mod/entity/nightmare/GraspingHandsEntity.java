package com.somnium.mod.entity.nightmare;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.world.World;

/**
 * Руки из-под кровати (сонный паралич): тело спрятано в полу, наружу торчат только
 * поднятые бледные руки. Без ИИ и неуязвимы, живут несколько секунд.
 */
public class GraspingHandsEntity extends AbstractNightmareEntity {

    private static final int LIFETIME = 200;

    public GraspingHandsEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        this.setNoGravity(true);
        this.noClip = true;
        this.setAiDisabled(true);
        this.setSilent(true);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes();
    }

    @Override
    public void tick() {
        super.tick();
        this.setVelocity(0, 0, 0);
        if (!this.getWorld().isClient() && this.age > LIFETIME) this.discard();
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isCollidable() {
        return false;
    }

    @Override
    public boolean canHit() {
        return false;
    }

    @Override
    public boolean shouldSave() {
        return false;
    }
}
