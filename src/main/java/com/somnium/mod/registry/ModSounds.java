package com.somnium.mod.registry;

import com.somnium.mod.SomniumMod;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/**
 * ИСПРАВЛЕНО ("никакого шёпота нету", "добавь тревожную музыку"): раньше здесь
 * регистрировались СВОИ SoundEvent'ы (somnium:ambient.sanity.whisper и т.д.) — но ни
 * assets/somnium/sounds.json, ни сами .ogg файлы никогда не существовали. Это не крашит игру,
 * а просто тихо ничего не проигрывает (в логе клиента в лучшем случае "Missing sound for
 * event"). Хуже того: из 5 объявленных звуков только 2 (MINER_SCREAM, DREAM_WAKE) вообще
 * вызывались из кода — WHISPER_AMBIENT, RUPTURE_STINGER и DREAM_ENTER не были подключены НИ К
 * ЧЕМУ, то есть даже с реальными .ogg-файлами их всё равно никто бы не услышал.
 *
 * У меня в рабочей среде нет доступа к аудио-редактору или интернету, чтобы записать/скачать
 * настоящие .ogg-файлы — поэтому вместо кастомных звуков мод теперь напрямую переиспользует
 * уже существующие ванильные SoundEvent'ы (они гарантированно есть на клиенте у каждого
 * игрока, без sounds.json). Тот же приём уже применён к рендереру мобов (см.
 * NightmarePlaceholderRenderer — ванильные текстуры вместо кастомных ассетов).
 *
 * Реальное воспроизведение см.:
 *  - MINER_SCREAM   — ScreamingMinerEntity (при агро)
 *  - DREAM_WAKE     — WakingBellItem (использование предмета)
 *  - WHISPER_AMBIENT, DREAM_DREAD_AMBIENCE — DreamManager#tickDreamAmbience (ДОБАВЛЕНО:
 *    периодический "шёпот за спиной" в Лесу теней + общая тревожная фоновая атмосфера
 *    во всех снов)
 *  - RUPTURE_STINGER — BleedThroughManager#tryRupture (ДОБАВЛЕНО: резкий акцент в момент
 *    прорыва кошмаров в реальность)
 *  - DREAM_ENTER — DreamManager#enterDream (ДОБАВЛЕНО: звук при входе в сон)
 *
 * ПРОВЕРИТЬ ПРИ ПЕРВОЙ СБОРКЕ: поля SoundEvents.* здесь имеют тип RegistryEntry&lt;SoundEvent&gt;
 * (актуально с ~1.19.3), поэтому распаковываются через .value() — если в вашей версии Yarn
 * SoundEvents.* остались просто SoundEvent (более старый API), уберите .value() по ошибке
 * компилятора.
 */
public final class ModSounds {

    private ModSounds() {}

    public static final SoundEvent DREAM_WAKE = SoundEvents.BLOCK_BELL_USE;
    public static final SoundEvent DREAM_ENTER = SoundEvents.BLOCK_PORTAL_TRAVEL;
    public static final SoundEvent DREAM_DREAD_AMBIENCE =
        SoundEvents.AMBIENT_CAVE.value();
    public static final SoundEvent RUPTURE_STINGER = SoundEvents.ENTITY_WITHER_SPAWN;
    public static final SoundEvent WHISPER_AMBIENT = SoundEvents.ENTITY_ENDERMAN_AMBIENT;
    public static final SoundEvent MINER_SCREAM = SoundEvents.ENTITY_GHAST_SCREAM;

    public static void register() {
        // Регистрировать больше нечего — все звуки выше это прямые ссылки на уже существующие
        // ванильные SoundEvent'ы. Метод оставлен ради единообразия точки входа (вызывается из
        // SomniumMod#onInitialize вместе с остальными register()).
        SomniumMod.LOGGER.info("[Somnium] Звуки подключены (используются ванильные SoundEvent'ы)");
    }
}
