package com.somnium.mod.dream;

import com.somnium.mod.SomniumMod;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Реестр всех типов снов. Данные грузятся из data/somnium/dream/*.json через
 * стандартный Fabric SimpleResourceReloadListener (регистрация — см. bootstrap()).
 *
 * Сейчас здесь заглушка со статической регистрацией 6 базовых снов "в коде",
 * чтобы каркас был рабочим сразу. В реальной сборке нужно заменить jsonDefaults()
 * на настоящую загрузку через ResourceManager (см. TODO ниже) — тогда сны будут
 * полностью дата-драйвенными и редактируемыми без пересборки мода.
 */
public final class DreamRegistry {

    private static final Map<Identifier, DreamType> DREAMS = new LinkedHashMap<>();
    private static final Random RANDOM = new Random();

    private DreamRegistry() {}

    public static void bootstrap() {
        DREAMS.clear();
        for (DreamType dream : jsonDefaults()) {
            DREAMS.put(dream.id(), dream);
        }
        SomniumMod.LOGGER.info("[Somnium] Загружено {} типов снов", DREAMS.size());

        // TODO: заменить статическую загрузку на:
        // ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new DreamReloadListener());
        // чтобы читать data/somnium/dream/*.json как обычные датапак-ресурсы.
    }

    public static DreamType get(Identifier id) {
        return DREAMS.get(id);
    }

    public static Map<Identifier, DreamType> all() {
        return DREAMS;
    }

    /**
     * Взвешенный выбор следующего сна. Учитывает текущий рассудок игрока —
     * некоторые "тяжёлые" сны (например, Кошмар-босс) доступны только при низком рассудке.
     */
    public static DreamType pickWeighted(float currentSanity, Identifier avoidRepeatOf) {
        List<DreamType> pool = new ArrayList<>();
        int totalWeight = 0;
        for (DreamType dream : DREAMS.values()) {
            if (currentSanity > dream.minSanityToAppear()) continue;
            if (dream.id().equals(avoidRepeatOf) && DREAMS.size() > 1) continue;
            pool.add(dream);
            totalWeight += dream.baseWeight();
        }
        if (pool.isEmpty()) {
            pool.addAll(DREAMS.values());
            totalWeight = pool.stream().mapToInt(DreamType::baseWeight).sum();
        }
        int roll = RANDOM.nextInt(Math.max(1, totalWeight));
        int cursor = 0;
        for (DreamType dream : pool) {
            cursor += dream.baseWeight();
            if (roll < cursor) return dream;
        }
        return pool.get(pool.size() - 1);
    }

    public static DreamType pickSharedWeighted(float currentSanity, Identifier avoidRepeatOf) {
        return pickWeighted(currentSanity, avoidRepeatOf);
    }

    /**
     * ДОБАВЛЕНО: выбор сна с избеганием последних N снов из истории игрока.
     * Используется новой системой SharedDreamSession для предотвращения повторений.
     */
    public static DreamType pickWeightedAvoidingHistory(float currentSanity, List<Identifier> recentHistory) {
        List<DreamType> pool = new ArrayList<>();
        int totalWeight = 0;
        for (DreamType dream : DREAMS.values()) {
            if (currentSanity > dream.minSanityToAppear()) continue;
            if (recentHistory.contains(dream.id())) continue; // Избегаем недавних снов
            pool.add(dream);
            totalWeight += dream.baseWeight();
        }
        if (pool.isEmpty()) {
            // Если все сны были недавно - берём любой доступный
            pool.addAll(DREAMS.values());
            totalWeight = pool.stream().mapToInt(DreamType::baseWeight).sum();
        }
        int roll = RANDOM.nextInt(Math.max(1, totalWeight));
        int cursor = 0;
        for (DreamType dream : pool) {
            cursor += dream.baseWeight();
            if (roll < cursor) return dream;
        }
        return pool.get(pool.size() - 1);
    }

    /** Базовые 6 снов "зашитые" в код — см. соответствующие JSON в data/somnium/dream/ как эталон формата. */
    private static List<DreamType> jsonDefaults() {
        List<DreamType> list = new ArrayList<>();

        // ИЗМЕНЕНО: Тонущий город - оставлена дверь (воздушный карман логичен как цель)
        list.add(new DreamType(
                SomniumMod.id("drowning_city"), "somnium.dream.drowning_city",
                "somnium:drowning_city_ruins",
                List.of(SomniumMod.id("drowned_wretch"), SomniumMod.id("phantom_eel")),
                20, 100f, 6000L,
                "Найти воздушный карман на крыше собора до того, как вода поднимется до верха.",
                DreamObjectiveType.REACH_DOOR, null, 0
        ));

        // ИЗМЕНЕНО: Лес теней - ведьмины огни ведут к Дереву-Маяку; выход — канал в его свете
        list.add(new DreamType(
                SomniumMod.id("shadow_forest"), "somnium.dream.shadow_forest",
                "somnium:shadow_forest_procedural",
                List.of(SomniumMod.id("lurking_shade")),
                20, 100f, 7200L,
                "Иди на ведьмины огни к Дереву-Маяку и встань в его свет — дерево заберёт тебя из сна. Шёпот зовёт обернуться — не смотри: тень ждёт твоего взгляда.",
                DreamObjectiveType.REACH_DOOR, null, 0
        ));

        // ИЗМЕНЕНО: Пустошь зеркал, редизайн "Поймай своё отражение" — без босса:
        // коснись убегающего зеркала 3 раза, пока Двойник идёт по твоему следу
        list.add(new DreamType(
                SomniumMod.id("mirror_wastes"), "somnium.dream.mirror_wastes",
                "somnium:mirror_wastes_procedural",
                List.of(), // монстров нет: Двойника спавнит тик сна, свипер страхует от случайных
                18, 100f, 6600L,
                "Поймай своё отражение. Через полминуты в пустоши появится зеркало — коснись его 3 раза, а оно будет исчезать и блестеть в новых местах. Двойник идёт за тобой по пятам и ускоряется после каждого касания — не дай ему схватить тебя.",
                null, null, 0 // выход через 3 касания зеркала (своя механика), не через дверь
        ));

        // ИЗМЕНЕНО: Шахта - процедурный лабиринт; стены за спиной обрушаются и зарастают
        list.add(new DreamType(
                SomniumMod.id("collapsing_mine"), "somnium.dream.collapsing_mine",
                "somnium:collapsing_mine_procedural",
                List.of(SomniumMod.id("screaming_miner"), SomniumMod.id("blind_burrower")),
                18, 100f, 6000L,
                "Найди дверь выхода из лабиринта. Шахта дышит: проходы обрушаются и зарастают за твоей спиной.",
                DreamObjectiveType.REACH_DOOR, null, 0
        ));

        // ИЗМЕНЕНО: Кровавый пир - редизайн "Последний ужин": 5 блюд, тронутые отодвинуть, финал - Тост
        // ИЗМЕНЕНО: боевые мобы убраны по фидбеку — пир пугает атмосферой и гостями, а не боём
        list.add(new DreamType(
                SomniumMod.id("crimson_feast"), "somnium.dream.crimson_feast",
                "somnium:crimson_feast_village",
                List.of(), // без монстров: судей спавнит сцена, агрессивных мобов чистит свипер
                14, 100f, 5400L,
                "Судьи подадут 5 блюд. Свежее блюдо — возьми и съешь (зажми ПКМ). Порченое (зелёное имя и дым) — возьми в левую руку, зажигалку в правую и нажми ПКМ: оно сгорит. В конце возьми Кубок Тоста и выпей его до дна — так ты проснёшься.",
                null, null, 0 // сон на выполнение сценария пира (блюда + тост), выход через тост
        ));

        // ИЗМЕНЕНО: Пустота с глазами - оставлена дверь (портал пробуждения логичен)
        list.add(new DreamType(
                SomniumMod.id("void_of_eyes"), "somnium.dream.void_of_eyes",
                "somnium:void_of_eyes_procedural",
                List.of(SomniumMod.id("watcher")),
                14, 100f, 4800L,
                "Отступать к порталу пробуждения, не отрывая взгляда от Наблюдателей.",
                DreamObjectiveType.REACH_DOOR, null, 0
        ));

        // ИЗМЕНЕНО: Сон-в-сне - БЕЗ цели, БЕЗ монстров, только таймаут
        // Игрок должен просто находиться в скопированном мире 7.5 минут
        list.add(new DreamType(
                SomniumMod.id("dream_within_dream"), "somnium.dream.dream_within_dream",
                "NONE",
                List.of(), // БЕЗ монстров
                6, 20f, 9000L,
                "", // БЕЗ цели
                null, null, 0 // БЕЗ objectiveType - только таймаут
        ));

        // ИЗМЕНЕНО: 8-й сон "Лестница в никуда" (бывш. "Падающие доски") — подъезд многоэтажки
        list.add(new DreamType(
                SomniumMod.id("falling_planks"), "somnium.dream.falling_planks",
                "somnium:falling_planks_void",
                List.of(), // нет монстров: свипер шахты страхует от случайных спавнов
                12, 100f, 3600L, // 3 минуты (короткий, интенсивный сон)
                "Подъезд, как в многоэтажке: марши уходят вверх и обрушаются за спиной — поднимайся, не стой на месте. Бирюзовые гнилые ступени (дымятся) ломаются под ногами. На последнем этаже открой люк в потолке (ПКМ) — там выход.",
                null, null, 0 // выход через люк (своя механика), не через дверь
        ));

        // ДОБАВЛЕНО: 9-й сон "Зеркальная комната" - отражение игрока копирует его, затем атакует
        list.add(new DreamType(
                SomniumMod.id("mirror_room"), "somnium.dream.mirror_room",
                "somnium:mirror_room_chamber",
                List.of(SomniumMod.id("mirror_reflection")),
                18, 50f, 3600L, // 3 минуты, требует средний уровень рассудка
                "Разбить зеркало и победить своё отражение.",
                DreamObjectiveType.BOSS_KILL, SomniumMod.id("mirror_reflection"), 0
        ));

        return list;
    }
}
