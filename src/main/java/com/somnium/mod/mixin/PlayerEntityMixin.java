package com.somnium.mod.mixin;

import com.mojang.datafixers.util.Either;
import com.somnium.mod.SomniumMod;
import com.somnium.mod.dream.DreamManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Unit;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Заменяет прежний подход через UseBlockCallback (SleepEventHandler, теперь удалён):
 * тот вариант проверял isSleeping() на СЛЕДУЮЩИЙ тик после клика по кровати, что могло
 * пропустить кейсы или сработать при неудачной попытке лечь (монстры рядом, не ночь и т.д.),
 * потому что сама проверка "разрешено ли лечь" происходит в ванильном коде ПОСЛЕ клика.
 *
 * В 1.21.11 метода PlayerEntity#sleep(BlockPos) больше нет — вместо него
 * PlayerEntity#trySleep(BlockPos) одновременно и пытается уложить игрока, и возвращает
 * результат: Either<SleepFailureReason, Unit>. Left = не удалось лечь (причина отказа),
 * Right = удалось. Перехватываем RETURN и реагируем только на успех.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {

    @Inject(method = "trySleep", at = @At("RETURN"))
    private void somnium$onSleep(BlockPos pos, CallbackInfoReturnable<Either<PlayerEntity.SleepFailureReason, Unit>> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;

        if (self.getEntityWorld().isClient()) return;
        if (!(self instanceof ServerPlayerEntity serverPlayer)) return;

        Either<PlayerEntity.SleepFailureReason, Unit> result = cir.getReturnValue();
        if (result.right().isEmpty()) return; // не удалось лечь — Left(SleepFailureReason)

        SomniumMod.LOGGER.debug("[Somnium] Игрок {} лёг спать — переход в сон через {} тиков", serverPlayer.getName().getString(), DreamManager.SLEEP_TRANSITION_TICKS);
        DreamManager.scheduleDream(serverPlayer);
    }
}
