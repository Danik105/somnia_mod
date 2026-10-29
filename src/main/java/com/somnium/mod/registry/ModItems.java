package com.somnium.mod.registry;

import com.somnium.mod.SomniumMod;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;

/**
 * Предметы мода.
 *  - Осколок Ясности — ПКМ: +20 рассудка (ClarityShardItem).
 *  - Ловец Снов — в инвентаре вдвое замедляет падение рассудка (SanityManager).
 *  - Эссенция Кошмара — крафтовый ресурс: дроп кошмаров и награда за выполненную цель сна.
 *  - Колокол Пробуждения — ПКМ во сне: проснуться (WakingBellItem).
 *  - Сырец, слиток и Меч Сноведений — из руды сноведений; меч силён только во снах.
 */
public final class ModItems {

    private ModItems() {}

    public static final Item CLARITY_SHARD = register("clarity_shard",
            settings -> new com.somnium.mod.item.ClarityShardItem(settings.maxCount(16)));

    public static final Item DREAM_CATCHER = register("dream_catcher",
            settings -> new Item(settings.maxCount(1)));

    public static final Item NIGHTMARE_ESSENCE = register("nightmare_essence",
            settings -> new Item(settings.maxCount(64)));

    public static final Item WAKING_BELL = register("waking_bell",
            settings -> new com.somnium.mod.item.WakingBellItem(settings.maxCount(4)));

    // ДОБАВЛЕНО ("руда сноведений"): сырец из руды, слиток из плавки, меч из слитков.
    // Руда и блок сноведений регистрируются как BlockItem в ModBlocks.
    public static final Item RAW_DREAM = register("raw_dream",
            settings -> new Item(settings.maxCount(64)));

    public static final Item DREAM_INGOT = register("dream_ingot",
            settings -> new Item(settings.maxCount(64)));

    public static final Item DREAM_SWORD = register("dream_sword",
            settings -> new com.somnium.mod.item.DreamSwordItem(settings.maxCount(1)));

    private static Item register(String path, java.util.function.Function<Item.Settings, Item> factory) {
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, SomniumMod.id(path));
        return Registry.register(Registries.ITEM, key, factory.apply(new Item.Settings()));
    }

    public static void register() {
        SomniumMod.LOGGER.info("[Somnium] Предметы зарегистрированы");
    }
}
