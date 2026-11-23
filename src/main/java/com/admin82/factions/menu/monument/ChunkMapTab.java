package com.admin82.factions.menu.monument;

import com.admin82.factions.map.MapColorCache;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.monument.MonumentView;
import com.admin82.factions.network.packet.MonumentActionPacket;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;

import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/** The Chunk Map tab: pick which chunks belong to the monument. */
final class ChunkMapTab {

    private final MonumentMenu menu;

    ChunkMapTab(MonumentMenu menu) {
        this.menu = menu;
    }

    int mapOffsetX;
    int mapOffsetZ;
    private int monumentMapViewSize = 11;
    private boolean monumentMapDragging;
    private float monumentMapDragDistance;
    private float monumentMapLastX;
    private float monumentMapLastY;
    private float monumentMapAccumX;
    private float monumentMapAccumY;
    @Nullable private MonumentTerrainTexture monumentTerrainTexture;

    // Chunk map tab

    @OnlyIn(Dist.CLIENT)
    UIElement buildChunkMap(MonumentView selected) {
        var panel = MonumentUi.hbox(8);
        panel.layout(l -> l.alignItems(YogaAlign.FLEX_START));

        int controllerChunkX = Math.floorDiv(selected.x(), 16);
        int controllerChunkZ = Math.floorDiv(selected.z(), 16);
        int[] centerX = {controllerChunkX + mapOffsetX};
        int[] centerZ = {controllerChunkZ + mapOffsetZ};
        var mapArea = new UIElement();
        mapArea.layout(l -> l.width(286).height(286).flexDirection(FlexDirection.COLUMN));
        if (monumentTerrainTexture == null) monumentTerrainTexture = new MonumentTerrainTexture(286);
        mapArea.style(style -> style.background(monumentTerrainTexture));
        var viewLabel = new Label();

        Runnable[] refresh = new Runnable[1];
        var redraw = new MapColorCache.Throttle();
        refresh[0] = () -> {
            MonumentView current = menu.selectedView();
            if (current == null) current = selected;
            mapArea.clearAllChildren();
            int halfView = monumentMapViewSize / 2;
            int cellSize = Math.max(10, (286 - monumentMapViewSize + 1) / monumentMapViewSize);
            int actualSize = cellSize * monumentMapViewSize;
            viewLabel.setText("§7Showing §f" + monumentMapViewSize + " x " + monumentMapViewSize + "§7 chunks");
            MonumentTerrainTexture texture = monumentTerrainTexture;
            Level level = Minecraft.getInstance().level;
            boolean matchingDimension = level != null
                    && level.dimension().location().toString().equals(current.dimension());
            texture.clear();
            for (int row = 0; row < monumentMapViewSize; row++) {
                for (int col = 0; col < monumentMapViewSize; col++) {
                    int chunkX = centerX[0] - halfView + col;
                    int chunkZ = centerZ[0] - halfView + row;
                    boolean loaded = matchingDimension && level.hasChunk(chunkX, chunkZ);
                    for (int pixelY = 0; pixelY < cellSize; pixelY++) {
                        for (int pixelX = 0; pixelX < cellSize; pixelX++) {
                            int imageX = col * cellSize + pixelX;
                            int imageY = row * cellSize + pixelY;
                            int color = loaded
                                    ? MonumentMapColors.CACHE.color(level, chunkX * 16 + pixelX * 16 / cellSize,
                                            chunkZ * 16 + pixelY * 16 / cellSize)
                                    : 0x404040;
                            texture.setPixel(imageX, imageY, color);
                        }
                    }
                }
            }
            for (int line = 0; line <= monumentMapViewSize; line++) {
                int pixel = line * cellSize;
                if (pixel < 286) {
                    texture.drawHLine(pixel, 0, actualSize - 1);
                    texture.drawVLine(pixel, 0, actualSize - 1);
                }
            }
            int border = Math.max(1, cellSize / 7);
            for (int row = 0; row < monumentMapViewSize; row++) {
                for (int col = 0; col < monumentMapViewSize; col++) {
                    int chunkX = centerX[0] - halfView + col;
                    int chunkZ = centerZ[0] - halfView + row;
                    long key = ChunkPos.asLong(chunkX, chunkZ);
                    boolean controller = chunkX == controllerChunkX && chunkZ == controllerChunkZ;
                    boolean designated = current.designatedChunks().contains(key);
                    boolean factionClaim = current.factionClaims().contains(key);
                    int imageX = col * cellSize;
                    int imageY = row * cellSize;
                    if (factionClaim) texture.drawChunkBorder(imageX, imageY, cellSize, 0xFF4444, border);
                    if (designated && !controller) {
                        int inset = factionClaim ? border : 0;
                        texture.drawChunkBorder(imageX + inset, imageY + inset,
                                cellSize - inset * 2, 0x22CC66, Math.max(1, border));
                    }
                    if (controller) texture.drawChunkBorder(imageX, imageY, cellSize, 0xFFCC00, border + 1);
                }
            }
            texture.upload();
            for (int row = -halfView; row <= halfView; row++) {
                var rowElement = new UIElement();
                rowElement.layout(l -> l.height(cellSize).flexDirection(FlexDirection.ROW));
                for (int col = -halfView; col <= halfView; col++) {
                    int chunkX = centerX[0] + col;
                    int chunkZ = centerZ[0] + row;
                    var cell = new UIElement();
                    UiKit.onClick(cell, event -> {
                        if (monumentMapDragDistance > 3f) return;
                        PacketDistributor.sendToServer(new MonumentActionPacket(
                                MonumentActionPacket.Action.TOGGLE_CHUNK, selected.id(), "", chunkX, chunkZ, 0));
                    });
                    cell.layout(l -> l.width(cellSize).height(cellSize));
                    rowElement.addChildren(cell);
                }
                mapArea.addChildren(rowElement);
            }
        };
        mapArea.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            monumentMapDragging = true;
            monumentMapDragDistance = 0f;
            monumentMapLastX = event.x;
            monumentMapLastY = event.y;
            monumentMapAccumX = 0f;
            monumentMapAccumY = 0f;
        });
        mapArea.addEventListener(UIEvents.MOUSE_UP, event -> monumentMapDragging = false);
        mapArea.addEventListener(UIEvents.MOUSE_MOVE, event -> {
            if (!monumentMapDragging) return;
            float deltaX = monumentMapLastX - event.x;
            float deltaY = monumentMapLastY - event.y;
            monumentMapLastX = event.x;
            monumentMapLastY = event.y;
            monumentMapDragDistance += Math.abs(deltaX) + Math.abs(deltaY);
            monumentMapAccumX += deltaX;
            monumentMapAccumY += deltaY;

            boolean moved = false;
            int pixelsPerChunk = Math.max(1, 286 / monumentMapViewSize);
            while (monumentMapAccumX >= pixelsPerChunk) {
                mapOffsetX++;
                centerX[0]++;
                monumentMapAccumX -= pixelsPerChunk;
                moved = true;
            }
            while (monumentMapAccumX <= -pixelsPerChunk) {
                mapOffsetX--;
                centerX[0]--;
                monumentMapAccumX += pixelsPerChunk;
                moved = true;
            }
            while (monumentMapAccumY >= pixelsPerChunk) {
                mapOffsetZ++;
                centerZ[0]++;
                monumentMapAccumY -= pixelsPerChunk;
                moved = true;
            }
            while (monumentMapAccumY <= -pixelsPerChunk) {
                mapOffsetZ--;
                centerZ[0]--;
                monumentMapAccumY += pixelsPerChunk;
                moved = true;
            }
            if (moved) redraw.request(refresh[0]);
        });
        mapArea.addEventListener(UIEvents.MOUSE_WHEEL, event -> {
            if (event.deltaY > 0) monumentMapViewSize = Math.max(7, monumentMapViewSize - 2);
            else if (event.deltaY < 0) monumentMapViewSize = Math.min(25, monumentMapViewSize + 2);
            refresh[0].run();
        });
        mapArea.addEventListener(UIEvents.TICK, event -> {
            redraw.flush(refresh[0]);
            if (!monumentMapDragging) return;
            long window = Minecraft.getInstance().getWindow().getWindow();
            if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                    != GLFW.GLFW_PRESS) {
                monumentMapDragging = false;
            }
        });
        refresh[0].run();

        var zoomOut = new Button().setText("-").setOnClick(event -> {
            monumentMapViewSize = Math.min(25, monumentMapViewSize + 2);
            refresh[0].run();
        });
        zoomOut.layout(l -> l.width(22).height(20));
        MonumentUi.tooltip(zoomOut, "§7Zoom out");
        var zoomIn = new Button().setText("+").setOnClick(event -> {
            monumentMapViewSize = Math.max(7, monumentMapViewSize - 2);
            refresh[0].run();
        });
        zoomIn.layout(l -> l.width(22).height(20));
        MonumentUi.tooltip(zoomIn, "§7Zoom in");
        var zoomRow = MonumentUi.hbox(6);
        zoomRow.layout(l -> l.height(22));
        zoomRow.addChildren(zoomOut, zoomIn, viewLabel);
        var recenter = new Button().setText("Center on Controller").setOnClick(event -> {
            mapOffsetX = 0;
            mapOffsetZ = 0;
            centerX[0] = controllerChunkX;
            centerZ[0] = controllerChunkZ;
            refresh[0].run();
        });
        recenter.layout(l -> l.width(150).height(20));

        var controls = MonumentUi.card();
        controls.layout(l -> l.flex(1));
        controls.addChildren(MonumentUi.sectionTitle("Monument Area"),
                new Label().setText("§7Click a chunk to add or remove it."),
                new Label().setText("§7Drag to pan, scroll to zoom."),
                MonumentUi.spacer(4), MonumentUi.sectionTitle("Legend"),
                MonumentUi.legendRow(0xFFCC00, "Controller chunk §8(always included)"),
                MonumentUi.legendRow(0x22CC66, "Monument chunk"),
                MonumentUi.legendRow(0xFF4444, "Claimed by a faction"),
                MonumentUi.legendRow(0x404040, "Not loaded on your client"),
                MonumentUi.spacer(4), MonumentUi.sectionTitle("View"),
                new Label().setText("§7Monument chunks: §f" + selected.designatedChunks().size()),
                zoomRow, recenter,
                MonumentUi.spacer(4),
                new Label().setText("§8Entry alerts, protection, crates and ore"),
                new Label().setText("§8generators only work inside these chunks."),
                new Label().setText("§8Max 64 chunks away from the controller."));
        panel.addChildren(mapArea, controls);
        return panel;
    }
}
