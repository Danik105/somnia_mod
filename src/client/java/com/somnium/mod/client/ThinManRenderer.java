package com.somnium.mod.client;

import com.somnium.mod.entity.nightmare.ThinManEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Тощий: обычная модель человека, вытянутая в высоту и сжатая в ширину. */
public class ThinManRenderer extends MobEntityRenderer<ThinManEntity, BipedEntityModel<ThinManEntity>> {

    private static final Identifier TEXTURE = new Identifier("somnium", "textures/entity/thin_man.png");

    public ThinManRenderer(EntityRendererFactory.Context context) {
        super(context, new BipedEntityModel<>(context.getPart(EntityModelLayers.ZOMBIE)), 0.4f);
    }

    @Override
    protected void scale(ThinManEntity entity, MatrixStack matrices, float amount) {
        matrices.scale(0.78f, 1.64f, 0.7f);
    }

    @Override
    public Identifier getTexture(ThinManEntity entity) {
        return TEXTURE;
    }
}
