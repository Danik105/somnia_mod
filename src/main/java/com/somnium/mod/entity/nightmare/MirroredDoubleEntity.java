package com.somnium.mod.entity.nightmare;

import com.somnium.mod.entity.SkinOwner;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

/**
 * Отражённый Двойник — выглядит как игрок (скин владельца). В Пустоши зеркал он без ИИ
 * и движется сценарием; прорвавшись в явь, дерётся сам и умеет меняться с жертвой местами.
 */
public class MirroredDoubleEntity extends AbstractNightmareEntity implements SkinOwner {

    private static final TrackedData<Optional<UUID>> SKIN_OWNER =
            DataTracker.registerData(MirroredDoubleEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

    private int swapCooldown = 0;

    public MirroredDoubleEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.24)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.0);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(SKIN_OWNER, Optional.empty());
    }

    public void setSkinOwner(UUID owner) {
        this.dataTracker.set(SKIN_OWNER, Optional.of(owner));
    }

    @Override
    public Optional<UUID> getSkinOwner() {
        return this.dataTracker.get(SKIN_OWNER);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient()) return;
        if (swapCooldown > 0) swapCooldown--;
        // Двойник бьёт так же сильно, как жертва
        PlayerEntity target = this.getWorld().getClosestPlayer(this, 32.0);
        if (target != null && this.age % 20 == 0) {
            double playerDamage = target.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
            this.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(Math.max(2.0, playerDamage * 0.8));
        }
        if (getSkinOwner().isEmpty() && target != null) setSkinOwner(target.getUuid());
    }

    /** "Зеркальный обмен": удачный удар меняет двойника и жертву местами (перезарядка 10 с). */
    @Override
    public boolean tryAttack(Entity target) {
        boolean hit = super.tryAttack(target);
        if (hit && swapCooldown <= 0 && target instanceof ServerPlayerEntity player
                && this.getWorld() instanceof ServerWorld world) {
            Vec3d playerPos = player.getPos();
            Vec3d doublePos = this.getPos();
            float playerYaw = player.getYaw();
            player.teleport(world, doublePos.x, doublePos.y, doublePos.z, playerYaw + 180.0f, player.getPitch());
            this.teleport(playerPos.x, playerPos.y, playerPos.z);
            this.setYaw(playerYaw);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 100, 0));
            world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, this.getSoundCategory(), 1.0f, 1.4f);
            player.sendMessage(Text.translatable("somnium.entity.mirrored_double.swap"), true);
            swapCooldown = 200;
        }
        return hit;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        getSkinOwner().ifPresent(uuid -> nbt.putUuid("SkinOwner", uuid));
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid("SkinOwner")) setSkinOwner(nbt.getUuid("SkinOwner"));
    }
}
