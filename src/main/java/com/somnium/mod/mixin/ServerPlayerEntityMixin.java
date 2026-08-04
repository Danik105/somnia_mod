package com.somnium.mod.mixin;

import com.somnium.mod.dream.DreamManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "Смерть" внутри сна не должна быть настоящей смертью — по замыслу мода игрок должен
 * резко ПРОСНУТЬСЯ (вернуться в реальный мир), а не увидеть экран смерти/потерять предметы.
 *
 * Поэтому здесь мы ПОЛНОСТЬЮ ОТМЕНЯЕМ ci.cancel() ванильную обработку смерти, если игрок
 * находится в активном сне, и вместо неё вызываем DreamManager.onDeathInDream(), который
 * восстанавливает здоровье и телепортирует игрока обратно (см. DreamManager#onDeathInDream).
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    @Inject(method = "onDeath", at = @At("HEAD"), cancellable = true)
    private void somnium$onDeath(net.minecraft.entity.damage.DamageSource source, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (DreamManager.isDreaming(self.getUuid())) {
            ci.cancel();
            DreamManager.onDeathInDream(self);
        }
    }

    /**
     * ИСПРАВЛЕНИЕ (мгновенная "смерть" от падения сразу при входе в сон): смерть выше
     * отменяется, но сам урон (и тряска экрана/красная вспышка/звук) уже успевает
     * произойти к моменту onDeath. В коротком окне после телепортации в сон/обратно
     * (см. DreamManager#grantLandingImmunity) блокируем любой урон целиком — этого
     * времени достаточно, чтобы игрок гарантированно "приземлился", а не проходит
     * насквозь через реальный игровой процесс сна.
     */
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void somnium$onDamage(net.minecraft.entity.damage.DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (self.getWorld() instanceof ServerWorld world) {
            MinecraftServer server = world.getServer();
            if (server != null && DreamManager.isLandingImmune(self.getUuid(), server.getTicks())) {
                cir.setReturnValue(false);
            }
        }
    }
}
