package com.somnium.mod.registry;

import com.somnium.mod.SomniumMod;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;

/**
 * Предметы мода.
 *  - CLARITY_SHARD ("Осколок Ясности") — восстанавливает рассудок при использовании,
 *    основная награда за успешное прохождение снов; также цель сбора в сне "Кровавый пир"
 *    (см. DreamRegistry, DreamObjectiveType.COLLECT_ITEMS).
 *  - DREAM_CATCHER ("Ловец Снов") — ИСПРАВЛЕНО ("предметов нет" / предмет ничего не делал):
 *    раньше существовал только как запись в реестре без единого эффекта. Теперь реально снижает
 *    скорость падения рассудка, если лежит у игрока в инвентаре — см. SanityManager#computeDrainRate.
 *  - NIGHTMARE_ESSENCE ("Эссенция Кошмара") — ДОБАВЛЕНО: крафтовый ресурс, роняется любым
 *    монстром-кошмаром при смерти от руки игрока (см. AbstractNightmareEntity#onDeath). Раньше
 *    ни один монстр ничего не ронял — это был единственный источник материалов мода.
 *  - WAKING_BELL ("Колокол Пробуждения") — ДОБАВЛЕНО: расходник, позволяющий добровольно и
 *    немедленно проснуться посреди сна (см. WakingBellItem) — раньше у игрока не было НИКАКОГО
 *    способа выйти из сна раньше времени вручную, кроме риска умереть или ждать таймер.
 */
public final class ModItems {

    private ModItems() {}

    public static final Item CLARITY_SHARD = register("clarity_shard",
            settings -> new Item(settings.maxCount(16)));

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
