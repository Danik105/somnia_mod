package com.somnium.mod.entity;

import com.somnium.mod.dream.DreamManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * Тело игрока, лежащее в кровати в яви, пока он во сне. Рисуется скином владельца
 * (см. клиентский SleepingBodyRenderer), занимает кровать (её нельзя занять или сломать).
 * Неуязвимо, не двигается и удаляет себя само, если владелец уже не спит
 * (например, после перезапуска сервера).
 */
public class SleepingBodyEntity extends LivingEntity implements SkinOwner {

    private static final TrackedData<Optional<UUID>> OWNER =
            DataTracker.registerData(SleepingBodyEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final int SELF_CLEAN_GRACE_TICKS = 20;
    private static final float PLAYER_EYE_HEIGHT = 1.62f;

    public SleepingBodyEntity(EntityType<? extends SleepingBodyEntity> type, World world) {
        super(type, world);
        this.setInvulnerable(true);
        this.setSilent(true);
        this.setNoGravity(true);
    }

    public static DefaultAttributeContainer.Builder createSleepingBodyAttributes() {
        return LivingEntity.createLivingAttributes();
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(OWNER, Optional.empty());
    }

    public void setOwner(ServerPlayerEntity player) {
        this.dataTracker.set(OWNER, Optional.of(player.getUuid()));
        this.setCustomName(Text.translatable("somnium.dream.sleeping_body_name", player.getName()));
        this.setCustomNameVisible(true);
    }

    @Override
    public Optional<UUID> getSkinOwner() {
        return this.dataTracker.get(OWNER);
    }

    /** Владелец проснулся: освободить кровать и исчезнуть. */
    public void removeFromBed() {
        if (this.isSleeping()) this.wakeUp();
        this.discard();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient()) return;
        if (this.age > SELF_CLEAN_GRACE_TICKS && this.age % 10 == 0) {
            Optional<UUID> owner = getSkinOwner();
            if (owner.isEmpty() || !DreamManager.isDreaming(owner.get())) removeFromBed();
        }
    }

    /**
     * Рендер лежачей позы сдвигает модель к изголовью на высоту глаз стоя. Хитбокс тела
     * 0,6 блока, и без этого модель съезжала с кровати больше чем на блок.
     */
    @Override
    protected float getActiveEyeHeight(EntityPose pose, EntityDimensions dimensions) {
        return pose == EntityPose.SLEEPING ? 0.2f : PLAYER_EYE_HEIGHT;
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
    public void pushAway(Entity entity) {}

    @Override
    public Iterable<ItemStack> getArmorItems() {
        return Collections.emptyList();
    }

    @Override
    public ItemStack getEquippedStack(EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void equipStack(EquipmentSlot slot, ItemStack stack) {}

    @Override
    public Arm getMainArm() {
        return Arm.RIGHT;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        getSkinOwner().ifPresent(uuid -> nbt.putUuid("Owner", uuid));
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid("Owner")) this.dataTracker.set(OWNER, Optional.of(nbt.getUuid("Owner")));
    }
}
