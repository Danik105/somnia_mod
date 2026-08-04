package com.somnium.mod.client;

import com.somnium.mod.entity.nightmare.MirrorReflectionEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;

/**
 * Специальный рендерер для зеркального отражения игрока.
 * Использует модель игрока и скин Стива по умолчанию.
 */
public class MirrorReflectionRenderer extends MobEntityRenderer<MirrorReflectionEntity, BipedEntityModel<MirrorReflectionEntity>> {

    private static final Identifier STEVE_SKIN = new Identifier("minecraft", "textures/entity/player/wide/steve.png");

    public MirrorReflectionRenderer(EntityRendererFactory.Context context) {
        super(context, new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public Identifier getTexture(MirrorReflectionEntity entity) {
        return STEVE_SKIN;
    }
}
