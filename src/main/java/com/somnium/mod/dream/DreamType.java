package com.somnium.mod.dream;

import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Описание одного типа сна. Загружается из JSON (data/somnium/dream/*.json),
 * что позволяет добавлять новые сны без перекомпиляции мода (data-pack friendly).
 *
 * @param id                уникальный id сна, например "somnium:drowning_city"
 * @param displayNameKey    ключ перевода для названия (показывается при входе в сон)
 * @param structureTemplate id NBT-структуры/шаблона локации сна (или "PROCEDURAL" для генератора)
 * @param monsterEntityIds  список id кастомных сущностей-монстров этого сна
 * @param baseWeight        базовый вес выбора сна (для взвешенной случайности)
 * @param minSanityToAppear сон может выпасть только если рассудок игрока <= этого значения (100 = всегда доступен)
 * @param durationTicks     сколько тиков длится сон, если игрок не найдёт "Дверь пробуждения" раньше
 * @param objective         текстовое описание цели сна, показывается в HUD
 * @param objectiveType     ИСПРАВЛЕНИЕ ("задание сна нельзя выполнить"): раньше {@code objective}
 *                          был чисто текстовым — ничего в коде его не проверяло. Это поле говорит
 *                          DreamManager, КАК проверять выполнение цели (см. DreamObjectiveType).
 * @param objectiveTargetId для BOSS_KILL — id сущности, которую нужно победить (спавнится
 *                          гарантированно один раз, отдельно от рядовых монстров сна); для
 *                          COLLECT_ITEMS — id предмета, который нужно собрать; null для REACH_DOOR
 * @param objectiveCount    для COLLECT_ITEMS — сколько предметов нужно собрать; не используется
 *                          другими типами целей
 */
public record DreamType(
        Identifier id,
        String displayNameKey,
        String structureTemplate,
        List<Identifier> monsterEntityIds,
        int baseWeight,
        float minSanityToAppear,
        long durationTicks,
        String objective,
        DreamObjectiveType objectiveType,
        Identifier objectiveTargetId,
        int objectiveCount
) {
    public static final long DEFAULT_DURATION_TICKS = 6000L; // 5 минут реального времени
}
