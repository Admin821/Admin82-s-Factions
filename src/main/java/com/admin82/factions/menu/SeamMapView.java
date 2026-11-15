package com.admin82.factions.menu;

import com.admin82.factions.menu.factiontable.TerrainColors;
import com.admin82.factions.seam.SeamCell;
import com.admin82.factions.seam.SeamTier;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A seam map you drag around with the mouse (and, if zoomable, zoom with the scroll wheel). The terrain is drawn
 * underneath; chunks with a seam get a tint and their ore on top, depleted seams are greyed out completely, and
 * chunks with no seam are just the terrain. Chunks it has no data for are dimmed. When the view moves outside what it
 * has asked for, it asks for more ({@code request}).
 */
@OnlyIn(Dist.CLIENT)
public final class SeamMapView {

    /** Asks for the seams within {@code radius} chunks of a chunk. */
    public interface Request {
        void at(int x, int z, int radius);
    }

    private static final SeamCell NONE = new SeamCell(0, 0, "", SeamTier.DEPLETED.ordinal(), false, 0, 0, 0);

    private final Map<Long, SeamCell> cells = new HashMap<>();
    /** Sparse maps: areas the server has sent (chunks in them that aren't in {@link #cells} have no seam). */
    private final List<int[]> known = new ArrayList<>();
    private final Map<String, ItemStack> stacks = new HashMap<>();
    private final int px;
    private final Request request;
    private final int maxRadius;
    private final boolean sparse;
    private final boolean zoomable;
    private final String unknownText;
    @Nullable private final Consumer<SeamCell> onSelect;

    public String playerChunk = "";
    @Nullable public String selected;

    /** View centre in chunks (chunk c covers c..c+1) and pixels per chunk. */
    private double viewX, viewZ, ppc;
    private final double maxPpc;
    /** What the terrain picture was last drawn for (what's on screen). */
    private double dX, dZ, dPpc;
    @Nullable private int[] requested;

    private boolean dragging;
    private float dragDist, lastX, lastY;
    private int ticks;
    private final MapColorCache.Throttle redraw = new MapColorCache.Throttle();
    @Nullable private UIElement box;
    private int boxX, boxY;
    private float mouseX = -1, mouseY = -1;
    private List<Component> hoverLines = List.of();

    /**
     * @param size        chunks across at the starting zoom
     * @param maxRadius   furthest from the centre the request can load
     * @param sparse      the server only sends chunks that have a seam (inside an area it sent, the rest have none)
     * @param zoomable    scroll wheel zooms
     * @param unknownText tooltip of chunks it has no data for
     * @param onSelect    called when a chunk is clicked (not dragged), or null
     */
    public SeamMapView(int size, int cellPx, int centerX, int centerZ, Request request, int maxRadius, boolean sparse,
                       boolean zoomable, String unknownText, @Nullable Consumer<SeamCell> onSelect) {
        this.px = size * cellPx + (size - 1);
        this.ppc = cellPx + 1; // whole pixels per chunk (cell + 1 px grid line), so the grid stays even
        this.maxPpc = Math.max(ppc, 40);
        this.viewX = centerX + 0.5;
        this.viewZ = centerZ + 0.5;
        this.request = request;
        this.maxRadius = maxRadius;
        this.sparse = sparse;
        this.zoomable = zoomable;
        this.unknownText = unknownText;
        this.onSelect = onSelect;
        snapshot();
    }

    public int pixels() {
        return px;
    }

    /** Adds (or replaces) chunks, all of them listed (with or without a seam), and redraws. */
    public void addCells(Collection<SeamCell> list) {
        for (SeamCell c : list) cells.put(c.key(), c);
        paint();
    }

    /** Sparse maps: an area the server sent; {@code list} is every seam in it. */
    public void addArea(int x, int z, int radius, Collection<SeamCell> list) {
        known.add(new int[]{x - radius, z - radius, x + radius, z + radius});
        if (known.size() > 32) known.remove(0);
        // Seams it no longer lists (deleted) go
        cells.values().removeIf(c -> Math.abs(c.chunkX() - x) <= radius && Math.abs(c.chunkZ() - z) <= radius);
        for (SeamCell c : list) cells.put(c.key(), c);
        paint();
    }

    /** The chunk's seam (a no-seam cell for known empty chunks), or null when there's no data for it. */
    @Nullable
    public SeamCell get(int x, int z) {
        SeamCell c = cells.get(ChunkPos.asLong(x, z));
        if (c != null) return c;
        return sparse && isKnown(x, z) ? new SeamCell(x, z, "", SeamTier.DEPLETED.ordinal(), false, 0, 0, 0) : null;
    }

    /** Like {@link #get} without making a cell (for drawing). */
    @Nullable
    private SeamCell state(int x, int z) {
        SeamCell c = cells.get(ChunkPos.asLong(x, z));
        if (c != null) return c;
        return sparse && isKnown(x, z) ? NONE : null;
    }

    private boolean isKnown(int x, int z) {
        for (int[] r : known) if (x >= r[0] && x <= r[2] && z >= r[1] && z <= r[3]) return true;
        return false;
    }

    /** Moves the map to a chunk and asks for what's there. */
    public void moveTo(int x, int z) {
        viewX = x + 0.5;
        viewZ = z + 0.5;
        requested = null;
        fill();
    }

    /** "1 chunk = 20 px" style zoom note, or "" when not zoomable. */
    public String zoomText() {
        if (!zoomable) return "";
        double across = px / ppc;
        return across < 1000 ? Math.round(across) + " chunks across" : String.format("%,d chunks across", Math.round(across));
    }

    /** A new element showing the map (call again whenever the screen is rebuilt; the state lives here). */
    public UIElement element() {
        box = new UIElement();
        box.layout(l -> l.width(px).height(px));
        box.style(s -> s.background(new Painter()));
        if (requested == null) maybeRequest();

        box.addEventListener(UIEvents.MOUSE_DOWN, ev -> {
            if (ev.button != 0) return;
            dragging = true;
            dragDist = 0;
            lastX = ev.x;
            lastY = ev.y;
        });
        box.addEventListener(UIEvents.MOUSE_UP, ev -> dragging = false);
        box.addEventListener(UIEvents.MOUSE_MOVE, ev -> {
            if (!dragging) return;
            float dx = lastX - ev.x, dz = lastY - ev.y;
            lastX = ev.x;
            lastY = ev.y;
            dragDist += Math.abs(dx) + Math.abs(dz);
            viewX += dx / ppc;
            viewZ += dz / ppc;
            redraw.request(this::fill);
        });
        if (zoomable) {
            box.addEventListener(UIEvents.MOUSE_WHEEL, ev -> {
                if (ev.deltaY == 0) return;
                double old = ppc;
                ppc = ev.deltaY > 0 ? Math.min(maxPpc, ppc * 1.25) : Math.max(1e-4, ppc / 1.25);
                if (ppc >= 4) {
                    // Close up, whole pixels per chunk keep every grid line one pixel wide
                    long whole = Math.round(ppc);
                    if (whole == Math.round(old)) whole += ev.deltaY > 0 ? 1 : -1;
                    ppc = Math.max(1, Math.min(maxPpc, whole));
                }
                if (ppc == old) return;
                // Keep the chunk under the mouse where it is
                if (mouseX >= boxX && mouseY >= boxY && mouseX < boxX + px && mouseY < boxY + px) {
                    double mx = mouseX - boxX - px / 2.0, mz = mouseY - boxY - px / 2.0;
                    viewX += mx / old - mx / ppc;
                    viewZ += mz / old - mz / ppc;
                }
                redraw.request(this::fill);
            });
        }
        UiKit.onClick(box, ev -> {
            if (dragDist > 4 || onSelect == null) return; // that was a drag, not a click
            int[] c = chunkAtMouse();
            if (c == null) return;
            SeamCell cell = get(c[0], c[1]);
            if (cell == null) return;
            selected = c[0] + "," + c[1];
            onSelect.accept(cell);
        });
        box.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> {
            if (!hoverLines.isEmpty()) ev.hoverTooltips = HoverTooltips.empty().append(hoverLines.toArray(Component[]::new));
        });
        box.addEventListener(UIEvents.TICK, ev -> {
            ticks++;
            redraw.flush(this::fill);
            if (dragging && GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                dragging = false;
            }
            if (ticks % 40 == 0 && !dragging) paint(); // catch chunks that loaded since
        });
        return box;
    }

    private void snapshot() {
        dPpc = ppc;
        // Line chunk edges up with whole pixels (no wobbling or doubled grid lines while dragging)
        dX = dPpc >= 1 ? Math.round(viewX * dPpc) / dPpc : viewX;
        dZ = dPpc >= 1 ? Math.round(viewZ * dPpc) / dPpc : viewZ;
    }

    /** Redraws for the current view and asks for data if it moved outside what it has. */
    private void fill() {
        snapshot();
        paint();
        maybeRequest();
    }

    private void maybeRequest() {
        double half = px / 2.0 / ppc;
        int cx = (int) Math.floor(viewX), cz = (int) Math.floor(viewZ);
        int need = (int) Math.min(Math.ceil(half) + 1, maxRadius);
        if (requested != null && cx - need >= requested[0] && cz - need >= requested[1]
                && cx + need <= requested[2] && cz + need <= requested[3]) return;
        int r = (int) Math.min(maxRadius, Math.max(Math.ceil(half * 1.5) + 2, need));
        requested = new int[]{cx - r, cz - r, cx + r, cz + r};
        request.at(cx, cz, r);
    }

    @Nullable
    private int[] chunkAtMouse() {
        if (mouseX < boxX || mouseY < boxY || mouseX >= boxX + px || mouseY >= boxY + px) return null;
        return new int[]{(int) Math.floor(dX + (mouseX - boxX - px / 2.0) / dPpc),
                (int) Math.floor(dZ + (mouseY - boxY - px / 2.0) / dPpc)};
    }

    // Drawing

    private static final int UNLOADED = 0x262626;
    private static final int GRID = 0x101010;

    /** One terrain image per map size, shared by every map of that size (only one is open at a time). */
    private static final Map<Integer, Terrain> TERRAIN = new HashMap<>();

    private record Terrain(NativeImage image, DynamicTexture texture, ResourceLocation id) {}

    private Terrain terrain() {
        return TERRAIN.computeIfAbsent(px, size -> {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation id = Minecraft.getInstance().getTextureManager().register("adminsfactions_seammap", texture);
            return new Terrain(image, texture, id);
        });
    }

    /** Close enough that each chunk is drawn on its own (tints, ores, outlines) every frame. */
    private boolean near() {
        return dPpc >= 4;
    }

    /**
     * Draws the terrain (loaded chunks only) into the image. Zoomed out, the seams are drawn into it too, as dots,
     * and chunks without data are darkened.
     */
    private void paint() {
        Terrain t = terrain();
        Level level = Minecraft.getInstance().level;
        boolean grid = dPpc >= 6, far = !near();
        int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        boolean loaded = false, unknown = false;
        for (int y = 0; y < px; y++) {
            double wz = dZ + (y + 0.5 - px / 2.0) / dPpc;
            int cz = (int) Math.floor(wz);
            for (int x = 0; x < px; x++) {
                double wx = dX + (x + 0.5 - px / 2.0) / dPpc;
                int cx = (int) Math.floor(wx);
                if (cx != lastX || cz != lastZ) {
                    lastX = cx;
                    lastZ = cz;
                    loaded = level != null && level.hasChunk(cx, cz);
                    unknown = far && state(cx, cz) == null;
                }
                int rgb = loaded ? TerrainColors.CACHE.color(level, (int) Math.floor(wx * 16), (int) Math.floor(wz * 16)) : UNLOADED;
                if (grid && ((wx - cx) * dPpc < 1 || (wz - cz) * dPpc < 1)) rgb = GRID;
                else if (unknown) rgb = darken(rgb, 0.35f);
                t.image.setPixelRGBA(x, y, abgr(rgb));
            }
        }
        if (far) {
            // Seams as dots at least 2 px across
            double half = px / 2.0 / dPpc;
            int dot = (int) Math.max(2, Math.round(dPpc));
            for (SeamCell c : cells.values()) {
                if (!c.hasSeam() || Math.abs(c.chunkX() + 0.5 - dX) > half + 1 || Math.abs(c.chunkZ() + 0.5 - dZ) > half + 1) continue;
                int sx = (int) Math.floor((c.chunkX() + 0.5 - dX) * dPpc + px / 2.0) - dot / 2;
                int sy = (int) Math.floor((c.chunkZ() + 0.5 - dZ) * dPpc + px / 2.0) - dot / 2;
                int rgb = c.minable() ? SeamMap.color(c) & 0xFFFFFF : 0x6A6A6A;
                for (int y = Math.max(0, sy); y < Math.min(px, sy + dot); y++) {
                    for (int x = Math.max(0, sx); x < Math.min(px, sx + dot); x++) t.image.setPixelRGBA(x, y, abgr(rgb));
                }
            }
        }
        t.texture.upload();
    }

    private static int darken(int rgb, float f) {
        return ((int) (((rgb >> 16) & 0xFF) * f) << 16) | ((int) (((rgb >> 8) & 0xFF) * f) << 8) | (int) ((rgb & 0xFF) * f);
    }

    private static int abgr(int rgb) {
        return 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }

    private ItemStack stack(String ore) {
        return stacks.computeIfAbsent(ore, SeamMap::ore);
    }

    /** The tooltip of the chunk under the mouse. */
    private List<Component> tooltipAt(int x, int z) {
        boolean here = (x + "," + z).equals(playerChunk);
        SeamCell c = get(x, z);
        if (c == null) {
            return List.of(Component.literal("§7Chunk " + x + ", " + z + (here ? " §e(you are here)" : "")),
                    Component.literal("§8" + unknownText));
        }
        List<Component> lines = SeamMap.tooltip(c, here);
        if (onSelect != null) lines.add(Component.literal("§8Click to select"));
        return lines;
    }

    /** Draws the terrain picture, then (close up) each chunk's seam, then the outlines. */
    private final class Painter extends ColorRectTexture {
        Painter() {
            super(0);
        }

        @Override
        protected void drawInternal(GuiGraphics gfx, float mx, float my, float x, float y,
                                    float width, float height, float partialTick) {
            int bx = (int) x, by = (int) y;
            boxX = bx;
            boxY = by;
            mouseX = mx;
            mouseY = my;
            gfx.blit(terrain().id, bx, by, 0f, 0f, px, px, px, px);
            gfx.enableScissor(bx, by, bx + px, by + px);
            if (near()) drawCells(gfx, bx, by);

            // Outlines: you (white), selected (orange)
            gfx.pose().pushPose();
            gfx.pose().translate(0, 0, 310);
            outline(gfx, bx, by, playerChunk, 0xFFFFFFFF);
            outline(gfx, bx, by, selected, 0xFFFF9A1A);
            if (zoomable && px / dPpc / 2 > maxRadius) {
                var font = Minecraft.getInstance().font;
                String s = "Zoom in to load seams further out";
                gfx.fill(bx, by + px - 11, bx + px, by + px, 0xA0000000);
                gfx.drawString(font, s, bx + (px - font.width(s)) / 2, by + px - 10, 0xFFAAAAAA, false);
            }
            gfx.pose().popPose();
            gfx.disableScissor();

            // Tooltip of the chunk under the mouse
            int[] c = chunkAtMouse();
            hoverLines = c == null ? List.of() : tooltipAt(c[0], c[1]);
            if (box != null && box.getModularUI() != null && !hoverLines.isEmpty()) {
                box.getModularUI().setHoverTooltip(hoverLines, ItemStack.EMPTY, Minecraft.getInstance().font, null);
            }
        }

        private void drawCells(GuiGraphics gfx, int bx, int by) {
            double half = px / 2.0 / dPpc;
            int minX = (int) Math.floor(dX - half), maxX = (int) Math.floor(dX + half);
            int minZ = (int) Math.floor(dZ - half), maxZ = (int) Math.floor(dZ + half);
            int inset = dPpc >= 6 ? 1 : 0;
            int size = (int) Math.ceil(dPpc) - inset;
            float scale = (float) Math.min(1, (dPpc - 3) / 16.0);
            for (int cz = minZ; cz <= maxZ; cz++) {
                for (int cx = minX; cx <= maxX; cx++) {
                    int ix = (int) Math.floor(bx + (cx - dX) * dPpc + px / 2.0 + 1e-6) + inset;
                    int iy = (int) Math.floor(by + (cz - dZ) * dPpc + px / 2.0 + 1e-6) + inset;
                    SeamCell c = state(cx, cz);
                    if (c == null) {
                        gfx.fill(ix, iy, ix + size, iy + size, 0xA0000000); // no data: dimmed
                        continue;
                    }
                    if (!c.hasSeam()) continue; // just the terrain
                    int tier = SeamMap.color(c) & 0xFFFFFF;
                    if (c.minable()) {
                        gfx.fill(ix, iy, ix + size, iy + size, 0x70000000 | tier);
                        if (dPpc >= 10) gfx.renderOutline(ix, iy, size, size, 0xFF000000 | tier);
                    } else {
                        gfx.fill(ix, iy, ix + size, iy + size, 0xFF2C2C2C); // depleted: no terrain
                    }
                    if (dPpc >= 8) {
                        float icon = 16 * scale;
                        gfx.pose().pushPose();
                        gfx.pose().translate(ix + (size - icon) / 2f, iy + (size - icon) / 2f, 0);
                        gfx.pose().scale(scale, scale, 1);
                        gfx.renderItem(stack(c.ore()), 0, 0);
                        gfx.pose().popPose();
                    }
                    if (!c.minable()) {
                        gfx.pose().pushPose();
                        gfx.pose().translate(0, 0, 300);
                        gfx.fill(ix, iy, ix + size, iy + size, 0xB0383838); // grey the ore right out
                        gfx.pose().popPose();
                    }
                }
            }
        }

        private void outline(GuiGraphics gfx, int bx, int by, @Nullable String chunk, int color) {
            if (chunk == null || chunk.isEmpty()) return;
            String[] p = chunk.split(",");
            int cx, cz;
            try {
                cx = Integer.parseInt(p[0]);
                cz = Integer.parseInt(p[1]);
            } catch (RuntimeException e) {
                return;
            }
            int size = (int) Math.max(4, Math.ceil(dPpc));
            int ix = (int) Math.floor(bx + (cx - dX) * dPpc + px / 2.0 + 1e-6) + ((int) Math.ceil(dPpc) - size) / 2;
            int iy = (int) Math.floor(by + (cz - dZ) * dPpc + px / 2.0 + 1e-6) + ((int) Math.ceil(dPpc) - size) / 2;
            gfx.renderOutline(ix, iy, size, size, color);
            if (size >= 8) gfx.renderOutline(ix + 1, iy + 1, size - 2, size - 2, color);
        }
    }
}
