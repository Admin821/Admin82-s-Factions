package com.admin82.factions.menu;

import com.admin82.factions.faction.*;
import com.admin82.factions.network.packet.*;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.Config;
import com.admin82.factions.network.packet.VaultActionPacket;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener;
import java.util.stream.Collectors;

public class FactionTableMenu extends AbstractContainerMenu {

    // ── Persistent state ──────────────────────────────────────────────────────
    private final BlockPos tablePos;
    private String tableDim;
    private final AtomicReference<Faction>              factionRef         = new AtomicReference<>();
    private final AtomicReference<List<FactionSummary>> allFactions        = new AtomicReference<>(List.of());
    private final AtomicReference<Set<String>>          otherClaimedChunks = new AtomicReference<>(Set.of());
    private final AtomicReference<List<String>>         availablePlayers   = new AtomicReference<>(List.of());
    private final AtomicReference<Long>                 playerWalletRef    = new AtomicReference<>(0L);
    private final AtomicReference<Long>                 factionVaultRef    = new AtomicReference<>(0L);

    Tab         currentTab       = Tab.OVERVIEW;
    int         allFactionsScroll = 0;
    FactionRole selectedPermRole = FactionRole.MEMBER;

    int mapOffsetX = 0;
    int mapOffsetZ = 0;

    // Drag-to-pan state (plain instance fields — accessible from inner lambdas)
    private boolean mapIsDragging = false;
    private float   mapDragDist   = 0f;
    private float   mapLastDragX  = 0f;
    private float   mapLastDragY  = 0f;
    private float   mapAccumDragX = 0f;
    private float   mapAccumDragY = 0f;

    // ── Wars tab state ──────────────────────────────────────────────────────────
    private enum WarsSubView { LIST, SELECT_ATTACKERS }
    private WarsSubView  warsSubView           = WarsSubView.LIST;
    @Nullable private FactionSummary warsTarget = null;
    private final Set<UUID> warsSelectedAttackers = new HashSet<>();
    @Nullable private UIElement warsPanel = null;

    // ── Vassal tab state (populated from server buf at menu open) ──────────────
    boolean vassalIsVassal      = false;
    boolean vassalIsSuzerain    = false;
    String  vassalSuzerainName  = "";
    long    vassalPendingTax    = 0L;
    List<VassalSubjectInfo> vassalSubjects = new ArrayList<>();

    // Live UIElement references
    @Nullable private UIElement warsListArea;
    @Nullable private UIElement permArea;
    @Nullable private UIElement memberDetailArea;
    @Nullable private UIElement mapGrid;
    @Nullable private TerrainMapTexture terrainMapTexture;
    @Nullable @SuppressWarnings("rawtypes") private Selector inviteSelectorRef;
    @Nullable private Player playerRef;

    private static final int WARS_ROWS = 8;
    private static final int GRID_PX   = 234; // fixed map widget pixel size
    private int              mapViewSize = 9;  // chunks visible each axis; changes on scroll-zoom

    public enum Tab { OVERVIEW, MEMBERS, PERMISSIONS, TERRITORY, UPKEEP, WARS, VAULT, LEADERBOARD, VASSAL }

    // ── Constructors ──────────────────────────────────────────────────────────

    public FactionTableMenu(int containerId, Inventory inv, BlockPos tablePos, @Nullable Faction faction) {
        super(ModMenuTypes.FACTION_TABLE.get(), containerId);
        this.tablePos = tablePos;
        this.tableDim = inv.player.level().dimension().location().toString();
        this.factionRef.set(faction);
        if (this instanceof IModularUIHolderMenu h) h.setModularUI(createModularUI(inv.player));
    }

    public FactionTableMenu(int containerId, Inventory inv, FriendlyByteBuf buf) {
        this(containerId, inv, buf.readBlockPos(), buf.readBoolean() ? Faction.fromNetwork(buf) : null);
        // Read vassal data appended by FactionTableBlock
        if (buf.isReadable()) {
            this.vassalIsVassal     = buf.readBoolean();
            this.vassalIsSuzerain   = buf.readBoolean();
            this.vassalSuzerainName = buf.readUtf(64);
            this.vassalPendingTax   = buf.readLong();
            int count = buf.readVarInt();
            for (int i = 0; i < count; i++) {
                UUID sid      = buf.readUUID();
                String sname  = buf.readUtf(64);
                long   stax   = buf.readLong();
                this.vassalSubjects.add(new VassalSubjectInfo(sid, sname, stax));
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Nullable public Faction getFaction()  { return factionRef.get(); }
    public BlockPos          getTablePos() { return tablePos; }

    /** Called by SyncEconomyPacket to update wallet/vault balances. */
    public void updateEconomy(long wallet, long vault) {
        playerWalletRef.set(wallet);
        factionVaultRef.set(vault);
    }

    public void updateFaction(@Nullable Faction f) { factionRef.set(f); }

    @SuppressWarnings("unchecked")
    public void updateAllFactions(List<FactionSummary> factions,
                                  List<String>         otherClaims,
                                  List<String>         players) {
        allFactions.set(factions);
        otherClaimedChunks.set(new HashSet<>(otherClaims));
        availablePlayers.set(players);
        if (warsListArea != null && playerRef != null) {
            warsListArea.clearAllChildren();
            fillWarsFactionList(warsListArea, playerRef);
        }
        if (mapGrid != null) {
            int coreX = SectionPos.blockToSectionCoord(tablePos.getX());
            int coreZ = SectionPos.blockToSectionCoord(tablePos.getZ());
            mapGrid.clearAllChildren();
            fillMapCells(mapGrid, coreX, coreZ, playerRef != null && isOfficer(playerRef));
        }
        if (inviteSelectorRef != null) inviteSelectorRef.setCandidates(players);
    }

    public void rebuildForFaction(@Nullable Faction f, Player player) {
        factionRef.set(f);
        if (this instanceof IModularUIHolderMenu h) h.setModularUI(createModularUI(player));
    }

    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player p) {
        return p.distanceToSqr(tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5) <= 64.0;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════════════

    private ModularUI createModularUI(Player player) {
        playerRef = player;
        tableDim  = player.level().dimension().location().toString();

        var frame = new UIElement();
        frame.layout(l -> l.width(424).height(344).paddingAll(2));
        frame.addClass("preview_bg");

        var root = new UIElement();
        root.layout(l -> l.width(420).height(340));
        root.addClass("panel_bg");

        root.addChildren(factionRef.get() == null ? buildCreatePanel() : buildManagePanel(player));
        frame.addChildren(root);

        return ModularUI.of(
                UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)),
                player);
    }

    // ── Create-faction panel ──────────────────────────────────────────────────

    private UIElement buildCreatePanel() {
        var nameVal   = new String[]{""};
        var descVal   = new String[]{""};
        var statusVal = new String[]{""};
        var panel = new UIElement();
        panel.layout(l -> l.width(420).height(340).paddingAll(16).gapAll(8));
        panel.addChildren(
                new Label().setText("§6§lFaction Table").lss("horizontal-align", "center"),
                new Label().setText("§7Create a faction to claim land and build power.").lss("horizontal-align", "center"),
                new Label().setText("§fFaction Name:"),
                new TextField().setValue("").bindObserver(v -> nameVal[0] = v).layout(l -> l.width(388)),
                new Label().setText("§fDescription (optional):"),
                new TextField().setValue("").bindObserver(v -> descVal[0] = v).layout(l -> l.width(388)),
                new Button().setText("§aCreate Faction")
                        .setOnClick(e -> {
                            String name = nameVal[0].trim();
                            if (name.length() < 3) { statusVal[0] = "§cName must be at least 3 characters!"; return; }
                            PacketDistributor.sendToServer(new CreateFactionPacket(name, descVal[0].trim()));
                            statusVal[0] = "§7Creating...";
                        })
                        .layout(l -> l.alignSelf(YogaAlign.CENTER).width(180)),
                new Label().bindDataSource(SupplierDataSource.of(() -> Component.literal(statusVal[0])))
        );
        return panel;
    }

    // ── Management panel (tabbed) ─────────────────────────────────────────────

    private UIElement buildManagePanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.width(420).height(340).flexDirection(YogaFlexDirection.COLUMN));

        UIElement[] tabPanels = {
                buildOverviewPanel(player),
                buildMembersPanel(player),
                buildPermissionsPanel(player),
                buildTerritoryPanel(player),
                buildUpkeepPanel(player),
                buildWarsPanel(player),
                buildVaultPanel(player),
                buildLeaderboardPanel(),
                buildVassalPanel(player)
        };

        var contentArea = new UIElement();
        contentArea.layout(l -> l.flex(1).width(420));
        contentArea.addChildren(tabPanels[currentTab.ordinal()]);

        var tabBar = new UIElement();
        tabBar.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(24).width(420).gapAll(2).paddingHorizontal(2));
        buildTabButtons(tabBar, Tab.values(), tabPanels, contentArea);

        panel.addChildren(tabBar, contentArea);
        return panel;
    }

    /** Rebuilds tab button bar; active tab gets a gold-tinted background. */
    private void buildTabButtons(UIElement tabBar, Tab[] tabs, UIElement[] panels, UIElement contentArea) {
        tabBar.clearAllChildren();
        for (int i = 0; i < tabs.length; i++) {
            final int idx    = i;
            final Tab tab    = tabs[i];
            // Only show the Vassal tab when the player's faction is involved in a vassal relationship
            if (tab == Tab.VASSAL && !vassalIsVassal && !vassalIsSuzerain) continue;
            boolean   active = tab == currentTab;
            var btn = new Button()
                    .setText(tabLabel(tab))
                    .setOnClick(e -> {
                        currentTab = tab;
                        contentArea.clearAllChildren();
                        contentArea.addChildren(panels[idx]);
                        buildTabButtons(tabBar, tabs, panels, contentArea);
                    })
                    .layout(l -> l.flex(1).height(22));
            if (active) btn.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
            tabBar.addChildren(btn);
        }
    }

    private static String tabLabel(Tab t) {
        return switch (t) {
            case OVERVIEW    -> "Overview";
            case MEMBERS     -> "Members";
            case PERMISSIONS -> "Perms";
            case TERRITORY   -> "Land";
            case UPKEEP      -> "Upkeep";
            case WARS        -> "Wars";
            case VAULT       -> "Vault";
            case LEADERBOARD -> "Board";
            case VASSAL      -> "Vassal";
        };
    }

    // ── Overview ─────────────────────────────────────────────────────────────

    private UIElement buildOverviewPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(10).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        panel.addChildren(
                new Label().bindDataSource(SupplierDataSource.of(() -> {
                    var f = factionRef.get();
                    return Component.literal(f != null ? "§6§l" + f.getName() : "");
                })),
                new Label().bindDataSource(SupplierDataSource.of(() -> {
                    var f = factionRef.get();
                    return Component.literal(f != null && !f.getDescription().isEmpty() ? "§7" + f.getDescription() : "");
                })),
                new Label().bindDataSource(SupplierDataSource.of(() -> {
                    var f = factionRef.get(); if (f == null) return Component.empty();
                    return Component.literal("§fMembers: §e" + f.getMembers().size()
                            + "   §fTerritory: §e" + f.getLandClaims().size()
                            + "   §fPower: §a" + f.getPower());
                })),
                new Label().bindDataSource(SupplierDataSource.of(() -> {
                    var f = factionRef.get(); if (f == null) return Component.empty();
                    return Component.literal("§fWars: " +
                            (f.getWars().isEmpty() ? "§a0 active" : "§c" + f.getWars().size() + " active"));
                }))
        );
        boolean isOwner = factionRef.get() != null && factionRef.get().getOwnerId().equals(player.getUUID());
        var btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(8).width(400));
        if (isOwner) {
            var moveBtn = new Button().setText("Move Table")
                    .setOnClick(e -> PacketDistributor.sendToServer(new RequestMoveTablePacket()));
            moveBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                    Component.literal("§eMove Faction Table"),
                    Component.literal("§7Click this, then right-click a new empty"),
                    Component.literal("§7block position to relocate your table.")));
            moveBtn.layout(l -> l.flex(1));
            var disbandBtn = new Button().setText("§cDisband")
                    .setOnClick(e -> PacketDistributor.sendToServer(new DisbandFactionPacket()));
            disbandBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                    Component.literal("§cDisband Faction"),
                    Component.literal("§7Permanently deletes the faction and releases"),
                    Component.literal("§7all claimed land. §cThis cannot be undone!")));
            disbandBtn.layout(l -> l.flex(1));
            btnRow.addChildren(moveBtn, disbandBtn);
        } else {
            var leaveBtn = new Button().setText("Leave Faction")
                    .setOnClick(e -> PacketDistributor.sendToServer(
                            new MemberActionPacket(MemberActionPacket.Action.LEAVE, player.getUUID(), "", FactionRole.MEMBER)));
            leaveBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                    Component.literal("§eLeave Faction"),
                    Component.literal("§7Removes you from the faction.")));
            leaveBtn.layout(l -> l.flex(1));
            btnRow.addChildren(leaveBtn);
        }
        panel.addChildren(btnRow);
        return panel;
    }

    // ── Members ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private UIElement buildMembersPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(8).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));

        Faction      faction     = factionRef.get();
        List<String> memberNames = faction == null ? List.of() :
                faction.getMembers().stream().map(FactionMember::getPlayerName).collect(Collectors.toList());

        memberDetailArea = new UIElement();
        memberDetailArea.layout(l -> l.flex(1).width(404));
        if (!memberNames.isEmpty()) fillMemberDetail(memberDetailArea, memberNames.get(0), player);

        var memberSel = new Selector<String>();
        memberSel.setCandidates(memberNames);
        if (!memberNames.isEmpty()) memberSel.setValue(memberNames.get(0));
        memberSel.setOnValueChanged(name -> {
            memberDetailArea.clearAllChildren();
            fillMemberDetail(memberDetailArea, name, player);
        });
        memberSel.layout(l -> l.width(404).height(20));

        panel.addChildren(
                new Label().bindDataSource(SupplierDataSource.of(() -> {
                    var f = factionRef.get();
                    return Component.literal("§7Members (" + (f != null ? f.getMembers().size() : 0) + ")");
                })),
                memberSel, memberDetailArea
        );

        if (isOfficer(player)) {
            var inviteNameVal  = new String[]{""};
            var inviteSel      = new Selector<String>();
            inviteSel.setCandidates(availablePlayers.get());
            inviteSel.setOnValueChanged(n -> inviteNameVal[0] = n);
            inviteSel.layout(l -> l.flex(1).height(20));
            inviteSelectorRef = inviteSel;

            var inviteRow = new UIElement();
            inviteRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).width(404));
            inviteRow.addChildren(
                    inviteSel,
                    new Button().setText("Invite").setOnClick(e -> {
                        String n = inviteNameVal[0].trim();
                        if (!n.isEmpty()) PacketDistributor.sendToServer(
                                new MemberActionPacket(MemberActionPacket.Action.INVITE,
                                        new UUID(0, 0), n, FactionRole.MEMBER));
                    }).layout(l -> l.width(54))
            );
            panel.addChildren(inviteRow);
        }
        return panel;
    }

    private void fillMemberDetail(UIElement area, @Nullable String playerName, Player self) {
        area.clearAllChildren();
        if (playerName == null) return;
        Faction faction = factionRef.get(); if (faction == null) return;
        FactionMember target = faction.getMembers().stream()
                .filter(m -> m.getPlayerName().equalsIgnoreCase(playerName)).findFirst().orElse(null);
        if (target == null) return;

        FactionMember selfMember = faction.getMember(self.getUUID());
        boolean isOwner     = faction.getOwnerId().equals(self.getUUID());
        boolean selfOfficer = selfMember != null && selfMember.getRole().getLevel() >= FactionRole.OFFICER.getLevel();

        area.addChildren(new Label().setText("§fRole: " + roleColor(target.getRole()) + capitalize(target.getRole().getId())));

        UUID uid = target.getUuid();
        var  actionRow = new UIElement();
        actionRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).width(404));

        boolean canKick = !uid.equals(self.getUUID()) && selfOfficer
                && selfMember != null && target.getRole().getLevel() < selfMember.getRole().getLevel();
        if (canKick) {
            actionRow.addChildren(new Button().setText("Kick")
                    .setOnClick(e -> PacketDistributor.sendToServer(
                            new MemberActionPacket(MemberActionPacket.Action.KICK, uid, "", FactionRole.MEMBER)))
                    .layout(l -> l.width(40)));
        }
        if (isOwner && target.getRole() != FactionRole.OWNER) {
            for (FactionRole r : new FactionRole[]{FactionRole.ADMIN, FactionRole.OFFICER, FactionRole.MEMBER}) {
                if (r != target.getRole()) {
                    FactionRole fr = r;
                    actionRow.addChildren(new Button().setText("-> " + capitalize(fr.getId()))
                            .setOnClick(e -> PacketDistributor.sendToServer(
                                    new MemberActionPacket(MemberActionPacket.Action.SET_ROLE, uid, "", fr)))
                            .layout(l -> l.width(80)));
                }
            }
        }
        area.addChildren(actionRow);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private UIElement buildPermissionsPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(8).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        boolean isOwner = factionRef.get() != null && factionRef.get().getOwnerId().equals(player.getUUID());

        permArea = new UIElement();
        permArea.layout(l -> l.width(404));
        fillPermArea(permArea, isOwner);

        var roleBar = new UIElement();
        roleBar.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).width(404));
        for (FactionRole role : new FactionRole[]{FactionRole.ADMIN, FactionRole.OFFICER, FactionRole.MEMBER}) {
            final FactionRole fr = role;
            var roleBtn = new Button()
                    .setText(roleColor(role) + capitalize(role.getId()))
                    .setOnClick(e -> { selectedPermRole = fr; permArea.clearAllChildren(); fillPermArea(permArea, isOwner); });
            roleBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                    Component.literal(roleColor(fr) + "§l" + capitalize(fr.getId()) + " permissions"),
                    Component.literal("§7Configure what " + fr.getId() + "s are allowed to do"),
                    Component.literal("§8Toggles below apply to this role only.")));
            roleBtn.layout(l -> l.flex(1).height(20));
            roleBar.addChildren(roleBtn);
        }
        panel.addChildren(
                new Label().setText("§7Permissions §8(owner only) — toggle what each role is allowed to do"),
                roleBar, permArea);
        return panel;
    }

    private void fillPermArea(UIElement area, boolean isOwner) {
        area.clearAllChildren();
        Faction faction = factionRef.get();
        FactionRole role = selectedPermRole;
        for (FactionPermission perm : FactionPermission.values()) {
            boolean current = faction != null && faction.getRolePermission(role, perm);
            var row = new UIElement();
            row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).height(28));
            var labelCol = new UIElement();
            labelCol.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(1));
            labelCol.addChildren(
                    new Label().setText(permLabel(perm)),
                    new Label().setText("§8" + permDescription(perm))
            );
            row.addChildren(
                    labelCol,
                    new Toggle().setOn(current).setOnToggleChanged(on -> {
                        if (!isOwner) return;
                        PacketDistributor.sendToServer(new UpdateRolePermissionPacket(role.getId(), perm.getKey(), on));
                    }).layout(l -> l.width(50))
            );
            area.addChildren(row);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TERRITORY — interactive live chunk map
    // ══════════════════════════════════════════════════════════════════════════

    private UIElement buildTerritoryPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(6).gapAll(4).flexDirection(YogaFlexDirection.COLUMN));

        final int     coreX    = SectionPos.blockToSectionCoord(tablePos.getX());
        final int     coreZ    = SectionPos.blockToSectionCoord(tablePos.getZ());
        final boolean canClaim = isOfficer(player);

        // Stats
        var statsRow = new UIElement();
        statsRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).width(408).height(18));
        statsRow.addChildren(
                new Label().bindDataSource(SupplierDataSource.of(() -> {
                    var f = factionRef.get();
                    return Component.literal("§7Territory: §e" + (f != null ? f.getLandClaims().size() : 0) + " chunks");
                })).layout(l -> l.flex(1)),
                new Label().setText("§7Core: §e" + coreX + "," + coreZ).layout(l -> l.width(100))
        );

        // Legend
        var legend = new Label().setText("§6■§rCore  §9■§rMine  §c■§rOther  §8■§rFree  §8drag·scroll");

        // Map grid — fixed GRID_PX × GRID_PX; cell size computed from mapViewSize
        mapGrid = new UIElement();
        mapGrid.layout(l -> l.width(GRID_PX).height(GRID_PX).alignSelf(YogaAlign.CENTER));
        terrainMapTexture = new TerrainMapTexture(GRID_PX);
        mapGrid.style(s -> s.background(terrainMapTexture));
        fillMapCells(mapGrid, coreX, coreZ, canClaim);

        // ── Drag-to-pan ───────────────────────────────────────────────────────
        mapGrid.addEventListener(UIEvents.MOUSE_DOWN, ev -> {
            mapIsDragging = true;
            mapDragDist   = 0f;
            mapLastDragX  = ev.x;
            mapLastDragY  = ev.y;
            mapAccumDragX = 0f;
            mapAccumDragY = 0f;
        });
        mapGrid.addEventListener(UIEvents.MOUSE_UP, ev -> {
            mapIsDragging = false;
        });
        mapGrid.addEventListener(UIEvents.MOUSE_MOVE, ev -> {
            if (!mapIsDragging) return;
            float dx = mapLastDragX - ev.x;
            float dz = mapLastDragY - ev.y;
            mapLastDragX  = ev.x;
            mapLastDragY  = ev.y;
            mapDragDist  += Math.abs(dx) + Math.abs(dz);
            mapAccumDragX += dx;
            mapAccumDragY += dz;
            boolean moved = false;
            int ppc = Math.max(1, GRID_PX / mapViewSize); // pixels per chunk at current zoom
            while (mapAccumDragX >=  ppc) { mapOffsetX++; mapAccumDragX -= ppc; moved = true; }
            while (mapAccumDragX <= -ppc) { mapOffsetX--; mapAccumDragX += ppc; moved = true; }
            while (mapAccumDragY >=  ppc) { mapOffsetZ++; mapAccumDragY -= ppc; moved = true; }
            while (mapAccumDragY <= -ppc) { mapOffsetZ--; mapAccumDragY += ppc; moved = true; }
            if (moved) { mapGrid.clearAllChildren(); fillMapCells(mapGrid, coreX, coreZ, canClaim); }
        });

        // ── Scroll-to-zoom (mouse wheel) ──────────────────────────────────────
        mapGrid.addEventListener(UIEvents.MOUSE_WHEEL, ev -> {
            if      (ev.deltaY > 0) mapViewSize = Math.max(5,  mapViewSize - 2); // scroll up  = zoom in
            else if (ev.deltaY < 0) mapViewSize = Math.min(17, mapViewSize + 2); // scroll down = zoom out
            mapGrid.clearAllChildren();
            fillMapCells(mapGrid, coreX, coreZ, canClaim);
        });

        // ── Live tracking — claim changes only (player pos shown via live label, not cell) ──
        int[] lastCC = {-1};
        mapGrid.addEventListener(UIEvents.TICK, ev -> {
            // If the mouse was released outside the map, clear the drag state
            if (mapIsDragging) {
                long win = Minecraft.getInstance().getWindow().getWindow();
                if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(win, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT)
                        != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                    mapIsDragging = false;
                    mapDragDist   = 0f;
                }
            }
            // Rebuild only when claim count changes (position already shown via SupplierDataSource)
            Faction fTick = factionRef.get();
            int cc = fTick != null ? fTick.getLandClaims().size() : 0;
            if (cc != lastCC[0]) {
                lastCC[0] = cc;
                mapGrid.clearAllChildren();
                fillMapCells(mapGrid, coreX, coreZ, canClaim);
            }
        });

        // ── Nav buttons ───────────────────────────────────────────────────────
        var navRow = new UIElement();
        navRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(3).width(408));
        navRow.addChildren(
                mkNavBtn("Table", 50, e -> { mapOffsetX = 0; mapOffsetZ = 0; rebuildMap(coreX, coreZ, canClaim); }),
                mkNavBtn("Me", -1, e -> {
                    var mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        mapOffsetX = SectionPos.blockToSectionCoord(mc.player.getBlockX()) - coreX;
                        mapOffsetZ = SectionPos.blockToSectionCoord(mc.player.getBlockZ()) - coreZ;
                        rebuildMap(coreX, coreZ, canClaim);
                    }
                })
        );

        panel.addChildren(statsRow, legend, mapGrid, navRow);
        return panel;
    }

    private void rebuildMap(int coreX, int coreZ, boolean canClaim) {
        if (mapGrid != null) { mapGrid.clearAllChildren(); fillMapCells(mapGrid, coreX, coreZ, canClaim); }
    }

    private Button mkNavBtn(String text, int width, UIEventListener onClick) {
        var btn = new Button().setText(text).setOnClick(onClick);
        if (width > 0) btn.layout(l -> l.width(width));
        else           btn.layout(l -> l.flex(1));
        return btn;
    }

    // ── Map cell grid ─────────────────────────────────────────────────────────

    /**
     * Populates the mapGrid with MAP_SIZE × MAP_SIZE Button cells.
     *
     * Each cell background is the actual Minecraft MapColor of the chunk's top
     * surface, blended with the faction-claim overlay (core=gold, mine=blue,
     * other=red, free=terrain only).  The default "Button" text is cleared to
     * avoid visual noise; only the player's current chunk shows "P".
     */
    private void fillMapCells(UIElement grid, int coreX, int coreZ, boolean canClaim) {
        var   mc    = Minecraft.getInstance();
        Level level = mc.level;
        int   viewCX = coreX + mapOffsetX;
        int   viewCZ = coreZ + mapOffsetZ;

        Faction     myFaction = factionRef.get();
        Set<String> others    = otherClaimedChunks.get();

        // ── O(1) claim lookup: build HashSet once instead of O(n) stream per cell ──
        final Set<String> myClaims = new HashSet<>();
        if (myFaction != null) {
            for (LandClaim c : myFaction.getLandClaims()) {
                myClaims.add(c.chunkX() + "," + c.chunkZ() + "," + c.dimension());
            }
        }

        // ── Precompute claimable set: one pass over claims instead of 4 lookups per cell ──
        // A chunk is claimable if: free, not other-faction, adjacent to core or any own claim.
        final Set<String> claimableSet = new HashSet<>();
        if (canClaim && myFaction != null) {
            final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1}};
            // Adjacent to core chunk
            for (int[] d : DIRS) {
                String key = (coreX+d[0]) + "," + (coreZ+d[1]) + "," + tableDim;
                if (!myClaims.contains(key) && !others.contains(key)) claimableSet.add(key);
            }
            // Adjacent to own claims
            for (LandClaim c : myFaction.getLandClaims()) {
                if (!c.dimension().toString().equals(tableDim)) continue;
                for (int[] d : DIRS) {
                    String key = (c.chunkX()+d[0]) + "," + (c.chunkZ()+d[1]) + "," + tableDim;
                    if (!myClaims.contains(key) && !others.contains(key)) claimableSet.add(key);
                }
            }
        }

        int cellSize = Math.max(8, GRID_PX / mapViewSize);
        int halfView = mapViewSize / 2;
        final int fcs = cellSize;
        int actualSize = fcs * mapViewSize; // exact rendered px — may be < GRID_PX

        // Resize grid element so no unwritten grey strips show at edges
        if (mapGrid != null) {
            final int as = actualSize;
            mapGrid.layout(l -> l.width(as).height(as).alignSelf(YogaAlign.CENTER));
        }

        // ── Step 1: per-pixel terrain render into the NativeImage texture ────
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
                                    ? computeBlockColor(level,
                                            cx * 16 + (px * 16) / fcs,
                                            cz * 16 + (py * 16) / fcs)
                                    : 0x404040;
                            terrainMapTexture.setPixel(imgX, imgY, rgb);
                        }
                    }
                }
            }
            // ── chunk grid lines ──────────────────────────────────────────
            for (int i = 0; i <= mapViewSize; i++) {
                int pos = i * fcs;
                if (pos < GRID_PX) {
                    terrainMapTexture.drawHLine(pos, 0, actualSize - 1);
                    terrainMapTexture.drawVLine(pos, 0, actualSize - 1);
                }
            }
            // claim borders — drawn into NativeImage so adjacent claims never stack visually
            int borderPx = Math.max(1, fcs / 7);
            for (int row = 0; row < mapViewSize; row++) {
                for (int col = 0; col < mapViewSize; col++) {
                    int cx = viewCX - halfView + col;
                    int cz = viewCZ - halfView + row;
                    boolean isCore2  = cx == coreX && cz == coreZ;
                    boolean isMine2  = myClaims.contains(cx + "," + cz + "," + tableDim);
                    boolean isOther2 = others.contains(cx + "," + cz + "," + tableDim);
                    if (isCore2 || isMine2 || isOther2) {
                        int rgb = isCore2 ? 0xffcc00 : isMine2 ? 0x33bbff : 0xff4444;
                        terrainMapTexture.drawChunkBorder(col * fcs, row * fcs, fcs, rgb, borderPx);
                    }
                }
            }
            terrainMapTexture.upload();
        }

        // ── Step 2: transparent cells for click interaction only ───────────────
        for (int row = 0; row < mapViewSize; row++) {
            var rowEl = new UIElement();
            rowEl.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(fcs));

            for (int col = 0; col < mapViewSize; col++) {
                int cx = viewCX - halfView + col;
                int cz = viewCZ - halfView + row;

                boolean isCore  = cx == coreX && cz == coreZ;
                boolean isMine  = myClaims.contains(cx + "," + cz + "," + tableDim);
                boolean isOther = others.contains(cx + "," + cz + "," + tableDim);

                // O(1) claimable lookup (precomputed above)
                boolean isClaimable = !isMine && !isOther && !isCore
                        && claimableSet.contains(cx + "," + cz + "," + tableDim);

                // Plain UIElement — no background; terrain + borders show through
                var cell = new UIElement();
                cell.layout(l -> l.width(fcs).height(fcs));

                // Claim / unclaim via MOUSE_UP + drag-distance guard
                final boolean fIsMine = isMine, fIsOther = isOther, fIsClaimable = isClaimable;
                int fCx = cx, fCz = cz;
                if (!isCore && canClaim) {
                    cell.addEventListener(UIEvents.MOUSE_UP, ev -> {
                        if (mapDragDist > 5f) return;
                        if (fIsMine) {
                            PacketDistributor.sendToServer(new UnclaimChunkPacket(fCx, fCz, tableDim));
                        } else if (!fIsOther && fIsClaimable) {
                            PacketDistributor.sendToServer(new ClaimChunkPacket(fCx, fCz, tableDim));
                        }
                    });
                    // Tooltip: explain why a free chunk can't be claimed
                    if (!isMine && !isOther && !isClaimable) {
                        cell.addEventListener(UIEvents.HOVER_TOOLTIPS, ev ->
                                ev.hoverTooltips = HoverTooltips.empty().append(
                                        Component.literal("§cNot adjacent to your territory."),
                                        Component.literal("§7Claims must connect to your base or existing land.")));
                    }
                }
                rowEl.addChildren(cell);
            }
            grid.addChildren(rowEl);
        }
    }

    // ── Terrain colour helpers ────────────────────────────────────────────────

    /**
     * Returns the terrain RGB colour for a single world block ({@code wx}, {@code wz}).
     * Uses MOTION_BLOCKING vs WORLD_SURFACE heightmaps to detect water depth;
     * applies saturation boost and height shading to land blocks.
     */
    private static int computeBlockColor(Level level, int wx, int wz) {
        int wyLand    = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz) - 1;
        int wySurface = level.getHeight(Heightmap.Types.WORLD_SURFACE,   wx, wz) - 1;
        if (wyLand < level.getMinBuildHeight()) wyLand = level.getMinBuildHeight();
        int waterDepth = Math.max(0, wySurface - wyLand);
        if (waterDepth > 0) {
            int shade = Math.max(60, 210 - waterDepth * 12);
            return ((shade / 6) << 16) | ((shade * 2 / 5) << 8) | shade;
        }
        var pos = new BlockPos(wx, wyLand, wz);
        int col = level.getBlockState(pos).getMapColor(level, pos).col;
        if (col == 0) col = 0x707070;
        col = saturateColor(col, 1.8f);
        float hf = Math.max(0.70f, Math.min(1.30f, 1.0f + (wyLand - 64) * 0.003f));
        int r2 = Math.min(255, (int)(((col >> 16) & 0xFF) * hf));
        int g2 = Math.min(255, (int)(((col >>  8) & 0xFF) * hf));
        int b2 = Math.min(255, (int)(( col        & 0xFF) * hf));
        return (r2 << 16) | (g2 << 8) | b2;
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

    // ── Terrain map texture backing NativeImage ────────────────────────────

    /**
     * Thin wrapper around a Minecraft {@link net.minecraft.client.renderer.texture.DynamicTexture}
     * that implements LDLib2's {@link IGuiTexture} interface by extending {@link
     * com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture}.
     * Callers write pixels with {@link #setPixel} then call {@link #upload} once per frame.
     */
    private static final class TerrainMapTexture
            extends com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture {

        private final com.mojang.blaze3d.platform.NativeImage image;
        private final net.minecraft.client.renderer.texture.DynamicTexture dynTex;
        private final net.minecraft.resources.ResourceLocation rl;
        private final int size;

        TerrainMapTexture(int size) {
            super(0x00000000); // transparent fill — parent's drawInternal is overridden below
            this.size  = size;
            this.image = new com.mojang.blaze3d.platform.NativeImage(
                    com.mojang.blaze3d.platform.NativeImage.Format.RGBA, size, size, false);
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    image.setPixelRGBA(x, y, abgr(0x404040)); // default: unloaded grey
            this.dynTex = new net.minecraft.client.renderer.texture.DynamicTexture(image);
            this.rl = net.minecraft.client.Minecraft.getInstance()
                    .getTextureManager().register("adminsfactions_terrainmap", dynTex);
        }

        /** Write one pixel; {@code rgb} is 24-bit 0x00RRGGBB. */
        void setPixel(int x, int y, int rgb) {
            image.setPixelRGBA(x, y, abgr(rgb));
        }

        /** Reset every pixel to the unloaded-chunk grey. */
        void clear() {
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
        void drawHLine(int y, int x1, int x2) {
            if (y < 0 || y >= size) return;
            for (int x = Math.max(0, x1); x <= Math.min(size - 1, x2); x++)
                darkenPixel(x, y);
        }

        /** Draw a vertical grid line at column {@code x}, rows [{@code y1}, {@code y2}]. */
        void drawVLine(int x, int y1, int y2) {
            if (x < 0 || x >= size) return;
            for (int y = Math.max(0, y1); y <= Math.min(size - 1, y2); y++)
                darkenPixel(x, y);
        }

        /** Draw a horizontal line of solid colour (0x00RRGGBB). */
        void fillHLine(int y, int x1, int x2, int rgb) {
            if (y < 0 || y >= size) return;
            int packed = abgr(rgb);
            for (int x = Math.max(0, x1); x <= Math.min(size - 1, x2); x++)
                image.setPixelRGBA(x, y, packed);
        }

        /** Draw a vertical line of solid colour (0x00RRGGBB). */
        void fillVLine(int x, int y1, int y2, int rgb) {
            if (x < 0 || x >= size) return;
            int packed = abgr(rgb);
            for (int y = Math.max(0, y1); y <= Math.min(size - 1, y2); y++)
                image.setPixelRGBA(x, y, packed);
        }

        /** Paint a solid-colour inset border around the chunk tile at pixel (ix, iy),
         *  spanning fcs pixels square, with border thickness bpx pixels. */
        void drawChunkBorder(int ix, int iy, int fcs, int rgb, int bpx) {
            for (int b = 0; b < bpx; b++) {
                fillHLine(iy + b,           ix, ix + fcs - 1, rgb); // top
                fillHLine(iy + fcs - 1 - b, ix, ix + fcs - 1, rgb); // bottom
                fillVLine(ix + b,           iy, iy + fcs - 1, rgb); // left
                fillVLine(ix + fcs - 1 - b, iy, iy + fcs - 1, rgb); // right
            }
        }

        /** Push all buffered pixels to the GPU.  Call once after all {@link #setPixel} calls. */
        void upload() { dynTex.upload(); }

        /** Converts 0x00RRGGBB → the ABGR int expected by {@link com.mojang.blaze3d.platform.NativeImage#setPixelRGBA}. */
        private static int abgr(int rgb) {
            return (0xFF                << 24)  // alpha = opaque
                 | ((rgb        & 0xFF) << 16)  // B → bits 16-23
                 | (((rgb >> 8) & 0xFF) <<  8)  // G → bits  8-15
                 | ( (rgb >> 16) & 0xFF       ); // R → bits  0-7
        }

        @Override
        protected void drawInternal(net.minecraft.client.gui.GuiGraphics gfx,
                float mouseX, float mouseY, float screenX, float screenY,
                float elemW, float elemH, float partialTick) {
            // LDLib2 passes: (gfx, localMouseX, localMouseY, screenX, screenY, elemW, elemH, partialTick)
            // blit(rl, destX, destY, uOffset, vOffset, blitW, blitH, texW, texH)
            gfx.blit(rl, (int) screenX, (int) screenY, 0f, 0f, (int) elemW, (int) elemH, size, size);
        }
    }

    // ── Wars ──────────────────────────────────────────────────────────────────

    private UIElement buildWarsPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(8).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        warsPanel = panel;
        fillWarsPanel(player);
        return panel;
    }

    private void fillWarsPanel(Player player) {
        if (warsPanel == null) return;
        warsPanel.clearAllChildren();
        if (warsSubView == WarsSubView.SELECT_ATTACKERS) buildAttackerSelectView(player);
        else buildWarsListView(player);
    }

    private void buildWarsListView(Player player) {
        // ── Active wars summary ───────────────────────────────────────────────
        Faction myFaction = factionRef.get();
        warsPanel.addChildren(new Label().setText("§6§l⚔ Wars"));
        if (myFaction != null && !myFaction.getWars().isEmpty()) {
            warsPanel.addChildren(new Label().setText("§7── Your Active Wars ──"));
            for (WarEntry we : myFaction.getWars()) {
                var wr = new UIElement();
                wr.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(18).width(404));
                wr.addChildren(new Label().setText("§c⚔ At war with: §e" + we.targetFactionName()).layout(l -> l.flex(1)));
                warsPanel.addChildren(wr);
            }
        } else {
            warsPanel.addChildren(new Label().setText("§7No active wars."));
        }

        // ── Faction list to wage war against ─────────────────────────────────
        warsPanel.addChildren(new Label().setText("§7── Wage War ──"));
        warsListArea = new UIElement();
        warsListArea.layout(l -> l.flex(1).width(404));
        fillWarsFactionList(warsListArea, player);

        var scrollRow = new UIElement();
        scrollRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4));
        scrollRow.addChildren(
                new Button().setText("▲").setOnClick(e -> {
                    allFactionsScroll = Math.max(0, allFactionsScroll - 1);
                    warsListArea.clearAllChildren(); fillWarsFactionList(warsListArea, player);
                }).layout(l -> l.width(20)),
                new Button().setText("▼").setOnClick(e -> {
                    int max = Math.max(0, allFactions.get().size() - WARS_ROWS);
                    allFactionsScroll = Math.min(max, allFactionsScroll + 1);
                    warsListArea.clearAllChildren(); fillWarsFactionList(warsListArea, player);
                }).layout(l -> l.width(20))
        );
        warsPanel.addChildren(warsListArea, scrollRow);
    }

    private void fillWarsFactionList(UIElement area, Player player) {
        area.clearAllChildren();
        Faction myFaction = factionRef.get();
        List<FactionSummary> list = allFactions.get();
        if (list.isEmpty()) { area.addChildren(new Label().setText("§7No other factions found.")); return; }
        boolean canWar = isOfficer(player);
        for (int i = 0; i < WARS_ROWS; i++) {
            int idx = i + allFactionsScroll;
            if (idx >= list.size()) break;
            FactionSummary s = list.get(idx);
            boolean atWar = myFaction != null && myFaction.getWars().stream()
                    .anyMatch(w -> w.targetFactionId().equals(s.id()));
            var row = new UIElement();
            row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(20).width(404));
            row.addChildren(
                    new Label().setText((atWar ? "§c" : "§e") + s.name()).layout(l -> l.width(110)),
                    new Label().setText("§7" + s.memberCount() + "mbr").layout(l -> l.width(42)),
                    new Label().setText("§aPow:" + s.power()).layout(l -> l.width(56)),
                    new Label().setText("§b" + s.onlineCount() + " on").layout(l -> l.flex(1))
            );
            if (canWar) {
                if (atWar) {
                    row.addChildren(new Label().setText("§c⚔ War").layout(l -> l.width(72)));
                } else {
                    var wageBtn = new Button().setText("§c⚔ Wage War")
                            .setOnClick(e -> {
                                warsTarget = s;
                                warsSelectedAttackers.clear();
                                warsSelectedAttackers.add(player.getUUID()); // pre-select self
                                warsSubView = WarsSubView.SELECT_ATTACKERS;
                                fillWarsPanel(player);
                            });
                    wageBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                            Component.literal("§c⚔ Wage War on §e" + s.name()),
                            Component.literal("§7Power: §a" + s.power() + "  §7Members: §e" + s.memberCount()),
                            Component.literal("§7Attackers get §c" + com.admin82.factions.Config.WAR_ATTACKER_LIVES.get() + " lives §7each.")));
                    wageBtn.layout(l -> l.width(72));
                    row.addChildren(wageBtn);
                }
            }
            area.addChildren(row);
        }
    }

    private void buildAttackerSelectView(Player player) {
        Faction myFaction = factionRef.get();
        warsPanel.addChildren(
                new Label().setText("§6§lChoose Attackers"),
                new Label().setText("§7Target: §c" + (warsTarget != null ? warsTarget.name() : "?"))
        );
        if (myFaction != null) {
            for (FactionMember fm : myFaction.getMembers()) {
                UUID uid = fm.getUuid();
                boolean selected = warsSelectedAttackers.contains(uid);
                var row = new UIElement();
                row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(20).width(404));
                row.addChildren(
                        new Label().setText(roleColor(fm.getRole()) + fm.getPlayerName() + " §8(" + fm.getRole().name().toLowerCase() + ")").layout(l -> l.flex(1)),
                        new Toggle().setOn(selected).setOnToggleChanged(on -> {
                            if (on) warsSelectedAttackers.add(uid);
                            else    warsSelectedAttackers.remove(uid);
                        }).layout(l -> l.width(30))
                );
                warsPanel.addChildren(row);
            }
        }
        var btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).paddingTop(6));
        btnRow.addChildren(
                new Button().setText("§a✓ Declare War")
                        .setOnClick(e -> {
                            if (warsTarget != null && !warsSelectedAttackers.isEmpty()) {
                                PacketDistributor.sendToServer(
                                        new com.admin82.factions.network.packet.WageWarPacket(
                                                warsTarget.id(), new ArrayList<>(warsSelectedAttackers)));
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isOfficer(Player player) {
        Faction f = factionRef.get(); if (f == null) return false;
        FactionMember m = f.getMember(player.getUUID());
        return m != null && m.getRole().getLevel() >= FactionRole.OFFICER.getLevel();
    }

    private static String roleColor(FactionRole role) {
        return switch (role) {
            case OWNER   -> "§6";
            case ADMIN   -> "§5";
            case OFFICER -> "§9";
            case MEMBER  -> "§f";
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String permLabel(FactionPermission p) {
        return switch (p) {
            case MEMBER_BUILD        -> "Build";
            case MEMBER_INTERACT     -> "Interact";
            case MEMBER_USE_STORAGE  -> "Use Storage";
            case OFFICER_INVITE      -> "Invite members";
            case OFFICER_CLAIM       -> "Claim land";
            case OFFICER_KICK        -> "Kick members";
            case OFFICER_DECLARE_WAR -> "Declare war";
            case VAULT_WITHDRAW      -> "Vault withdraw";
        };
    }

    private static String permDescription(FactionPermission p) {
        return switch (p) {
            case MEMBER_BUILD        -> "Can place & break blocks in claimed territory";
            case MEMBER_INTERACT     -> "Can use buttons, levers, doors, chests, etc.";
            case MEMBER_USE_STORAGE  -> "Can open and take items from faction storage";
            case OFFICER_INVITE      -> "Can send invitations to other players";
            case OFFICER_CLAIM       -> "Can claim/unclaim chunks on the territory map";
            case OFFICER_KICK        -> "Can remove lower-ranked members from the faction";
            case OFFICER_DECLARE_WAR -> "Can declare war on other factions";
            case VAULT_WITHDRAW      -> "Can withdraw coins from the faction vault";
        };
    }

    // ── Upkeep ────────────────────────────────────────────────────────────────

    private UIElement buildUpkeepPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(8).gapAll(5).flexDirection(YogaFlexDirection.COLUMN));
        panel.addChildren(new Label().setText("§6§lLand Deeds & Upkeep"));

        Faction faction = factionRef.get();
        if (faction == null) {
            panel.addChildren(new Label().setText("§7You are not in a faction."));
            return panel;
        }

        var claims = faction.getLandClaims();
        int numClaims = claims.size();
        long dailyCost = (long) numClaims * Currency.UPKEEP_PER_CLAIM_PER_DAY;

        // Deed list header
        var listHeader = new UIElement();
        listHeader.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(16).width(404).paddingHorizontal(2));
        listHeader.addChildren(
                new Label().setText("§8Chunk (X, Z)").layout(l -> l.flex(1)),
                new Label().setText("§8Dimension").layout(l -> l.width(80)),
                new Label().setText("§8Cost/day").layout(l -> l.width(56))
        );
        panel.addChildren(listHeader);

        // Scrollable deed rows
        int[] scroll = {0};
        final int DEED_ROWS = 5;

        var deedArea = new UIElement();
        deedArea.layout(l -> l.width(404).flexDirection(YogaFlexDirection.COLUMN).gapAll(1));

        Runnable fillDeeds = () -> {
            deedArea.clearAllChildren();
            if (claims.isEmpty()) {
                deedArea.addChildren(new Label().setText("§7No claimed chunks yet."));
                return;
            }
            for (int i = 0; i < DEED_ROWS; i++) {
                int idx = i + scroll[0];
                if (idx >= claims.size()) break;
                var claim = claims.get(idx);
                var row = new UIElement();
                row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(17).width(404));
                row.addChildren(
                        new Label().setText("§f(" + claim.chunkX() + ", " + claim.chunkZ() + ")")
                                .layout(l -> l.flex(1)),
                        new Label().setText("§7" + simplifyDim(claim.dimension().toString()))
                                .layout(l -> l.width(80)),
                        new Label().setText("§e" + Currency.format(Currency.UPKEEP_PER_CLAIM_PER_DAY))
                                .layout(l -> l.width(56))
                );
                deedArea.addChildren(row);
            }
        };
        fillDeeds.run();

        var scrollRow = new UIElement();
        scrollRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4));
        scrollRow.addChildren(
                new Button().setText("▲").setOnClick(e -> {
                    scroll[0] = Math.max(0, scroll[0] - 1); fillDeeds.run();
                }).layout(l -> l.width(20)),
                new Button().setText("▼").setOnClick(e -> {
                    scroll[0] = Math.min(Math.max(0, claims.size() - DEED_ROWS), scroll[0] + 1);
                    fillDeeds.run();
                }).layout(l -> l.width(20))
        );

        panel.addChildren(deedArea, scrollRow);
        panel.addChildren(new Label().setText("§8─────────────────────────────────────────"));

        // Summary
        panel.addChildren(new Label().setText(
                "§fClaims: §e" + numClaims + " §7× §e" + Currency.format(Currency.UPKEEP_PER_CLAIM_PER_DAY)
                        + "§7/day = §e" + Currency.format(dailyCost) + "§7/day total"));

        // Vault balance (live)
        panel.addChildren(new Label().bindDataSource(SupplierDataSource.of(() ->
                Component.literal("§fFaction Vault: §a" + Currency.format(factionVaultRef.get())))));

        panel.addChildren(new Label().setText("§8─────────────────────────────────────────"));

        // Live upkeep-time countdown
        // We compute: how many seconds of upkeep does the current vault balance fund?
        //   secondsRemaining = vaultBalance * 86400 / dailyCost
        // We count the elapsed time from when the panel was opened and subtract.
        long[] openedAt = { System.currentTimeMillis() };

        var timerLabel = new Label();
        timerLabel.layout(l -> l.width(404));
        timerLabel.addEventListener(UIEvents.TICK, ev -> {
            long vault  = factionVaultRef.get();
            long daily  = (long) faction.getLandClaims().size() * Currency.UPKEEP_PER_CLAIM_PER_DAY;
            if (faction.getLandClaims().isEmpty()) {
                timerLabel.setText("§aNo claims — no upkeep required.");
                return;
            }
            if (vault <= 0 || daily <= 0) {
                timerLabel.setText("§c⚠ Vault empty — claims will be released on next upkeep cycle!");
                return;
            }
            // Base seconds from vault balance
            long baseSeconds = (vault * 86400L) / daily;
            // Subtract time elapsed since panel was opened (visual countdown)
            long elapsed = (System.currentTimeMillis() - openedAt[0]) / 1000L;
            long remaining = Math.max(0L, baseSeconds - elapsed);
            long d = remaining / 86400;
            long h = (remaining % 86400) / 3600;
            long m = (remaining % 3600) / 60;
            long s = remaining % 60;
            String color = remaining > 86400 ? "§a" : remaining > 3600 ? "§e" : "§c";
            String txt = "⏱ " + color;
            if (d > 0) txt += d + "d ";
            txt += String.format("%02d", h) + "h " + String.format("%02d", m) + "m " + String.format("%02d", s) + "s";
            txt += " §7of upkeep funded";
            timerLabel.setText(txt);
        });
        panel.addChildren(timerLabel);
        panel.addChildren(new Label().setText("§8(Based on current vault balance ÷ daily cost)"));

        return panel;
    }

    /** Converts a dimension resource location string to a short readable name. */
    private static String simplifyDim(String dim) {
        if (dim == null) return "Overworld";
        if (dim.contains("overworld")) return "Overworld";
        if (dim.contains("nether"))    return "Nether";
        if (dim.contains("end"))       return "The End";
        int colon = dim.lastIndexOf(':');
        return colon >= 0 ? dim.substring(colon + 1) : dim;
    }

    // ── Vault ─────────────────────────────────────────────────────────────────

    private UIElement buildVaultPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(10).gapAll(8).flexDirection(YogaFlexDirection.COLUMN));

        panel.addChildren(new Label().setText("§6§lFaction Bank"));

        // Wallet section
        panel.addChildren(new Label().setText("§7── Personal Wallet ──"));
        panel.addChildren(new Label().bindDataSource(SupplierDataSource.of(() ->
                Component.literal("§fBalance: §a" + Currency.format(playerWalletRef.get())))));

        long[] walletQty  = {0};
        String[] walletCoin = {"Copper"};
        var walletFields = new UIElement();
        walletFields.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).height(24));
        TextField walletAmtField = new TextField();
        walletAmtField.setValue("0");
        walletAmtField.bindObserver(v -> { try { walletQty[0] = Math.max(0, Long.parseLong(v.trim())); } catch (NumberFormatException ignored) {} });
        walletAmtField.layout(l -> l.width(80));
        var walletCoinSel = new Selector<String>();
        walletCoinSel.setCandidates(java.util.List.of("Copper", "Silver", "Gold", "Platinum"));
        walletCoinSel.setValue("Copper");
        walletCoinSel.setOnValueChanged(v -> walletCoin[0] = v);
        walletCoinSel.layout(l -> l.width(86).height(24));
        var depositWalletBtn = new Button().setText("§aDeposit").setOnClick(e -> {
            long amt = walletQty[0] * vaultCoinMult(walletCoin[0]);
            if (amt > 0) PacketDistributor.sendToServer(new VaultActionPacket(VaultActionPacket.Action.DEPOSIT_WALLET, amt));
        });
        depositWalletBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                Component.literal("§aDeposit physical coins"),
                Component.literal("§7Takes coin items from your inventory."),
                Component.literal("§7Adds to virtual wallet balance.")));
        depositWalletBtn.layout(l -> l.width(70));
        var withdrawWalletBtn = new Button().setText("§eWithdraw").setOnClick(e -> {
            long amt = walletQty[0] * vaultCoinMult(walletCoin[0]);
            if (amt > 0) PacketDistributor.sendToServer(new VaultActionPacket(VaultActionPacket.Action.WITHDRAW_WALLET, amt));
        });
        withdrawWalletBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                Component.literal("§eWithdraw to physical coins"),
                Component.literal("§7Converts wallet balance into coin items.")));
        withdrawWalletBtn.layout(l -> l.width(76));
        walletFields.addChildren(walletAmtField, walletCoinSel, depositWalletBtn, withdrawWalletBtn);

        // Faction vault section
        panel.addChildren(new Label().setText("§7── Faction Vault ──"));
        panel.addChildren(new Label().bindDataSource(SupplierDataSource.of(() ->
                Component.literal("§fVault: §a" + Currency.format(factionVaultRef.get())))));

        long[] vaultQty  = {0};
        String[] vaultCoin = {"Copper"};
        var vaultFields = new UIElement();
        vaultFields.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).height(24));
        TextField vaultAmtField = new TextField();
        vaultAmtField.setValue("0");
        vaultAmtField.bindObserver(v -> { try { vaultQty[0] = Math.max(0, Long.parseLong(v.trim())); } catch (NumberFormatException ignored) {} });
        vaultAmtField.layout(l -> l.width(80));
        var vaultCoinSel = new Selector<String>();
        vaultCoinSel.setCandidates(java.util.List.of("Copper", "Silver", "Gold", "Platinum"));
        vaultCoinSel.setValue("Copper");
        vaultCoinSel.setOnValueChanged(v -> vaultCoin[0] = v);
        vaultCoinSel.layout(l -> l.width(86).height(24));
        var depositFactionBtn = new Button().setText("§aDeposit").setOnClick(e -> {
            long amt = vaultQty[0] * vaultCoinMult(vaultCoin[0]);
            if (amt > 0) PacketDistributor.sendToServer(new VaultActionPacket(VaultActionPacket.Action.DEPOSIT_FACTION, amt));
        });
        depositFactionBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                Component.literal("§aDeposit to Faction Vault"),
                Component.literal("§7Transfers from your wallet to the faction vault."),
                Component.literal("§7Used for upkeep and faction expenses.")));
        depositFactionBtn.layout(l -> l.width(70));
        var withdrawFactionBtn = new Button().setText("§eWithdraw").setOnClick(e -> {
            long amt = vaultQty[0] * vaultCoinMult(vaultCoin[0]);
            if (amt > 0) PacketDistributor.sendToServer(new VaultActionPacket(VaultActionPacket.Action.WITHDRAW_FACTION, amt));
        });
        withdrawFactionBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                Component.literal("§eWithdraw from Faction Vault"),
                Component.literal("§7Requires VAULT_WITHDRAW permission or owner.")));
        withdrawFactionBtn.layout(l -> l.width(76));
        vaultFields.addChildren(vaultAmtField, vaultCoinSel, depositFactionBtn, withdrawFactionBtn);

        panel.addChildren(walletFields, vaultFields);

        // Upkeep info
        panel.addChildren(new Label().setText("§8Upkeep: §720 copper / claim / 24h. Faction vault covers it."));

        return panel;
    }

    /** Converts a coin-type dropdown selection to its copper multiplier. */
    private static long vaultCoinMult(String coin) {
        return switch (coin) {
            case "Platinum" -> Currency.COPPER_PER_PLATINUM;
            case "Gold"     -> Currency.COPPER_PER_GOLD;
            case "Silver"   -> Currency.COPPER_PER_SILVER;
            default         -> 1L;
        };
    }

    // ── Leaderboard ───────────────────────────────────────────────────────────

    private UIElement buildLeaderboardPanel() {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(10).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        panel.addChildren(new Label().setText("§6§lFaction Leaderboard — by vault wealth"));

        // Header row
        var header = new UIElement();
        header.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(18).width(400).gapAll(4));
        header.addChildren(
                new Label().setText("§7#").layout(l -> l.width(20)),
                new Label().setText("§7Faction").layout(l -> l.flex(1)),
                new Label().setText("§7Members").layout(l -> l.width(54)),
                new Label().setText("§7Vault").layout(l -> l.width(80))
        );
        panel.addChildren(header);

        // Live leaderboard rows
        var listArea = new UIElement();
        listArea.layout(l -> l.flex(1).width(400));

        // Rebuild once per second — sorting a small list every tick wastes CPU
        int[] lbTick = {0};
        listArea.addEventListener(UIEvents.TICK, ev -> {
            if (++lbTick[0] % 20 != 0) return;
            listArea.clearAllChildren();
            var sorted = allFactions.get().stream()
                    .sorted((a, b) -> Long.compare(b.totalWealth(), a.totalWealth()))
                    .limit(10)
                    .toList();
            if (sorted.isEmpty()) {
                listArea.addChildren(new Label().setText("§7No factions found."));
                return;
            }
            for (int i = 0; i < sorted.size(); i++) {
                FactionSummary fs = sorted.get(i);
                String rank = switch (i) {
                    case 0 -> "§6§l1.";
                    case 1 -> "§7§l2.";
                    case 2 -> "§c§l3.";
                    default -> "§f" + (i + 1) + ".";
                };
                var row = new UIElement();
                row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(18).width(400));
                row.addChildren(
                        new Label().setText(rank).layout(l -> l.width(20)),
                        new Label().setText("§e" + fs.name()).layout(l -> l.flex(1)),
                        new Label().setText("§f" + fs.memberCount()).layout(l -> l.width(54)),
                        new Label().setText("§a" + Currency.format(fs.totalWealth())).layout(l -> l.width(80))
                );
                listArea.addChildren(row);
            }
        });
        panel.addChildren(listArea);
        return panel;
    }

    // ── Vassal ────────────────────────────────────────────────────────────────

    private UIElement buildVassalPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(10).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));

        Faction myFaction = factionRef.get();
        if (myFaction == null) {
            panel.addChildren(new Label().setText("§7Join a faction to see vassal information."));
            return panel;
        }

        // Note: VassalManager is server-side only. On the client we use data sent at menu-open
        // time (vassalSuzerainName, vassalIsVassal, vassalSubjects). These are populated by
        // FactionTableBlock when it writes the buf for the FriendlyByteBuf constructor path.
        // For now we show the static data we have; live updates would require a sync packet.

        if (vassalIsVassal) {
            // ── Vassal view ─────────────────────────────────────────────────
            panel.addChildren(
                    new Label().setText("§c§l⚑ Vassal State"),
                    new Label().setText("§7Your faction is a vassal of:"),
                    new Label().setText("§e" + (vassalSuzerainName.isEmpty() ? "Unknown" : vassalSuzerainName)),
                    new Label().setText("§7Accumulated tax (pending): §e" + Currency.format(vassalPendingTax)),
                    new Label().setText("§8Tax is collected from your market sales and land claims.")
            );

            var btnRow = new UIElement();
            btnRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(8).paddingTop(6));

            var buyoutBtn = new Button()
                    .setText("§a💰 Buy Independence (§e" + Currency.format(Config.VASSAL_BUYOUT_COPPER.get()) + "§a)")
                    .setOnClick(e -> PacketDistributor.sendToServer(
                            new ConquestActionPacket(ConquestActionPacket.Action.BUYOUT, myFaction.getId())))
                    .layout(l -> l.flex(1).height(30));
            btnRow.addChildren(buyoutBtn);
            panel.addChildren(btnRow);

        } else if (vassalIsSuzerain) {
            // ── Suzerain view ────────────────────────────────────────────────
            panel.addChildren(new Label().setText("§6§l⚑ Vassal States (" + vassalSubjects.size() + ")"));

            if (vassalSubjects.isEmpty()) {
                panel.addChildren(new Label().setText("§7You have no vassal factions."));
            } else {
                for (VassalSubjectInfo v : vassalSubjects) {
                    var row = new UIElement();
                    row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).height(28).width(400));

                    row.addChildren(
                            new Label().setText("§e" + v.name()).layout(l -> l.flex(1)),
                            new Label().setText("§7Tax: §a" + Currency.format(v.pendingTax())).layout(l -> l.width(100)),
                            new Button().setText("§a✓ Collect")
                                    .setOnClick(e -> PacketDistributor.sendToServer(
                                            new ConquestActionPacket(ConquestActionPacket.Action.COLLECT_TAX, v.factionId())))
                                    .layout(l -> l.width(58).height(20)),
                            new Button().setText("§cFree")
                                    .setOnClick(e -> PacketDistributor.sendToServer(
                                            new ConquestActionPacket(ConquestActionPacket.Action.FREE_VASSAL, v.factionId())))
                                    .layout(l -> l.width(38).height(20))
                    );
                    panel.addChildren(row);
                }
            }
        } else {
            panel.addChildren(
                    new Label().setText("§7Your faction is not in any vassal relationship."),
                    new Label().setText("§7Win a war to gain a vassal, or lose one to become a vassal.")
            );
        }

        return panel;
    }

    /** Lightweight client-side snapshot of a vassal faction. */
    public record VassalSubjectInfo(UUID factionId, String name, long pendingTax) {}
}
