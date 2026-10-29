package com.somnium.mod.entity.nightmare;

import com.somnium.mod.dimension.ModDimensions;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

/**
 * Попутчица и безликие пассажиры (сны "Лифт" и "Электричка"). Во сне стоят или сидят
 * без ИИ, а поворачивает их сценарий. Вне снов (прорыв кошмаров) подкрадываются и
 * от прикосновения темнеет в глазах и мутится рассудок.
 *
 * Вариант 0 — Попутчица (волосы закрывают лицо), 1 — пассажир в кепке без лица.
 */
public class PassengerEntity extends AbstractNightmareEntity {

    private static final TrackedData<Integer> VARIANT =
            DataTracker.registerData(PassengerEntity.class, TrackedDataHandlerRegistry.INTEGER);

    public PassengerEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 16.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.22)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(VARIANT, 0);
    }

    public int getVariant() {
        return this.dataTracker.get(VARIANT);
    }

    public void setVariant(int variant) {
        this.dataTracker.set(VARIANT, variant);
    }

    /** Сидит ровно на сиденье, когда едет на невидимой стойке. */
    @Override
    public double getHeightOffset() {
        return 0.0;
    }

    @Override
    public boolean tryAttack(Entity target) {
        boolean hit = super.tryAttack(target);
        if (hit && target instanceof ServerPlayerEntity player) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 80, 0));
            SanityManager.add(player, -3.0f);
        }
        return hit;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (ModDimensions.isSessionDimension(this.getWorld().getRegistryKey())
                && !source.isOf(DamageTypes.OUT_OF_WORLD) && !source.isOf(DamageTypes.GENERIC_KILL)) {
            return false;
        }
        return super.damage(source, amount);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Variant", getVariant());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        setVariant(nbt.getInt("Variant"));
    }
}
