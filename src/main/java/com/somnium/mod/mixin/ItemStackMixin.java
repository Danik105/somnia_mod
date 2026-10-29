package com.somnium.mod.mixin;

import com.somnium.mod.dream.DreamManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Точный момент "доел/допил" — для блюд и Кубка Тоста Кровавого пира.
 * Раньше это угадывалось по таймеру использования и иногда не засчитывалось.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "finishUsing", at = @At("HEAD"))
    private void somnium$finishUsing(World world, LivingEntity user, CallbackInfoReturnable<ItemStack> cir) {
        if (!world.isClient() && user instanceof ServerPlayerEntity player) {
            DreamManager.onFinishUsing(player, (ItemStack) (Object) this);
        }
    }
}
