package com.somnium.mod.dimension;

import com.somnium.mod.SomniumMod;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Ключи измерений снов.
 *
 * Подход: одно физическое измерение "somnium:dream" на каждый ТИП сна
 * (dream_drowning_city, dream_shadow_forest, ...), а не одно общее измерение
 * с телепортацией по координатам. Это проще для процедурной генерации и
 * гарантирует, что миры снов не засоряют друг друга структурами.
 *
 * Сами измерения регистрируются через datapack: data/somnium/dimension/*.json
 * и data/somnium/dimension_type/dream_dimension_type.json — стандартный ванильный
 * механизм кастомных измерений, доступный любому моду без спец. API.
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

    private static RegistryKey<World> key(String path) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(SomniumMod.MOD_ID, path));
    }

    /** Сопоставление id сна (из DreamType) -> ключ измерения, куда телепортировать игрока. */
    public static RegistryKey<World> forDreamId(Identifier dreamId) {
        return switch (dreamId.getPath()) {
            case "drowning_city" -> DROWNING_CITY;
            case "shadow_forest" -> SHADOW_FOREST;
            case "mirror_wastes" -> MIRROR_WASTES;
            case "collapsing_mine" -> COLLAPSING_MINE;
            case "crimson_feast" -> CRIMSON_FEAST;
            case "void_of_eyes" -> VOID_OF_EYES;
            case "dream_within_dream" -> DREAM_WITHIN_DREAM;
            case "falling_planks" -> FALLING_PLANKS;
            case "mirror_room" -> MIRROR_ROOM;
            default -> throw new IllegalArgumentException("Неизвестный сон: " + dreamId);
        };
    }

    /**
     * Проверяет, является ли данное измерение одним из измерений снов.
     * Используется для блокировки естественного спавна ванильных мобов.
     */
    public static boolean isDreamDimension(RegistryKey<World> dimension) {
        return dimension.equals(DROWNING_CITY)
                || dimension.equals(SHADOW_FOREST)
                || dimension.equals(MIRROR_WASTES)
                || dimension.equals(COLLAPSING_MINE)
                || dimension.equals(CRIMSON_FEAST)
                || dimension.equals(VOID_OF_EYES)
                || dimension.equals(DREAM_WITHIN_DREAM)
                || dimension.equals(FALLING_PLANKS)
                || dimension.equals(MIRROR_ROOM);
    }

    /**
     * ИСПРАВЛЕНИЕ (падение насмерть сразу при входе в сон): раньше точка спавна вычислялась
     * через world.getTopY(HEIGHTMAP, 0, 0) сразу в момент телепортации. Хайтмап для чанка (0,0)
     * измерения-сна, в которое игрок попадает АБСОЛЮТНО ВПЕРВЫЕ, физически ещё может быть не
     * заполнен на момент этого вызова (генерация/хайтмапы чанка успевают досчитаться не мгновенно) —
     * из-за этого topY иногда возвращал "пустоту" (или устаревшее значение), игрок спавнился
     * высоко над реальной поверхностью, при приземлении получал фатальный урон от падения,
     * ServerPlayerEntityMixin ловил "смерть" и тут же будил игрока обратно в реальный мир —
     * выглядело это как "спавнит сверху -> падение -> мгновенно назад в мир", и по пути мобы/
     * предметы сна просто не успевали быть замеченными.
     *
     * Так как все 7 измерений сейчас используют детерминированный плейсхолдер-генератор
     * "minecraft:flat" (см. data/somnium/dimension/*.json), верх платформы полностью известен
     * ЗАРАНЕЕ — сумма высот слоёв каждого генератора. Поэтому вместо чтения (потенциально ещё не
     * готового) хайтмапа мы считаем точку спавна арифметически, что гарантированно не зависит от
     * готовности чанка. Если вы позже замените плейсхолдер на настоящую процедурную генерацию
     * (Приоритет 2), не забудьте обновить/убрать это значение или снова переключиться на хайтмап
     * (но тогда обязательно дождитесь готовности чанка, см. DreamManager#findDreamSpawn).
     */
    public static int platformSurfaceY(RegistryKey<World> dimension) {
        // Считаем от min_y = 0 (см. dream_dimension_type.json) + сумма высот слоёв "layers".
        if (dimension.equals(DROWNING_CITY)) return 0 + 1 + 4 + 2 + 3;   // bedrock(1) + stone(4) + sand(2) + water(3) = 10
        if (dimension.equals(SHADOW_FOREST)) return 0 + 1 + 3 + 1;       // bedrock(1) + dirt(3) + podzol(1) = 5
        if (dimension.equals(MIRROR_WASTES)) return 0 + 1 + 3 + 2;       // bedrock(1) + sandstone(3) + sand(2) = 6
        if (dimension.equals(COLLAPSING_MINE)) return 0 + 1 + 30 + 1;    // bedrock(1) + stone(30) + cobblestone(1) = 32
        if (dimension.equals(CRIMSON_FEAST)) return 0 + 1 + 3 + 1;       // bedrock(1) + dirt(3) + grass(1) = 5
        if (dimension.equals(VOID_OF_EYES)) return 0 + 1;                // black_concrete(1) = 1
        if (dimension.equals(DREAM_WITHIN_DREAM)) return 0 + 1 + 4;      // bedrock(1) + blackstone(4) = 5
        if (dimension.equals(FALLING_PLANKS)) return 64;                 // платформа в пустоте, Y=64
        if (dimension.equals(MIRROR_ROOM)) return 0 + 1 + 4;             // bedrock(1) + blackstone(4) = 5
        return 64; // не должно случиться — неизвестное измерение сна, безопасный дефолт
    }
}
