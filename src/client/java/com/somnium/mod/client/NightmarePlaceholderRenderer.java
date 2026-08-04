package com.somnium.mod.client;

import com.somnium.mod.entity.nightmare.*;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;

/**
 * Placeholder рендерер для монстров-кошмаров — использует ванильные текстуры зомби/скелетов
 * до появления кастомных моделей.
 */
public class NightmarePlaceholderRenderer<T extends AbstractNightmareEntity> extends MobEntityRenderer<T, BipedEntityModel<T>> {

    private static final Identifier TEX_ZOMBIE          = new Identifier("minecraft", "textures/entity/zombie/zombie.png");
    private static final Identifier TEX_DROWNED         = new Identifier("minecraft", "textures/entity/zombie/drowned.png");
    private static final Identifier TEX_HUSK            = new Identifier("minecraft", "textures/entity/zombie/husk.png");
    private static final Identifier TEX_SKELETON        = new Identifier("minecraft", "textures/entity/skeleton/skeleton.png");
    private static final Identifier TEX_STRAY           = new Identifier("minecraft", "textures/entity/skeleton/stray.png");
    private static final Identifier TEX_WITHER_SKELETON = new Identifier("minecraft", "textures/entity/skeleton/wither_skeleton.png");
    private static final Identifier TEX_PIGLIN          = new Identifier("minecraft", "textures/entity/piglin/piglin.png");

    // ДОБАВЛЕНО ("убери поломанных жителей + нарисуй красивых сущностей"): кастомные
    // текстуры 64x64 в формате зомби-модели — вместо текстуры жителя, которая криво
    // натягивалась на бипеда и выглядела месивом из частей тел.
    private static Identifier somniumTex(String name) {
        return new Identifier("somnium", "textures/entity/" + name + ".png");
    }

    public NightmarePlaceholderRenderer(EntityRendererFactory.Context context) {
        super(context, new BipedEntityModel<>(context.getPart(EntityModelLayers.ZOMBIE)), 0.5f);
    }

    @Override
    public Identifier getTexture(T entity) {
        // Тонущий город
        if (entity instanceof DrownedWretchEntity) return TEX_DROWNED;
        if (entity instanceof PhantomEelEntity) return TEX_STRAY;
        // Лес теней
        if (entity instanceof LurkingShadeEntity) return TEX_WITHER_SKELETON;
        // Пустошь зеркал
        if (entity instanceof MirroredDoubleEntity) return somniumTex("mirrored_double");
        // Шахта
        if (entity instanceof ScreamingMinerEntity) return somniumTex("screaming_miner");
        if (entity instanceof BlindBurrowerEntity) return TEX_SKELETON;
        // Кровавый пир
        if (entity instanceof FeralVillagerEntity) return somniumTex("feral_villager");
        if (entity instanceof FleshGolemEntity) return somniumTex("flesh_golem");
        // Пустота с глазами
        if (entity instanceof WatcherEntity) return somniumTex("watcher");
        // Сон-в-сне (босс)
        if (entity instanceof NightmareAmalgamEntity) return TEX_WITHER_SKELETON;
        return TEX_ZOMBIE;
    }
}
