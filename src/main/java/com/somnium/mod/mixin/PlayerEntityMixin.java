package com.somnium.mod.mixin;

import com.mojang.datafixers.util.Either;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.haunt.SleepParalysis;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Unit;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Игрок успешно лёг в кровать (Right) — через SLEEP_TRANSITION_TICKS он провалится в сон. */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {

    /** Во время сонного паралича игрок "ещё не доспал": ванильный пропуск ночи не включится. */
    @Inject(method = "canResetTimeBySleeping", at = @At("HEAD"), cancellable = true)
    private void somnium$paralysisKeepsNight(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayerEntity player && SleepParalysis.isActive(player.getUuid())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "trySleep", at = @At("RETURN"))
    private void somnium$onSleep(BlockPos pos, CallbackInfoReturnable<Either<PlayerEntity.SleepFailureReason, Unit>> cir) {
        if ((Object) this instanceof ServerPlayerEntity player && cir.getReturnValue().right().isPresent()) {
            DreamManager.onPlayerSlept(player);
        }
    }
}
