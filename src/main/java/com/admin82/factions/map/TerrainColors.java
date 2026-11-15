package com.admin82.factions.map;

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

/** Works out map colours from the blocks in the world. */
public final class TerrainColors {

    private TerrainColors() {}

    // Terrain colour helpers

    /**
     * Returns a per-block terrain colour matching Xaero's/JourneyMap quality:
     *  • Samples the actual top-face texture from the baked model so oak logs
     *    look brown, stone looks speckled grey, sand is pale yellow, etc.
     *  • Multiplies biome tint onto the texture so grass/leaves stay correct.
     *  • Adds a tiny positional brightness jitter (±4%) so flat areas look
     *    subtly textured rather than a solid wash of colour.
     *  • Uses MOTION_BLOCKING (includes leaves) for hill shading so tree crowns
     *    become raised bumps with visible bright/dark edge rings.
     *  • 12% brightness per block of height difference → ±60% for a 5-block
     *    crown edge, creating clearly visible individual trees.
     */
    @OnlyIn(Dist.CLIENT)
    /** Shared cache of {@link #computeBlockColor} for the territory and war maps. */
    public static final MapColorCache CACHE =
            new MapColorCache(TerrainColors::computeBlockColor);

    static int computeBlockColor(Level level, int wx, int wz) {
        try {
            // Water
            int wyFloor   = level.getHeight(Heightmap.Types.OCEAN_FLOOR,   wx, wz) - 1;
            int wySurface = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
            if (wyFloor   < level.getMinBuildHeight()) wyFloor   = level.getMinBuildHeight();
            if (wySurface < level.getMinBuildHeight()) wySurface = level.getMinBuildHeight();

            if (wySurface > wyFloor) {
                var surfPos = new BlockPos(wx, wySurface, wz);
                if (!level.getFluidState(surfPos).isEmpty()) {
                    int base;
                    try {
                        base = BiomeColors
                                .getAverageWaterColor(level, surfPos);
                    } catch (Exception e) { base = 0x3F76E4; }
                    float f = Math.max(0.20f, 1.0f - (wySurface - wyFloor) * 0.05f);
                    return ((int)(((base >> 16) & 0xFF) * f) << 16)
                         | ((int)(((base >>  8) & 0xFF) * f) << 8)
                         |  (int)(( base        & 0xFF) * f);
                }
            }

            // Land
            int wyTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz) - 1;
            if (wyTop < level.getMinBuildHeight()) wyTop = level.getMinBuildHeight();

            var pos   = new BlockPos(wx, wyTop, wz);
            var state = level.getBlockState(pos);

            // Texture colour: baked top-face sprite → biome tint multiplied on top
            int col = getBlockRenderColor(state, level, pos);
            col = saturateColor(col, 1.35f);

            // Tiny positional jitter breaks up uniform surfaces (±4%)
            long h  = wx * 374761393L ^ wz * 668265263L;
            float jitter = 1.0f + (((h >> 16) & 0xFF) - 128) / 3200.0f;

            // Proportional hill shading
            int wyN = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx,     wz - 1) - 1;
            int wyE = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx + 1, wz    ) - 1;
            if (wyN <= level.getMinBuildHeight() || wyN > level.getMaxBuildHeight()) wyN = wyTop;
            if (wyE <= level.getMinBuildHeight() || wyE > level.getMaxBuildHeight()) wyE = wyTop;

            float hill  = 1.0f + (wyTop - wyN) * 0.12f + (wyTop - wyE) * 0.06f;
            hill = Math.max(0.40f, Math.min(1.60f, hill));
            float alt   = Math.max(0.70f, Math.min(1.25f, 1.0f + (wyTop - 64) * 0.004f));
            float total = hill * alt * jitter;

            int r = Math.min(255, Math.max(0, (int)(((col >> 16) & 0xFF) * total)));
            int g = Math.min(255, Math.max(0, (int)(((col >>  8) & 0xFF) * total)));
            int b = Math.min(255, Math.max(0, (int)(( col        & 0xFF) * total)));
            return (r << 16) | (g << 8) | b;

        } catch (Exception e) {
            return 0x404040;
        }
    }

    /**
     * Samples the top-face baked-model sprite for {@code state}, averages the
     * non-transparent pixel colours, then multiplies by the biome tint if the
     * block has one.  Falls back to biome tint alone, then MapColor.
     */
    @OnlyIn(Dist.CLIENT)
    private static int getBlockRenderColor(BlockState state,
                                           Level level, BlockPos pos) {
        try {
            var mc    = Minecraft.getInstance();
            var model = mc.getBlockRenderer().getBlockModel(state);
            var quads = model.getQuads(state, Direction.UP,
                    RandomSource.create(42L));
            if (!quads.isEmpty()) {
                var contents = quads.get(0).getSprite().contents();
                int w = contents.width(), h = contents.height();
                long rS = 0, gS = 0, bS = 0; int n = 0;
                for (int sy = 0; sy < h; sy += 2) {
                    for (int sx = 0; sx < w; sx += 2) {
                        // NativeImage: pixels stored RGBA, R in LSB
                        int px = contents.getOriginalImage().getPixelRGBA(sx, sy);
                        if (((px >> 24) & 0xFF) < 64) continue;
                        rS += px & 0xFF; gS += (px >> 8) & 0xFF; bS += (px >> 16) & 0xFF; n++;
                    }
                }
                if (n > 0) {
                    int tex = (int)(rS/n) << 16 | (int)(gS/n) << 8 | (int)(bS/n);
                    int tint = mc.getBlockColors().getColor(state, level, pos, 0);
                    if (tint != -1) {
                        tex = ((tex >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255) << 16
                            | ((tex >>  8 & 0xFF) * (tint >>  8 & 0xFF) / 255) << 8
                            |  (tex       & 0xFF) * (tint       & 0xFF) / 255;
                    }
                    return tex;
                }
            }
        } catch (Exception ignored) {}
        try {
            int t = Minecraft.getInstance()
                    .getBlockColors().getColor(state, level, pos, 0);
            if (t != -1) return t;
        } catch (Exception ignored) {}
        int c = state.getMapColor(level, pos).col;
        return (c == 0 || c == -1) ? 0x707070 : c;
    }

    /**
     * Boosts the saturation of an RGB colour by {@code factor}
     * (1.0 = no change, 2.0 = twice as saturated, 0.0 = greyscale).
     * Stone stays grey; grass becomes vivid green; water stays blue.
     */
    private static int saturateColor(int rgb, float factor) {
        if (rgb == 0) return rgb;
        float r   = ((rgb >> 16) & 0xFF) / 255f;
        float g   = ((rgb >>  8) & 0xFF) / 255f;
        float b   = ( rgb        & 0xFF) / 255f;
        float lum = 0.299f * r + 0.587f * g + 0.114f * b;
        int r2 = Math.min(255, Math.max(0, (int)((lum + (r - lum) * factor) * 255)));
        int g2 = Math.min(255, Math.max(0, (int)((lum + (g - lum) * factor) * 255)));
        int b2 = Math.min(255, Math.max(0, (int)((lum + (b - lum) * factor) * 255)));
        return (r2 << 16) | (g2 << 8) | b2;
    }
}
