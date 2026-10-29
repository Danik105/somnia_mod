package com.somnium.mod.client;

import com.somnium.mod.entity.nightmare.PhantomEelEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.SilverfishEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Угорь: вытянутое сегментное тело (модель чешуйницы, увеличенная) со своей текстурой. */
public class PhantomEelRenderer extends MobEntityRenderer<PhantomEelEntity, SilverfishEntityModel<PhantomEelEntity>> {

    private static final Identifier TEXTURE = new Identifier("somnium", "textures/entity/phantom_eel.png");

    public PhantomEelRenderer(EntityRendererFactory.Context context) {
        super(context, new SilverfishEntityModel<>(context.getPart(EntityModelLayers.SILVERFISH)), 0.3f);
    }

    @Override
    protected void scale(PhantomEelEntity entity, MatrixStack matrices, float amount) {
        matrices.scale(1.8f, 1.4f, 2.4f);
    }

    @Override
    public Identifier getTexture(PhantomEelEntity entity) {
        return TEXTURE;
    }
}
