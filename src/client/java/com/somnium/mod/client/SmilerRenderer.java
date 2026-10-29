package com.somnium.mod.client;

import com.somnium.mod.entity.nightmare.SmilerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.EyesFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * Улыбака: само тело невидимо, светится только слой с глазами и улыбкой (как глаза паука).
 * На свету сущность прячет и его.
 */
public class SmilerRenderer extends MobEntityRenderer<SmilerEntity, BipedEntityModel<SmilerEntity>> {

    private static final Identifier TEXTURE = new Identifier("somnium", "textures/entity/smiler.png");
    private static final RenderLayer GLOW = RenderLayer.getEyes(new Identifier("somnium", "textures/entity/smiler_glow.png"));

    public SmilerRenderer(EntityRendererFactory.Context context) {
        super(context, new BipedEntityModel<>(context.getPart(EntityModelLayers.ZOMBIE)), 0.0f);
        this.addFeature(new EyesFeatureRenderer<>(this) {
            @Override
            public RenderLayer getEyesTexture() {
                return GLOW;
            }

            @Override
            public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, SmilerEntity entity,
                               float limbAngle, float limbDistance, float tickDelta, float animationProgress,
                               float headYaw, float headPitch) {
                if (!entity.isHidden()) {
                    super.render(matrices, vertexConsumers, light, entity, limbAngle, limbDistance, tickDelta,
                            animationProgress, headYaw, headPitch);
                }
            }
        });
    }

    @Override
    public Identifier getTexture(SmilerEntity entity) {
        return TEXTURE;
    }
}
