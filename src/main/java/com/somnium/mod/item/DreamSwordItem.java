package com.somnium.mod.item;

import com.somnium.mod.dimension.ModDimensions;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Меч Сноведений: наяву "спит" (4 урона), во снах и в мире снов просыпается — Сила II,
 * пока он в руке. Уходит в сон вместе с игроком (тег somnium:dream_carry) — раньше
 * все вещи снимались при засыпании, и меч нельзя было использовать там, где он силён.
 */
public class DreamSwordItem extends SwordItem {

    public DreamSwordItem(Settings settings) {
        super(DreamToolMaterial.INSTANCE, 3, -2.4f, settings);
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, world, entity, slot, selected);
        if (world.isClient() || !selected || !(entity instanceof ServerPlayerEntity player)) return;
        if (!ModDimensions.isDreamDimension(world.getRegistryKey())) return;
        if (world.getTime() % 20 == 0) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 45, 1, true, false, true));
        }
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.somnium.dream_sword.tooltip").formatted(Formatting.DARK_PURPLE));
    }
}
