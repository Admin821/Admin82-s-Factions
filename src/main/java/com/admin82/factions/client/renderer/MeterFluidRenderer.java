package com.admin82.factions.client.renderer;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.meter.MeterBlock;
import com.admin82.factions.meter.MeterBlockEntity;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import com.admin82.factions.meter.MeterKind;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import org.joml.Matrix4f;

/**
 * Draws the fluid inside a fluid meter's see-through tank, filled up to how full it is, and makes a power meter's
 * wires glow brighter the fuller its battery is.
 */
@OnlyIn(Dist.CLIENT)
public class MeterFluidRenderer implements BlockEntityRenderer<MeterBlockEntity> {

    // Inside of the tank, in block pixels (the frame is 1 px thick).
    private static final float MIN = 1.2f / 16f;
    private static final float MAX = 14.8f / 16f;
    private static final float BOTTOM = 0.2f / 16f;
    private static final float TOP = 14.8f / 16f;

    private final net.minecraft.client.gui.Font font;

    public MeterFluidRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public void render(MeterBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        renderScreen(be, pose, buffers);
        if (be.kind == MeterKind.POWER) { renderGlow(be, pose, buffers); return; }
        FluidStack fluid = be.tank.getFluid();
        int capacity = be.tank.getCapacity();
        if (fluid.isEmpty() || capacity <= 0) return;
        float fill = Math.min(1f, fluid.getAmount() / (float) capacity);
        if (fill <= 0) return;

        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid.getFluid());
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ext.getStillTexture(fluid));
        int color = ext.getTintColor(fluid);
        if ((color >>> 24) == 0) color |= 0xFF000000;
        // Glowing fluids like lava light themselves.
        if (fluid.getFluid().getFluidType().getLightLevel(fluid) > 0) light = LightTexture.FULL_BRIGHT;

        float top = BOTTOM + (TOP - BOTTOM) * Math.max(fill, 0.02f);
        VertexConsumer vc = buffers.getBuffer(RenderType.translucent());
        Matrix4f m = pose.last().pose();
        var p = pose.last();

        // Top
        quad(vc, m, p, sprite, color, light, 0, 1, 0,
                MIN, top, MAX, MAX, top, MAX, MAX, top, MIN, MIN, top, MIN, MIN, MAX, MIN, MAX);
        // Bottom
        quad(vc, m, p, sprite, color, light, 0, -1, 0,
                MIN, BOTTOM, MAX, MAX, BOTTOM, MAX, MAX, BOTTOM, MIN, MIN, BOTTOM, MIN, MIN, MAX, MIN, MAX);
        // Sides: the texture is cut off at the fluid line, not squashed
        float v0 = 1f - (top - BOTTOM), v1 = 1f;
        quad(vc, m, p, sprite, color, light, 0, 0, -1, // north
                MAX, BOTTOM, MIN, MIN, BOTTOM, MIN, MIN, top, MIN, MAX, top, MIN, MIN, MAX, v1, v0);
        quad(vc, m, p, sprite, color, light, 0, 0, 1, // south
                MIN, BOTTOM, MAX, MAX, BOTTOM, MAX, MAX, top, MAX, MIN, top, MAX, MIN, MAX, v1, v0);
        quad(vc, m, p, sprite, color, light, -1, 0, 0, // west
                MIN, BOTTOM, MIN, MIN, BOTTOM, MAX, MIN, top, MAX, MIN, top, MIN, MIN, MAX, v1, v0);
        quad(vc, m, p, sprite, color, light, 1, 0, 0, // east
                MAX, BOTTOM, MAX, MAX, BOTTOM, MIN, MAX, top, MIN, MAX, top, MAX, MIN, MAX, v1, v0);
    }

    /**
     * The PC on top: what's stored with a fill bar, the network, whether it's buying or selling, and coins per hour.
     */
    private void renderScreen(MeterBlockEntity be, PoseStack pose, MultiBufferSource buffers) {
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(be.getBlockPos())) > 32 * 32) return;
        Direction facing = be.getBlockState().hasProperty(MeterBlock.FACING) ? be.getBlockState().getValue(MeterBlock.FACING) : Direction.NORTH;
        PcScreen.begin(pose, buffers, facing, 28);
        int w = PcScreen.W;

        // Header: what it meters, and buy/sell
        float hs = 0.8f, sm = 0.6f;
        boolean power = be.kind == MeterKind.POWER;
        String what = power ? "§ePOWER" : be.tank.isEmpty() ? "§bFLUID" : "§b" + be.tank.getFluid().getHoverName().getString().toUpperCase(java.util.Locale.ROOT);
        String role = switch (be.role) { case SUPPLIER -> "§aSELL"; case CONSUMER -> "§6BUY"; default -> "§8—"; };
        smallRight(pose, buffers, role, w - 5, 4.5f, hs);
        // Long fluid names shrink to fit beside the role instead of being cut off (very long ones are cut only below 35%)
        float nameRoom = (w - 10) - font.width(role) * hs - 5;
        float ws = Math.max(0.35f, Math.min(hs, nameRoom / Math.max(1, font.width(what))));
        String shown = PcScreen.fit(font, what, (int) (nameRoom / ws));
        PcScreen.small(font, pose, buffers, shown, 5, 4.5f + (hs - ws) * 4, ws);

        // Stock and its bar
        long stored = be.stored(), cap = be.capacity();
        int color = 0xFFE0C040;
        if (!power && !be.tank.isEmpty()) {
            color = IClientFluidTypeExtensions.of(be.tank.getFluid().getFluid()).getTintColor(be.tank.getFluid()) | 0xFF000000;
            if ((color & 0xFFFFFF) == 0xFFFFFF) color = 0xFF4890E0; // untinted fluids (lava, modded) get blue
        }
        PcScreen.bar(buffers, pose, 5, 13, w - 5, 15.5f, cap <= 0 ? 0 : stored / (float) cap, color);

        // Three lines: stock | coins held, the network, state | coins per hour
        int room = (int) ((w - 10) / sm);
        String amount = "§f" + PcScreen.shortCount(stored) + "§7/" + PcScreen.shortCount(cap) + " " + be.kind.unit;
        String held = com.admin82.factions.economy.Currency.format(be.screenCoins);
        PcScreen.small(font, pose, buffers, PcScreen.fit(font, amount, room - font.width(held) - 6), 5, 19, sm);
        smallRight(pose, buffers, held, w - 5, 19, sm);

        String net = be.screenNetwork.isEmpty() ? "§8No network" : "§7Net §f" + be.screenNetwork;
        PcScreen.small(font, pose, buffers, PcScreen.fit(font, net, room), 5, 26.5f, sm);

        String rate = be.screenRate == 0 ? "" : (be.screenRate > 0 ? "§a+" : "§c-") + coins(Math.abs(be.screenRate)) + "/h";
        PcScreen.small(font, pose, buffers, PcScreen.fit(font, be.screenState, room - font.width(rate) - 6), 5, 34, sm);
        smallRight(pose, buffers, rate, w - 5, 34, sm);
        pose.popPose();
    }

    /** Small text ending at rightX. */
    private void smallRight(PoseStack pose, MultiBufferSource buffers, String text, float rightX, float y, float scale) {
        if (text.isEmpty()) return;
        pose.pushPose();
        pose.translate(rightX, y, 0);
        pose.scale(scale, scale, 1);
        PcScreen.right(font, pose, buffers, text, 0, 0);
        pose.popPose();
    }

    /** Copper as its two biggest coins, without colours: 45c, 66s 12c, 3g 5s (see Currency) */
    private static String coins(long copper) {
        String[] parts = com.admin82.factions.economy.Currency.format(copper).replaceAll("§.", "").trim().split("\\s+");
        return parts.length <= 2 ? String.join(" ", parts) : parts[0] + " " + parts[1];
    }

    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox(MeterBlockEntity be) {
        return new net.minecraft.world.phys.AABB(be.getBlockPos()).expandTowards(0, 1, 0); // the PC is in the block above
    }

    /** Only the wire pixels of the core texture. */
    private static final ResourceLocation GLOW = ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "textures/block/meter/core_glow.png");

    /**
     * Glowing wires over the battery core (blocks 1-15 of the model), fully lit, brighter as it fills. Uses the core
     * faces' own UVs from meter.json so the glow lines up with the wires underneath.
     */
    private static void renderGlow(MeterBlockEntity be, PoseStack pose, MultiBufferSource buffers) {
        int cap = be.energy.getMaxEnergyStored();
        if (cap <= 0) return;
        float fill = Math.min(1f, be.energy.getEnergyStored() / (float) cap);
        if (fill < 0.01f) return;
        int c = (int) (255 * (0.2f + 0.8f * fill)); // a faint glow once there's any power, full at 100%
        int color = 0xFF000000 | c << 16 | c << 8 | c;

        Direction facing = be.getBlockState().hasProperty(MeterBlock.FACING) ? be.getBlockState().getValue(MeterBlock.FACING) : Direction.NORTH;
        float turn = switch (facing) { case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0; };
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-turn)); // same turn as the blockstate's "y"
        pose.translate(-0.5, 0, -0.5);
        VertexConsumer vc = buffers.getBuffer(RenderType.eyes(GLOW));
        Matrix4f m = pose.last().pose();
        var p = pose.last();
        float e = 0.002f, a = 1 / 16f - e, b = 15 / 16f + e, y0 = 0, y1 = 15 / 16f + e;
        int light = LightTexture.FULL_BRIGHT;
        // UVs (in 16ths) of the core faces in meter.json
        quad(vc, m, p, color, light, 0, 0, -1, b, y0, a, a, y0, a, a, y1, a, b, y1, a, 0, 3.5f, 3.75f, 0);       // north
        quad(vc, m, p, color, light, 0, 0, 1, a, y0, b, b, y0, b, b, y1, b, a, y1, b, 0, 3.5f, 7.75f, 4);        // south
        quad(vc, m, p, color, light, -1, 0, 0, a, y0, a, a, y0, b, a, y1, b, a, y1, a, 3.75f, 7.25f, 7.75f, 4);  // west
        quad(vc, m, p, color, light, 1, 0, 0, b, y0, b, b, y0, a, b, y1, a, b, y1, b, 3.75f, 7.25f, 3.75f, 0);   // east
        pose.popPose();
    }

    /** A face with UVs in 16ths of a whole (non-atlas) texture. */
    private static void quad(VertexConsumer vc, Matrix4f m, PoseStack.Pose p, int color, int light, float nx, float ny, float nz,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4,
                             float u0, float u1, float vBottom, float vTop) {
        vertex(vc, m, p, x1, y1, z1, u0 / 16f, vBottom / 16f, color, light, nx, ny, nz);
        vertex(vc, m, p, x2, y2, z2, u1 / 16f, vBottom / 16f, color, light, nx, ny, nz);
        vertex(vc, m, p, x3, y3, z3, u1 / 16f, vTop / 16f, color, light, nx, ny, nz);
        vertex(vc, m, p, x4, y4, z4, u0 / 16f, vTop / 16f, color, light, nx, ny, nz);
    }

    /**
     * One face, corners counter-clockwise from bottom-left as seen from outside. UVs are fractions of the sprite:
     * {@code u0..u1} across, {@code vBottom} at the first two corners and {@code vTop} at the last two.
     */
    private static void quad(VertexConsumer vc, Matrix4f m, PoseStack.Pose p, TextureAtlasSprite s, int color, int light,
                             float nx, float ny, float nz,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4,
                             float u0, float u1, float vBottom, float vTop) {
        vertex(vc, m, p, x1, y1, z1, s.getU(u0), s.getV(vBottom), color, light, nx, ny, nz);
        vertex(vc, m, p, x2, y2, z2, s.getU(u1), s.getV(vBottom), color, light, nx, ny, nz);
        vertex(vc, m, p, x3, y3, z3, s.getU(u1), s.getV(vTop), color, light, nx, ny, nz);
        vertex(vc, m, p, x4, y4, z4, s.getU(u0), s.getV(vTop), color, light, nx, ny, nz);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, PoseStack.Pose p, float x, float y, float z, float u, float v,
                               int color, int light, float nx, float ny, float nz) {
        vc.addVertex(m, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
    }
}
