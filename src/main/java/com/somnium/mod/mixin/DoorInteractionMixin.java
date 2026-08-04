package com.somnium.mod.mixin;

import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.WakeDoorTracker;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Перехватывает взаимодействие игрока с дверью (правый клик) для отслеживания
 * открытия "Двери пробуждения" в снах.
 */
@Mixin(DoorBlock.class)
public class DoorInteractionMixin {

    @Inject(method = "onUse", at = @At("HEAD"))
    private void somnium$onDoorInteraction(
            BlockState state,
            World world,
            BlockPos pos,
            PlayerEntity player,
            Hand hand,
            BlockHitResult hit,
            CallbackInfoReturnable<ActionResult> cir) {

        if (world.isClient()) return;
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return;

        // Проверяем, находится ли игрок в активном сне
        if (!DreamManager.isDreaming(serverPlayer.getUuid())) return;

        // Проверяем, является ли эта дверь берёзовой (Двери пробуждения делаются из берёзы)
        if (!state.isOf(net.minecraft.block.Blocks.BIRCH_DOOR)) return;

        // Отмечаем, что игрок открыл дверь
        WakeDoorTracker.onDoorOpened(serverPlayer.getUuid());
    }
}
