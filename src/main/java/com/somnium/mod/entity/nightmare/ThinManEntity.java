package com.somnium.mod.entity.nightmare;

import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.registry.ModSounds;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Тощий: худой монстр ростом в три с лишним блока, без лица. Приходит, когда рассудок
 * падает ниже 20, и преследует одного игрока: стоит вдали и смотрит, а стоит отвернуться,
 * оказывается ближе и всегда там, где его не видно. Если долго смотреть на него, исчезает
 * с треском помех и бьёт по рассудку. Подобравшись вплотную, бросается на игрока
 * (скример, урон, темнота) и снова уходит вдаль. Уходит насовсем, когда игрок выспится.
 *
 * Режим "мелькания" (glimpse) — для случайных событий: просто стоит и тает через пару секунд.
 */
public class ThinManEntity extends AbstractNightmareEntity {

    private static final double WATCH_DOT = 0.93;
    private static final int WATCH_TO_VANISH = 30;
    private static final double LEAVE_SANITY = 30.0;
    private static final double FAR = 30.0;

    @Nullable private UUID victimId;
    private int watchedTicks;
    private long nextStep;
    private int glimpseTicks = -1;
    private long hauntSince;

    public ThinManEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 80.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 6.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void initGoals() {
        // Не ходит: появляется и исчезает, всё в tick()
    }

    public void haunt(ServerPlayerEntity victim) {
        this.victimId = victim.getUuid();
        this.hauntSince = this.getWorld().getTime();
        this.nextStep = hauntSince + 200;
    }

    public void glimpse(int ticks) {
        this.glimpseTicks = ticks;
    }

    @Nullable
    public UUID getVictim() {
        return victimId;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(this.getWorld() instanceof ServerWorld world)) return;
        this.setVelocity(0, Math.min(0, this.getVelocity().y), 0);

        ServerPlayerEntity nearest = world.getClosestPlayer(this, 64) instanceof ServerPlayerEntity sp ? sp : null;
        if (glimpseTicks >= 0) {
            if (nearest != null) Horror.face(this, nearest.getEyePos());
            if (--glimpseTicks <= 0 || (nearest != null && Horror.isLookingAt(nearest, this, WATCH_DOT) && ++watchedTicks > 15)) {
                vanishParticles(world);
                this.discard();
            }
            return;
        }

        ServerPlayerEntity victim = victimId == null ? null
                : world.getServer().getPlayerManager().getPlayer(victimId);
        if (victim == null || victim.getServerWorld() != world || !victim.isAlive() || victim.isCreative()
                || victim.isSpectator() || DreamManager.isDreaming(victim.getUuid())
                || SanityManager.get(victim).getSanity() > LEAVE_SANITY) {
            vanishParticles(world);
            this.discard();
            return;
        }
        Horror.face(this, victim.getEyePos());
        if (Horror.inCutscene(victim.getUuid())) return;

        long now = world.getTime();
        double dist = this.distanceTo(victim);
        if (dist > 72) {
            relocate(world, victim, FAR);
            return;
        }

        if (Horror.isLookingAt(victim, this, WATCH_DOT)) {
            if (++watchedTicks == 1) {
                Horror.sound(victim, SoundEvents.ENTITY_WARDEN_HEARTBEAT, victim.getEyePos(), 1.0f, 0.8f);
            }
            if (watchedTicks >= WATCH_TO_VANISH) {
                watchedTicks = 0;
                Horror.sound(victim, SoundEvents.ENTITY_ENDERMAN_TELEPORT, this.getPos(), 1.5f, 0.4f);
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 100, 0, false, false, false));
                SanityManager.add(victim, -3.0f);
                relocate(world, victim, FAR + 4);
                nextStep = now + 160;
            }
            return;
        }
        watchedTicks = 0;

        if (dist < 4.0) {
            attack(world, victim);
            return;
        }
        if (now >= nextStep) {
            // Пока на него не смотрят, шаг ближе — и всегда за пределами взгляда
            nextStep = now + 100 + this.random.nextInt(120);
            double closer = Math.max(2.5, dist - 7 - this.random.nextInt(4));
            relocate(world, victim, closer);
            if (closer < 12) Horror.sound(victim, ModSounds.WHISPER_AMBIENT, this.getEyePos(), 1.0f, 0.7f);
        }
    }

    private void attack(ServerWorld world, ServerPlayerEntity victim) {
        Horror.screamer(victim, Horror.FACE_THIN);
        victim.damage(world.getDamageSources().mobAttack(this), 6.0f);
        victim.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 120, 0));
        SanityManager.add(victim, -5.0f);
        relocate(world, victim, FAR + 6);
        nextStep = world.getTime() + 300;
    }

    /** Переносится на distance от жертвы, за её спину (в стороне от взгляда). */
    public void relocate(ServerWorld world, ServerPlayerEntity victim, double distance) {
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = 180 + (this.random.nextDouble() - 0.5) * 140;
            Vec3d point = Horror.around(victim, angle, distance);
            BlockPos ground = Horror.ground(world, point.add(0, 1, 0), 12);
            if (ground == null) continue;
            if (!world.getBlockState(ground.up(2)).getCollisionShape(world, ground.up(2)).isEmpty()) continue;
            vanishParticles(world);
            this.refreshPositionAndAngles(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, 0f, 0f);
            Horror.face(this, victim.getEyePos());
            return;
        }
    }

    private void vanishParticles(ServerWorld world) {
        world.spawnParticles(ParticleTypes.SMOKE, this.getX(), this.getBodyY(0.5), this.getZ(), 25, 0.3, 1.2, 0.3, 0.01);
    }

    /** Ударить нельзя по-настоящему: от удара он исчезает и отнимает рассудок. */
    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.isOf(DamageTypes.OUT_OF_WORLD) || source.isOf(DamageTypes.GENERIC_KILL)) return super.damage(source, amount);
        if (this.getWorld() instanceof ServerWorld world && source.getAttacker() instanceof ServerPlayerEntity player) {
            SanityManager.add(player, -2.0f);
            Horror.sound(player, SoundEvents.ENTITY_ENDERMAN_TELEPORT, this.getPos(), 1.5f, 0.4f);
            if (glimpseTicks >= 0) {
                this.discard();
            } else {
                relocate(world, player, FAR);
            }
        }
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public boolean isSilent() {
        return true;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (victimId != null) nbt.putUuid("Victim", victimId);
        nbt.putInt("Glimpse", glimpseTicks);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid("Victim")) victimId = nbt.getUuid("Victim");
        glimpseTicks = nbt.contains("Glimpse") ? nbt.getInt("Glimpse") : -1;
    }
}
