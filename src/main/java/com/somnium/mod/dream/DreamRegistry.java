package com.somnium.mod.dream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.somnium.mod.SomniumMod;
import com.somnium.mod.dream.scenario.DreamScenarios;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Реестр типов снов, загружаемый из датапака: data/&lt;namespace&gt;/dream/&lt;name&gt;.json.
 * Перезагружается командой /reload. Неверные файлы пропускаются с предупреждением в лог.
 */
public final class DreamRegistry {

    private static volatile Map<Identifier, DreamType> dreams = Collections.emptyMap();
    private static final Random RANDOM = new Random();

    private DreamRegistry() {}

    @Nullable
    public static DreamType get(Identifier id) {
        return dreams.get(id);
    }

    public static Collection<DreamType> all() {
        return dreams.values();
    }

    public static boolean isEmpty() {
        return dreams.isEmpty();
    }

    /**
     * Взвешенный случайный выбор сна, доступного при текущем рассудке, без повторов
     * недавних снов. Если подходящих нет — сначала без учёта истории, потом любой.
     */
    @Nullable
    public static DreamType pick(float sanity, List<Identifier> recent) {
        List<DreamType> pool = new ArrayList<>();
        for (DreamType dream : dreams.values()) {
            if (sanity <= dream.maxSanity() && !recent.contains(dream.id())) pool.add(dream);
        }
        if (pool.isEmpty()) {
            for (DreamType dream : dreams.values()) {
                if (sanity <= dream.maxSanity()) pool.add(dream);
            }
        }
        if (pool.isEmpty()) pool.addAll(dreams.values());
        if (pool.isEmpty()) return null;

        int total = 0;
        for (DreamType dream : pool) total += Math.max(1, dream.weight());
        int roll = RANDOM.nextInt(total);
        for (DreamType dream : pool) {
            roll -= Math.max(1, dream.weight());
            if (roll < 0) return dream;
        }
        return pool.get(pool.size() - 1);
    }

    public static final class Loader implements SimpleSynchronousResourceReloadListener {

        @Override
        public Identifier getFabricId() {
            return SomniumMod.id("dreams");
        }

        @Override
        public void reload(ResourceManager manager) {
            Map<Identifier, DreamType> loaded = new LinkedHashMap<>();
            Map<Identifier, Resource> files = manager.findResources("dream", id -> id.getPath().endsWith(".json"));
            for (Map.Entry<Identifier, Resource> entry : files.entrySet()) {
                Identifier file = entry.getKey();
                String path = file.getPath();
                Identifier dreamId = new Identifier(file.getNamespace(),
                        path.substring("dream/".length(), path.length() - ".json".length()));
                try (Reader reader = entry.getValue().getReader()) {
                    DreamType type = parse(dreamId, JsonHelper.deserialize(reader));
                    if (type != null) loaded.put(dreamId, type);
                } catch (Exception e) {
                    SomniumMod.LOGGER.error("[Somnium] Не удалось прочитать сон {}: {}", file, e.getMessage());
                }
            }
            dreams = Collections.unmodifiableMap(loaded);
            SomniumMod.LOGGER.info("[Somnium] Загружено снов: {}", loaded.size());
        }

        @Nullable
        private static DreamType parse(Identifier id, JsonObject json) {
            Identifier scenario = new Identifier(JsonHelper.getString(json, "scenario", id.toString()));
            if (!DreamScenarios.exists(scenario)) {
                SomniumMod.LOGGER.warn("[Somnium] Сон {} ссылается на неизвестный сценарий {} — пропущен", id, scenario);
                return null;
            }
            Identifier dimension = new Identifier(JsonHelper.getString(json, "dimension"));
            List<Identifier> monsters = new ArrayList<>();
            JsonArray array = JsonHelper.getArray(json, "monsters", new JsonArray());
            for (JsonElement element : array) {
                monsters.add(new Identifier(element.getAsString()));
            }
            String base = "somnium.dream." + id.getPath();
            return new DreamType(
                    id,
                    JsonHelper.getString(json, "name", base),
                    JsonHelper.getString(json, "objective", base + ".objective"),
                    RegistryKey.of(RegistryKeys.WORLD, dimension),
                    scenario,
                    List.copyOf(monsters),
                    JsonHelper.getInt(json, "weight", 10),
                    JsonHelper.getFloat(json, "max_sanity", 100.0f),
                    Math.max(200, JsonHelper.getInt(json, "duration_ticks", 6000)));
        }
    }
}
