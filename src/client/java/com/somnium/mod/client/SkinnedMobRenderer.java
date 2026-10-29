package com.somnium.mod.client;

import com.somnium.mod.entity.SkinOwner;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.Identifier;

/** Моб в облике игрока: модель игрока + скин владельца (Отражение, Двойник). */
public class SkinnedMobRenderer<T extends MobEntity & SkinOwner> extends BipedEntityRenderer<T, PlayerEntityModel<T>> {

    public SkinnedMobRenderer(EntityRendererFactory.Context context) {
        super(context, new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public Identifier getTexture(T entity) {
        return SkinTextures.of(entity);
    }
}
