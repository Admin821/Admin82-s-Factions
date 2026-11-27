package com.admin82.factions.map;

import com.admin82.factions.menu.UiKit;
import com.admin82.factions.screen.MapScreen;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class MapMenu extends AbstractContainerMenu implements IModularUIHolderMenu {

    private static final int TILE_SIZE = 512;
    private static final int TILE_CHUNKS = TILE_SIZE / 16;
    private static final int MAX_TILES = 64;
    private static final long TILE_CACHE_TTL = 200;

    private final Map<Long, Tile> tiles = new HashMap<>();
    @Nullable private ModularUI modularUI;
    private double centerX;
    private double centerZ;
    private double scale = 1.0;
    private int canvasWidth = 1;
    private int canvasHeight = 1;
    private boolean dragging;
    private float lastDragX;
    private float lastDragY;
    private int refreshCursor;
    private long tickCount;

    public MapMenu(int containerId, Player player) {
        super(null, containerId);
        centerX = player.getX();
        centerZ = player.getZ();
        setModularUI(createModularUI(player));
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        Player player = mc.player;
        MapMenu menu = new MapMenu(UiKit.CLIENT_MENU_ID, player);
        player.containerMenu = menu;
        mc.setScreen(new MapScreen(menu, player.getInventory(), Component.translatable("gui.adminsfactions.world_map")));
    }

    @Override public @Nullable ModularUI getModularUI() { return modularUI; }
    @Override public void setModularUI(ModularUI modularUI) { this.modularUI = modularUI; }
    @Override public @Nullable ModularUI ldlib2$getModularUI() { return modularUI; }
    @Override public void ldlib2$setModularUI(ModularUI modularUI) { this.modularUI = modularUI; }
    @Override public @Nullable ItemSlot ldlib2$getItemSlot(Slot slot) { return null; }
    @Override public void ldlib2$addSlot(ItemSlot itemSlot) { }

    private ModularUI createModularUI(Player player) {
        UIElement root = new UIElement();
        root.layout(layout -> layout.widthPercent(100).heightPercent(100));
        root.style(style -> style.backgroundTexture(new MapPainter(this)));
        root.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            dragging = true;
            lastDragX = event.x;
            lastDragY = event.y;
        });
        root.addEventListener(UIEvents.MOUSE_UP, event -> dragging = false);
        root.addEventListener(UIEvents.MOUSE_MOVE, event -> {
            if (!dragging) return;
            centerX -= (event.x - lastDragX) / scale;
            centerZ -= (event.y - lastDragY) / scale;
            lastDragX = event.x;
            lastDragY = event.y;
        });
        root.addEventListener(UIEvents.MOUSE_WHEEL, event -> zoomAt(event.deltaY, event.x, event.y));
        root.addEventListener(UIEvents.TICK, event -> tickMap());
        return ModularUI.of(UI.of(root), player);
    }

    private void zoomAt(double direction, double mouseX, double mouseY) {
        if (direction == 0) return;
        clampScale();
        double oldScale = scale;
        double minScale = minimumScale();
        scale = direction > 0 ? Math.min(16.0, scale * 1.25) : Math.max(minScale, scale / 1.25);
        if (scale == oldScale) return;
        centerX += (mouseX - canvasWidth / 2.0) * (1.0 / oldScale - 1.0 / scale);
        centerZ += (mouseY - canvasHeight / 2.0) * (1.0 / oldScale - 1.0 / scale);
    }

    private double minimumScale() {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        return 0.5 / Math.max(1.0, guiScale);
    }

    private void clampScale() {
        scale = Math.max(minimumScale(), Math.min(16.0, scale));
    }

    private void tickMap() {
        tickCount++;
        Minecraft mc = Minecraft.getInstance();
        if (dragging && GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            dragging = false;
        }
        clampScale();

        List<TilePosition> visible = visibleTiles();
        Set<Long> visibleKeys = new HashSet<>();
        for (TilePosition position : visible) {
            long key = tileKey(position.x(), position.z());
            visibleKeys.add(key);
            Tile tile = tiles.get(key);
            if (tile != null) tile.lastSeenTick = tickCount;
        }
        if (!visible.isEmpty()) {
            TilePosition position = visible.get(Math.floorMod(refreshCursor, visible.size()));
            refreshCursor = (refreshCursor + 1) % visible.size();
            refreshTile(position);
        }
        evictTiles(visibleKeys);
    }

    private List<TilePosition> visibleTiles() {
        double halfWidth = Math.max(1, canvasWidth) / (2.0 * scale);
        double halfHeight = Math.max(1, canvasHeight) / (2.0 * scale);
        long minX = tileCoordinate(centerX - halfWidth);
        long maxX = tileCoordinate(centerX + halfWidth);
        long minZ = tileCoordinate(centerZ - halfHeight);
        long maxZ = tileCoordinate(centerZ + halfHeight);
        List<TilePosition> positions = new ArrayList<>();
        for (long tileZ = minZ; tileZ <= maxZ; tileZ++) {
            for (long tileX = minX; tileX <= maxX; tileX++) {
                positions.add(new TilePosition((int) tileX, (int) tileZ));
            }
        }
        double centerTileX = Math.floor(centerX / TILE_SIZE);
        double centerTileZ = Math.floor(centerZ / TILE_SIZE);
        positions.sort(Comparator.comparingDouble(position -> Math.hypot(
                position.x() - centerTileX, position.z() - centerTileZ)));
        return positions;
    }

    private static long tileCoordinate(double blockCoordinate) {
        return Math.floorDiv((long) Math.floor(blockCoordinate), TILE_SIZE);
    }

    private static long tileKey(int tileX, int tileZ) {
        return ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
    }

    private void refreshTile(TilePosition position) {
        long key = tileKey(position.x(), position.z());
        Tile previous = tiles.get(key);
        TerrainMapTexture texture = previous == null ? null : previous.texture;
        if (texture != null) texture.clear();
        boolean hasData = false;
        long startChunkX = (long) position.x() * TILE_CHUNKS;
        long startChunkZ = (long) position.z() * TILE_CHUNKS;
        for (int localChunkX = 0; localChunkX < TILE_CHUNKS; localChunkX++) {
            long chunkX = startChunkX + localChunkX;
            if (chunkX < Integer.MIN_VALUE || chunkX > Integer.MAX_VALUE) continue;
            for (int localChunkZ = 0; localChunkZ < TILE_CHUNKS; localChunkZ++) {
                long chunkZ = startChunkZ + localChunkZ;
                if (chunkZ < Integer.MIN_VALUE || chunkZ > Integer.MAX_VALUE) continue;
                int[][] colors = MapChunkManager.getSlice((int) chunkX, (int) chunkZ, Integer.MAX_VALUE);
                if (colors == null) continue;
                hasData = true;
                if (texture == null) texture = new TerrainMapTexture(TILE_SIZE);
                for (int x = 0; x < 16 && x < colors.length; x++) {
                    if (colors[x] == null) continue;
                    for (int z = 0; z < 16 && z < colors[x].length; z++) {
                        texture.setPixel(localChunkX * 16 + x, localChunkZ * 16 + z, colors[x][z]);
                    }
                }
            }
        }
        if (hasData) {
            texture.upload();
            if (previous == null) tiles.put(key, new Tile(key, texture, tickCount));
            else previous.lastSeenTick = tickCount;
        } else if (previous != null) {
            tiles.remove(key);
            previous.texture.close();
        }
    }

    private void evictTiles(Set<Long> visibleKeys) {
        Iterator<Map.Entry<Long, Tile>> iterator = tiles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Tile> entry = iterator.next();
            if (!visibleKeys.contains(entry.getKey()) && tickCount - entry.getValue().lastSeenTick > TILE_CACHE_TTL) {
                entry.getValue().texture.close();
                iterator.remove();
            }
        }
        while (tiles.size() > MAX_TILES) {
            Tile oldest = tiles.values().stream().min(Comparator.comparingLong(tile -> tile.lastSeenTick)).orElse(null);
            if (oldest == null) break;
            tiles.remove(oldest.key);
            oldest.texture.close();
        }
    }

    private void releaseTextures() {
        for (Tile tile : tiles.values()) tile.texture.close();
        tiles.clear();
    }

    @Override public void removed(Player player) {
        super.removed(player);
        dragging = false;
        releaseTextures();
    }

    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }

    private static final class Tile {
        private final long key;
        private final TerrainMapTexture texture;
        private long lastSeenTick;

        private Tile(long key, TerrainMapTexture texture, long lastSeenTick) {
            this.key = key;
            this.texture = texture;
            this.lastSeenTick = lastSeenTick;
        }
    }

    private record TilePosition(int x, int z) { }

    private static final class MapPainter extends ColorRectTexture {
        private final MapMenu menu;

        private MapPainter(MapMenu menu) {
            super(0xFF404040);
            this.menu = menu;
        }

        @Override
        protected void drawInternal(GuiGraphics gfx, float mouseX, float mouseY, float x, float y,
                                    float width, float height, float partialTick) {
            menu.canvasWidth = Math.max(1, (int) width);
            menu.canvasHeight = Math.max(1, (int) height);
            menu.clampScale();
            int left = (int) x;
            int top = (int) y;
            int right = (int) Math.ceil(x + width);
            int bottom = (int) Math.ceil(y + height);
            gfx.fill(left, top, right, bottom, 0xFF404040);
            gfx.enableScissor(left, top, right, bottom);

            long baseTileX = menu.tileCoordinate(menu.centerX);
            long baseTileZ = menu.tileCoordinate(menu.centerZ);
            double offsetX = menu.centerX - baseTileX * TILE_SIZE;
            double offsetZ = menu.centerZ - baseTileZ * TILE_SIZE;
            gfx.pose().pushPose();
            gfx.pose().translate(x + width / 2.0 - offsetX * menu.scale,
                    y + height / 2.0 - offsetZ * menu.scale, 0);
            gfx.pose().scale((float) menu.scale, (float) menu.scale, 1);
            for (TilePosition position : menu.visibleTiles()) {
                Tile tile = menu.tiles.get(tileKey(position.x(), position.z()));
                if (tile != null) {
                    tile.texture.drawFull(gfx, (position.x() - baseTileX) * TILE_SIZE,
                            (position.z() - baseTileZ) * TILE_SIZE, TILE_SIZE, TILE_SIZE);
                }
            }
            gfx.pose().popPose();

            Player player = Minecraft.getInstance().player;
            if (player != null) {
                double markerX = x + width / 2.0 + (player.getX() - menu.centerX) * menu.scale;
                double markerY = y + height / 2.0 + (player.getZ() - menu.centerZ) * menu.scale;
                if (markerX >= x - 5 && markerX <= x + width + 5 && markerY >= y - 5 && markerY <= y + height + 5) {
                    gfx.pose().pushPose();
                    gfx.pose().translate(markerX, markerY, 10f);
                    gfx.pose().mulPose(Axis.ZP.rotationDegrees(player.getViewYRot(partialTick)));
                    gfx.fill(-2, -3, 2, 2, 0xFF000000);
                    gfx.fill(-1, -4, 1, 3, 0xFF000000);
                    gfx.fill(-1, -2, 1, 2, 0xFFFFFFFF);
                    gfx.fill(0, 2, 1, 3, 0xFFFF3333);
                    gfx.pose().popPose();
                }
            }
            gfx.disableScissor();

            if (mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height) {
                long blockX = (long) Math.floor(menu.centerX + (mouseX - x - width / 2.0) / menu.scale);
                long blockZ = (long) Math.floor(menu.centerZ + (mouseY - y - height / 2.0) / menu.scale);
                String coordinates = "X: " + blockX + "  Z: " + blockZ;
                var font = Minecraft.getInstance().font;
                int boxLeft = left + 8;
                int boxTop = top + 8;
                gfx.fill(boxLeft - 4, boxTop - 3, boxLeft + font.width(coordinates) + 4, boxTop + 11, 0xA0000000);
                gfx.drawString(font, coordinates, boxLeft, boxTop, 0xFFFFFFFF, false);
            }
        }
    }
}
