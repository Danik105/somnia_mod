package com.somnium.mod.registry;

import com.somnium.mod.SomniumMod;
import net.minecraft.item.Item;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;

public final class ModTags {

    private ModTags() {}

    /**
     * Предметы, которые игрок "уносит с собой" в сон: они не снимаются при входе в сон
     * и физически остаются в инвентаре (могут быть потеряны или израсходованы во сне).
     * По умолчанию: Колокол Пробуждения и Меч Сноведений (см. data/somnium/tags/items).
     */
    public static final TagKey<Item> DREAM_CARRY = TagKey.of(RegistryKeys.ITEM, SomniumMod.id("dream_carry"));
}
