package com.admin82.factions.client.renderer;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.seam.SeamExtractorBlock;
import com.admin82.factions.seam.SeamExtractorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The Seam Extractor's moving parts: three rings that spin up while it mines (and wind down after), and the red
 * mining laser from the emitter under the roof down into the bore.
 */
public class SeamExtractorRenderer implements BlockEntityRenderer<SeamExtractorBlockEntity> {

    public static final ModelResourceLocation RING = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "block/seam_extractor_ring"));

    /** Full speed, in degrees a second. */
    private static final float TOP_SPEED = 540;
    /** Beam colour by upgrade path: none yellow, Speed red, Quantity blue, Efficiency green (SeamPath order). */
    private static final int[] BEAM_COLORS = {0xFFFFD020, 0xFFFF2A18, 0xFF2A7CFF, 0xFF2AFF50};
    /** Screen: 14 × 7.5 block pixels at 8 canvas units a pixel. */
    private static final float SW = 112, SH = 60;

    public SeamExtractorRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(SeamExtractorBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!state.hasProperty(SeamExtractorBlock.ACTIVE) || be.getLevel() == null) return;
        boolean active = state.getValue(SeamExtractorBlock.ACTIVE);

        // Ease the spin toward full speed while mining and back to a stop after
        long now = System.nanoTime();
        float dt = be.lastFrame == 0 ? 0 : Math.min(0.1f, (now - be.lastFrame) / 1e9f);
        be.lastFrame = now;
        float target = active ? TOP_SPEED : 0;
        be.ringSpeed += (target - be.ringSpeed) * Math.min(1, dt * (active ? 0.8f : 0.5f));
        // Wrap at 3600: whole turns for every ring (0.7x, 1x and 1.6x), so none of them jumps when it wraps
        be.ringAngle = (be.ringAngle + be.ringSpeed * dt) % 3600f;
        float power = be.ringSpeed / TOP_SPEED;

        pose.pushPose();
        pose.translate(-state.getValue(SeamExtractorBlock.CX), 0, -state.getValue(SeamExtractorBlock.CZ)); // to the north-west corner
        BlockPos origin = be.getBlockPos().offset(-state.getValue(SeamExtractorBlock.CX), 0, -state.getValue(SeamExtractorBlock.CZ));
        int ringLight = LevelRenderer.getLightColor(be.getLevel(), origin.above());

        BakedModel ring = Minecraft.getInstance().getModelManager().getModel(RING);
        VertexConsumer vc = buffers.getBuffer(RenderType.cutout());
        ring(pose, vc, ring, 7, be.ringAngle, 1f, ringLight, overlay);
        ring(pose, vc, ring, 18, -be.ringAngle * 1.6f, 0.62f, ringLight, overlay);
        ring(pose, vc, ring, 29, be.ringAngle * 0.7f + 22.5f, 1f, ringLight, overlay);

        if (active && power > 0.15f) {
            long time = be.getLevel().getGameTime();
            int color = BEAM_COLORS[Mth.clamp(be.screenPath, 0, BEAM_COLORS.length - 1)];
            // Wider with every upgrade level (none: thin, level 4: about twice as wide)
            float width = 1 + 0.25f * Mth.clamp(be.screenLevel, 0, com.admin82.factions.seam.SeamPath.MAX_LEVEL);
            float pulse = Mth.sin((time + partialTick) * 0.7f) * 0.015f;
            pose.pushPose();
            pose.translate(0.5, 2.5 / 16, 0.5); // the beam is drawn round (0.5, 0.5) from here: the machine's middle
            BeaconRenderer.renderBeaconBeam(pose, buffers, BeaconRenderer.BEAM_LOCATION, partialTick, 2.5f, time, 0, 2,
                    color, (0.05f * width + pulse) * power, 0.11f * width * power);
            pose.popPose();

            // Amplifier lasers: each upgrade level lights the lenses of the next ring down, starting at the top;
            // the last level makes them all stronger.
            int level = Mth.clamp(be.screenLevel, 0, com.admin82.factions.seam.SeamPath.MAX_LEVEL);
            float thick = (level >= 4 ? 1.7f : 1f) * power;
            float flicker = 0.75f + 0.25f * Mth.sin((time + partialTick) * 1.3f);
            if (level >= 1) miniLasers(pose, buffers, 29, be.ringAngle * 0.7f + 22.5f, 1f, color, thick, flicker);
            if (level >= 2) miniLasers(pose, buffers, 18, -be.ringAngle * 1.6f, 0.62f, color, thick, flicker);
            if (level >= 3) miniLasers(pose, buffers, 7, be.ringAngle, 1f, color, thick, flicker);
        }

        screen(be, state, pose, buffers);
        pose.popPose();
    }

    /** The front monitor: ore, extraction progress, charge and state. */
    private static void screen(SeamExtractorBlockEntity be, BlockState state, PoseStack pose, MultiBufferSource buffers) {
        var font = Minecraft.getInstance().font;
        pose.pushPose();
        // Turn the north-facing layout to the machine's front, round the middle of the footprint
        pose.translate(1, 0, 1);
        pose.mulPose(Axis.YP.rotationDegrees(180 - state.getValue(SeamExtractorBlock.FACING).toYRot()));
        pose.translate(-1, 0, -1);
        // Top-left of the glass as seen from the front (x 9..23, y 13.5..21 px, glass at z 0)
        pose.translate(23 / 16.0, 21 / 16.0, -0.004);
        pose.mulPose(Axis.YP.rotationDegrees(180));
        float s = 1 / 128f;
        pose.scale(s, -s, s);
        PcScreen.rect(buffers, pose, 0, 0, SW, SH, 0xFF0B1014, 0);
        pose.translate(0, 0, 0.5);

        String state0 = be.screenState.isEmpty() ? "..." : be.screenState;
        boolean mining = "MINING".equals(state0);
        int pathColor = BEAM_COLORS[Mth.clamp(be.screenPath, 0, BEAM_COLORS.length - 1)];

        // Ore name, shrunk to fit
        String ore = be.screenOre.isEmpty() ? "§8No seam here" : oreName(be.screenOre);
        float room = SW - 8;
        float os = Math.max(0.35f, Math.min(0.7f, room / Math.max(1, font.width(ore))));
        PcScreen.small(font, pose, buffers, PcScreen.fit(font, ore, (int) (room / os)), 4, 4 + (0.7f - os) * 4, os);

        float sm = 0.5f;
        PcScreen.small(font, pose, buffers, "§7PROGRESS", 4, 14, sm);
        smallRight(font, pose, buffers, "§f" + Math.round(be.screenProgress * 100) + "%", SW - 4, 14, sm);
        PcScreen.bar(buffers, pose, 4, 19.5f, SW - 4, 23.5f, be.screenProgress, pathColor);

        int charge = Math.round(be.screenCharge * 100);
        PcScreen.small(font, pose, buffers, "§7CHARGE", 4, 27, sm);
        smallRight(font, pose, buffers, (charge < 20 ? "§c" : charge < 60 ? "§e" : "§a") + charge + "%", SW - 4, 27, sm);
        PcScreen.bar(buffers, pose, 4, 32.5f, SW - 4, 36.5f, be.screenCharge,
                charge < 20 ? 0xFFE03A2A : charge < 60 ? 0xFFE0B020 : 0xFF3AD05A);

        String color = mining ? "§a" : "NO POWER".equals(state0) || "OFF CLAIM".equals(state0) ? "§c" : "UNOWNED".equals(state0) ? "§e"
                : "OUTPUT FULL".equals(state0) ? "§e" : "§7";
        String shown = color + (mining && (be.getLevel().getGameTime() / 10) % 2 == 0 ? "● " : "  ") + state0;
        float ss = 0.6f;
        PcScreen.small(font, pose, buffers, shown, (SW - font.width(shown) * ss) / 2, 44, ss);
        pose.popPose();
    }

    private static void smallRight(net.minecraft.client.gui.Font font, PoseStack pose, MultiBufferSource buffers, String text, float right, float y, float scale) {
        PcScreen.small(font, pose, buffers, text, right - font.width(text) * scale, y, scale);
    }

    private static String oreName(String id) {
        var rl = ResourceLocation.tryParse(id);
        if (rl == null) return id;
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
        return item == net.minecraft.world.item.Items.AIR ? id : new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
    }

    /** Thin beams from a ring's four lenses into the main beam, turning with the ring. */
    private static void miniLasers(PoseStack pose, MultiBufferSource buffers, float y, float angle, float scale, int color,
                                   float thick, float flicker) {
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
        pose.pushPose();
        pose.translate(1, (y + 1) / 16, 1); // the lenses' middle height
        pose.mulPose(Axis.YP.rotationDegrees(angle));
        pose.scale(scale, 1, scale);
        for (int i = 0; i < 4; i++) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(90 * i));
            // from the lens face (5 px out) to the main beam (1 px out); a soft glow and a bright core
            laser(vc, pose.last().pose(), 5 / 16f, 1 / 16f, 0.45f / 16 * thick, r, g, b, 0.35f * flicker);
            laser(vc, pose.last().pose(), 5 / 16f, 1 / 16f, 0.18f / 16 * thick, Math.min(1, r + 0.5f), Math.min(1, g + 0.5f), Math.min(1, b + 0.5f), 0.8f * flicker);
            pose.popPose();
        }
        pose.popPose();
    }

    /** A beam along -z from {@code from} to {@code to}, as two crossed quads drawn from both sides. */
    private static void laser(VertexConsumer vc, org.joml.Matrix4f m, float from, float to, float w, float r, float g, float b, float a) {
        float z0 = -from, z1 = -to;
        // flat
        vc.addVertex(m, -w, 0, z0).setColor(r, g, b, a); vc.addVertex(m, w, 0, z0).setColor(r, g, b, a);
        vc.addVertex(m, w, 0, z1).setColor(r, g, b, a); vc.addVertex(m, -w, 0, z1).setColor(r, g, b, a);
        vc.addVertex(m, -w, 0, z1).setColor(r, g, b, a); vc.addVertex(m, w, 0, z1).setColor(r, g, b, a);
        vc.addVertex(m, w, 0, z0).setColor(r, g, b, a); vc.addVertex(m, -w, 0, z0).setColor(r, g, b, a);
        // upright
        vc.addVertex(m, 0, -w, z0).setColor(r, g, b, a); vc.addVertex(m, 0, w, z0).setColor(r, g, b, a);
        vc.addVertex(m, 0, w, z1).setColor(r, g, b, a); vc.addVertex(m, 0, -w, z1).setColor(r, g, b, a);
        vc.addVertex(m, 0, -w, z1).setColor(r, g, b, a); vc.addVertex(m, 0, w, z1).setColor(r, g, b, a);
        vc.addVertex(m, 0, w, z0).setColor(r, g, b, a); vc.addVertex(m, 0, -w, z0).setColor(r, g, b, a);
    }

    /** A ring at {@code y} pixels up, turned {@code angle} degrees round the machine's middle. */
    private static void ring(PoseStack pose, VertexConsumer vc, BakedModel model, float y, float angle, float scale, int light, int overlay) {
        pose.pushPose();
        pose.translate(1, y / 16, 1);
        pose.mulPose(Axis.YP.rotationDegrees(angle));
        pose.scale(scale, 1, scale);
        circle(pose, vc, light);
        pose.translate(-1, 0, -1);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer()
                .renderModel(pose.last(), vc, null, model, 1, 1, 1, light, overlay); // the glowing nubs
        pose.popPose();
    }

    private static final int SEGMENTS = 48;
    /** Ring size in block pixels: outer and inner radius, height. */
    private static final float OUTER = 8 / 16f, INNER = 6.5f / 16f, HEIGHT = 2 / 16f;
    /** Where the ring's top and side art is in seam_extractor.png (texels of 128). */
    private static final float TOP_V0 = 32 / 128f, TOP_V1 = 36 / 128f, SIDE_V0 = 36 / 128f, SIDE_V1 = 40 / 128f;
    private static final float U0 = 80 / 128f, U1 = 112 / 128f;
    /** Segments one copy of the art stretches over, round the ring. */
    private static final int ART_EVERY = 8;

    /** A round ring centred on the pose's origin, made of {@link #SEGMENTS} straight pieces. */
    private static void circle(PoseStack pose, VertexConsumer vc, int light) {
        var sprite = Minecraft.getInstance().getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "block/seam_extractor"));
        var last = pose.last();
        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = (float) (i * Math.PI * 2 / SEGMENTS), a1 = (float) ((i + 1) * Math.PI * 2 / SEGMENTS);
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            float t0 = (i % ART_EVERY) / (float) ART_EVERY, t1 = (i % ART_EVERY + 1) / (float) ART_EVERY;
            float u0 = sprite.getU(U0 + (U1 - U0) * t0), u1 = sprite.getU(U0 + (U1 - U0) * t1);
            float tv0 = sprite.getV(TOP_V0), tv1 = sprite.getV(TOP_V1), sv0 = sprite.getV(SIDE_V0), sv1 = sprite.getV(SIDE_V1);
            float cm = (c0 + c1) / 2, sm = (s0 + s1) / 2;
            // top and bottom
            quad(vc, last, light, 1f, 0, 1, 0,
                    INNER * c0, HEIGHT, INNER * s0, u0, tv1, INNER * c1, HEIGHT, INNER * s1, u1, tv1,
                    OUTER * c1, HEIGHT, OUTER * s1, u1, tv0, OUTER * c0, HEIGHT, OUTER * s0, u0, tv0);
            quad(vc, last, light, 0.5f, 0, -1, 0,
                    OUTER * c0, 0, OUTER * s0, u0, tv0, OUTER * c1, 0, OUTER * s1, u1, tv0,
                    INNER * c1, 0, INNER * s1, u1, tv1, INNER * c0, 0, INNER * s0, u0, tv1);
            // outside and inside
            quad(vc, last, light, 0.8f, cm, 0, sm,
                    OUTER * c0, HEIGHT, OUTER * s0, u0, sv0, OUTER * c1, HEIGHT, OUTER * s1, u1, sv0,
                    OUTER * c1, 0, OUTER * s1, u1, sv1, OUTER * c0, 0, OUTER * s0, u0, sv1);
            quad(vc, last, light, 0.6f, -cm, 0, -sm,
                    INNER * c0, 0, INNER * s0, u0, sv1, INNER * c1, 0, INNER * s1, u1, sv1,
                    INNER * c1, HEIGHT, INNER * s1, u1, sv0, INNER * c0, HEIGHT, INNER * s0, u0, sv0);
        }
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, int light, float shade, float nx, float ny, float nz,
                             float x0, float y0, float z0, float u0, float v0, float x1, float y1, float z1, float u1, float v1,
                             float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3) {
        vertex(vc, pose, light, shade, nx, ny, nz, x0, y0, z0, u0, v0);
        vertex(vc, pose, light, shade, nx, ny, nz, x1, y1, z1, u1, v1);
        vertex(vc, pose, light, shade, nx, ny, nz, x2, y2, z2, u2, v2);
        vertex(vc, pose, light, shade, nx, ny, nz, x3, y3, z3, u3, v3);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, int light, float shade, float nx, float ny, float nz,
                               float x, float y, float z, float u, float v) {
        vc.addVertex(pose, x, y, z).setColor(shade, shade, shade, 1f).setUv(u, v).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, nx, ny, nz);
    }

    @Override
    public AABB getRenderBoundingBox(SeamExtractorBlockEntity be) {
        BlockState state = be.getBlockState();
        BlockPos pos = be.getBlockPos();
        if (!state.hasProperty(SeamExtractorBlock.CX)) return new AABB(pos);
        BlockPos o = pos.offset(-state.getValue(SeamExtractorBlock.CX), 0, -state.getValue(SeamExtractorBlock.CZ));
        return new AABB(o).expandTowards(1, 2, 1);
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
