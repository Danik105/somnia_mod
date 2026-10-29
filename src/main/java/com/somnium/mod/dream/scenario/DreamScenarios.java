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
        register("elevator", ElevatorScenario::new);
        register("night_train", NightTrainScenario::new);
        register("backrooms", BackroomsScenario::new);
        register("school", SchoolScenario::new);
        register("hospital", HospitalScenario::new);
        register("fairground", FairgroundScenario::new);
        register("frozen_lake", FrozenLakeScenario::new);
        register("library", LibraryScenario::new);
        register("cornfield", CornfieldScenario::new);
        register("endless_fall", EndlessFallScenario::new);
        register("theater", TheaterScenario::new);
        register("lighthouse", LighthouseScenario::new);
        register("basement", BasementScenario::new);
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
