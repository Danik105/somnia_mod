package com.somnium.mod.dream;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.List;

/**
 * Описание типа сна. Загружается из data/&lt;namespace&gt;/dream/*.json (см. DreamRegistry),
 * поэтому веса, длительность, монстров и порог рассудка можно менять датапаком.
 *
 * @param id           id сна (= имя файла), например somnium:drowning_city
 * @param nameKey      ключ перевода названия
 * @param objectiveKey ключ перевода цели (показывается в чате при входе)
 * @param dimension    измерение, в котором строится сон
 * @param scenario     id Java-сценария (DreamScenarios), который строит и ведёт сон
 * @param monsters     монстры, которых сценарий расставляет при входе и которые
 *                     прорываются в явь при низком рассудке
 * @param weight       вес при случайном выборе сна
 * @param maxSanity    сон выпадает, только если рассудок не выше этого значения
 * @param durationTicks сколько длится сон до пробуждения по таймеру
 */
public record DreamType(
        Identifier id,
        String nameKey,
        String objectiveKey,
        RegistryKey<World> dimension,
        Identifier scenario,
        List<Identifier> monsters,
        int weight,
        float maxSanity,
        int durationTicks
) {}
