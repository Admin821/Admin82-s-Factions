package com.admin82.factions.map;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

// Terrain map texture backing NativeImage

/**
 * Thin wrapper around a Minecraft {@link net.minecraft.client.renderer.texture.DynamicTexture}
 * that implements LDLib2's {@link IGuiTexture} interface by extending {@link
 * com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture}.
 * Callers write pixels with {@link #setPixel} then call {@link #upload} once per frame.
 */
@OnlyIn(Dist.CLIENT)
public final class TerrainMapTexture
        extends ColorRectTexture {

    private final com.mojang.blaze3d.platform.NativeImage image;
    private final DynamicTexture dynTex;
    private final ResourceLocation rl;
    private final int size;

    public TerrainMapTexture(int size) {
        super(0x00000000); // transparent fill, parent's drawInternal is overridden below
        this.size  = size;
        this.image = new com.mojang.blaze3d.platform.NativeImage(
                com.mojang.blaze3d.platform.NativeImage.Format.RGBA, size, size, false);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                image.setPixelRGBA(x, y, abgr(0x404040)); // default: unloaded grey
        this.dynTex = new DynamicTexture(image);
        this.rl = Minecraft.getInstance()
                .getTextureManager().register("adminsfactions_terrainmap", dynTex);
    }

    /** Write one pixel; {@code rgb} is 24-bit 0x00RRGGBB. */
    public void setPixel(int x, int y, int rgb) {
        image.setPixelRGBA(x, y, abgr(rgb));
    }

    /** Reset every pixel to the unloaded-chunk grey. */
    public void clear() {
        int grey = abgr(0x404040);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                image.setPixelRGBA(x, y, grey);
    }

    /** Darken a pixel by ~50% to render a grid line without losing terrain colour. */
    private void darkenPixel(int x, int y) {
        int abgr = image.getPixelRGBA(x, y);
        int r  =  abgr        & 0xFF;
        int g  = (abgr >>  8) & 0xFF;
        int b  = (abgr >> 16) & 0xFF;
        image.setPixelRGBA(x, y,
                (0xFF << 24) | ((b / 2) << 16) | ((g / 2) << 8) | (r / 2));
    }

    /** Draw a horizontal grid line at row {@code y}, columns [{@code x1}, {@code x2}]. */
    public void drawHLine(int y, int x1, int x2) {
        if (y < 0 || y >= size) return;
        for (int x = Math.max(0, x1); x <= Math.min(size - 1, x2); x++)
            darkenPixel(x, y);
    }

    /** Draw a vertical grid line at column {@code x}, rows [{@code y1}, {@code y2}]. */
    public void drawVLine(int x, int y1, int y2) {
        if (x < 0 || x >= size) return;
        for (int y = Math.max(0, y1); y <= Math.min(size - 1, y2); y++)
            darkenPixel(x, y);
    }

    /** Draw a horizontal line of solid colour (0x00RRGGBB). */
    private void fillHLine(int y, int x1, int x2, int rgb) {
        if (y < 0 || y >= size) return;
        int packed = abgr(rgb);
        for (int x = Math.max(0, x1); x <= Math.min(size - 1, x2); x++)
            image.setPixelRGBA(x, y, packed);
    }

    /** Draw a vertical line of solid colour (0x00RRGGBB). */
    private void fillVLine(int x, int y1, int y2, int rgb) {
        if (x < 0 || x >= size) return;
        int packed = abgr(rgb);
        for (int y = Math.max(0, y1); y <= Math.min(size - 1, y2); y++)
            image.setPixelRGBA(x, y, packed);
    }

    /** Paint a solid-colour inset border around the chunk tile at pixel (ix, iy),
     *  spanning fcs pixels square, with border thickness bpx pixels. */
    public void drawChunkBorder(int ix, int iy, int fcs, int rgb, int bpx) {
        for (int b = 0; b < bpx; b++) {
            fillHLine(iy + b,           ix, ix + fcs - 1, rgb); // top
            fillHLine(iy + fcs - 1 - b, ix, ix + fcs - 1, rgb); // bottom
            fillVLine(ix + b,           iy, iy + fcs - 1, rgb); // left
            fillVLine(ix + fcs - 1 - b, iy, iy + fcs - 1, rgb); // right
        }
    }

    /** Push all buffered pixels to the GPU.  Call once after all {@link #setPixel} calls. */
    public void upload() { dynTex.upload(); }

    /** Converts 0x00RRGGBB → the ABGR int expected by {@link com.mojang.blaze3d.platform.NativeImage#setPixelRGBA}. */
    private static int abgr(int rgb) {
        return (0xFF                << 24)  // alpha = opaque
             | ((rgb        & 0xFF) << 16)  // B → bits 16-23
             | (((rgb >> 8) & 0xFF) <<  8)  // G → bits  8-15
             | ( (rgb >> 16) & 0xFF       ); // R → bits  0-7
    }

    @Override
    protected void drawInternal(GuiGraphics gfx,
            float mouseX, float mouseY, float screenX, float screenY,
            float elemW, float elemH, float partialTick) {
        // LDLib2 passes: (gfx, localMouseX, localMouseY, screenX, screenY, elemW, elemH, partialTick)
        // blit(rl, destX, destY, uOffset, vOffset, blitW, blitH, texW, texH)
        gfx.blit(rl, (int) screenX, (int) screenY, 0f, 0f, (int) elemW, (int) elemH, size, size);
    }
}
