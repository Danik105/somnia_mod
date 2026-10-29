package com.somnium.mod.client;

import com.somnium.mod.entity.nightmare.GraspingHandsEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/** Руки из-под кровати: от модели человека видны только поднятые, шевелящиеся руки. */
public class GraspingHandsRenderer extends MobEntityRenderer<GraspingHandsEntity, GraspingHandsRenderer.HandsModel> {

    private static final Identifier TEXTURE = new Identifier("somnium", "textures/entity/grasping_hands.png");

    public GraspingHandsRenderer(EntityRendererFactory.Context context) {
        super(context, new HandsModel(context.getPart(EntityModelLayers.ZOMBIE)), 0.0f);
    }

    @Override
    protected void scale(GraspingHandsEntity entity, MatrixStack matrices, float amount) {
        matrices.scale(1.0f, 1.3f, 1.0f);
    }

    /** Тело в полу: свет берём над полом, иначе руки были бы чёрными. */
    @Override
    protected int getBlockLight(GraspingHandsEntity entity, BlockPos pos) {
        return Math.max(4, super.getBlockLight(entity, pos.up(3)));
    }

    @Override
    protected int getSkyLight(GraspingHandsEntity entity, BlockPos pos) {
        return super.getSkyLight(entity, pos.up(3));
    }

    @Override
    public Identifier getTexture(GraspingHandsEntity entity) {
        return TEXTURE;
    }

    public static class HandsModel extends BipedEntityModel<GraspingHandsEntity> {

        public HandsModel(ModelPart root) {
            super(root);
        }

        @Override
        public void setAngles(GraspingHandsEntity entity, float limbAngle, float limbDistance, float age,
                              float headYaw, float headPitch) {
            super.setAngles(entity, limbAngle, limbDistance, age, headYaw, headPitch);
            head.visible = false;
            hat.visible = false;
            body.visible = false;
            rightLeg.visible = false;
            leftLeg.visible = false;
            float sway = MathHelper.sin(age * 0.45f) * 0.3f;
            rightArm.pitch = MathHelper.PI + sway;
            leftArm.pitch = MathHelper.PI - sway;
            rightArm.roll = 0.2f + MathHelper.sin(age * 0.3f) * 0.15f;
            leftArm.roll = -0.2f - MathHelper.cos(age * 0.3f) * 0.15f;
        }
    }
}
