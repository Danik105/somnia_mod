package com.somnium.mod.client;

import com.somnium.mod.entity.nightmare.CrawlerEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.SpiderEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Ползун: многоногое тело (модель паука), бледная кожа. */
public class CrawlerRenderer extends MobEntityRenderer<CrawlerEntity, SpiderEntityModel<CrawlerEntity>> {

    private static final Identifier TEXTURE = new Identifier("somnium", "textures/entity/crawler.png");

    public CrawlerRenderer(EntityRendererFactory.Context context) {
        super(context, new SpiderEntityModel<>(context.getPart(EntityModelLayers.SPIDER)), 0.6f);
    }

    @Override
    protected void scale(CrawlerEntity entity, MatrixStack matrices, float amount) {
        matrices.scale(0.85f, 0.7f, 1.1f);
    }

    @Override
    public Identifier getTexture(CrawlerEntity entity) {
        return TEXTURE;
    }
}
