package com.admin82.factions.menu.monument;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Map colours for the chunk map, worked out from the blocks in the world. */
final class MonumentMapColors {

    private MonumentMapColors() {}

    @OnlyIn(Dist.CLIENT)
    static final com.admin82.factions.menu.MapColorCache CACHE =
            new com.admin82.factions.menu.MapColorCache(MonumentMapColors::computeBlockColor);

    static int computeBlockColor(Level level, int worldX, int worldZ) {
        try {
            int floorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, worldX, worldZ) - 1;
            int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
            floorY = Math.max(floorY, level.getMinBuildHeight());
            surfaceY = Math.max(surfaceY, level.getMinBuildHeight());
            if (surfaceY > floorY) {
                BlockPos surfacePos = new BlockPos(worldX, surfaceY, worldZ);
                if (!level.getFluidState(surfacePos).isEmpty()) {
                    int base;
                    try {
                        base = BiomeColors.getAverageWaterColor(level, surfacePos);
                    } catch (Exception ignored) {
                        base = 0x3F76E4;
                    }
                    float depth = Math.max(0.20f, 1.0f - (surfaceY - floorY) * 0.05f);
                    return ((int) (((base >> 16) & 0xFF) * depth) << 16)
                            | ((int) (((base >> 8) & 0xFF) * depth) << 8)
                            | (int) ((base & 0xFF) * depth);
                }
            }

            int topY = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ) - 1,
                    level.getMinBuildHeight());
            BlockPos pos = new BlockPos(worldX, topY, worldZ);
            int color = saturateColor(getBlockRenderColor(level.getBlockState(pos), level, pos), 1.35f);
            long hash = worldX * 374761393L ^ worldZ * 668265263L;
            float jitter = 1.0f + (((hash >> 16) & 0xFF) - 128) / 3200.0f;
            int northY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ - 1) - 1;
            int eastY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX + 1, worldZ) - 1;
            if (northY <= level.getMinBuildHeight() || northY > level.getMaxBuildHeight()) northY = topY;
            if (eastY <= level.getMinBuildHeight() || eastY > level.getMaxBuildHeight()) eastY = topY;
            float hill = Math.clamp(1.0f + (topY - northY) * 0.12f + (topY - eastY) * 0.06f, 0.40f, 1.60f);
            float altitude = Math.clamp(1.0f + (topY - 64) * 0.004f, 0.70f, 1.25f);
            float brightness = hill * altitude * jitter;
            int red = Math.clamp((int) (((color >> 16) & 0xFF) * brightness), 0, 255);
            int green = Math.clamp((int) (((color >> 8) & 0xFF) * brightness), 0, 255);
            int blue = Math.clamp((int) ((color & 0xFF) * brightness), 0, 255);
            return red << 16 | green << 8 | blue;
        } catch (Exception ignored) {
            return 0x404040;
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static int getBlockRenderColor(BlockState state,
                                           Level level, BlockPos pos) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            var quads = minecraft.getBlockRenderer().getBlockModel(state)
                    .getQuads(state, Direction.UP, RandomSource.create(42L));
            if (!quads.isEmpty()) {
                var contents = quads.getFirst().getSprite().contents();
                long red = 0, green = 0, blue = 0;
                int samples = 0;
                for (int y = 0; y < contents.height(); y += 2) {
                    for (int x = 0; x < contents.width(); x += 2) {
                        int pixel = contents.getOriginalImage().getPixelRGBA(x, y);
                        if (((pixel >> 24) & 0xFF) < 64) continue;
                        red += pixel & 0xFF;
                        green += pixel >> 8 & 0xFF;
                        blue += pixel >> 16 & 0xFF;
                        samples++;
                    }
                }
                if (samples > 0) {
                    int texture = (int) (red / samples) << 16 | (int) (green / samples) << 8 | (int) (blue / samples);
                    int tint = minecraft.getBlockColors().getColor(state, level, pos, 0);
                    if (tint != -1) {
                        texture = ((texture >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255) << 16
                                | ((texture >> 8 & 0xFF) * (tint >> 8 & 0xFF) / 255) << 8
                                | (texture & 0xFF) * (tint & 0xFF) / 255;
                    }
                    return texture;
                }
            }
        } catch (Exception ignored) {
        }
        int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        if (tint != -1) return tint;
        int mapColor = state.getMapColor(level, pos).col;
        return mapColor == 0 || mapColor == -1 ? 0x707070 : mapColor;
    }

    private static int saturateColor(int color, float factor) {
        float red = (color >> 16 & 0xFF) / 255f;
        float green = (color >> 8 & 0xFF) / 255f;
        float blue = (color & 0xFF) / 255f;
        float luminance = 0.299f * red + 0.587f * green + 0.114f * blue;
        int saturatedRed = Math.clamp((int) ((luminance + (red - luminance) * factor) * 255), 0, 255);
        int saturatedGreen = Math.clamp((int) ((luminance + (green - luminance) * factor) * 255), 0, 255);
        int saturatedBlue = Math.clamp((int) ((luminance + (blue - luminance) * factor) * 255), 0, 255);
        return saturatedRed << 16 | saturatedGreen << 8 | saturatedBlue;
    }
}
