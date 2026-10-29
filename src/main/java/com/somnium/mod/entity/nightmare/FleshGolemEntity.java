package com.somnium.mod.entity.nightmare;

import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Мясной Голем — медленный мини-босс Кровавого пира. Когда жертва ближе 4,5 блоков,
 * бьёт оземь по площади (за 1,5 с до удара — рык и искры, чтобы успеть отбежать).
 *
 * Может дремать (неподвижен и неуязвим, как часть декораций) и охранять точку:
 * не уходит от неё дальше радиуса и бросает погоню за теми, кто вышел из зоны.
 */
public class FleshGolemEntity extends AbstractNightmareEntity {

    private int slamCooldown = 0;
    private boolean slamWarned = false;
    private boolean dormant = false;
    @Nullable private BlockPos guardPos;
    private double guardRadius;

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

    public void setDormant(boolean dormant) {
        this.dormant = dormant;
        this.setAiDisabled(dormant);
        this.setInvulnerable(dormant);
        if (dormant) this.setTarget(null);
    }

    public void setGuard(BlockPos pos, double radius) {
        this.guardPos = pos;
        this.guardRadius = radius;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        return !dormant && super.damage(source, amount);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(this.getWorld() instanceof ServerWorld world) || dormant) return;

        if (guardPos != null) {
            LivingEntity target = this.getTarget();
            if (target != null && target.squaredDistanceTo(Vec3d.ofCenter(guardPos)) > guardRadius * guardRadius) {
                this.setTarget(null);
            }
            if (this.getTarget() == null && this.age % 20 == 0
                    && this.squaredDistanceTo(Vec3d.ofCenter(guardPos)) > 9.0) {
                this.getNavigation().startMovingTo(guardPos.getX() + 0.5, guardPos.getY() + 1, guardPos.getZ() + 2.5, 1.0);
            }
        }

        if (slamCooldown > 0) {
            slamCooldown--;
            if (slamCooldown == 30 && !slamWarned && this.getTarget() != null) {
                slamWarned = true;
                world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, this.getSoundCategory(), 1.0f, 0.6f);
                world.spawnParticles(ParticleTypes.CRIT, this.getX(), this.getY() + 2.5, this.getZ(), 12, 0.7, 0.5, 0.7, 0.1);
            }
        }
        if (slamCooldown <= 0 && this.getTarget() != null && this.squaredDistanceTo(this.getTarget()) < 4.5 * 4.5) {
            slamWarned = false;
            slamCooldown = 160;
            for (PlayerEntity player : world.getEntitiesByClass(PlayerEntity.class, this.getBoundingBox().expand(4.0),
                    p -> !p.isCreative() && !p.isSpectator())) {
                player.damage(world.getDamageSources().mobAttack(this), 6.0f);
                Vec3d away = player.getPos().subtract(this.getPos()).normalize();
                player.addVelocity(away.x * 1.2, 0.6, away.z * 1.2);
                player.velocityModified = true;
            }
            world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE, this.getSoundCategory(), 1.2f, 0.5f);
            world.spawnParticles(ParticleTypes.EXPLOSION, this.getX(), this.getY() + 0.5, this.getZ(), 3, 1.2, 0.3, 1.2, 0.0);
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.NETHERRACK.getDefaultState()),
                    this.getX(), this.getY() + 0.2, this.getZ(), 40, 1.5, 0.2, 1.5, 0.1);
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("Dormant", dormant);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        dormant = nbt.getBoolean("Dormant");
    }
}
