package com.admin82.factions.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Renders amber glowing outlines (x-ray, visible through walls) around storage
 * blocks the Resource War winner is entitled to loot.
 * Uses Tesselator directly so RenderSystem.disableDepthTest() is not overridden
 * by a RenderType.
 */
@OnlyIn(Dist.CLIENT)
public final class ContainerHighlightRenderer {

    private ContainerHighlightRenderer() {}

    private static volatile List<BlockPos> highlights = List.of();

    public static void setHighlights(List<BlockPos> positions) { highlights = List.copyOf(positions); }
    public static void clearHighlights()                       { highlights = List.of(); }
    public static List<BlockPos> getHighlights()               { return highlights; }

    @OnlyIn(Dist.CLIENT)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        List<BlockPos> snapshot = highlights;
        if (snapshot.isEmpty()) return;

        Vec3 cam = event.getCamera().getPosition();
        var poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z); // world origin

        // Disable depth test so outlines are visible through walls (x-ray).
        // We use Tesselator directly so this stays in effect during the draw.
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(4.0f);

        Tesselator tess = Tesselator.getInstance();
        var buf = tess.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);

        for (BlockPos pos : snapshot) {
            // Render a slightly expanded outline so it doesn't z-fight with the block surface
            LevelRenderer.renderLineBox(poseStack, buf,
                    pos.getX() - 0.005,
                    pos.getY() - 0.005,
                    pos.getZ() - 0.005,
                    pos.getX() + 1.005,
                    pos.getY() + 1.005,
                    pos.getZ() + 1.005,
                    1f, 0.7f, 0f, 1f); // amber / gold
        }

        var mesh = buf.build();
        if (mesh != null) {
            RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }

        RenderSystem.enableDepthTest();
        RenderSystem.lineWidth(1.0f);
        poseStack.popPose();
    }
}