package com.admin82.factions.menu.monument;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class MonumentTerrainTexture
        extends ColorRectTexture {
    private final NativeImage image;
    private final DynamicTexture texture;
    private final ResourceLocation location;
    private final int size;

    MonumentTerrainTexture(int size) {
        super(0x00000000);
        this.size = size;
        image = new NativeImage(
                NativeImage.Format.RGBA, size, size, false);
        texture = new DynamicTexture(image);
        location = Minecraft.getInstance().getTextureManager().register("adminsfactions_monument_terrain", texture);
        clear();
    }

    void setPixel(int x, int y, int color) {
        if (x >= 0 && x < size && y >= 0 && y < size) image.setPixelRGBA(x, y, abgr(color));
    }

    void clear() {
        int gray = abgr(0x404040);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) image.setPixelRGBA(x, y, gray);
        }
    }

    void drawHLine(int y, int startX, int endX) {
        if (y < 0 || y >= size) return;
        for (int x = Math.max(0, startX); x <= Math.min(size - 1, endX); x++) darken(x, y);
    }

    void drawVLine(int x, int startY, int endY) {
        if (x < 0 || x >= size) return;
        for (int y = Math.max(0, startY); y <= Math.min(size - 1, endY); y++) darken(x, y);
    }

    private void darken(int x, int y) {
        int value = image.getPixelRGBA(x, y);
        int red = value & 0xFF;
        int green = value >> 8 & 0xFF;
        int blue = value >> 16 & 0xFF;
        image.setPixelRGBA(x, y, 0xFF000000 | blue / 2 << 16 | green / 2 << 8 | red / 2);
    }

    void drawChunkBorder(int x, int y, int cellSize, int color, int thickness) {
        if (cellSize <= 0) return;
        int packed = abgr(color);
        for (int border = 0; border < thickness; border++) {
            int left = x + border;
            int right = x + cellSize - 1 - border;
            int top = y + border;
            int bottom = y + cellSize - 1 - border;
            for (int pixelX = left; pixelX <= right; pixelX++) {
                if (pixelX >= 0 && pixelX < size && top >= 0 && top < size) image.setPixelRGBA(pixelX, top, packed);
                if (pixelX >= 0 && pixelX < size && bottom >= 0 && bottom < size) image.setPixelRGBA(pixelX, bottom, packed);
            }
            for (int pixelY = top; pixelY <= bottom; pixelY++) {
                if (left >= 0 && left < size && pixelY >= 0 && pixelY < size) image.setPixelRGBA(left, pixelY, packed);
                if (right >= 0 && right < size && pixelY >= 0 && pixelY < size) image.setPixelRGBA(right, pixelY, packed);
            }
        }
    }

    void upload() { texture.upload(); }

    private static int abgr(int color) {
        return 0xFF000000 | (color & 0xFF) << 16 | (color >> 8 & 0xFF) << 8 | color >> 16 & 0xFF;
    }

    @Override
    protected void drawInternal(GuiGraphics graphics,
                                float mouseX, float mouseY, float screenX, float screenY,
                                float width, float height, float partialTick) {
        graphics.blit(location, (int) screenX, (int) screenY, 0f, 0f,
                (int) width, (int) height, size, size);
    }
}
