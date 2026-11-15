package com.admin82.factions.screen;

import com.admin82.factions.item.BarracksItem;
import com.admin82.factions.item.FactionTableItem;
import com.admin82.factions.item.OutpostItem;
import com.admin82.factions.registry.ModItems;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.SectionPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Shows the chunk you're standing in while you hold something that cares about chunks (faction table, barracks,
 * outpost, seam extractor): soft see-through walls that fade out above and below you, a bright line where they meet
 * your feet, glowing corner posts, and fainter walls for the chunks around it.
 */
@OnlyIn(Dist.CLIENT)
public final class ChunkBorderRenderer {

    private ChunkBorderRenderer() {}

    /** Blocks the walls reach below and above your feet before they fade out. */
    private static final double BELOW = 3, ABOVE = 9;

    public static void onRenderLevel(RenderLevelStageEvent event) {
        // AFTER_ENTITIES: poseStack has camera rotation but NOT translation, so translate(-cam) + world coords.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        int color = colorFor(mc.player.getMainHandItem());
        if (color == 0) color = colorFor(mc.player.getOffhandItem());
        if (color == 0) return;
        float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;

        int chunkX = SectionPos.blockToSectionCoord(mc.player.getBlockX());
        int chunkZ = SectionPos.blockToSectionCoord(mc.player.getBlockZ());
        double feet = mc.player.getY() + 0.02;
        float time = (mc.level.getGameTime() % 80 + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 80f;
        float pulse = 0.85f + 0.15f * (float) Math.sin(time * Math.PI * 2);

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = pose.last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // Walls: neighbours faint, yours stronger
        VertexConsumer walls = buffers.getBuffer(Types.WALLS);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                chunkWalls(walls, m, chunkX + dx, chunkZ + dz, feet, r, g, b, 0.05f * pulse);
            }
        }
        chunkWalls(walls, m, chunkX, chunkZ, feet, r, g, b, 0.22f * pulse);
        buffers.endBatch(Types.WALLS);

        // Edges: a bright ring at your feet and the four corner posts
        RenderSystem.lineWidth(2.5f);
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        double x0 = chunkX * 16.0, z0 = chunkZ * 16.0, x1 = x0 + 16, z1 = z0 + 16;
        float a = 0.9f * pulse;
        line(lines, pose, x0, feet, z0, x1, feet, z0, r, g, b, a, a);
        line(lines, pose, x1, feet, z0, x1, feet, z1, r, g, b, a, a);
        line(lines, pose, x1, feet, z1, x0, feet, z1, r, g, b, a, a);
        line(lines, pose, x0, feet, z1, x0, feet, z0, r, g, b, a, a);
        double[][] corners = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        for (double[] c : corners) {
            line(lines, pose, c[0], feet - BELOW, c[1], c[0], feet, c[1], r, g, b, 0f, a);
            line(lines, pose, c[0], feet, c[1], c[0], feet + ABOVE, c[1], r, g, b, a, 0f);
        }
        buffers.endBatch(RenderType.lines());
        RenderSystem.lineWidth(1.0f);
        pose.popPose();
    }

    /** The colour for a held item, or 0 when it doesn't show chunks. */
    private static int colorFor(ItemStack stack) {
        var item = stack.getItem();
        if (item == ModItems.SEAM_EXTRACTOR.get()) return 0x40D8FF;   // cyan
        if (item instanceof OutpostItem) return 0xFF9A1A;             // orange
        if (item instanceof FactionTableItem || item instanceof BarracksItem) return 0xFFD23A; // gold
        return 0;
    }

    /** Four see-through walls around a chunk, strongest at your feet and fading to nothing above and below. */
    private static void chunkWalls(VertexConsumer buf, Matrix4f m, int cx, int cz, double feet,
                                   float r, float g, float b, float alpha) {
        double x0 = cx * 16.0, z0 = cz * 16.0, x1 = x0 + 16, z1 = z0 + 16;
        wall(buf, m, x0, z0, x1, z0, feet, r, g, b, alpha);
        wall(buf, m, x1, z0, x1, z1, feet, r, g, b, alpha);
        wall(buf, m, x1, z1, x0, z1, feet, r, g, b, alpha);
        wall(buf, m, x0, z1, x0, z0, feet, r, g, b, alpha);
    }

    private static void wall(VertexConsumer buf, Matrix4f m, double ax, double az, double bx, double bz, double feet,
                             float r, float g, float b, float alpha) {
        double bottom = feet - BELOW, top = feet + ABOVE;
        // Lower half: clear at the bottom to full at your feet
        quad(buf, m, ax, az, bx, bz, bottom, feet, r, g, b, 0f, alpha);
        // Upper half: full at your feet to clear at the top
        quad(buf, m, ax, az, bx, bz, feet, top, r, g, b, alpha, 0f);
    }

    private static void quad(VertexConsumer buf, Matrix4f m, double ax, double az, double bx, double bz,
                             double y0, double y1, float r, float g, float b, float a0, float a1) {
        buf.addVertex(m, (float) ax, (float) y0, (float) az).setColor(r, g, b, a0);
        buf.addVertex(m, (float) bx, (float) y0, (float) bz).setColor(r, g, b, a0);
        buf.addVertex(m, (float) bx, (float) y1, (float) bz).setColor(r, g, b, a1);
        buf.addVertex(m, (float) ax, (float) y1, (float) az).setColor(r, g, b, a1);
    }

    private static void line(VertexConsumer buf, PoseStack pose, double x0, double y0, double z0, double x1, double y1, double z1,
                             float r, float g, float b, float a0, float a1) {
        float nx = (float) (x1 - x0), ny = (float) (y1 - y0), nz = (float) (z1 - z0);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0) return;
        nx /= len; ny /= len; nz /= len;
        var p = pose.last();
        buf.addVertex(p, (float) x0, (float) y0, (float) z0).setColor(r, g, b, a0).setNormal(p, nx, ny, nz);
        buf.addVertex(p, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a1).setNormal(p, nx, ny, nz);
    }

    /** See-through, double-sided walls that don't hide what's behind them (no depth writing). */
    private static final class Types extends RenderType {
        private Types(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling,
                      boolean sort, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sort, setup, clear);
        }

        static final RenderType WALLS = create("adminsfactions_chunk_walls", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS, 4096, false, true,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));
    }
}
