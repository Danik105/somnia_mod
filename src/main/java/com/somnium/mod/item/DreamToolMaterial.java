package com.somnium.mod.item;

import com.somnium.mod.registry.ModItems;
import net.minecraft.item.ToolMaterial;
import net.minecraft.recipe.Ingredient;

/**
 * Материал сноведений. В реальном мире меч из него спит — урон чуть выше деревянного;
 * настоящая сила приходит только во сне (см. DreamSwordItem#inventoryTick — эффект
 * Силы III, пока меч в руке в любом из измерений снов).
 */
public class DreamToolMaterial implements ToolMaterial {

    public static final DreamToolMaterial INSTANCE = new DreamToolMaterial();

    @Override
    public int getDurability() {
        return 450;
    }

    @Override
    public float getMiningSpeedMultiplier() {
        return 6.0f;
    }

    @Override
    public float getAttackDamage() {
        return 1.0f; // меч: 3 + 1 = 4 урона в реальном мире ("спящий" меч)
    }

    @Override
    public int getMiningLevel() {
        return 2; // как железо
    }

    @Override
    public int getEnchantability() {
        return 22; // сны любят чары — почти как золото
    }

    @Override
    public Ingredient getRepairIngredient() {
        return Ingredient.ofItems(ModItems.DREAM_INGOT);
    }
}
