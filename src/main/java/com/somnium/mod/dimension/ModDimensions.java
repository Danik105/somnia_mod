package com.somnium.mod.dimension;

import com.somnium.mod.SomniumMod;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;

/**
 * Ключи измерений мода. Сами измерения описаны датапаком:
 * data/somnium/dimension/*.json и data/somnium/dimension_type/*.json.
 *
 * Измерения снов (session dimensions) — временные: в них строятся сны, игрок
 * не может ломать блоки, посторонние сущности удаляются. Мир снов за порталом —
 * постоянное измерение, где можно свободно гулять.
 */
public final class ModDimensions {

    private ModDimensions() {}

    public static final RegistryKey<World> DROWNING_CITY = key("dream_drowning_city");
    public static final RegistryKey<World> SHADOW_FOREST = key("dream_shadow_forest");
    public static final RegistryKey<World> MIRROR_WASTES = key("dream_mirror_wastes");
    public static final RegistryKey<World> COLLAPSING_MINE = key("dream_collapsing_mine");
    public static final RegistryKey<World> CRIMSON_FEAST = key("dream_crimson_feast");
    public static final RegistryKey<World> VOID_OF_EYES = key("dream_void_of_eyes");
    public static final RegistryKey<World> DREAM_WITHIN_DREAM = key("dream_within_dream");
    public static final RegistryKey<World> FALLING_PLANKS = key("dream_falling_planks");
    public static final RegistryKey<World> MIRROR_ROOM = key("dream_mirror_room");
    public static final RegistryKey<World> ELEVATOR = key("dream_elevator");
    public static final RegistryKey<World> NIGHT_TRAIN = key("dream_night_train");
    public static final RegistryKey<World> BACKROOMS = key("dream_backrooms");
    public static final RegistryKey<World> DREAM_PORTAL_WORLD = key("dream_portal_world");

    private static RegistryKey<World> key(String path) {
        return RegistryKey.of(RegistryKeys.WORLD, SomniumMod.id(path));
    }

    /** Любое измерение мода, включая мир снов за порталом (меч просыпается, ванильный спавн выключен). */
    public static boolean isDreamDimension(RegistryKey<World> dimension) {
        return isSessionDimension(dimension) || dimension.equals(DREAM_PORTAL_WORLD);
    }

    /** Измерения, в которых проходят сны (все, кроме мира снов за порталом). */
    public static boolean isSessionDimension(RegistryKey<World> dimension) {
        return dimension.getValue().getNamespace().equals(SomniumMod.MOD_ID)
                && dimension.getValue().getPath().startsWith("dream_")
                && !dimension.equals(DREAM_PORTAL_WORLD);
    }
}
