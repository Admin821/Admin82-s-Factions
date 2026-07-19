package com.admin82.factions.screen;

import com.admin82.factions.item.BarracksItem;
import com.admin82.factions.item.FactionTableItem;
import com.admin82.factions.item.OutpostItem;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.SectionPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@OnlyIn(Dist.CLIENT)
public final class ChunkBorderRenderer {

    private ChunkBorderRenderer() {}

    public static void onRenderLevel(RenderLevelStageEvent event) {
        // AFTER_ENTITIES: poseStack has camera rotation but NOT translation.
        // Use translate(-cam) + world coords (the well-known working pattern).
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        ItemStack main = mc.player.getMainHandItem();
        ItemStack off  = mc.player.getOffhandItem();
        boolean holding = main.getItem() instanceof BarracksItem
            || off.getItem() instanceof BarracksItem
            || main.getItem() instanceof OutpostItem
                || off.getItem()  instanceof OutpostItem
                || main.getItem() instanceof FactionTableItem
                || off.getItem()  instanceof FactionTableItem;
        if (!holding) return;

        int    chunkX  = SectionPos.blockToSectionCoord((int) mc.player.getX());
        int    chunkZ  = SectionPos.blockToSectionCoord((int) mc.player.getZ());
        double playerY = mc.player.getY();
        double yBot    = playerY - 2.0;
        double yTop    = playerY + 14.0;

        Vec3 cam = event.getCamera().getPosition();

        var poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z); // move origin to world 0,0,0

        RenderSystem.lineWidth(2.0f);

        MultiBufferSource.BufferSource bs = mc.renderBuffers().bufferSource();
        var buf = bs.getBuffer(RenderType.lines());

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int    chX = chunkX + dx;
                int    chZ = chunkZ + dz;
                double x0  = chX * 16.0;
                double x1  = x0 + 16.0;
                double z0  = chZ * 16.0;
                double z1  = z0 + 16.0;

                boolean isCurrent = (dx == 0 && dz == 0);
                float r, g, b, a;
                if (isCurrent) {
                    r = 1.0f; g = 0.85f; b = 0.0f; a = 1.0f;
                } else {
                    float fade = 1.0f - (Math.abs(dx) + Math.abs(dz)) / 2.5f;
                    r = 0.4f; g = 0.5f; b = 1.0f; a = Math.max(0.2f, fade * 0.55f);
                }

                // Render at world coordinates (translate already moved origin)
                LevelRenderer.renderLineBox(poseStack, buf, x0, yBot, z0, x1, yTop, z1, r, g, b, a);
            }
        }

        bs.endBatch(RenderType.lines());
        RenderSystem.lineWidth(1.0f);
        poseStack.popPose();
    }
}