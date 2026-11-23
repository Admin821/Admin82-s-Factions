package com.admin82.factions.menu.factiontable;

import com.admin82.factions.map.MapColorCache;
import com.admin82.factions.map.TerrainColors;
import com.admin82.factions.map.TerrainMapTexture;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.Config;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.network.packet.*;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;

import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/** The Territory tab: the live chunk map, claiming, chunkloading and the outpost list. */
final class TerritoryTab {

    private final FactionTableMenu menu;

    TerritoryTab(FactionTableMenu menu) {
        this.menu = menu;
    }

    private int mapOffsetX = 0;
    private int mapOffsetZ = 0;

    // Drag-to-pan state (plain instance fields, accessible from inner lambdas)
    private boolean mapIsDragging = false;
    private float   mapDragDist   = 0f;
    private float   mapLastDragX  = 0f;
    private float   mapLastDragY  = 0f;
    private float   mapAccumDragX = 0f;
    private float   mapAccumDragY = 0f;

    @Nullable UIElement mapGrid;
    private int selectedChunkX = Integer.MIN_VALUE;
    private int selectedChunkZ = Integer.MIN_VALUE;
    @Nullable private UIElement chunkInfoContainer;

    // Outpost list state
    final AtomicReference<
            List<SyncOutpostsPacket.OutpostItem>>
            outpostListRef = new AtomicReference<>(List.of());
    @Nullable private SyncOutpostsPacket.OutpostItem outpostTpPending = null;
    final AtomicLong outpostTpCostRef = new AtomicLong(1_000L);
    @Nullable UIElement territoryContentArea; // swappable map vs outpost-list
    boolean showingOutpostList = false;

    @Nullable private TerrainMapTexture terrainMapTexture;

    static final int GRID_PX   = 234; // fixed map widget pixel size
    private int              mapViewSize = 9;  // chunks visible each axis; changes on scroll-zoom

    // TERRITORY, interactive live chunk map

    @OnlyIn(Dist.CLIENT)
    UIElement buildTerritoryPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(6).gapAll(4).flexDirection(FlexDirection.COLUMN));

        final int     coreX    = SectionPos.blockToSectionCoord(menu.tablePos.getX());
        final int     coreZ    = SectionPos.blockToSectionCoord(menu.tablePos.getZ());
        final boolean canClaim = menu.hasPermission(player, FactionPermission.OFFICER_CLAIM);

        // Stats + Outpost toggle button row
        var statsRow = new UIElement();
        statsRow.layout(l -> l.flexDirection(FlexDirection.ROW).width(408).height(18).gapAll(4));
        statsRow.addChildren(
                new Label().bindDataSource(SupplierDataSource.of(() -> {
                    var f = menu.factionRef.get();
                    return Component.literal("§7Territory: §e" + (f != null ? f.getLandClaims().size() : 0) + " chunks");
                })).layout(l -> l.flex(1)),
                new Label().setText("§7Core: §e" + coreX + "," + coreZ).layout(l -> l.width(100))
        );

        // Outpost toggle button
        var outpostBtn = new Button()
                .setText("§d⚑ Outposts")
                .setOnClick(e -> {
                    if (showingOutpostList) showTerritoryMap(coreX, coreZ, canClaim);
                    else                   showOutpostList();
                });
        outpostBtn.layout(l -> l.width(80).height(16));
        statsRow.addChildren(outpostBtn);

        // Legend (only shown in map view, hidden in outpost view via same area)
        var legend = new Label().setText("§6■§rCore  §9■§rMine  §c■§rOther  §8■§rFree");
        legend.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                Component.literal("§7Drag the map to pan, scroll to zoom.")));

        // Swappable content area
        territoryContentArea = new UIElement();
        territoryContentArea.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).gapAll(4));
        showTerritoryMap(coreX, coreZ, canClaim); // default: show map

        panel.addChildren(statsRow, legend, territoryContentArea);
        return panel;
    }

    private final MapColorCache.Throttle mapRedraw = new MapColorCache.Throttle();

    /** Seams the faction has scanned, by chunk key; asked for around the view as it moves. */
    private final java.util.Map<Long, com.admin82.factions.seam.SeamCell> seams = new java.util.HashMap<>();
    private int seamAskX = Integer.MIN_VALUE, seamAskZ = Integer.MIN_VALUE;

    /** More scanned seams from the server: redraw so they show. */
    void addSeams(java.util.List<com.admin82.factions.seam.SeamCell> cells) {
        if (cells.isEmpty()) return;
        for (var c : cells) seams.put(c.key(), c);
        mapRedraw.request(() -> { if (mapGrid != null) { mapGrid.clearAllChildren(); fillMapCells(mapGrid, lastCoreX, lastCoreZ, lastCanClaim); } });
    }

    private int lastCoreX, lastCoreZ;
    private boolean lastCanClaim;

    @OnlyIn(Dist.CLIENT)
    private void rebuildMap(int coreX, int coreZ, boolean canClaim) {
        if (mapGrid != null) { mapGrid.clearAllChildren(); fillMapCells(mapGrid, coreX, coreZ, canClaim); }
    }

    /**
     * Swaps the territory content area to show the live map + chunk info panel.
     */
    @OnlyIn(Dist.CLIENT)
    private void showTerritoryMap(int coreX, int coreZ, boolean canClaim) {
        if (territoryContentArea == null) return;
        showingOutpostList = false;
        territoryContentArea.clearAllChildren();

        var mapRow = new UIElement();
        mapRow.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6));
        mapGrid = new UIElement();
        mapGrid.layout(l -> l.width(GRID_PX).height(GRID_PX).alignSelf(YogaAlign.CENTER));
        terrainMapTexture = new TerrainMapTexture(GRID_PX);
        mapGrid.style(s -> s.background(terrainMapTexture));
        fillMapCells(mapGrid, coreX, coreZ, canClaim);

        // Drag-to-pan
        mapGrid.addEventListener(UIEvents.MOUSE_DOWN, ev -> {
            mapIsDragging = true; mapDragDist = 0f;
            mapLastDragX = ev.x; mapLastDragY = ev.y;
            mapAccumDragX = 0f; mapAccumDragY = 0f;
        });
        mapGrid.addEventListener(UIEvents.MOUSE_UP, ev -> mapIsDragging = false);
        mapGrid.addEventListener(UIEvents.MOUSE_MOVE, ev -> {
            if (!mapIsDragging) return;
            float dx = mapLastDragX - ev.x, dz = mapLastDragY - ev.y;
            mapLastDragX = ev.x; mapLastDragY = ev.y;
            mapDragDist += Math.abs(dx) + Math.abs(dz);
            mapAccumDragX += dx; mapAccumDragY += dz;
            boolean moved = false;
            int ppc = Math.max(1, GRID_PX / mapViewSize);
            while (mapAccumDragX >=  ppc) { mapOffsetX++; mapAccumDragX -= ppc; moved = true; }
            while (mapAccumDragX <= -ppc) { mapOffsetX--; mapAccumDragX += ppc; moved = true; }
            while (mapAccumDragY >=  ppc) { mapOffsetZ++; mapAccumDragY -= ppc; moved = true; }
            while (mapAccumDragY <= -ppc) { mapOffsetZ--; mapAccumDragY += ppc; moved = true; }
            if (moved) mapRedraw.request(() -> { mapGrid.clearAllChildren(); fillMapCells(mapGrid, coreX, coreZ, canClaim); });
        });
        mapGrid.addEventListener(UIEvents.MOUSE_WHEEL, ev -> {
            if      (ev.deltaY > 0) mapViewSize = Math.max(5,  mapViewSize - 2);
            else if (ev.deltaY < 0) mapViewSize = Math.min(17, mapViewSize + 2);
            mapGrid.clearAllChildren(); fillMapCells(mapGrid, coreX, coreZ, canClaim);
        });
        int[] lastCC = {-1};
        mapGrid.addEventListener(UIEvents.TICK, ev -> {
            mapRedraw.flush(() -> { mapGrid.clearAllChildren(); fillMapCells(mapGrid, coreX, coreZ, canClaim); });
            if (mapIsDragging) {
                long win = Minecraft.getInstance().getWindow().getWindow();
                if (GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                        != GLFW.GLFW_PRESS) {
                    mapIsDragging = false; mapDragDist = 0f;
                }
            }
            Faction fTick = menu.factionRef.get();
            int cc = fTick != null ? fTick.getLandClaims().hashCode() : 0;
            if (cc != lastCC[0]) {
                boolean firstTick = lastCC[0] == -1;
                lastCC[0] = cc;
                mapGrid.clearAllChildren();
                fillMapCells(mapGrid, coreX, coreZ, canClaim);
                if (!firstTick) refreshChunkInfoPanel(coreX, coreZ, canClaim);
            }
        });

        chunkInfoContainer = new UIElement();
        chunkInfoContainer.layout(l -> l.width(162).flexDirection(FlexDirection.COLUMN).gapAll(4));
        refreshChunkInfoPanel(coreX, coreZ, canClaim);

        mapRow.addChildren(mapGrid, chunkInfoContainer);
        territoryContentArea.addChildren(mapRow);
    }

    /**
     * Swaps the territory content area to show the outpost list.
     */
    @OnlyIn(Dist.CLIENT)
    void showOutpostList() {
        if (territoryContentArea == null) return;
        showingOutpostList = true;
        territoryContentArea.clearAllChildren();

        // Teleport confirmation view
        if (outpostTpPending != null) {
            var item = outpostTpPending;
            long cost = outpostTpCostRef.get();
            String costStr = Currency.format(cost);
            territoryContentArea.addChildren(
                    new Label().setText("§d§l⤏ Teleport to Outpost"),
                    new Label().setText("§7Location: §fX:" + item.pos().getX() + " Y:" + item.pos().getY() + " Z:" + item.pos().getZ()),
                    new Label().setText("§7This will cost §e" + costStr + " §7(physical coins from your inventory)."),
                    new Label().setText("§8You must have sufficient coins on you to confirm.")
            );
            var btnRow = new UIElement();
            btnRow.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(8).paddingTop(6));
            btnRow.addChildren(
                    new Button().setText("§a✔ Confirm Teleport")
                            .setOnClick(e -> {
                                PacketDistributor.sendToServer(
                                        new OutpostActionPacket(
                                                OutpostActionPacket.Action.TELEPORT,
                                                item.id()));
                                outpostTpPending = null;
                                Minecraft.getInstance().setScreen(null);
                            })
                            .layout(l -> l.flex(1)),
                    new Button().setText("§c✘ Cancel")
                            .setOnClick(e -> { outpostTpPending = null; showOutpostList(); })
                            .layout(l -> l.width(80))
            );
            territoryContentArea.addChildren(btnRow);
            return;
        }

        var list = outpostListRef.get();

        if (list.isEmpty()) {
            territoryContentArea.addChildren(
                    new Label().setText("§7Your faction has no outposts."),
                    new Label().setText("§8Place an Outpost item to create one.")
            );
            return;
        }

        // Column header
        var header = new UIElement();
        header.layout(l -> l.flexDirection(FlexDirection.ROW).height(14).width(396).paddingHorizontal(2));
        header.addChildren(
                new Label().setText("§8Coordinates").layout(l -> l.width(130)),
                new Label().setText("§8Dimension").layout(l -> l.width(80)),
                new Label().setText("§8Status").layout(l -> l.width(80)),
                new Label().setText("§8Capture").layout(l -> l.flex(1))
        );
        territoryContentArea.addChildren(header);

        // Rows
        for (var item : list) {
            var row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.COLUMN).width(396).paddingHorizontal(2).paddingBottom(4));

            // Main info row
            var infoRow = new UIElement();
            infoRow.layout(l -> l.flexDirection(FlexDirection.ROW).height(12).width(396));

            String coordStr = "§f" + item.name() + " §8" + item.pos().getX() + ", " + item.pos().getY() + ", " + item.pos().getZ();
            String dimStr   = FactionTableMenu.simplifyDim(item.dimension());
            String statusStr;
            if (item.disintegrating())                         statusStr = "§c⚠ Degrading";
            else if (!item.capturingFactionName().isEmpty())   statusStr = "§c⚔ Under attack";
            else if (item.unsupplied())                        statusStr = "§6⚠ Unsupplied";
            else                                               statusStr = "§a● Active";

            infoRow.addChildren(
                    new Label().setText(coordStr).layout(l -> l.width(130)),
                    new Label().setText("§7" + dimStr).layout(l -> l.width(80)),
                    new Label().setText(statusStr).layout(l -> l.width(80))
            );

            // Teleport button (only if not disintegrating)
            if (!item.disintegrating()) {
                final var fItem = item;
                long tpCost = outpostTpCostRef.get();
                var tpBtn = new Button().setText("§d⤏")
                        .setOnClick(e -> { outpostTpPending = fItem; showOutpostList(); });
                tpBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev ->
                        ev.hoverTooltips = HoverTooltips.empty().append(
                                Component.literal("§d⤏ Teleport to Outpost"),
                                Component.literal("§7Cost: §e" + Currency.format(tpCost) + " §7(physical coins)")));
                tpBtn.layout(l -> l.width(20).height(14));
                infoRow.addChildren(tpBtn);
            }

            // Flag health (only while damaged): the fields carry hits taken and max HP
            if (!item.capturingFactionName().isEmpty() && item.captureTimeSeconds() > 0) {
                int maxHp = (int) item.captureTimeSeconds();
                int hp = maxHp - (int) item.captureProgress();
                String capInfo = "§c" + item.capturingFactionName() + " §8— flag §e" + hp + "§7/" + maxHp + " HP";
                infoRow.addChildren(new Label().setText(capInfo).layout(l -> l.flex(1)));
            } else {
                infoRow.addChildren(new Label().setText("").layout(l -> l.flex(1)));
            }

            row.addChildren(infoRow);
            // Thin divider
            row.addChildren(new Label().setText("§8─────────────────────────────────────────────────").layout(l -> l.height(8)));
            territoryContentArea.addChildren(row);
        }
    }

    /** Rebuilds the right-side chunk info panel based on the currently selected chunk. */
    @OnlyIn(Dist.CLIENT)
    private void refreshChunkInfoPanel(int coreX, int coreZ, boolean canClaim) {
        if (chunkInfoContainer == null) return;
        chunkInfoContainer.clearAllChildren();
        chunkInfoContainer.addChildren(new Label().setText("§8─── Chunk Info ───"));

        if (selectedChunkX == Integer.MIN_VALUE) {
            chunkInfoContainer.addChildren(
                new Label().setText("§8Left-click a chunk"),
                new Label().setText("§8to inspect it.")
            );
            return;
        }

        chunkInfoContainer.addChildren(
            new Label().setText("§7X: §f" + selectedChunkX + "  Z: §f" + selectedChunkZ)
        );

        Faction myFaction = menu.factionRef.get();
        boolean isCore  = selectedChunkX == coreX && selectedChunkZ == coreZ;
        boolean isMine  = myFaction != null && myFaction.hasClaim(selectedChunkX, selectedChunkZ, menu.tableDim);
        boolean isOther = menu.otherClaimedChunks.get().contains(selectedChunkX + "," + selectedChunkZ + "," + menu.tableDim);

        if (isCore) {
            chunkInfoContainer.addChildren(
                new Label().setText("§6■ Core chunk"),
                new Label().setText("§7Your faction base.")
            );
            LandClaim coreClaim = myFaction != null ? myFaction.getClaim(selectedChunkX, selectedChunkZ, menu.tableDim) : null;
            if (coreClaim != null) addChunkloadSection(coreClaim, canClaim);
            return;
        }

        if (isMine) {
            chunkInfoContainer.addChildren(new Label().setText("§9■ Owned by you"));
            // Show protection status based on vault balance ref (synced from server)
            if (menu.factionVaultRef.get() <= 0) {
                chunkInfoContainer.addChildren(new Label().setText("§c⚠ UNPROTECTED"));
                chunkInfoContainer.addChildren(new Label().setText("§cDeposit coins to restore."));
            } else {
                chunkInfoContainer.addChildren(new Label().setText("§a✓ Protected"));
            }
            LandClaim ownedClaim = myFaction.getClaim(selectedChunkX, selectedChunkZ, menu.tableDim);
            if (ownedClaim != null) addChunkloadSection(ownedClaim, canClaim);
            if (canClaim) {
                int fSelX = selectedChunkX, fSelZ = selectedChunkZ;
                var unclaimBtn = new Button().setText("§c▶ Unclaim")
                    .setOnClick(e -> {
                        PacketDistributor.sendToServer(new UnclaimChunkPacket(fSelX, fSelZ, menu.tableDim));
                        selectedChunkX = Integer.MIN_VALUE; selectedChunkZ = Integer.MIN_VALUE;
                        refreshChunkInfoPanel(coreX, coreZ, canClaim);
                        if (mapGrid != null) { mapGrid.clearAllChildren(); fillMapCells(mapGrid, coreX, coreZ, canClaim); }
                    });
                unclaimBtn.layout(l -> l.width(130).height(18));
                chunkInfoContainer.addChildren(unclaimBtn);
            }
            return;
        }

        if (isOther) {
            chunkInfoContainer.addChildren(
                new Label().setText("§c■ Enemy territory"),
                new Label().setText("§7Owned by another"),
                new Label().setText("§7faction.")
            );
            return;
        }

        // Free chunk, check adjacency
        boolean adj = false;
        if (myFaction != null) {
            adj = (Math.abs(selectedChunkX - coreX) == 1 && selectedChunkZ == coreZ)
                || (selectedChunkX == coreX && Math.abs(selectedChunkZ - coreZ) == 1)
                || (selectedChunkX == coreX && selectedChunkZ == coreZ);
            if (!adj) {
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    if (myFaction.hasClaim(selectedChunkX + d[0], selectedChunkZ + d[1], menu.tableDim)) {
                        adj = true; break;
                    }
                }
            }
        }

        if (!canClaim || !adj) {
            chunkInfoContainer.addChildren(new Label().setText("§8■ Free land"));
            if (!adj) {
                chunkInfoContainer.addChildren(
                    new Label().setText("§cNot adjacent to"),
                    new Label().setText("§cyour territory.")
                );
            } else {
                chunkInfoContainer.addChildren(new Label().setText("§7Requires officer+"));
            }
            return;
        }

        // Claimable, show deed cost
        int claimCount = myFaction != null ? myFaction.getLandClaims().size() : 0;
        long baseCost  = SyncServerConfigPacket.get(Config.CLAIM_COST_COPPER);
        long cost      = EconomyManager.computeClaimCost(baseCost, claimCount, menu.claimRateRef.get());
        long vault     = menu.factionVaultRef.get();
        boolean canAfford = vault >= cost;

        chunkInfoContainer.addChildren(
            new Label().setText("§a■ Claimable"),
            new Label().setText("§7Deed: §e" + Currency.format(cost)),
            new Label().setText("§7Vault: §" + (canAfford ? "a" : "c") + Currency.format(vault))
        );
        int fSelX = selectedChunkX, fSelZ = selectedChunkZ;
        var purchaseBtn = new Button()
            .setText(canAfford ? "§a▶ Purchase Deed" : "§c✗ Can't afford")
            .setOnClick(e -> {
                if (!canAfford) return;
                PacketDistributor.sendToServer(new ClaimChunkPacket(fSelX, fSelZ, menu.tableDim));
                selectedChunkX = Integer.MIN_VALUE; selectedChunkZ = Integer.MIN_VALUE;
                refreshChunkInfoPanel(coreX, coreZ, canClaim);
                if (mapGrid != null) { mapGrid.clearAllChildren(); fillMapCells(mapGrid, coreX, coreZ, canClaim); }
            });
        purchaseBtn.layout(l -> l.width(130).height(18));
        chunkInfoContainer.addChildren(purchaseBtn);
    }

    /** Chunkloading deed purchase / on-off toggle for one of the faction's own chunks. */
    @OnlyIn(Dist.CLIENT)
    private void addChunkloadSection(LandClaim claim, boolean canManage) {
        int cx = claim.chunkX(), cz = claim.chunkZ();
        chunkInfoContainer.addChildren(new Label().setText("§8── Chunkloading ──"));
        long vault = menu.factionVaultRef.get();
        if (!claim.chunkloadDeed()) {
            long cost = claim.chunkloadDeedCost();
            boolean canAfford = vault >= cost;
            chunkInfoContainer.addChildren(
                new Label().setText("§7Keeps this chunk loaded"),
                new Label().setText("§7when nobody is near."),
                new Label().setText("§7Deed: §e" + Currency.format(cost)),
                new Label().setText("§7Upkeep: §e2x §7while on"));
            if (!canManage) {
                chunkInfoContainer.addChildren(new Label().setText("§7Requires officer+"));
                return;
            }
            var buy = new Button()
                .setText(canAfford ? "§a▶ Buy Chunkload Deed" : "§c✗ Can't afford")
                .setOnClick(e -> {
                    if (!canAfford) return;
                    PacketDistributor.sendToServer(new ChunkloadActionPacket(
                            ChunkloadActionPacket.Action.PURCHASE, cx, cz, menu.tableDim, true));
                });
            buy.layout(l -> l.width(150).height(18));
            buy.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                    Component.literal("§eChunkloading deed"),
                    Component.literal("§7One-time cost: 2x this chunk's price."),
                    Component.literal("§7While on, this chunk's upkeep is doubled."),
                    Component.literal("§8Turn it off any time; turning it back on is free.")));
            chunkInfoContainer.addChildren(buy);
            return;
        }

        if (canManage) {
            var row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(18));
            var toggle = UiKit.toggle().setOn(claim.chunkloadEnabled()).setOnToggleChanged(on ->
                    PacketDistributor.sendToServer(new ChunkloadActionPacket(
                            ChunkloadActionPacket.Action.SET_ENABLED, cx, cz, menu.tableDim, on)));
            toggle.layout(l -> l.width(40));
            row.addChildren(toggle, new Label().setText(claim.chunkloadEnabled() ? "§fEnabled" : "§7Disabled"));
            chunkInfoContainer.addChildren(row);
        } else {
            chunkInfoContainer.addChildren(
                new Label().setText(claim.chunkloadEnabled() ? "§fEnabled" : "§7Disabled"),
                new Label().setText("§8Officers can change this."));
        }

        if (!claim.chunkloadEnabled()) {
            chunkInfoContainer.addChildren(new Label().setText("§7Off: normal upkeep"));
        } else if (vault <= 0) {
            chunkInfoContainer.addChildren(new Label().setText("§ePaused: vault empty"));
        } else {
            chunkInfoContainer.addChildren(new Label().setText("§d⚡ Chunk stays loaded"));
        }
        chunkInfoContainer.addChildren(new Label().setText("§7Upkeep: §e"
                + Currency.format(claim.effectiveDailyCost()) + "§7/day"));
    }

    /** Chunk deed cost: starts at {@code baseCost} and grows ×1.4 for each chunk already owned. */
    private static long computeClaimCost(long baseCost, int ownedChunks) {
        return EconomyManager.computeClaimCost(baseCost, ownedChunks, 1.4);
    }

    private Button mkNavBtn(String text, int width, UIEventListener onClick) {
        var btn = new Button().setText(text).setOnClick(onClick);
        if (width > 0) btn.layout(l -> l.width(width));
        else           btn.layout(l -> l.flex(1));
        return btn;
    }

    // Map cell grid

    /**
     * Populates the mapGrid with MAP_SIZE × MAP_SIZE Button cells.
     *
     * Each cell background is the actual Minecraft MapColor of the chunk's top
     * surface, blended with the faction-claim overlay (core=gold, mine=blue,
     * other=red, free=terrain only).  The default "Button" text is cleared to
     * avoid visual noise; only the player's current chunk shows "P".
     */
    @OnlyIn(Dist.CLIENT)
    void fillMapCells(UIElement grid, int coreX, int coreZ, boolean canClaim) {
        var   mc    = Minecraft.getInstance();
        Level level = mc.level;
        int   viewCX = coreX + mapOffsetX;
        int   viewCZ = coreZ + mapOffsetZ;
        lastCoreX = coreX; lastCoreZ = coreZ; lastCanClaim = canClaim;
        // Ask for the faction's scanned seams round here when the view has moved a few chunks
        if (Math.abs(viewCX - seamAskX) > 3 || Math.abs(viewCZ - seamAskZ) > 3) {
            seamAskX = viewCX; seamAskZ = viewCZ;
            PacketDistributor.sendToServer(new com.admin82.factions.network.packet.SeamMemoryPacket(viewCX, viewCZ, java.util.List.of()));
        }

        Faction     myFaction = menu.factionRef.get();
        Set<String> others    = menu.otherClaimedChunks.get();

        // O(1) claim lookup: build HashSet once instead of O(n) stream per cell
        final Set<String> myClaims = new HashSet<>();
        final Set<String> myChunkloaded = new HashSet<>();
        if (myFaction != null) {
            for (LandClaim c : myFaction.getLandClaims()) {
                myClaims.add(c.chunkX() + "," + c.chunkZ() + "," + c.dimension());
                if (c.isChunkloading()) myChunkloaded.add(c.chunkX() + "," + c.chunkZ() + "," + c.dimension());
            }
        }

        // Precompute claimable set: one pass over claims instead of 4 lookups per cell
        // A chunk is claimable if: free, not other-faction, adjacent to core or any own claim.
        final Set<String> claimableSet = new HashSet<>();
        if (canClaim && myFaction != null) {
            final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1}};
            // Adjacent to core chunk
            for (int[] d : DIRS) {
                String key = (coreX+d[0]) + "," + (coreZ+d[1]) + "," + menu.tableDim;
                if (!myClaims.contains(key) && !others.contains(key)) claimableSet.add(key);
            }
            // Adjacent to own claims
            for (LandClaim c : myFaction.getLandClaims()) {
                if (!c.dimension().toString().equals(menu.tableDim)) continue;
                for (int[] d : DIRS) {
                    String key = (c.chunkX()+d[0]) + "," + (c.chunkZ()+d[1]) + "," + menu.tableDim;
                    if (!myClaims.contains(key) && !others.contains(key)) claimableSet.add(key);
                }
            }
        }

        int cellSize = Math.max(8, GRID_PX / mapViewSize);
        int halfView = mapViewSize / 2;
        final int fcs = cellSize;
        int actualSize = fcs * mapViewSize; // exact rendered px, may be < GRID_PX

        // Resize grid element so no unwritten grey strips show at edges
        if (mapGrid != null) {
            final int as = actualSize;
            mapGrid.layout(l -> l.width(as).height(as).alignSelf(YogaAlign.CENTER));
        }

        // Step 1: per-pixel terrain render into the NativeImage texture
        if (terrainMapTexture != null) {
            terrainMapTexture.clear(); // erase stale pixels from previous zoom/pan
            for (int row = 0; row < mapViewSize; row++) {
                for (int col = 0; col < mapViewSize; col++) {
                    int cx = viewCX - halfView + col;
                    int cz = viewCZ - halfView + row;
                    boolean loaded = level != null && level.hasChunk(cx, cz);
                    for (int py = 0; py < fcs; py++) {
                        for (int px = 0; px < fcs; px++) {
                            int imgX = col * fcs + px;
                            int imgY = row * fcs + py;
                            if (imgX >= GRID_PX || imgY >= GRID_PX) continue;
                            int rgb = loaded
                                    ? TerrainColors.CACHE.color(level,
                                            cx * 16 + (px * 16) / fcs,
                                            cz * 16 + (py * 16) / fcs)
                                    : 0x404040;
                            terrainMapTexture.setPixel(imgX, imgY, rgb);
                        }
                    }
                }
            }
            // chunk grid lines
            for (int i = 0; i <= mapViewSize; i++) {
                int pos = i * fcs;
                if (pos < GRID_PX) {
                    terrainMapTexture.drawHLine(pos, 0, actualSize - 1);
                    terrainMapTexture.drawVLine(pos, 0, actualSize - 1);
                }
            }
            // claim borders, drawn into NativeImage so adjacent claims never stack visually
            int borderPx = Math.max(1, fcs / 7);
            for (int row = 0; row < mapViewSize; row++) {
                for (int col = 0; col < mapViewSize; col++) {
                    int cx = viewCX - halfView + col;
                    int cz = viewCZ - halfView + row;
                    // isCore: ONLY the viewer's own faction table chunk (never an enemy's).
                    // isMine / isOther are mutually exclusive; isCore implies isMine.
                    // Enemy table chunks always arrive in `others` and render as plain red , 
                    // no special color is ever applied to reveal an enemy's table location.
                    boolean isCore2  = cx == coreX && cz == coreZ;
                    boolean isMine2  = myClaims.contains(cx + "," + cz + "," + menu.tableDim);
                    boolean isOther2 = others.contains(cx + "," + cz + "," + menu.tableDim);
                    if (isCore2 || isMine2 || isOther2) {
                        // Core = gold only for the VIEWER's own table; everything in `others`
                        // is uniform red regardless of whether it is another faction's table.
                        int rgb = isCore2 ? 0xffcc00 : isMine2 ? 0x33bbff : 0xff4444;
                        terrainMapTexture.drawChunkBorder(col * fcs, row * fcs, fcs, rgb, borderPx);
                    }
                    if (myChunkloaded.contains(cx + "," + cz + "," + menu.tableDim)) {
                        // Chunkloaded: purple inner outline inside the ownership border
                        terrainMapTexture.drawChunkBorder(col * fcs + borderPx, row * fcs + borderPx,
                                fcs - borderPx * 2, 0xcc55ff, Math.max(1, borderPx));
                    }
                }
            }
            // Selected chunk, white outline drawn on top of all other borders
            if (selectedChunkX != Integer.MIN_VALUE) {
                int selC = selectedChunkX - (viewCX - halfView);
                int selR = selectedChunkZ - (viewCZ - halfView);
                if (selC >= 0 && selC < mapViewSize && selR >= 0 && selR < mapViewSize) {
                    terrainMapTexture.drawChunkBorder(selC * fcs, selR * fcs, fcs, 0xFFFFFF, borderPx + 1);
                }
            }
            terrainMapTexture.upload();
        }

        // Step 2: transparent cells for click interaction only
        for (int row = 0; row < mapViewSize; row++) {
            var rowEl = new UIElement();
            rowEl.layout(l -> l.flexDirection(FlexDirection.ROW).height(fcs));

            for (int col = 0; col < mapViewSize; col++) {
                int cx = viewCX - halfView + col;
                int cz = viewCZ - halfView + row;

                boolean isCore  = cx == coreX && cz == coreZ;
                boolean isMine  = myClaims.contains(cx + "," + cz + "," + menu.tableDim);
                boolean isOther = others.contains(cx + "," + cz + "," + menu.tableDim);

                // O(1) claimable lookup (precomputed above)
                boolean isClaimable = !isMine && !isOther && !isCore
                        && claimableSet.contains(cx + "," + cz + "," + menu.tableDim);

                // Plain UIElement, no background; terrain + borders show through
                var cell = new UIElement();
                cell.layout(l -> l.width(fcs).height(fcs).alignItems(YogaAlign.CENTER).justifyContent(org.appliedenergistics.yoga.YogaJustify.CENTER));
                // A seam the faction has scanned: its ore on the chunk, and the seam's details on hover
                var seam = seams.get(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
                if (seam != null && seam.hasSeam()) {
                    if (seam.minable() && fcs >= 10) {
                        var icon = new com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot().setItem(com.admin82.factions.menu.SeamMap.ore(seam.ore()));
                        icon.style(st -> st.background(com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY));
                        int icon16 = Math.min(16, fcs - 4);
                        icon.layout(l -> l.width(icon16).height(icon16));
                        cell.addChildren(icon);
                    }
                    var seamTip = com.admin82.factions.menu.SeamMap.tooltip(seam, false).toArray(Component[]::new);
                    cell.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(seamTip));
                }

                // Claim / unclaim via MOUSE_UP + drag-distance guard
                final boolean fIsMine = isMine, fIsOther = isOther, fIsClaimable = isClaimable;
                int fCx = cx, fCz = cz;
                // Left-click selects the chunk and shows info/actions in the right panel
                UiKit.onClick(cell, ev -> {
                    if (mapDragDist > 5f) return;
                    if (selectedChunkX == fCx && selectedChunkZ == fCz) {
                        selectedChunkX = Integer.MIN_VALUE;
                        selectedChunkZ = Integer.MIN_VALUE;
                    } else {
                        selectedChunkX = fCx;
                        selectedChunkZ = fCz;
                    }
                    refreshChunkInfoPanel(coreX, coreZ, canClaim);
                    if (mapGrid != null) { mapGrid.clearAllChildren(); fillMapCells(mapGrid, coreX, coreZ, canClaim); }
                });
                rowEl.addChildren(cell);
            }
            grid.addChildren(rowEl);
        }
    }
}
