package com.somnium.mod.client;

import com.somnium.mod.entity.SleepingBodyEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

/** Тело спящего игрока: модель игрока со скином владельца, лежит в кровати (поза SLEEPING). */
public class SleepingBodyRenderer extends LivingEntityRenderer<SleepingBodyEntity, PlayerEntityModel<SleepingBodyEntity>> {

    public SleepingBodyRenderer(EntityRendererFactory.Context context) {
        super(context, new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER), false), 0.0f);
    }

    @Override
    public Identifier getTexture(SleepingBodyEntity entity) {
        return SkinTextures.of(entity);
    }

    @Override
    protected boolean hasLabel(SleepingBodyEntity entity) {
        return entity.hasCustomName() && super.hasLabel(entity);
    }
}
