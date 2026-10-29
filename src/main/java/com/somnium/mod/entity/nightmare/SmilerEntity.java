package com.somnium.mod.entity.nightmare;

import com.somnium.mod.dimension.ModDimensions;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.sanity.SanityManager;
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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Улыбака: в темноте видно только светящиеся глаза и широкую улыбку, тела нет.
 * Подбирается, пока жертва стоит в темноте (свет ниже 8), а на свету замирает и пропадает
 * совсем. Дотянувшись, кричит в лицо (скример) и снова растворяется во тьме.
 * Живёт в сне "Закулисье" и в тёмных пещерах у игроков с рассудком ниже 25.
 */
public class SmilerEntity extends AbstractNightmareEntity {

    private static final TrackedData<Boolean> HIDDEN =
            DataTracker.registerData(SmilerEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final int DARK = 8;
    private static final int LIFETIME_OUTSIDE = 2400;

    private long nextAttack;

    public SmilerEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        this.setInvisible(true);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return createNightmareAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 30.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.34)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 5.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(HIDDEN, false);
    }

    /** На свету улыбку не видно (рендер гасит светящийся слой). */
    public boolean isHidden() {
        return this.dataTracker.get(HIDDEN);
    }

    @Override
    protected void initGoals() {
        // Охота в tick(): обычный ИИ лез бы и на свет
    }

    @Override
    public void tick() {
        super.tick();
        if (!(this.getWorld() instanceof ServerWorld world)) return;
        this.setInvisible(true);
        boolean inDream = ModDimensions.isSessionDimension(world.getRegistryKey());
        if (!inDream && this.age > LIFETIME_OUTSIDE) {
            this.discard();
            return;
        }
        boolean lit = world.getLightLevel(this.getBlockPos().up()) >= DARK;
        if (this.age % 5 == 0) this.dataTracker.set(HIDDEN, lit);

        PlayerEntity target = world.getClosestPlayer(this.getX(), this.getY(), this.getZ(), 40,
                p -> !((PlayerEntity) p).isCreative() && !p.isSpectator());
        if (!(target instanceof ServerPlayerEntity victim)) {
            this.getNavigation().stop();
            return;
        }
        if (!inDream && SanityManager.get(victim).getSanity() > 35) {
            this.discard();
            return;
        }
        Horror.face(this, victim.getEyePos());
        boolean victimInDark = world.getLightLevel(victim.getBlockPos().up()) < DARK;
        if (!victimInDark || lit) {
            this.getNavigation().stop();
            return;
        }
        if (this.age % 10 == 0) this.getNavigation().startMovingTo(victim, 1.0);
        if (this.squaredDistanceTo(victim) < 2.6 && world.getTime() >= nextAttack) {
            nextAttack = world.getTime() + 100;
            Horror.screamer(victim, Horror.FACE_SMILER);
            victim.damage(world.getDamageSources().mobAttack(this), 5.0f);
            victim.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 80, 0));
            SanityManager.add(victim, -4.0f);
            retreat(world, victim);
        }
    }

    /** Растворяется и появляется в темноте подальше. */
    private void retreat(ServerWorld world, ServerPlayerEntity victim) {
        for (int attempt = 0; attempt < 12; attempt++) {
            Vec3d point = Horror.around(victim, this.random.nextDouble() * 360, 14 + this.random.nextInt(10));
            BlockPos ground = Horror.ground(world, point.add(0, 1, 0), 8);
            if (ground != null && world.getLightLevel(ground.up()) < DARK) {
                this.refreshPositionAndAngles(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, 0f, 0f);
                this.getNavigation().stop();
                return;
            }
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (isHidden() && !source.isOf(DamageTypes.OUT_OF_WORLD) && !source.isOf(DamageTypes.GENERIC_KILL)) return false;
        return super.damage(source, amount);
    }

    @Override
    public boolean isSilent() {
        return true;
    }
}
