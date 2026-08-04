package com.somnium.mod.registry;

import com.somnium.mod.SomniumMod;
import com.somnium.mod.entity.SleepingBodyEntity;
import com.somnium.mod.entity.nightmare.*;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;

/**
 * Регистрация всех сущностей-кошмаров (7 по числу снов + Phantom Eel как второй монстр
 * "Тонущего города" + Nightmare Amalgam как босс "Сна-в-сне" = 10 штук).
 * Модели/текстуры/анимации — отдельная задача клиентского модуля
 * (assets/somnium/models/entity/*, textures/entity/*, а также
 * EntityRendererRegistry в client-модуле, см. SomniumClient.java).
 */
public final class ModEntities {

    private ModEntities() {}

    public static final EntityType<DrownedWretchEntity> DROWNED_WRETCH = register(
            "drowned_wretch", DrownedWretchEntity::new, SpawnGroup.MONSTER, 0.6f, 1.95f);

    public static final EntityType<LurkingShadeEntity> LURKING_SHADE = register(
            "lurking_shade", LurkingShadeEntity::new, SpawnGroup.MONSTER, 0.6f, 2.2f);

    public static final EntityType<MirroredDoubleEntity> MIRRORED_DOUBLE = register(
            "mirrored_double", MirroredDoubleEntity::new, SpawnGroup.MONSTER, 0.6f, 1.95f);

    public static final EntityType<ScreamingMinerEntity> SCREAMING_MINER = register(
            "screaming_miner", ScreamingMinerEntity::new, SpawnGroup.MONSTER, 0.6f, 1.95f);

    public static final EntityType<BlindBurrowerEntity> BLIND_BURROWER = register(
            "blind_burrower", BlindBurrowerEntity::new, SpawnGroup.MONSTER, 1.2f, 1.4f);

    public static final EntityType<FeralVillagerEntity> FERAL_VILLAGER = register(
            "feral_villager", FeralVillagerEntity::new, SpawnGroup.MONSTER, 0.6f, 1.95f);

    public static final EntityType<FleshGolemEntity> FLESH_GOLEM = register(
            "flesh_golem", FleshGolemEntity::new, SpawnGroup.MONSTER, 1.4f, 2.9f);

    public static final EntityType<WatcherEntity> WATCHER = register(
            "watcher", WatcherEntity::new, SpawnGroup.MONSTER, 1.0f, 2.6f);

    public static final EntityType<NightmareAmalgamEntity> NIGHTMARE_AMALGAM = register(
            "nightmare_amalgam", NightmareAmalgamEntity::new, SpawnGroup.MONSTER, 1.8f, 3.4f);

    // ДОБАВЛЕНО: "phantom_eel" был упомянут в DreamRegistry как мёртвая ссылка на никогда не
    // зарегистрированную сущность (drowning_city ссылался на id, которого не существовало в этом
    // реестре — Registries.ENTITY_TYPE тихо подставлял свинью вместо предупреждения, см. старый
    // комментарий в DreamRegistry). Теперь зарегистрирован по-настоящему.
    public static final EntityType<PhantomEelEntity> PHANTOM_EEL = register(
            "phantom_eel", PhantomEelEntity::new, SpawnGroup.WATER_CREATURE, 0.5f, 0.5f);

    // ДОБАВЛЕНО (моб-наблюдатель): Stalker с механикой Weeping Angel - замирает когда игрок
    // смотрит на него, быстро приближается когда не смотрит.
    public static final EntityType<StalkerEntity> STALKER = register(
            "stalker", StalkerEntity::new, SpawnGroup.MONSTER, 0.6f, 1.95f);

    // Зеркальное отражение игрока в сне "Зеркальная комната"
    public static final EntityType<MirrorReflectionEntity> MIRROR_REFLECTION = register(
            "mirror_reflection", MirrorReflectionEntity::new, SpawnGroup.MONSTER, 0.6f, 1.95f);

    // ДОБАВЛЕНО (мультиплеер): спящее тело игрока, которое остаётся в реальном мире,
    // пока сам игрок находится в измерении сна. Другие игроки видят это тело лежащим в кровати.
    public static final EntityType<SleepingBodyEntity> SLEEPING_BODY = Registry.register(
            Registries.ENTITY_TYPE,
            RegistryKey.of(RegistryKeys.ENTITY_TYPE, SomniumMod.id("sleeping_body")),
            EntityType.Builder.create(SleepingBodyEntity::new, SpawnGroup.MISC)
                    .setDimensions(0.6f, 0.6f)
                    .build("sleeping_body")
    );

    private static <T extends net.minecraft.entity.mob.HostileEntity> EntityType<T> register(
            String path, EntityType.EntityFactory<T> factory, SpawnGroup group, float width, float height) {
        RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, SomniumMod.id(path));
        EntityType<T> type = Registry.register(
                Registries.ENTITY_TYPE,
                key,
                EntityType.Builder.create(factory, group)
                        .setDimensions(width, height)
                        .build(path)
        );
        return type;
    }

    public static void register() {
        // Метод-триггер для загрузки класса (гарантирует инициализацию static полей выше)
        SomniumMod.LOGGER.info("[Somnium] Зарегистрировано 12 сущностей-кошмаров + 1 служебная (sleeping_body)");
    }

    /** Регистрация дефолтных атрибутов — вызывается в ModInitializer ДО первого спавна. */
    public static void registerAttributes() {
        FabricDefaultAttributeRegistry.register(DROWNED_WRETCH, DrownedWretchEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(LURKING_SHADE, LurkingShadeEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(MIRRORED_DOUBLE, MirroredDoubleEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(SCREAMING_MINER, ScreamingMinerEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(BLIND_BURROWER, BlindBurrowerEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(FERAL_VILLAGER, FeralVillagerEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(FLESH_GOLEM, FleshGolemEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(WATCHER, WatcherEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(NIGHTMARE_AMALGAM, NightmareAmalgamEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(PHANTOM_EEL, PhantomEelEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(STALKER, StalkerEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(MIRROR_REFLECTION, MirrorReflectionEntity.createMirrorReflectionAttributes());
        FabricDefaultAttributeRegistry.register(SLEEPING_BODY, SleepingBodyEntity.createSleepingBodyAttributes());
    }
}
