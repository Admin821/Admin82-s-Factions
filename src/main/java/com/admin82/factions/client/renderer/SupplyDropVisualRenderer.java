package com.admin82.factions.client.renderer;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.client.model.CarePackageParachuteModel;
import com.admin82.factions.entity.SupplyDropVisualEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

public class SupplyDropVisualRenderer extends EntityRenderer<SupplyDropVisualEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            AdminsFactions.MODID, "textures/item/carepackage_parachute.png");

    private final ModelPart model;

    public SupplyDropVisualRenderer(EntityRendererProvider.Context context) {
        super(context);
        model = context.bakeLayer(CarePackageParachuteModel.LAYER).getChild("main");
        shadowRadius = 1.25F;
    }

    @Override
    public void render(SupplyDropVisualEntity entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight) {
        poseStack.pushPose();
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.5F, 0.0F);
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        model.render(poseStack, vertices, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
        super.render(entity, yaw, partialTick, poseStack, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(SupplyDropVisualEntity entity) {
        return TEXTURE;
    }
}