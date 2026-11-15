package com.admin82.factions.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

/**
 * Drawing on the glowing screen of the little computer model (the meters' PC, also on the outpost storage box).
 * <p>
 * The screen is 12×6 pixels of the block (x 2-14, front face at z 9 when the model faces north). {@link #begin} sets
 * up a 96×48 canvas on it, 8 canvas units to a pixel, x to the right and y down as seen from the front, and clears
 * it to the dark background. Everything is drawn at full brightness.
 */
public final class PcScreen {

    public static final int W = 96, H = 48;
    public static final int FULL = LightTexture.FULL_BRIGHT;

    private PcScreen() {}

    /**
     * Pushes the pose and moves onto the screen of a model turned to {@code facing} (blockstate y rotation: north 0,
     * east 90, south 180, west 270) whose screen's top edge is {@code topPx} block pixels up. Pop the pose afterwards.
     */
    public static void begin(PoseStack pose, MultiBufferSource buffers, Direction facing, float topPx) {
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-(facing.toYRot() + 180)));
        pose.translate(-0.5, 0, -0.5);
        // Top-left corner as seen from the front, just in front of the glass
        pose.translate(14 / 16.0, topPx / 16.0, 9 / 16.0 - 0.002);
        pose.mulPose(Axis.YP.rotationDegrees(180));
        float s = 1 / 128f;
        pose.scale(s, -s, s);
        rect(buffers, pose, 2, 2, W - 2, H - 2, 0xFF0B1014, 0);
        pose.translate(0, 0, 0.5);
    }

    /** A filled rectangle; {@code z} lifts it off what's under it (canvas units, towards the viewer). */
    public static void rect(MultiBufferSource buffers, PoseStack pose, float x0, float y0, float x1, float y1, int argb, float z) {
        VertexConsumer buf = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();
        buf.addVertex(m, x0, y0, z).setColor(argb);
        buf.addVertex(m, x0, y1, z).setColor(argb);
        buf.addVertex(m, x1, y1, z).setColor(argb);
        buf.addVertex(m, x1, y0, z).setColor(argb);
    }

    /** A bar from x0 to x1 filled to {@code fraction}. */
    public static void bar(MultiBufferSource buffers, PoseStack pose, float x0, float y0, float x1, float y1, float fraction, int color) {
        rect(buffers, pose, x0, y0, x1, y1, 0xFF283038, 0.1f);
        fraction = Math.max(0, Math.min(1, fraction));
        if (fraction > 0) rect(buffers, pose, x0, y0, x0 + (x1 - x0) * fraction, y1, color, 0.2f);
    }

    public static void text(Font font, PoseStack pose, MultiBufferSource buffers, String text, float x, float y) {
        font.drawInBatch(text, x, y, 0xFFFFFFFF, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, FULL);
    }

    public static void right(Font font, PoseStack pose, MultiBufferSource buffers, String text, float rightX, float y) {
        text(font, pose, buffers, text, rightX - font.width(text), y);
    }

    public static void centered(Font font, PoseStack pose, MultiBufferSource buffers, String text, float cx, float y) {
        text(font, pose, buffers, text, cx - font.width(text) / 2f, y);
    }

    /** Text at a smaller {@code scale}, its top-left at (x, y). */
    public static void small(Font font, PoseStack pose, MultiBufferSource buffers, String text, float x, float y, float scale) {
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1);
        text(font, pose, buffers, text, 0, 0);
        pose.popPose();
    }

    /** Cuts {@code text} (colour codes allowed) down to {@code width} font units, ending in "…" if it was cut. */
    public static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        String cut = font.plainSubstrByWidth(text, width - font.width("…"));
        return cut + "…";
    }

    /** 950, 1.2k, 34k, 1.5M */
    public static String shortCount(long n) {
        if (n < 0) return "-" + shortCount(-n);
        if (n < 1000) return Long.toString(n);
        if (n < 10_000) return String.format("%.1fk", n / 1000.0);
        if (n < 1_000_000) return (n / 1000) + "k";
        if (n < 10_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n < 1_000_000_000) return (n / 1_000_000) + "M";
        return String.format("%.1fG", n / 1_000_000_000.0);
    }
}
