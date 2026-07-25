package com.admin82.factions.client.renderer;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.block.CarePackageBlock;
import com.admin82.factions.blockentity.CarePackageBlockEntity;
import com.admin82.factions.client.model.CarePackageModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

public class CarePackageBlockEntityRenderer implements BlockEntityRenderer<CarePackageBlockEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            AdminsFactions.MODID, "textures/item/carepackage.png");
        private static final ResourceLocation OPEN_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            AdminsFactions.MODID, "textures/item/opened_joke.png");

    private final ModelPart closedModel;
    private final ModelPart openModel;

    public CarePackageBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        closedModel = context.bakeLayer(CarePackageModel.CLOSED_LAYER).getChild("main");
        openModel = context.bakeLayer(CarePackageModel.OPEN_LAYER).getChild("main");
    }

    @Override
    public void render(CarePackageBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        boolean open = blockEntity.getBlockState().getValue(CarePackageBlock.OPEN);
        Direction facing = blockEntity.getBlockState().getValue(CarePackageBlock.FACING);

        poseStack.pushPose();
        poseStack.translate(0.5F, 1.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(270.0F - facing.toYRot()));
        poseStack.translate(0.0F, 0.0F, -0.984375F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(open ? OPEN_TEXTURE : TEXTURE));
        (open ? openModel : closedModel).render(
                poseStack, vertices, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }
}