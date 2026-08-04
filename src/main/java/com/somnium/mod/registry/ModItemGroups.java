package com.somnium.mod.registry;

import com.somnium.mod.SomniumMod;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;

/**
 * ИСПРАВЛЕНО ("нет предметов из мода"): ModItems.CLARITY_SHARD и ModItems.DREAM_CATCHER
 * регистрировались в Registries.ITEM, но НИ В ОДНУ ItemGroup (вкладку творческого режима)
 * никогда не добавлялись — из-за этого предметы физически существовали (их можно было
 * выдать через /give somnium:clarity_shard), но игрок никогда не видел их в инвентаре
 * творческого режима и мог решить, что предметов мода вообще нет.
 * Обратите внимание: перевод "itemGroup.somnium" уже был подготовлен в lang-файлах —
 * не хватало только этого класса и вызова register() из SomniumMod#onInitialize.
 */
public final class ModItemGroups {

    private ModItemGroups() {}

    public static final RegistryKey<ItemGroup> SOMNIUM_GROUP_KEY =
            RegistryKey.of(RegistryKeys.ITEM_GROUP, SomniumMod.id("somnium"));

    public static final ItemGroup SOMNIUM_GROUP = FabricItemGroup.builder()
            .icon(() -> new ItemStack(ModItems.CLARITY_SHARD))
            .displayName(Text.translatable("itemGroup.somnium"))
            .entries((context, entries) -> {
                entries.add(ModItems.CLARITY_SHARD);
                entries.add(ModItems.DREAM_CATCHER);
                // ДОБАВЛЕНО: новые предметы (см. ModItems) — раньше вкладка содержала только 2
                // из 2 существовавших предметов; теперь добавлены и оба новых.
                entries.add(ModItems.NIGHTMARE_ESSENCE);
                entries.add(ModItems.WAKING_BELL);
            })
            .build();

    public static void register() {
        Registry.register(Registries.ITEM_GROUP, SOMNIUM_GROUP_KEY, SOMNIUM_GROUP);
        SomniumMod.LOGGER.info("[Somnium] Вкладка творческого режима зарегистрирована");
    }
}
