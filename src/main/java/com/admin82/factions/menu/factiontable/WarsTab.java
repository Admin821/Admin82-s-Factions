package com.admin82.factions.menu.factiontable;

import com.admin82.factions.Config;
import com.admin82.factions.faction.*;
import com.admin82.factions.map.MapColorCache;
import com.admin82.factions.map.TerrainColors;
import com.admin82.factions.map.TerrainMapTexture;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.network.packet.*;
import com.admin82.factions.war.WarDemand;
import com.admin82.factions.war.WarType;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/** The Wars tab: the war list, declaring war, territory picks and negotiations. */
final class WarsTab {

    final FactionTableMenu menu;

    WarsTab(FactionTableMenu menu) {
        this.menu = menu;
    }

    // Wars tab state
    enum WarsSubView { LIST, SELECT_WAR_TYPE, CONFIRM_ALL_OUT, SELECT_TERRITORY, SELECT_ATTACKERS, NEGOTIATIONS, COMPOSE_DEMAND }
    WarsSubView  warsSubView           = WarsSubView.LIST;
    @Nullable private FactionSummary warsTarget = null;
    private final Set<UUID> warsSelectedAttackers = new HashSet<>();
    @Nullable UIElement warsPanel = null;
    @Nullable private WarType            warsSelectedType     = null;
    @Nullable FactionSummary     warsNegotiateTarget  = null;
    final AtomicReference<List<WarDemand>> warDemandsRef
            = new AtomicReference<>(List.of());
    // Territory-war selection state
    final AtomicReference<List<String>> enemyClaimKeysRef
            = new AtomicReference<>(List.of());
    /** Chunk keys ("cx,cz,dim") the player has selected as territorial prizes. */
    private final Set<String> warsSelectedChunks = new LinkedHashSet<>();
    int    enemyCoreCX  = 0;
    int    enemyCoreCZ  = 0;
    String enemyCoreDim = "";
    int    warMapOffsetX  = 0;
    int    warMapOffsetZ  = 0;
    private int    warMapViewSize = 9;
    private boolean warMapDragging = false;
    private float   warMapDragDist = 0f;
    private float   warMapLastX    = 0f;
    private float   warMapLastZ    = 0f;
    private float   warMapAccumX   = 0f;
    private float   warMapAccumZ   = 0f;
    @Nullable private UIElement         warTerrainMapGrid;
    @Nullable private TerrainMapTexture warTerrainTexture;
    @Nullable private String            warInspectedChunkKey;
    @Nullable private UIElement         warChunkInfoPanel;

    @Nullable UIElement warsListArea;

    private static final int WARS_ROWS = 8;

    // Wars

    UIElement buildWarsPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(8).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        warsPanel = panel;
        fillWarsPanel(player);
        return panel;
    }

    void fillWarsPanel(Player player) {
        if (warsPanel == null) return;
        warsPanel.clearAllChildren();
        switch (warsSubView) {
            case SELECT_WAR_TYPE  -> buildWarTypeSelectView(player);
            case CONFIRM_ALL_OUT  -> buildAllOutConfirmView(player);
            case SELECT_TERRITORY -> buildTerritorySelectView(player);
            case SELECT_ATTACKERS -> buildAttackerSelectView(player);
            case NEGOTIATIONS     -> menu.negotiations.buildNegotiationsView(player);
            case COMPOSE_DEMAND   -> menu.negotiations.buildComposeDemandView(player);
            default               -> buildWarsListView(player);
        }
    }

    private void buildWarsListView(Player player) {
        Faction myFaction = menu.factionRef.get();
        boolean canWar = menu.canWageWar(player);
        List<WarEntry> wars = myFaction != null ? myFaction.getWars() : List.of();

        // Header
        var header = new UIElement();
        header.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).height(14));
        header.addChildren(new Label().setText("§6§l⚔ Wars").layout(l -> l.flex(1)),
                UiKit.rightLabel(wars.isEmpty() ? "§a● At peace" : "§c● " + wars.size() + " active war" + (wars.size() == 1 ? "" : "s")));
        warsPanel.addChildren(header);

        // Active wars card
        var active = FactionTableMenu.card();
        active.addChildren(new Label().setText("§e§lActive Wars"));
        if (wars.isEmpty()) {
            active.addChildren(new Label().setText("§7You are not at war with anyone."));
        } else {
            for (WarEntry we : wars) {
                var wr = UiKit.background(new UIElement(), UiKit.ROW);
                wr.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(6)
                        .height(24).paddingHorizontal(4));
                String type = we.warType() != null ? we.warType().displayName : "Faction Fight";
                var info = new UIElement();
                info.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN));
                info.addChildren(new Label().setText("§c⚔ §f§l" + we.targetFactionName()),
                        new Label().setText("§8" + type + " · started " + warAgo(we.startTime())));
                wr.addChildren(info);
                { // anyone in the faction can follow negotiations; only officers act
                    FactionSummary fts = menu.allFactions.get().stream()
                            .filter(f -> f.id().equals(we.targetFactionId()))
                            .findFirst()
                            .orElseGet(() -> new FactionSummary(we.targetFactionId(), we.targetFactionName(), 0, 0, 0L));
                    var negotiateBtn = new Button().setText("§e⚑ Negotiate").setOnClick(e -> {
                        warsNegotiateTarget = fts;
                        menu.negotiations.confirmDemandId = null;
                        menu.negotiations.confirmWhitePeace = false;
                        warsSubView = WarsSubView.NEGOTIATIONS;
                        PacketDistributor.sendToServer(new OpenNegotiationsPacket(fts.id()));
                        fillWarsPanel(player);
                    });
                    negotiateBtn.layout(l -> l.width(80).height(16));
                    negotiateBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                            Component.literal("§e⚑ Wartime Negotiations"),
                            Component.literal("§7Send demands to end the war early.")));
                    wr.addChildren(negotiateBtn);
                }
                active.addChildren(wr);
            }
        }
        warsPanel.addChildren(active);

        // Other factions card
        var others = FactionTableMenu.card();
        others.layout(l -> l.flex(1));
        var othersHeader = new UIElement();
        othersHeader.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).height(12));
        othersHeader.addChildren(new Label().setText("§e§lOther Factions").layout(l -> l.flex(1)),
                UiKit.rightLabel("§8" + menu.allFactions.get().size() + " total"));
        others.addChildren(othersHeader);
        if (!canWar) others.addChildren(new Label().setText("§8Only officers and above can declare war."));

        warsListArea = new UIElement();
        warsListArea.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(2));
        var pageLabel = new Label();
        pageLabel.lss("horizontal-align", "center");
        pageLabel.layout(l -> l.flex(1));
        Runnable refresh = () -> {
            fillWarsFactionList(warsListArea, player);
            int total = menu.allFactions.get().size();
            pageLabel.setText(total == 0 ? "" : "§8" + (menu.allFactionsScroll + 1) + "–"
                    + Math.min(total, menu.allFactionsScroll + WARS_ROWS) + " of " + total);
        };
        warsListArea.addEventListener(UIEvents.MOUSE_WHEEL, ev -> {
            int max = Math.max(0, menu.allFactions.get().size() - WARS_ROWS);
            menu.allFactionsScroll = Math.max(0, Math.min(max, menu.allFactionsScroll + (ev.deltaY > 0 ? -1 : 1)));
            refresh.run();
        });
        others.addChildren(warsListArea);

        if (menu.allFactions.get().size() > WARS_ROWS) {
            var scrollRow = new UIElement();
            scrollRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(16));
            scrollRow.addChildren(
                    new Button().setText("▲").setOnClick(e -> {
                        menu.allFactionsScroll = Math.max(0, menu.allFactionsScroll - WARS_ROWS);
                        refresh.run();
                    }).layout(l -> l.width(20).height(14)),
                    pageLabel,
                    new Button().setText("▼").setOnClick(e -> {
                        menu.allFactionsScroll += WARS_ROWS;
                        refresh.run();
                    }).layout(l -> l.width(20).height(14)));
            others.addChildren(scrollRow);
        }
        refresh.run();
        warsPanel.addChildren(others);
    }

    static String warAgo(long startMillis) {
        long minutes = Math.max(0, (System.currentTimeMillis() - startMillis) / 60_000L);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    void fillWarsFactionList(UIElement area, Player player) {
        area.clearAllChildren();
        Faction myFaction = menu.factionRef.get();
        List<FactionSummary> list = menu.allFactions.get();
        if (list.isEmpty()) {
            area.addChildren(new Label().setText("§7No other factions exist yet."));
            return;
        }
        boolean canWar = menu.canWageWar(player);
        menu.allFactionsScroll = Math.max(0, Math.min(menu.allFactionsScroll, list.size() - WARS_ROWS));
        for (int i = 0; i < WARS_ROWS; i++) {
            int idx = i + menu.allFactionsScroll;
            if (idx >= list.size()) break;
            FactionSummary s = list.get(idx);
            boolean atWar = myFaction != null && myFaction.getWars().stream()
                    .anyMatch(w -> w.targetFactionId().equals(s.id()));
            var row = UiKit.background(new UIElement(), UiKit.ROW);
            row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(6)
                    .height(20).paddingHorizontal(4));
            row.addChildren(
                    new Label().setText((atWar ? "§c" : "§f") + s.name()).layout(l -> l.flex(1)),
                    new Label().setText("§7" + s.memberCount() + " member" + (s.memberCount() == 1 ? "" : "s")).layout(l -> l.width(64)),
                    new Label().setText((s.onlineCount() > 0 ? "§a● " : "§8● ") + s.onlineCount() + " online").layout(l -> l.width(58)));
            var pact = menu.alliances.allianceWith(s.id());
            if (pact != null && pact.state() == SyncAlliancesPacket.State.ALLY) {
                row.addChildren(new Label().setText("§a🤝 Allied").layout(l -> l.width(72)));
            } else if (pact != null && pact.state() == SyncAlliancesPacket.State.COOLDOWN) {
                var truce = new Label().setText("§6⏳ " + AllianceManager.formatDuration(pact.time()));
                truce.layout(l -> l.width(72));
                NegotiationsView.tooltip(truce, "§7Your alliance ended recently.", "§7You can't declare war until the cooldown runs out.");
                row.addChildren(truce);
            } else if (atWar) {
                row.addChildren(new Label().setText("§c§l⚔ At war").layout(l -> l.width(72)));
            } else if (canWar) {
                var wageBtn = new Button().setText("§c⚔ Declare").setOnClick(e -> {
                    warsTarget = s;
                    warsSelectedType = null;
                    warsSubView = WarsSubView.SELECT_WAR_TYPE;
                    fillWarsPanel(player);
                });
                wageBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                        Component.literal("§c⚔ Declare war on §e" + s.name()),
                        Component.literal("§7Members: §e" + s.memberCount() + "  §7Online: §a" + s.onlineCount()),
                        Component.literal("§7Attackers get §c" + SyncServerConfigPacket.get(Config.WAR_ATTACKER_LIVES) + " lives §7each.")));
                wageBtn.layout(l -> l.width(72).height(16));
                row.addChildren(wageBtn);
            } else {
                row.addChildren(new Label().setText("§8—").layout(l -> l.width(72)));
            }
            area.addChildren(row);
        }
    }

    private void buildAttackerSelectView(Player player) {
        Faction myFaction = menu.factionRef.get();
        int selCount = warsSelectedAttackers.size();

        warsPanel.addChildren(
                new Label().setText("§6§l⚔ Select War Party"),
                new Label().setText("§7War type: " + (warsSelectedType != null ? warsSelectedType.displayName : "§eFaction Fight")),
                new Label().setText("§7Declaring war on: §c"
                        + (warsTarget != null ? warsTarget.name() : "?")
                        + "  §8— click a member card to toggle selection")
        );

        // Member cards, 2 per row, showing player head + name + role
        if (myFaction != null) {
            List<FactionMember> members = myFaction.getMembers();
            for (int i = 0; i < members.size(); i += 2) {
                var row = new UIElement();
                row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).paddingBottom(4));
                for (int j = i; j < Math.min(i + 2, members.size()); j++) {
                    FactionMember fm = members.get(j);
                    UUID uid   = fm.getUuid();
                    boolean sel = warsSelectedAttackers.contains(uid);

                    // Build a player-head ItemStack that resolves the skin client-side
                    var skull = new ItemStack(
                            Items.PLAYER_HEAD);
                    skull.set(DataComponents.PROFILE,
                            new ResolvableProfile(
                                    Optional.of(fm.getPlayerName()),
                                    Optional.of(uid),
                                    new PropertyMap()));

                    var headSlot = new ItemSlot().setItem(skull);
                    headSlot.layout(l -> l.width(32).height(32).alignSelf(YogaAlign.CENTER));

                    var card = new UIElement();
                    card.layout(l -> l.width(196).paddingAll(6).gapAll(3)
                            .flexDirection(YogaFlexDirection.COLUMN));
                    UiKit.background(card, sel ? 0x8822AA22 : UiKit.CARD);

                    card.addChildren(
                            headSlot,
                            new Label().setText("§f" + fm.getPlayerName())
                                    .lss("horizontal-align", "center"),
                            new Label().setText(FactionTableMenu.roleColor(fm.getRole())
                                    + FactionTableMenu.capitalize(fm.getRole().getId()))
                                    .lss("horizontal-align", "center"),
                            new Label().setText(sel ? "§a✔ Selected" : "§8Click to add")
                                    .lss("horizontal-align", "center")
                    );
                    UiKit.onClick(card, ev -> {
                        if (warsSelectedAttackers.contains(uid)) warsSelectedAttackers.remove(uid);
                        else warsSelectedAttackers.add(uid);
                        fillWarsPanel(player);
                    });
                    row.addChildren(card);
                }
                warsPanel.addChildren(row);
            }
        }

        var btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).paddingTop(6));
        btnRow.addChildren(
                new Button().setText(selCount > 0
                                ? "§a⚔ Start War §7(" + selCount + " fighter" + (selCount == 1 ? "" : "s") + ")"
                                : "§8Select at least one fighter")
                        .setOnClick(e -> {
                            if (warsTarget != null && !warsSelectedAttackers.isEmpty()) {
                                PacketDistributor.sendToServer(
                                        new WageWarPacket(
                                                warsTarget.id(), new ArrayList<>(warsSelectedAttackers),
                                                warsSelectedType != null ? warsSelectedType : WarType.FIGHT,
                                                new ArrayList<>(warsSelectedChunks)));
                            }
                            warsSubView = WarsSubView.LIST;
                            fillWarsPanel(player);
                        })
                        .layout(l -> l.flex(1)),
                new Button().setText("§c✗ Cancel")
                        .setOnClick(e -> { warsSubView = WarsSubView.LIST; fillWarsPanel(player); })
                        .layout(l -> l.width(80))
        );
        warsPanel.addChildren(btnRow);
    }

    // Terms of War type selection

    private void buildWarTypeSelectView(Player player) {
        warsPanel.addChildren(
                new Label().setText("§6§l⚑ Terms of War"),
                new Label().setText("§7Choose the type of war against §c"
                        + (warsTarget != null ? warsTarget.name() : "?") + "§7:")
        );
        for (WarType type : WarType.values()) {
            final WarType ft = type;
            var btn = new Button()
                    .setText(type.displayName)
                    .setOnClick(e -> {
                        warsSelectedType = ft;
                        if (ft == WarType.TERRITORY) {
                            // Fetch the enemy's claims and show the territory selection map
                            warsSelectedChunks.clear();
                            enemyClaimKeysRef.set(List.of());
                            if (warsTarget != null) {
                                PacketDistributor.sendToServer(
                                        new RequestEnemyClaimsPacket(
                                                warsTarget.id()));
                            }
                            warsSubView = WarsSubView.SELECT_TERRITORY;
                        } else if (ft == WarType.ALL_OUT) {
                            // Show a mandatory confirmation screen before this devastating war
                            warsSubView = WarsSubView.CONFIRM_ALL_OUT;
                        } else {
                            warsSelectedAttackers.clear();
                            warsSelectedAttackers.add(player.getUUID());
                            warsSubView = WarsSubView.SELECT_ATTACKERS;
                        }
                        fillWarsPanel(player);
                    });
            btn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty()
                    .append(Component.literal(ft.displayName))
                    .append(Component.literal(ft.description)));
            btn.layout(l -> l.width(300).height(22).alignSelf(YogaAlign.CENTER));
            warsPanel.addChildren(btn);
        }
        warsPanel.addChildren(
                new Button().setText("§7← Back")
                        .setOnClick(e -> { warsSubView = WarsSubView.LIST; fillWarsPanel(player); })
                        .layout(l -> l.width(80))
        );
    }

    // All Out War, mandatory confirmation

    private void buildAllOutConfirmView(Player player) {
        var target = warsTarget != null ? warsTarget.name() : "?";
        warsPanel.addChildren(
                new Label().setText("§c§l⚠  ALL OUT WAR  ⚠").lss("horizontal-align", "center"),
                new Label().setText("§7Target: §c" + target).lss("horizontal-align", "center")
        );

        var warningBox = new UIElement();
        warningBox.layout(l -> l.width(400).paddingAll(8).gapAll(5).flexDirection(YogaFlexDirection.COLUMN));
        UiKit.background(warningBox, 0x55AA0000);
        warningBox.addChildren(
                new Label().setText("§eThe LOSER of this war — attacker or defender — will:"),
                new Label().setText("§c  ✗  Lose §lALL§r§c claimed territory (transferred to the winner)"),
                new Label().setText("§c  ✗  Have their entire faction vault seized by the winner"),
                new Label().setText("§c  ✗  Have their faction permanently §lDISBANDED"),
                new Label().setText("§8─────────────────────────────────────────────"),
                new Label().setText("§e⚠  Both sides risk total destruction. This cannot be undone.")
        );
        warsPanel.addChildren(warningBox);

        var btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(8).paddingTop(10));
        btnRow.addChildren(
                new Button().setText("§c⚔ Accept — Wage All Out War on " + target)
                        .setOnClick(e -> {
                            warsSelectedAttackers.clear();
                            warsSelectedAttackers.add(player.getUUID());
                            warsSubView = WarsSubView.SELECT_ATTACKERS;
                            fillWarsPanel(player);
                        })
                        .layout(l -> l.flex(1)),
                new Button().setText("§7✗ Decline")
                        .setOnClick(e -> { warsSubView = WarsSubView.SELECT_WAR_TYPE; fillWarsPanel(player); })
                        .layout(l -> l.width(80))
        );
        warsPanel.addChildren(btnRow);
    }

    // Territory-War chunk-selection map

    @OnlyIn(Dist.CLIENT)
    private void buildTerritorySelectView(Player player) {
        if (warsTarget == null) { warsSubView = WarsSubView.SELECT_WAR_TYPE; fillWarsPanel(player); return; }

        int playerCoreX = SectionPos.blockToSectionCoord(menu.tablePos.getX());
        int playerCoreZ = SectionPos.blockToSectionCoord(menu.tablePos.getZ());
        int selCount    = warsSelectedChunks.size();

        warsPanel.addChildren(
                new Label().setText("§6§l⚑ Territory Selection — §c" + warsTarget.name()),
                new Label().bindDataSource(SupplierDataSource.of(() ->
                        Component.literal("§7Selected: §a" + warsSelectedChunks.size()
                                + " chunk" + (warsSelectedChunks.size() == 1 ? "" : "s")
                                ))),
                new Label().setText("§8Click a red chunk to inspect it · double-click to pick it")
        );

        // Map + info panel
        // Always build the map structure so it displays immediately. If enemy-claim
        // data hasn't arrived yet, fillWarMapCells renders blank terrain; the map
        // updates via fillWarsPanel once SyncEnemyClaimsPacket triggers updateEnemyClaims.
        var mapRow = new UIElement();
        mapRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6));

        // Map
        warTerrainMapGrid = new UIElement();
        warTerrainMapGrid.layout(l -> l.width(TerritoryTab.GRID_PX).height(TerritoryTab.GRID_PX).alignSelf(YogaAlign.CENTER));
        if (warTerrainTexture == null)
            warTerrainTexture = new TerrainMapTexture(TerritoryTab.GRID_PX);
        warTerrainMapGrid.style(s -> s.background(warTerrainTexture));
        fillWarMapCells(playerCoreX, playerCoreZ);

            // Drag-to-pan events
            warTerrainMapGrid.addEventListener(UIEvents.MOUSE_DOWN, ev -> {
                warMapDragging = true; warMapDragDist = 0f;
                warMapLastX = ev.x; warMapLastZ = ev.y;
                warMapAccumX = 0f; warMapAccumZ = 0f;
            });
            warTerrainMapGrid.addEventListener(UIEvents.MOUSE_UP, ev -> warMapDragging = false);
            warTerrainMapGrid.addEventListener(UIEvents.MOUSE_MOVE, ev -> {
                if (!warMapDragging) return;
                float dx = warMapLastX - ev.x, dz = warMapLastZ - ev.y;
                warMapLastX = ev.x; warMapLastZ = ev.y;
                warMapDragDist += Math.abs(dx) + Math.abs(dz);
                warMapAccumX += dx; warMapAccumZ += dz;
                boolean moved = false;
                int ppc = Math.max(1, TerritoryTab.GRID_PX / warMapViewSize);
                while (warMapAccumX >=  ppc) { warMapOffsetX++; warMapAccumX -= ppc; moved = true; }
                while (warMapAccumX <= -ppc) { warMapOffsetX--; warMapAccumX += ppc; moved = true; }
                while (warMapAccumZ >=  ppc) { warMapOffsetZ++; warMapAccumZ -= ppc; moved = true; }
                while (warMapAccumZ <= -ppc) { warMapOffsetZ--; warMapAccumZ += ppc; moved = true; }
                if (moved) warMapRedraw.request(() -> fillWarMapCells(playerCoreX, playerCoreZ));
            });
            warTerrainMapGrid.addEventListener(UIEvents.MOUSE_WHEEL, ev -> {
                if      (ev.deltaY > 0) warMapViewSize = Math.max(5,  warMapViewSize - 2);
                else if (ev.deltaY < 0) warMapViewSize = Math.min(17, warMapViewSize + 2);
                fillWarMapCells(playerCoreX, playerCoreZ);
            });
            warTerrainMapGrid.addEventListener(UIEvents.TICK, ev -> {
                warMapRedraw.flush(() -> fillWarMapCells(playerCoreX, playerCoreZ));
                if (warMapDragging) {
                    long win = Minecraft.getInstance().getWindow().getWindow();
                    if (GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                            != GLFW.GLFW_PRESS) {
                        warMapDragging = false; warMapDragDist = 0f;
                    }
                }
            });

            warChunkInfoPanel = new UIElement();
            warChunkInfoPanel.layout(l -> l.width(162).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
            refreshWarChunkInfo(playerCoreX, playerCoreZ);

            mapRow.addChildren(warTerrainMapGrid, warChunkInfoPanel);
            warsPanel.addChildren(mapRow);

        // Legend row
        warsPanel.addChildren(new Label().setText(
                "§c■§r Enemy  §a■§r Selected  §9■§r Yours  §8drag · scroll"));

        // Bottom buttons
        var btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).paddingTop(2));
        btnRow.addChildren(
                warContinueBtn = (Button) new Button()
                        .setOnClick(e -> {
                            if (warsSelectedChunks.isEmpty()) return;
                            warsSelectedAttackers.clear();
                            warsSelectedAttackers.add(player.getUUID());
                            warsSubView = WarsSubView.SELECT_ATTACKERS;
                            fillWarsPanel(player);
                        })
                        .layout(l -> l.flex(1).height(20)),
                new Button().setText("§c✗ Clear")
                        .setOnClick(e -> {
                            warsSelectedChunks.clear();
                            refreshWarChunkInfo(playerCoreX, playerCoreZ);
                            fillWarMapCells(playerCoreX, playerCoreZ);
                        })
                        .layout(l -> l.width(60).height(20)),
                new Button().setText("§7← Back")
                        .setOnClick(e -> { warsSubView = WarsSubView.SELECT_WAR_TYPE; fillWarsPanel(player); })
                        .layout(l -> l.width(60).height(20))
        );
        warsPanel.addChildren(btnRow);
        updateWarContinueButton();
    }

    @Nullable private Button warContinueBtn;

    /** Keeps the "Continue" button's text in step with how many chunks are picked. */
    @OnlyIn(Dist.CLIENT)
    private void updateWarContinueButton() {
        if (warContinueBtn == null) return;
        int n = warsSelectedChunks.size();
        warContinueBtn.setText(n > 0
                ? "§a⚔ Continue §7(§e" + n + "§7 chunk" + (n == 1 ? "" : "s") + ")"
                : "§8Pick enemy chunks first");
    }

    /** Builds/refreshes the right-side chunk inspection panel in the territory-selection view. */
    @OnlyIn(Dist.CLIENT)
    private void refreshWarChunkInfo(int playerCoreX, int playerCoreZ) {
        updateWarContinueButton();
        if (warChunkInfoPanel == null) return;
        warChunkInfoPanel.clearAllChildren();
        warChunkInfoPanel.addChildren(new Label().setText("§8─── Chunk Info ───"));

        if (warInspectedChunkKey == null) {
            warChunkInfoPanel.addChildren(
                    new Label().setText("§8Click an enemy"),
                    new Label().setText("§8chunk to inspect it.")
            );
            return;
        }

        String[] p = warInspectedChunkKey.split(",", 3);
        int icx = Integer.parseInt(p[0]), icz = Integer.parseInt(p[1]);

        warChunkInfoPanel.addChildren(
                new Label().setText("§7X: §f" + icx + "  Z: §f" + icz)
        );

        // Determine chunk category
        String coreKey   = enemyCoreCX + "," + enemyCoreCZ + "," + enemyCoreDim;
        boolean isCore   = warInspectedChunkKey.equals(coreKey);
        boolean isEnemy  = enemyClaimKeysRef.get().contains(warInspectedChunkKey);
        boolean isSel    = warsSelectedChunks.contains(warInspectedChunkKey);

        if (!isEnemy) {
            // Own claim or free chunk, just show info, not claimable
            Set<String> myClaims = new HashSet<>();
            Faction myF = menu.factionRef.get();
            if (myF != null) for (LandClaim c : myF.getLandClaims())
                myClaims.add(c.chunkX() + "," + c.chunkZ() + "," + c.dimension().toString());
            if (myClaims.contains(warInspectedChunkKey)) {
                warChunkInfoPanel.addChildren(new Label().setText("§9■ Your territory"));
            } else {
                warChunkInfoPanel.addChildren(new Label().setText("§8■ Unclaimed land"));
            }
            return;
        }

        warChunkInfoPanel.addChildren(
                new Label().setText("§cOwned by:"),
                new Label().setText("§e" + (warsTarget != null ? warsTarget.name() : "?"))
        );

        if (isCore) {
            warChunkInfoPanel.addChildren(
                    new Label().setText("§c🔒 Core Chunk"),
                    new Label().setText("§8This is the enemy's"),
                    new Label().setText("§8faction base."),
                    new Label().setText("§cCannot be claimed.")
            );
            return;
        }

        // Claimable enemy chunk
        final String fKey = warInspectedChunkKey;
        if (isSel) {
            warChunkInfoPanel.addChildren(
                    new Label().setText("§a✔ In war claims")
            );
            var removeBtn = new Button().setText("§c✗ Remove")
                    .setOnClick(e -> {
                        warsSelectedChunks.remove(fKey);
                        refreshWarChunkInfo(playerCoreX, playerCoreZ);
                        fillWarMapCells(playerCoreX, playerCoreZ);
                    });
            removeBtn.layout(l -> l.width(140).height(18));
            warChunkInfoPanel.addChildren(removeBtn);
        } else {
            warChunkInfoPanel.addChildren(
                    new Label().setText("§7Claimable on victory")
            );
            var addBtn = new Button().setText("§a✔ Claim Territory")
                    .setOnClick(e -> {
                        warsSelectedChunks.add(fKey);
                        refreshWarChunkInfo(playerCoreX, playerCoreZ);
                        fillWarMapCells(playerCoreX, playerCoreZ);
                    });
            addBtn.layout(l -> l.width(140).height(18));
            warChunkInfoPanel.addChildren(addBtn);
        }

        // Show total selected so far
        warChunkInfoPanel.addChildren(
                new Label().setText("§8─────────────"),
                new Label().setText("§7Total: §a" + warsSelectedChunks.size() + " selected")
        );
    }

    private final MapColorCache.Throttle warMapRedraw = new MapColorCache.Throttle();

    /** Renders the war territory-selection map grid (separate from the territory tab map). */
    @OnlyIn(Dist.CLIENT)
    private void fillWarMapCells(int playerCoreX, int playerCoreZ) {
        if (warTerrainMapGrid == null || warTerrainTexture == null) return;
        warTerrainMapGrid.clearAllChildren();

        var   mc    = Minecraft.getInstance();
        Level level = mc.level;

        int viewCX   = playerCoreX + warMapOffsetX;
        int viewCZ   = playerCoreZ + warMapOffsetZ;
        int halfView = warMapViewSize / 2;

        // Build O(1) lookup sets
        List<String> enemyKeys = enemyClaimKeysRef.get();
        Set<String> enemySet  = new HashSet<>(enemyKeys);
        String      coreKey   = enemyCoreCX + "," + enemyCoreCZ + "," + enemyCoreDim;

        Set<String> myClaims = new HashSet<>();
        Faction myFaction = menu.factionRef.get();
        if (myFaction != null)
            for (LandClaim c : myFaction.getLandClaims())
                myClaims.add(c.chunkX() + "," + c.chunkZ() + "," + c.dimension().toString());

        int cellPx = Math.max(8, TerritoryTab.GRID_PX / warMapViewSize);
        int actual = cellPx * warMapViewSize;
        final int fcs = cellPx;
        warTerrainMapGrid.layout(l -> l.width(actual).height(actual).alignSelf(YogaAlign.CENTER));

        // Step 1: terrain + border texture
        warTerrainTexture.clear();
        for (int row = 0; row < warMapViewSize; row++) {
            for (int col = 0; col < warMapViewSize; col++) {
                int cx = viewCX - halfView + col, cz = viewCZ - halfView + row;
                boolean loaded = level != null && level.hasChunk(cx, cz);
                for (int py = 0; py < fcs; py++) {
                    for (int px = 0; px < fcs; px++) {
                        int ix = col * fcs + px, iy = row * fcs + py;
                        if (ix >= TerritoryTab.GRID_PX || iy >= TerritoryTab.GRID_PX) continue;
                        warTerrainTexture.setPixel(ix, iy, loaded
                                ? TerrainColors.CACHE.color(level, cx * 16 + (px * 16) / fcs, cz * 16 + (py * 16) / fcs)
                                : 0x404040);
                    }
                }
            }
        }
        // Grid lines
        for (int i = 0; i <= warMapViewSize; i++) {
            int pos = i * fcs;
            if (pos < TerritoryTab.GRID_PX) { warTerrainTexture.drawHLine(pos, 0, actual - 1); warTerrainTexture.drawVLine(pos, 0, actual - 1); }
        }
        // Claim borders
        int bpx = Math.max(1, fcs / 7);
        for (int row = 0; row < warMapViewSize; row++) {
            for (int col = 0; col < warMapViewSize; col++) {
                int cx = viewCX - halfView + col, cz = viewCZ - halfView + row;
                String key = cx + "," + cz + "," + menu.tableDim;
                boolean isCore     = key.equals(coreKey);
                boolean isSelected = warsSelectedChunks.contains(key);
                boolean isEnemy    = enemySet.contains(key);
                boolean isMine     = myClaims.contains(key);

                int colour;
                if (isCore)                             colour = 0x881111; // dark red = locked core
                else if (isEnemy && isSelected)         colour = 0x00dd44; // green = selected
                else if (isEnemy)                       colour = 0xff3333; // red = enemy/claimable
                else if (isMine)                        colour = 0x3399ff; // blue = own
                else                                    continue;

                warTerrainTexture.drawChunkBorder(col * fcs, row * fcs, fcs, colour, bpx);
            }
        }
        // White outline on currently-inspected chunk
        if (warInspectedChunkKey != null) {
            String[] ip = warInspectedChunkKey.split(",", 3);
            try {
                int icx = Integer.parseInt(ip[0]), icz = Integer.parseInt(ip[1]);
                int selC = icx - (viewCX - halfView);
                int selR = icz - (viewCZ - halfView);
                if (selC >= 0 && selC < warMapViewSize && selR >= 0 && selR < warMapViewSize)
                    warTerrainTexture.drawChunkBorder(selC * fcs, selR * fcs, fcs, 0xFFFFFF, bpx + 1);
            } catch (NumberFormatException ignored) {}
        }
        warTerrainTexture.upload();

        // Step 2: transparent click cells
        for (int row = 0; row < warMapViewSize; row++) {
            var rowEl = new UIElement();
            rowEl.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(fcs));
            for (int col = 0; col < warMapViewSize; col++) {
                int cx = viewCX - halfView + col, cz = viewCZ - halfView + row;
                String key = cx + "," + cz + "," + menu.tableDim;
                boolean isEnemy = enemySet.contains(key); // core or regular enemy chunk

                var cell = new UIElement();
                cell.layout(l -> l.width(fcs).height(fcs));

                // Any enemy chunk (including core) can be inspected on click;
                // double-click (two fast clicks) quick-adds it to the selection.
                if (isEnemy) {
                    final String fKey = key;
                    final int fcx = cx, fcz = cz;
                    final long[] lastClickTime = {0L};
                    UiKit.onClick(cell, ev -> {
                        if (warMapDragDist > 5f) return;
                        long now = System.currentTimeMillis();
                        boolean isDoubleClick = (now - lastClickTime[0]) < 350L;
                        lastClickTime[0] = now;

                        String coreKey2 = enemyCoreCX + "," + enemyCoreCZ + "," + enemyCoreDim;
                        if (isDoubleClick && !fKey.equals(coreKey2)) {
                            // Double-click: immediately toggle chunk in selection
                            if (warsSelectedChunks.contains(fKey)) warsSelectedChunks.remove(fKey);
                            else warsSelectedChunks.add(fKey);
                            refreshWarChunkInfo(playerCoreX, playerCoreZ);
                        } else {
                            // Single-click: inspect
                            if (fKey.equals(warInspectedChunkKey)) warInspectedChunkKey = null;
                            else                                    warInspectedChunkKey = fKey;
                            refreshWarChunkInfo(playerCoreX, playerCoreZ);
                        }
                        fillWarMapCells(playerCoreX, playerCoreZ);
                    });
                }
                rowEl.addChildren(cell);
            }
            warTerrainMapGrid.addChildren(rowEl);
        }
    }
}
