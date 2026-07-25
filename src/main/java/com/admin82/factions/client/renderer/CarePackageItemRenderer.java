package com.admin82.factions.client.renderer;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.client.model.CarePackageModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class CarePackageItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            AdminsFactions.MODID, "textures/item/carepackage.png");
    private ModelPart model;

    public CarePackageItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                             MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (model == null) {
            model = Minecraft.getInstance().getEntityModels()
                .bakeLayer(CarePackageModel.CLOSED_LAYER).getChild("main");
        }

        poseStack.pushPose();
        poseStack.translate(0.5F, 1.5F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        model.render(poseStack, vertices, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }
}