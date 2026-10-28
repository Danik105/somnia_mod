package com.somnium.mod.dream.scenario;

import com.somnium.mod.SomniumMod;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Java-сценарии снов. JSON сна выбирает сценарий полем "scenario". */
public final class DreamScenarios {

    private static final Map<Identifier, Supplier<DreamScenario>> SCENARIOS = new HashMap<>();

    static {
        register("drowning_city", DrowningCityScenario::new);
        register("shadow_forest", ShadowForestScenario::new);
        register("mirror_wastes", MirrorWastesScenario::new);
        register("collapsing_mine", CollapsingMineScenario::new);
        register("crimson_feast", CrimsonFeastScenario::new);
        register("void_of_eyes", VoidOfEyesScenario::new);
        register("dream_within_dream", DreamWithinDreamScenario::new);
        register("stairwell", StairwellScenario::new);
        register("mirror_room", MirrorRoomScenario::new);
    }

    private DreamScenarios() {}

    private static void register(String path, Supplier<DreamScenario> factory) {
        SCENARIOS.put(SomniumMod.id(path), factory);
    }

    public static boolean exists(Identifier id) {
        return SCENARIOS.containsKey(id);
    }

    @Nullable
    public static DreamScenario create(Identifier id) {
        Supplier<DreamScenario> factory = SCENARIOS.get(id);
        return factory == null ? null : factory.get();
    }
}
