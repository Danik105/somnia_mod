package com.somnium.mod.item;

import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Колокол Пробуждения: уходит в сон вместе с игроком (тег somnium:dream_carry),
 * ПКМ во сне — проснуться (+5 рассудка). Тратится один колокол.
 *
 * Пробуждение откладывается до конца тика: раньше будили прямо внутри use(), и ваниль
 * затем записывала результат в руку уже восстановленного инвентаря.
 */
public class WakingBellItem extends Item {

    public WakingBellItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!(user instanceof ServerPlayerEntity player)) return TypedActionResult.pass(stack);
        if (!DreamManager.isDreaming(player.getUuid())) {
            player.sendMessage(Text.translatable("somnium.item.waking_bell.not_dreaming"), true);
            return TypedActionResult.fail(stack);
        }
        if (!DreamManager.requestWake(player, DreamOutcome.WOKE_EARLY)) return TypedActionResult.fail(stack);
        world.playSound(null, player.getBlockPos(), ModSounds.DREAM_WAKE, SoundCategory.PLAYERS, 1.0f, 1.4f);
        if (!player.getAbilities().creativeMode) stack.decrement(1);
        return TypedActionResult.success(stack);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.somnium.waking_bell.tooltip").formatted(Formatting.GOLD));
    }
}
