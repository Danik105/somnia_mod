package com.somnium.mod;

import com.somnium.mod.advancement.ModCriteria;
import com.somnium.mod.event.SomniumEvents;
import com.somnium.mod.registry.ModBlocks;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModItemGroups;
import com.somnium.mod.registry.ModItems;
import com.somnium.mod.registry.ModSounds;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.GenerationStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа мода Somnium.
 *
 * Здесь только регистрация контента. Вся игровая логика подписана на события
 * в {@link SomniumEvents}, ядро снов — {@link com.somnium.mod.dream.DreamManager}.
 */
public final class SomniumMod implements ModInitializer {

    public static final String MOD_ID = "somnium";
    public static final Logger LOGGER = LoggerFactory.getLogger("Somnium");

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModEntities.register();
        ModEntities.registerAttributes();
        ModItems.register();
        ModBlocks.register();
        ModItemGroups.register();
        ModSounds.register();
        ModCriteria.register();

        // Жилы руды сноведений в обычном мире (как медь)
        BiomeModifications.addFeature(
                BiomeSelectors.foundInOverworld(),
                GenerationStep.Feature.UNDERGROUND_ORES,
                RegistryKey.of(RegistryKeys.PLACED_FEATURE, id("dream_ore")));

        SomniumEvents.register();

        LOGGER.info("[Somnium] Инициализация завершена. Сладких снов.");
    }
}
