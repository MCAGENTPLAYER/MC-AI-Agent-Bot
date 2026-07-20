package com.aibot.mod.entity;

import com.aibot.mod.AiBotMod;
import com.aibot.mod.Config;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public class AiBotRenderer extends LivingEntityRenderer<AiBotEntity, HumanoidModel<AiBotEntity>> {
    private static final ResourceLocation SKIN1 =
            new ResourceLocation(AiBotMod.MODID, "textures/entity/skin1.png");
    private static final ResourceLocation SKIN2 =
            new ResourceLocation(AiBotMod.MODID, "textures/entity/skin2.png");
    private static final ResourceLocation HALO_TEXTURE =
            new ResourceLocation(AiBotMod.MODID, "textures/entity/halo.png");

    public AiBotRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM)), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM_OUTER_ARMOR)),
                context.getModelManager()));
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        // 光环作为 RenderLayer 绑定到头部骨骼
        this.addLayer(new HaloLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(AiBotEntity entity) {
        return Config.getSkin() == 1 ? SKIN1 : SKIN2;
    }

    /** 隐藏 Bot 默认名字标签 */
    @Override
    protected boolean shouldShowName(AiBotEntity entity) {
        return false;
    }

    /** RenderLayer: 在头部骨骼位置渲染光环 */
    private class HaloLayer extends RenderLayer<AiBotEntity, HumanoidModel<AiBotEntity>> {
        public HaloLayer(AiBotRenderer renderer) {
            super(renderer);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                           AiBotEntity entity, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (!Config.isHaloEnabled()) return;
            float offsetZ = Config.getHaloOffsetZ();       // 前后偏移
            float angle   = Config.getHaloAngle();          // 倾斜角度
            boolean glow  = Config.isHaloGlow();             // 发光开关
            float size    = Config.getHaloSize();            // 光环大小
            float height  = Config.getHaloHeight();          // 头部上方偏移

            float[] color = getHaloColor(entity);

            try {
                poseStack.pushPose();
                // 绑定到头部骨骼位置 + 旋转
                getParentModel().head.translateAndRotate(poseStack);
                // 从头部骨骼偏移到头顶上方
                poseStack.translate(0.0D, -height, offsetZ);
                poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(angle));
                poseStack.scale(size, size, size);

                RenderType renderType = glow
                    ? RenderType.entityTranslucentEmissive(HALO_TEXTURE)
                    : RenderType.entityTranslucent(HALO_TEXTURE);
                VertexConsumer vertexConsumer = buffer.getBuffer(renderType);
                Matrix4f matrix = poseStack.last().pose();

                float alpha = 0.9F;
                int overlayU = OverlayTexture.NO_OVERLAY & 0xFFFF;
                int overlayV = OverlayTexture.NO_OVERLAY >> 16;
                int lu = packedLight & 0xFFFF;
                int lv = packedLight >> 16;
                vertexConsumer.vertex(matrix, -1, -1, 0.0F).color(color[0], color[1], color[2], alpha).uv(0, 0).overlayCoords(overlayU, overlayV).uv2(lu, lv).normal(0, 1, 0).endVertex();
                vertexConsumer.vertex(matrix,  1, -1, 0.0F).color(color[0], color[1], color[2], alpha).uv(1, 0).overlayCoords(overlayU, overlayV).uv2(lu, lv).normal(0, 1, 0).endVertex();
                vertexConsumer.vertex(matrix,  1,  1, 0.0F).color(color[0], color[1], color[2], alpha).uv(1, 1).overlayCoords(overlayU, overlayV).uv2(lu, lv).normal(0, 1, 0).endVertex();
                vertexConsumer.vertex(matrix, -1,  1, 0.0F).color(color[0], color[1], color[2], alpha).uv(0, 1).overlayCoords(overlayU, overlayV).uv2(lu, lv).normal(0, 1, 0).endVertex();

                poseStack.popPose();
            } catch (Exception e) {
                // Oculus 等光影模组会修改 BufferBuilder 顶点格式，导致 endVertex() 崩溃
                // 捕获异常防止客户端断开连接
                poseStack.popPose();
            }
        }
    }

    /** 根据当前动作返回颜色 (r, g, b) 0~1 */
    private float[] getHaloColor(AiBotEntity entity) {
        String action = entity.getCurrentAction();
        if (action == null) return new float[]{0.7F, 0.7F, 0.7F};

        return switch (action) {
            case "FOLLOW"    -> new float[]{0.2F, 1.0F, 0.2F};
            case "FARM"      -> new float[]{0.2F, 0.8F, 0.2F};
            case "CHOP_TREE" -> new float[]{0.6F, 0.3F, 0.0F};
            case "MINE"      -> new float[]{0.6F, 0.5F, 0.3F};
            case "COOK"      -> new float[]{1.0F, 0.4F, 0.0F};
            case "CRAFT"     -> new float[]{0.4F, 0.6F, 1.0F};
            case "SLEEP"     -> new float[]{0.3F, 0.3F, 0.8F};
            case "HUNT"      -> new float[]{1.0F, 0.2F, 0.2F};
            default          -> new float[]{0.7F, 0.7F, 0.7F};
        };
    }
}
