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

import java.util.List;

/**
 * Меч сноведений.
 * В реальном мире — слабый, "спящий" клинок (4 урона). В любом измерении снов
 * (включая мир снов за порталом) меч ПРОСЫПАЕТСЯ: пока он в руке, владелец
 * получает Силу III (+9 к урону) — итого удар сильнее незеритового меча.
 * Рецепт: 2 слитка сноведений + палка.
 */
public class DreamSwordItem extends SwordItem {

    public DreamSwordItem(Settings settings) {
        super(DreamToolMaterial.INSTANCE, 3, -2.4f, settings);
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, world, entity, slot, selected);
        if (world.isClient() || !(entity instanceof ServerPlayerEntity player)) {
            return;
        }
        if (!ModDimensions.isDreamDimension(world.getRegistryKey())) {
            return; // в реальном мире меч спит
        }
        // Меч просыпается: Сила III, пока он в главной руке во сне.
        // Обновляем раз в секунду, эффект с запасом — не мигает в HUD.
        if (selected && world.getTime() % 20 == 0) {
            player.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.STRENGTH, 45, 2, true, false, true));
        }
    }

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.somnium.dream_sword.tooltip")
                .formatted(Formatting.DARK_PURPLE));
    }
}
