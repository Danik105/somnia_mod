package com.somnium.mod.registry;

import com.somnium.mod.SomniumMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * Кастомные звуки Somnium ("кастомные звуки: микс ИИ + ваниль").
 *
 * Раньше здесь были прямые ссылки на ванильные SoundEvent'ы, потому что в рабочей среде
 * не было способа получить настоящие аудиофайлы. Теперь звуки сгенерированы ИИ и лежат в
 * assets/somnium/sounds/*.ogg (см. assets/somnium/sounds.json), поэтому регистрируем
 * собственные SoundEvent'ы по-настоящему — Registry.register(Registries.SOUND_EVENT, ...).
 *
 * Микс с ванилью: часть звуков остаётся ванильной там, где она и так идеальна
 * (шаги, удары, стекло мелких осколков, партикловые звуки внутри сущностей и т.п.),
 * а КЛЮЧЕВЫЕ атмосферные события заменены на кастомные:
 *  - DREAM_ENTER        — переход в сон (warped music box + whoosh), 5с
 *  - DREAM_WAKE         — пробуждение (вдох облегчения + тёплый колокольчик), 3с
 *  - DREAM_DREAD_AMBIENCE — тревожный фоновый дрон во всех снах, 20с
 *  - WHISPER_AMBIENT    — шёпот за спиной (Лес теней), 5с
 *  - RUPTURE_STINGER    — стингер прорыва кошмара в реальность, 2.5с
 *  - MINER_SCREAM       — крик Кричащего Шахтёра с эхом шахты, 3с
 *  - EXIT_PORTAL        — открытие выхода из сна (колокол собора / зеркало выхода), 4с
 *  - WATER_RISE         — подъём воды в Тонущем городе, 3с
 *  - MIRROR_SHATTER     — разрушение зеркальной стены в Зеркальной комнате, 2.5с
 *
 * ВАЖНО: имена констант совпадают с именами файлов и ключами в sounds.json.
 * Точки вызова НЕ менялись — все места, где использовались ModSounds.*, продолжают
 * работать, просто теперь звук реальный.
 */
public final class ModSounds {

    private ModSounds() {}

    public static final SoundEvent DREAM_WAKE = register("dream_wake");
    public static final SoundEvent DREAM_ENTER = register("dream_enter");
    public static final SoundEvent DREAM_DREAD_AMBIENCE = register("dread_ambience");
    public static final SoundEvent RUPTURE_STINGER = register("rupture_stinger");
    public static final SoundEvent WHISPER_AMBIENT = register("whisper_ambient");
    public static final SoundEvent MINER_SCREAM = register("miner_scream");
    public static final SoundEvent EXIT_PORTAL = register("exit_portal");
    public static final SoundEvent WATER_RISE = register("water_rise");
    public static final SoundEvent MIRROR_SHATTER = register("mirror_shatter");

    private static SoundEvent register(String name) {
        Identifier id = SomniumMod.id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static void register() {
        // Сами SoundEvent'ы зарегистрированы статически выше; метод оставлен ради
        // единообразия точки входа (вызывается из SomniumMod#onInitialize вместе с
        // остальными register()).
        SomniumMod.LOGGER.info("[Somnium] Звуки зарегистрированы (9 кастомных .ogg + ванильные в точках, где они уместнее)");
    }
}
