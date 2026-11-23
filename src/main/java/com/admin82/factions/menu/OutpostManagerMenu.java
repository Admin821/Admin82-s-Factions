package com.admin82.factions.menu;

import com.admin82.factions.Config;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.map.MapColorCache;
import com.admin82.factions.network.packet.OpenOutpostManagerPacket;
import com.admin82.factions.network.packet.OutpostActionPacket;
import com.admin82.factions.network.packet.SyncServerConfigPacket;
import com.admin82.factions.outpost.OutpostEntry;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.mojang.blaze3d.platform.NativeImage;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.vfyjxf.taffy.style.AlignItems;

import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/**
 * LDLib2 menu for the Outpost Manager.
 * Opened directly on the client from OpenOutpostManagerPacket.
 * Two tabs: Overview (status / info / actions) and Territory Map.
 */
public class OutpostManagerMenu extends AbstractContainerMenu implements IModularUIHolderMenu {

    @Nullable private ModularUI modularUI;

    private final OpenOutpostManagerPacket data;

    static final int GRID_PX     = 234;
    private      int mapViewSize = 9;
    private      int mapOffsetX  = 0, mapOffsetZ = 0;

    private boolean mapDragging = false;
    private float   mapDragDist = 0f, mapLastX = 0f, mapLastY = 0f;
    private float   mapAccumX  = 0f, mapAccumY = 0f;

    @Nullable private UIElement       mapGrid;
    @Nullable private OutpostTerrainMap mapTex;
    @Nullable private UIElement       inspPanel;
    @Nullable private String          inspKey; // "cx,cz"

    // Constructor

    public OutpostManagerMenu(int id, Inventory inv, OpenOutpostManagerPacket data) {
        super(null, id);
        this.data = data;
        if (FMLEnvironment.dist == Dist.CLIENT) {
            setModularUI(createModularUI(inv.player));
        }
    }

    @Override @Nullable public ModularUI getModularUI()    { return modularUI; }
    @Override public void setModularUI(ModularUI ui)       { this.modularUI = ui; }

    // Required abstract implementations for IModularUIHolderMenu
    @Override public ModularUI ldlib2$getModularUI()       { return modularUI; }
    @Override public void ldlib2$setModularUI(ModularUI ui){ this.modularUI = ui; }
    @Override @Nullable
    public ItemSlot ldlib2$getItemSlot(
            Slot slot) { return null; }
    @Override
    public void ldlib2$addSlot(ItemSlot slot) { /* no slots */ }

    @Override public boolean stillValid(Player p)          { return true; }
    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }

    // Root UI

    @OnlyIn(Dist.CLIENT)
    private ModularUI createModularUI(Player player) {
        var frame = new UIElement();
        frame.layout(l -> l.width(424).height(344).paddingAll(2));
        frame.addClass("preview_bg");

        var root = new UIElement();
        root.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN));
        root.addClass("panel_bg");

        // Title row
        var titleRow = new UIElement();
        titleRow.layout(l -> l.flexDirection(FlexDirection.ROW).height(22).paddingHorizontal(8).paddingTop(4));
        titleRow.addChildren(
                new Label().setText("§d§l" + data.outpostName()).layout(l -> l.flex(1)),
                UiKit.rightLabel("§5§o" + data.ownerName())
        );

        // Tab bar + content area
        var contentArea = new UIElement();
        contentArea.layout(l -> l.flex(1));

        var overviewPanel = buildOverviewPanel();
        var mapPanel      = buildMapPanel();
        contentArea.addChildren(overviewPanel);

        var tabBar = new UIElement();
        tabBar.layout(l -> l.flexDirection(FlexDirection.ROW).height(22).gapAll(2).paddingHorizontal(6));

        UIElement[] panels = {overviewPanel, mapPanel};
        String[]    labels = {"Overview", "Territory Map"};

        buildTabs(tabBar, labels, panels, contentArea, tabBar, 0);

        root.addChildren(titleRow, tabBar, contentArea);
        frame.addChildren(root);

        return ModularUI.of(
                UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)),
                player);
    }

    private void buildTabs(UIElement bar, String[] labels, UIElement[] panels,
                           UIElement content, UIElement barRef, int active) {
        bar.clearAllChildren();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            var btn = new Button()
                    .setText(labels[i])
                    .setOnClick(e -> {
                        content.clearAllChildren();
                        content.addChildren(panels[idx]);
                        buildTabs(barRef, labels, panels, content, barRef, idx);
                    })
                    .layout(l -> l.flex(1).height(20));
            if (i == active) btn.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
            bar.addChildren(btn);
        }
    }

    /** The server's outpost capture time (the config is synced from the server). */
    private static float captureTime() {
        return SyncServerConfigPacket.get(Config.WAR_OUTPOST_CAPTURE_TIME_SECONDS);
    }

    // Overview tab

    @OnlyIn(Dist.CLIENT)
    private UIElement buildOverviewPanel() {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(8).gapAll(5).flexDirection(FlexDirection.COLUMN));

        // Status
        if (data.disintegrating()) {
            panel.addChildren(center("§c§lFALLING APART"), center("§cIts upkeep was never paid."));
        } else if (data.hp() < data.maxHp()) {
            panel.addChildren(center("§c§lUNDER ATTACK" + (data.capturingFactionName().isEmpty() ? "" : " by §e" + data.capturingFactionName())),
                    center("§7Each break of the flag takes 1 HP. At 0 the attackers capture the outpost and everything in it."));
        } else if (data.unsuppliedUntil() > 0) {
            long left = Math.max(0, data.unsuppliedUntil() - System.currentTimeMillis());
            panel.addChildren(center("§6§lUNSUPPLIED"),
                    center("§eOut of coins: it falls apart in §f"
                            + (left / 3_600_000L) + "h " + (left / 60_000L % 60) + "m§e unless upkeep is paid."),
                    center("§7Put at least §e" + Currency.format(data.upkeepCost()) + " §7of coins in below."));
        } else if (data.capturable()) {
            panel.addChildren(center("§c§lAT WAR"), center("§7The owner is at war: expect attacks on the flag."));
        } else {
            panel.addChildren(center("§a§lHOLDING"),
                    center("§7Any faction except allies can capture it by breaking the flag " + data.maxHp()
                            + " times. Left alone, the flag repairs."));
        }

        // Flag health
        float pct = data.maxHp() <= 0 ? 0 : Math.max(0f, Math.min(1f, data.hp() / (float) data.maxHp()));
        var barBg = new UIElement();
        barBg.layout(l -> l.width(BAR_WIDTH).height(8).flexDirection(FlexDirection.ROW).alignSelf(AlignItems.CENTER));
        UiKit.background(barBg, UiKit.BAR_TRACK);
        if (pct > 0) {
            int fw = Math.max(1, (int) (BAR_WIDTH * pct));
            var fill = UiKit.background(new UIElement(), pct >= 1f ? 0xFF22AA44 : 0xFFCC3333);
            fill.layout(l -> l.width(fw).height(8));
            barBg.addChildren(fill);
        }
        panel.addChildren(barBg, center("§8Flag: §f" + data.hp() + "§7/" + data.maxHp() + " HP §8· level §f" + data.level()));

        // Coins and upgrades (owning faction only)
        if (data.isOwner() && data.coins() >= 0 && !data.disintegrating()) {
            long days = data.upkeepCost() > 0 ? data.coins() / data.upkeepCost() : 0;
            panel.addChildren(center("§8Coins: §e" + Currency.format(data.coins()) + " §8· upkeep §e" + Currency.format(data.upkeepCost())
                    + "§8/day · §7lasts " + days + " day" + (days == 1 ? "" : "s")));
            var open = new Button().setText("§6Coin storage")
                    .setOnClick(e -> PacketDistributor.sendToServer(new OutpostActionPacket(OutpostActionPacket.Action.OPEN_COINS, data.outpostId())));
            open.layout(l -> l.width(120).height(16).alignSelf(AlignItems.CENTER));
            open.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                    Component.literal("§7Put coins in to pay the daily upkeep."),
                    Component.literal(data.canManage() ? "§7Officers can take coins out too." : "§8Only officers can take coins out."),
                    Component.literal("§7Hoppers can fill it from the flag as well.")));
            panel.addChildren(open);
            if (data.upgradeCost() > 0 && data.canManage())
                panel.addChildren(center("§8Upgrade this outpost from the Faction Upgrades tab."));
        }

        // Rename (officers)
        if (data.canManage() && !data.disintegrating()) {
            var nameRow = new UIElement();
            nameRow.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).alignSelf(AlignItems.CENTER).height(16));
            var nameField = UiKit.textField("");
            nameField.setValue(nameText.isEmpty() ? data.outpostName() : nameText);
            nameField.bindObserver(v -> nameText = v);
            nameField.layout(l -> l.width(160).height(16));
            var rename = new Button().setText("§eRename").setOnClick(e -> {
                String n = (nameText.isEmpty() ? data.outpostName() : nameText).trim();
                if (n.isEmpty()) return;
                PacketDistributor.sendToServer(new OutpostActionPacket(OutpostActionPacket.Action.RENAME, data.outpostId(), 0, n));
                nameText = "";
            });
            rename.layout(l -> l.width(70).height(16));
            nameRow.addChildren(new Label().setText("§7Name").layout(l -> l.width(30)), nameField, rename);
            panel.addChildren(nameRow);
        }

        // Info rows
        panel.addChildren(
                center("§8Position: §7" + data.managerPos().getX() + ", " + data.managerPos().getY() + ", " + data.managerPos().getZ()
                        + "  §8· Land: §7" + OutpostEntry.chunkOffsets(data.level()).size() + " chunk"
                        + (data.level() == 1 ? "" : "s")),
                new Label().setText("§8── Actions ──").lss("horizontal-align", "center")
        );

        // Action buttons
        if (data.canSetSpawn()) {
            panel.addChildren(new Button()
                    .setText("§a§l Set as War Spawn")
                    .setOnClick(e -> {
                        PacketDistributor.sendToServer(new OutpostActionPacket(
                                OutpostActionPacket.Action.SET_WAR_SPAWN, data.outpostId()));
                        Minecraft.getInstance().setScreen(null);
                    })
                    .layout(l -> l.width(380).alignSelf(AlignItems.CENTER)));
        }

        if (data.isOwner() && !data.disintegrating()) {
            var row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(8).alignSelf(AlignItems.CENTER));
            row.addChildren(
                    tipped(new Button().setText("§e Move Outpost").setOnClick(e -> {
                        PacketDistributor.sendToServer(new OutpostActionPacket(OutpostActionPacket.Action.MOVE, data.outpostId()));
                        Minecraft.getInstance().setScreen(null);
                    }).layout(l -> l.width(140)), "§7Packs the outpost into an Outpost item to place somewhere else.",
                            "§7Its name, level and coins go with it.", "§eEmpty the storage box of items first."),
                    new Button().setText("§c Delete Outpost").setOnClick(e -> {
                        PacketDistributor.sendToServer(new OutpostActionPacket(OutpostActionPacket.Action.DELETE, data.outpostId()));
                        Minecraft.getInstance().setScreen(null);
                    }).layout(l -> l.width(140))
            );
            panel.addChildren(row);
        }

        if (!data.isOwner() && !data.canSetSpawn())
            panel.addChildren(new Label().setText("§8You do not own this outpost.").lss("horizontal-align", "center"));
        if (data.isOwner() && data.disintegrating())
            panel.addChildren(new Label().setText("§cThis outpost is falling apart.").lss("horizontal-align", "center"));

        return panel;
    }

    /** Text typed in the rename field. */
    private static String nameText = "";

    private static final int BAR_WIDTH = 360;

    private static Label center(String text) {
        return (Label) new Label().setText(text).lss("horizontal-align", "center");
    }

    // Territory Map tab

    @OnlyIn(Dist.CLIENT)
    private UIElement buildMapPanel() {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(6).gapAll(4).flexDirection(FlexDirection.COLUMN));

        panel.addChildren(new Label().setText("§8Chunk territory around the outpost  §8(drag/scroll)"));

        var mapRow = new UIElement();
        mapRow.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6));

        // Terrain map grid
        mapGrid = new UIElement();
        mapGrid.layout(l -> l.width(GRID_PX).height(GRID_PX).alignSelf(AlignItems.CENTER));
        if (mapTex == null) mapTex = new OutpostTerrainMap(GRID_PX);
        mapGrid.style(s -> s.background(mapTex));
        fillMapCells();

        // Drag-to-pan
        mapGrid.addEventListener(UIEvents.MOUSE_DOWN, ev -> {
            mapDragging = true; mapDragDist = 0f;
            mapLastX = ev.x; mapLastY = ev.y;
            mapAccumX = 0f; mapAccumY = 0f;
        });
        mapGrid.addEventListener(UIEvents.MOUSE_UP, ev -> mapDragging = false);
        mapGrid.addEventListener(UIEvents.MOUSE_MOVE, ev -> {
            if (!mapDragging) return;
            float dx = mapLastX - ev.x, dz = mapLastY - ev.y;
            mapLastX = ev.x; mapLastY = ev.y;
            mapDragDist += Math.abs(dx) + Math.abs(dz);
            mapAccumX += dx; mapAccumY += dz;
            boolean moved = false;
            int ppc = Math.max(1, GRID_PX / mapViewSize);
            while (mapAccumX >=  ppc) { mapOffsetX++; mapAccumX -= ppc; moved = true; }
            while (mapAccumX <= -ppc) { mapOffsetX--; mapAccumX += ppc; moved = true; }
            while (mapAccumY >=  ppc) { mapOffsetZ++; mapAccumY -= ppc; moved = true; }
            while (mapAccumY <= -ppc) { mapOffsetZ--; mapAccumY += ppc; moved = true; }
            if (moved) mapRedraw.request(this::fillMapCells);
        });
        mapGrid.addEventListener(UIEvents.MOUSE_WHEEL, ev -> {
            if      (ev.deltaY > 0) mapViewSize = Math.max(5,  mapViewSize - 2);
            else if (ev.deltaY < 0) mapViewSize = Math.min(17, mapViewSize + 2);
            fillMapCells();
        });
        mapGrid.addEventListener(UIEvents.TICK, ev -> {
            if (mapDragging) {
                long win = Minecraft.getInstance().getWindow().getWindow();
                if (GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                        != GLFW.GLFW_PRESS) {
                    mapDragging = false; mapDragDist = 0f;
                }
            }
            fillMapCells(); // refresh terrain as chunks load
        });

        // Inspection panel
        inspPanel = new UIElement();
        inspPanel.layout(l -> l.width(162).flexDirection(FlexDirection.COLUMN).gapAll(4));
        refreshInspPanel();

        mapRow.addChildren(mapGrid, inspPanel);
        panel.addChildren(mapRow);
        panel.addChildren(new Label().setText("§9\u25a0\u00a7r Mine  §c\u25a0\u00a7r Enemy  §6\u25a0\u00a7r Outpost  §8drag \u00b7 scroll"));

        return panel;
    }

    // Map rendering

    @OnlyIn(Dist.CLIENT)
    private void fillMapCells() {
        if (mapGrid == null || mapTex == null) return;
        mapGrid.clearAllChildren();

        var mc    = Minecraft.getInstance();
        var level = mc.level;
        int cellPx   = Math.max(8, GRID_PX / mapViewSize);
        int halfView = mapViewSize / 2;
        int actual   = cellPx * mapViewSize;
        int ccx = data.centerChunkX() + mapOffsetX;
        int ccz = data.centerChunkZ() + mapOffsetZ;

        mapTex.clear();

        for (int row = 0; row < mapViewSize; row++) {
            for (int col = 0; col < mapViewSize; col++) {
                int cx = ccx - halfView + col, cz = ccz - halfView + row;
                boolean loaded = level != null && level.hasChunk(cx, cz);
                for (int py = 0; py < cellPx; py++) {
                    for (int px = 0; px < cellPx; px++) {
                        int imgX = col * cellPx + px, imgY = row * cellPx + py;
                        if (imgX >= GRID_PX || imgY >= GRID_PX) continue;
                        int rgb = loaded
                                ? COLORS.color(level, cx * 16 + px * 16 / cellPx, cz * 16 + py * 16 / cellPx)
                                : 0x404040;
                        mapTex.setPixel(imgX, imgY, rgb);
                    }
                }
            }
        }

        // Grid lines
        for (int i = 0; i <= mapViewSize; i++) {
            int pos = i * cellPx;
            if (pos < GRID_PX) { mapTex.drawHLine(pos, 0, actual-1); mapTex.drawVLine(pos, 0, actual-1); }
        }

        // Faction borders
        int bpx = Math.max(1, cellPx / 7);
        for (int row = 0; row < mapViewSize; row++) {
            for (int col = 0; col < mapViewSize; col++) {
                int cx = ccx - halfView + col, cz = ccz - halfView + row;
                boolean isCenter = cx == data.centerChunkX() && cz == data.centerChunkZ();
                byte tile = getTile(cx, cz);
                int color;
                if (isCenter)       color = 0xAA6600;
                else if (tile == 1) color = 0x22AA44;
                else if (tile == 2) color = 0xCC3333;
                else continue;
                mapTex.drawChunkBorder(col * cellPx, row * cellPx, cellPx, color, bpx);
            }
        }

        // Inspected chunk highlight
        if (inspKey != null) {
            String[] p = inspKey.split(",", 2);
            try {
                int ix = Integer.parseInt(p[0]), iz = Integer.parseInt(p[1]);
                int sc = ix - (ccx - halfView), sr = iz - (ccz - halfView);
                if (sc >= 0 && sc < mapViewSize && sr >= 0 && sr < mapViewSize)
                    mapTex.drawChunkBorder(sc * cellPx, sr * cellPx, cellPx, 0xFFFFFF, bpx + 1);
            } catch (NumberFormatException ignored) {}
        }
        mapTex.upload();

        // Transparent click cells
        final int fcs = cellPx;
        for (int row = 0; row < mapViewSize; row++) {
            var rowEl = new UIElement();
            rowEl.layout(l -> l.flexDirection(FlexDirection.ROW).height(fcs));
            for (int col = 0; col < mapViewSize; col++) {
                int fcx = ccx - halfView + col, fcz = ccz - halfView + row;
                var cell = new UIElement();
                cell.layout(l -> l.width(fcs).height(fcs));
                UiKit.onClick(cell, ev -> {
                    if (mapDragDist > 5f) return;
                    String key = fcx + "," + fcz;
                    inspKey = key.equals(inspKey) ? null : key;
                    refreshInspPanel();
                    fillMapCells();
                });
                rowEl.addChildren(cell);
            }
            mapGrid.addChildren(rowEl);
        }
    }

    private void refreshInspPanel() {
        if (inspPanel == null) return;
        inspPanel.clearAllChildren();
        inspPanel.addChildren(new Label().setText("§8\u2500\u2500\u2500 Chunk Info \u2500\u2500\u2500"));
        if (inspKey == null) {
            inspPanel.addChildren(new Label().setText("§8Click a chunk"), new Label().setText("§8to inspect."));
            return;
        }
        String[] p = inspKey.split(",", 2);
        try {
            int icx = Integer.parseInt(p[0]), icz = Integer.parseInt(p[1]);
            boolean isCenter = icx == data.centerChunkX() && icz == data.centerChunkZ();
            byte tile = getTile(icx, icz);
            inspPanel.addChildren(
                    new Label().setText("§7X: §f" + icx),
                    new Label().setText("§7Z: §f" + icz)
            );
            if (isCenter)       inspPanel.addChildren(new Label().setText("§6Outpost Chunk"));
            else if (tile == 1) inspPanel.addChildren(new Label().setText("§9Your Faction"));
            else if (tile == 2) inspPanel.addChildren(new Label().setText("§cEnemy Territory"));
            else                inspPanel.addChildren(new Label().setText("§8Unclaimed"));
        } catch (NumberFormatException ignored) {}
    }

    private byte getTile(int cx, int cz) {
        byte[] tiles = data.mapTiles();
        if (tiles == null) return 0;
        int MS = 11, MR = 5;
        int r = (cz - data.centerChunkZ()) + MR;
        int c = (cx - data.centerChunkX()) + MR;
        if (r < 0 || r >= MS || c < 0 || c >= MS) return 0;
        int i = r * MS + c;
        return i < tiles.length ? tiles[i] : 0;
    }

    // Block colour helpers (mirrors FactionTableMenu)

    @OnlyIn(Dist.CLIENT)
    private static final MapColorCache COLORS = new MapColorCache(OutpostManagerMenu::computeBlockColor);
    private final MapColorCache.Throttle mapRedraw = new MapColorCache.Throttle();

    private static int computeBlockColor(Level level, int wx, int wz) {
        try {
            int wyF = level.getHeight(Heightmap.Types.OCEAN_FLOOR,   wx, wz) - 1;
            int wyS = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
            if (wyF < level.getMinBuildHeight()) wyF = level.getMinBuildHeight();
            if (wyS < level.getMinBuildHeight()) wyS = level.getMinBuildHeight();
            if (wyS > wyF) {
                var sp2 = new BlockPos(wx, wyS, wz);
                if (!level.getFluidState(sp2).isEmpty()) {
                    int base; try { base = BiomeColors.getAverageWaterColor(level, sp2); } catch (Exception e) { base = 0x3F76E4; }
                    float f = Math.max(0.20f, 1.0f - (wyS - wyF) * 0.05f);
                    return ((int)(((base>>16)&0xFF)*f)<<16)|((int)(((base>>8)&0xFF)*f)<<8)|(int)((base&0xFF)*f);
                }
            }
            int wyT = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz) - 1;
            if (wyT < level.getMinBuildHeight()) wyT = level.getMinBuildHeight();
            var pos   = new BlockPos(wx, wyT, wz);
            var state = level.getBlockState(pos);
            int col   = getBlockRenderColor(state, level, pos);
            long h    = wx * 374761393L ^ wz * 668265263L;
            float j   = 1.0f + (((h >> 16) & 0xFF) - 128) / 3200.0f;
            int wyN   = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz-1) - 1;
            int wyE   = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx+1, wz) - 1;
            if (wyN < level.getMinBuildHeight()) wyN = wyT;
            if (wyE < level.getMinBuildHeight()) wyE = wyT;
            float hill  = Math.max(0.40f, Math.min(1.60f, 1.0f + (wyT-wyN)*0.12f + (wyT-wyE)*0.06f));
            float alt   = Math.max(0.70f, Math.min(1.25f, 1.0f + (wyT-64)*0.004f));
            float total = hill * alt * j;
            int r = Math.min(255, Math.max(0, (int)(((col>>16)&0xFF)*total)));
            int g = Math.min(255, Math.max(0, (int)(((col>>8 )&0xFF)*total)));
            int b = Math.min(255, Math.max(0, (int)(( col     &0xFF)*total)));
            return (r<<16)|(g<<8)|b;
        } catch (Exception e) { return 0x404040; }
    }

    @OnlyIn(Dist.CLIENT)
    private static int getBlockRenderColor(BlockState state, Level level, BlockPos pos) {
        try {
            var mc = Minecraft.getInstance();
            var model = mc.getBlockRenderer().getBlockModel(state);
            var quads = model.getQuads(state, Direction.UP, RandomSource.create(42L));
            if (!quads.isEmpty()) {
                var c = quads.get(0).getSprite().contents();
                int w = c.width(), h = c.height(); long rS=0,gS=0,bS=0; int n=0;
                for (int sy=0;sy<h;sy+=2) for (int sx=0;sx<w;sx+=2) {
                    int px=c.getOriginalImage().getPixelRGBA(sx,sy);
                    if(((px>>24)&0xFF)<64) continue;
                    rS+=px&0xFF; gS+=(px>>8)&0xFF; bS+=(px>>16)&0xFF; n++;
                }
                if (n > 0) {
                    int tex = (int)(rS/n)<<16|(int)(gS/n)<<8|(int)(bS/n);
                    int t = mc.getBlockColors().getColor(state,level,pos,0);
                    if (t!=-1) tex=((tex>>16&0xFF)*(t>>16&0xFF)/255)<<16|((tex>>8&0xFF)*(t>>8&0xFF)/255)<<8|(tex&0xFF)*(t&0xFF)/255;
                    return tex;
                }
            }
        } catch (Exception ignored) {}
        try { int t=Minecraft.getInstance().getBlockColors().getColor(state,level,pos,0); if(t!=-1) return t; } catch (Exception ignored) {}
        int c = state.getMapColor(level,pos).col;
        return (c==0||c==-1) ? 0x707070 : c;
    }

    // TerrainMap texture (NativeImage + DynamicTexture, mirrors FactionTableMenu)

    @OnlyIn(Dist.CLIENT)
    private static final class OutpostTerrainMap extends ColorRectTexture {

        private final NativeImage image;
        private final net.minecraft.client.renderer.texture.DynamicTexture dynTex;
        private final ResourceLocation rl;
        private final int size;

        OutpostTerrainMap(int size) {
            super(0x00000000);
            this.size   = size;
            this.image  = new NativeImage(NativeImage.Format.RGBA, size, size, false);
            this.dynTex = new net.minecraft.client.renderer.texture.DynamicTexture(image);
            this.rl     = Minecraft.getInstance().getTextureManager()
                    .register("adminsfactions_outpost_terrain", dynTex);
            clear();
        }

        void setPixel(int x, int y, int rgb) { image.setPixelRGBA(x, y, abgr(rgb)); }

        void clear() {
            int grey = abgr(0x404040);
            for (int y=0;y<size;y++) for (int x=0;x<size;x++) image.setPixelRGBA(x,y,grey);
        }

        void drawHLine(int y, int x1, int x2) {
            if (y<0||y>=size) return;
            for (int x=Math.max(0,x1);x<=Math.min(size-1,x2);x++) darkenPixel(x,y);
        }
        void drawVLine(int x, int y1, int y2) {
            if (x<0||x>=size) return;
            for (int y=Math.max(0,y1);y<=Math.min(size-1,y2);y++) darkenPixel(x,y);
        }
        private void darkenPixel(int x, int y) {
            int v=image.getPixelRGBA(x,y);
            image.setPixelRGBA(x,y,(0xFF<<24)|((v&0xFF)/2<<16)|((v>>8&0xFF)/2<<8)|((v>>16&0xFF)/2));
        }
        void fillHLine(int y, int x1, int x2, int rgb) {
            if (y<0||y>=size) return;
            int p=abgr(rgb); for (int x=Math.max(0,x1);x<=Math.min(size-1,x2);x++) image.setPixelRGBA(x,y,p);
        }
        void fillVLine(int x, int y1, int y2, int rgb) {
            if (x<0||x>=size) return;
            int p=abgr(rgb); for (int y=Math.max(0,y1);y<=Math.min(size-1,y2);y++) image.setPixelRGBA(x,y,p);
        }
        void drawChunkBorder(int ix, int iy, int fcs, int rgb, int bpx) {
            for (int b=0;b<bpx;b++) {
                fillHLine(iy+b,ix,ix+fcs-1,rgb); fillHLine(iy+fcs-1-b,ix,ix+fcs-1,rgb);
                fillVLine(ix+b,iy,iy+fcs-1,rgb); fillVLine(ix+fcs-1-b,iy,iy+fcs-1,rgb);
            }
        }
        void upload() { dynTex.upload(); }

        private static int abgr(int rgb) {
            return (0xFF<<24)|((rgb&0xFF)<<16)|(((rgb>>8)&0xFF)<<8)|((rgb>>16)&0xFF);
        }

        @Override
        protected void drawInternal(GuiGraphics gfx,
                float mx, float my, float sx, float sy, float ew, float eh, float pt) {
            gfx.blit(rl, (int)sx, (int)sy, 0f, 0f, (int)ew, (int)eh, size, size);
        }
    }

    private static UIElement tipped(UIElement element, String... lines) {
        element.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.HOVER_TOOLTIPS, ev -> {
            var tips = com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips.empty();
            for (String line : lines) tips = tips.append(Component.literal(line));
            ev.hoverTooltips = tips;
        });
        return element;
    }
}
