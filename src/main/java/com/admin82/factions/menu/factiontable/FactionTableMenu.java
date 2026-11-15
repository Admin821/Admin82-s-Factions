package com.admin82.factions.menu.factiontable;

import com.admin82.factions.Config;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.Transaction;
import com.admin82.factions.faction.*;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.menu.factiontable.WarsTab.WarsSubView;
import com.admin82.factions.network.packet.*;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.war.WarDemand;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.appliedenergistics.yoga.YogaJustify;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class FactionTableMenu extends AbstractContainerMenu {

    final NegotiationsView negotiations = new NegotiationsView(this);

    final MembersTab members = new MembersTab(this);
    private final TerritoryTab territory = new TerritoryTab(this);
    final AlliancesTab alliances = new AlliancesTab(this);
    private final UpgradesTab upgrades = new UpgradesTab(this);
    final WarsTab wars = new WarsTab(this);
    final VaultTab vault = new VaultTab(this);

    // Persistent state
    final BlockPos tablePos;
    String tableDim;
    final AtomicReference<Faction>              factionRef         = new AtomicReference<>();
    final AtomicReference<List<FactionSummary>> allFactions        = new AtomicReference<>(List.of());
    final AtomicReference<Set<String>>          otherClaimedChunks = new AtomicReference<>(Set.of());
    final AtomicReference<List<String>>         availablePlayers   = new AtomicReference<>(List.of());
    final AtomicReference<Long>                 playerWalletRef    = new AtomicReference<>(0L);
    final AtomicReference<Long>                 factionVaultRef    = new AtomicReference<>(0L);
    final AtomicReference<Double>       claimRateRef      = new AtomicReference<>(1.4);

    private Tab         currentTab       = Tab.OVERVIEW;
    int         allFactionsScroll = 0;

    // Vassal tab state (populated from server buf at menu open)
    private boolean vassalIsVassal      = false;
    private boolean vassalIsSuzerain    = false;
    private String  vassalSuzerainName  = "";
    private long    vassalPendingTax    = 0L;
    private List<VassalSubjectInfo> vassalSubjects = new ArrayList<>();

    // Live UIElement references

    @Nullable Player playerRef;

    public enum Tab { OVERVIEW, MEMBERS, PERMISSIONS, TERRITORY, UPKEEP, WARS, VAULT, LEADERBOARD, VASSAL, ALLIANCES, UPGRADES }

    // Constructors

    public FactionTableMenu(int containerId, Inventory inv, BlockPos tablePos, @Nullable Faction faction) {
        this(containerId, inv, tablePos, faction, 1.4);
    }

    public FactionTableMenu(int containerId, Inventory inv, BlockPos tablePos, @Nullable Faction faction, double claimRateMultiplier) {
        super(ModMenuTypes.FACTION_TABLE.get(), containerId);
        this.tablePos = tablePos;
        this.tableDim = inv.player.level().dimension().location().toString();
        this.factionRef.set(faction);
        this.claimRateRef.set(Math.max(1.0, claimRateMultiplier));
        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu h) h.setModularUI(createModularUI(inv.player));
    }

    public FactionTableMenu(int containerId, Inventory inv, FriendlyByteBuf buf) {
        this(containerId, inv, buf.readBlockPos(), buf.readBoolean() ? Faction.fromNetwork(buf) : null,
                buf.isReadable() ? buf.readDouble() : 1.4);
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

    // Public API

    @Nullable public Faction getFaction()  { return factionRef.get(); }
    public BlockPos          getTablePos() { return tablePos; }

    /** Called by SyncEconomyPacket to update wallet/vault balances. */
    public void updateEconomy(long wallet, long vaultBalance) {
        boolean walletChanged = playerWalletRef.get() != wallet;
        boolean vaultChanged = factionVaultRef.get() != vaultBalance;
        playerWalletRef.set(wallet);
        factionVaultRef.set(vaultBalance);
        // Keep an open history list live too.
        if (currentTab == Tab.VAULT && vault.shownHistory == HistoryRequestPacket.Scope.WALLET && walletChanged
                || vault.shownHistory == HistoryRequestPacket.Scope.VAULT && vaultChanged) {
            PacketDistributor.sendToServer(new HistoryRequestPacket(vault.shownHistory));
        }
    }

    /** Called by SyncWarDemandsPacket to refresh the negotiations view. */
    public void updateWarDemands(UUID warId, List<WarDemand> demands) {
        wars.warDemandsRef.set(demands);
        if (negotiations.confirmDemandId != null && demands.stream().noneMatch(d -> d.demandId.equals(negotiations.confirmDemandId))) negotiations.confirmDemandId = null;
        if ((wars.warsSubView == WarsSubView.NEGOTIATIONS || wars.warsSubView == WarsSubView.COMPOSE_DEMAND)
                && playerRef != null) {
            wars.fillWarsPanel(playerRef);
        }
    }

    /** Called by SyncEnemyClaimsPacket, populates the Territory-War selection map. */
    public void updateEnemyClaims(UUID targetFactionId,
                                  List<String> claimKeys,
                                  int coreCX, int coreCZ, String coreDim) {
        wars.enemyClaimKeysRef.set(claimKeys);
        wars.enemyCoreCX  = coreCX;
        wars.enemyCoreCZ  = coreCZ;
        wars.enemyCoreDim = coreDim;
        // Centre the war map on the enemy territory
        if (tablePos != null) {
            int playerCoreX = SectionPos.blockToSectionCoord(tablePos.getX());
            int playerCoreZ = SectionPos.blockToSectionCoord(tablePos.getZ());
            wars.warMapOffsetX = coreCX - playerCoreX;
            wars.warMapOffsetZ = coreCZ - playerCoreZ;
        }
        if (wars.warsSubView == WarsSubView.SELECT_TERRITORY && playerRef != null) {
            wars.fillWarsPanel(playerRef);
        }
    }

    public void updateFaction(@Nullable Faction f) { factionRef.set(f); }

    @SuppressWarnings("unchecked")
    public void updateAllFactions(List<FactionSummary> factions,
                                  List<String>         otherClaims,
                                  List<String>         players) {
        allFactions.set(factions);
        otherClaimedChunks.set(new HashSet<>(otherClaims));
        availablePlayers.set(players);
        if (wars.warsListArea != null && playerRef != null) {
            wars.warsListArea.clearAllChildren();
            wars.fillWarsFactionList(wars.warsListArea, playerRef);
        }
        if (territory.mapGrid != null) {
            int coreX = SectionPos.blockToSectionCoord(tablePos.getX());
            int coreZ = SectionPos.blockToSectionCoord(tablePos.getZ());
            territory.mapGrid.clearAllChildren();
            territory.fillMapCells(territory.mapGrid, coreX, coreZ, playerRef != null && isOfficer(playerRef));
        }
        if (members.inviteSelectorRef != null) members.inviteSelectorRef.setCandidates(players);
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

    // UI CONSTRUCTION

    // Layout
    /** Content panels are built for 420px; the sidebar sits to their left. */
    static final int CONTENT_WIDTH = 420;
    private static final int SIDEBAR_WIDTH = 114;
    private static final int DESIGN_WIDTH = CONTENT_WIDTH + SIDEBAR_WIDTH + 6 + 16 + 4;
    private static final int DESIGN_HEIGHT = 360;
    static final String ACTIVE_BG = "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)";
    static final String DANGER_BG = "built-in(ui-mc:RECT_BORDER) color(#aa2222ff)";
    private int panelHeight = DESIGN_HEIGHT;
    @Nullable private UIElement[] tabPanels;
    @Nullable private UIElement contentAreaRef;
    @Nullable private UIElement sidebarRef;
    private boolean disbandArmed;
    private boolean leaveArmed;

    /** Called by SyncHistoryPacket with wallet or vault history. */
    public void updateHistory(HistoryRequestPacket.Scope scope,
                              List<Transaction> entries) {
        switch (scope) {
            case WALLET -> vault.walletHistory = entries;
            case VAULT -> vault.vaultHistory = entries;
            default -> { return; }
        }
        if (scope == vault.shownHistory) vault.renderBankHistory();
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI createModularUI(Player player) {
        playerRef = player;
        tableDim  = player.level().dimension().location().toString();
        int[] size = UiKit.panelSize(DESIGN_WIDTH, DESIGN_HEIGHT, DESIGN_WIDTH, 300);
        panelHeight = size[1];

        var frame = new UIElement();
        frame.layout(l -> l.width(DESIGN_WIDTH).height(panelHeight).paddingAll(2));
        frame.addClass("preview_bg");

        var root = new UIElement();
        root.layout(l -> l.width(DESIGN_WIDTH - 4).height(panelHeight - 4).paddingAll(8)
                .flexDirection(YogaFlexDirection.COLUMN).gapAll(6));
        root.addClass("panel_bg");

        root.addChildren(factionRef.get() == null ? buildCreatePanel() : buildManagePanel(player));
        frame.addChildren(root);

        return ModularUI.of(
                UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)),
                player);
    }

    // Create-faction panel

    private UIElement buildCreatePanel() {
        var nameVal   = new String[]{""};
        var descVal   = new String[]{""};
        var statusVal = new String[]{""};
        var wrapper = new UIElement();
        wrapper.layout(l -> l.flex(1).alignItems(YogaAlign.CENTER).justifyContent(YogaJustify.CENTER));
        var card = UiKit.background(new UIElement(), UiKit.CARD);
        card.layout(l -> l.width(360).paddingAll(12).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        var nameField = UiKit.textField("");
        nameField.setValue("");
        nameField.bindObserver(v -> nameVal[0] = v);
        nameField.getTextFieldStyle().placeholder(Component.literal("At least 3 characters"));
        nameField.layout(l -> l.height(18));
        var descField = UiKit.textField("");
        descField.setValue("");
        descField.bindObserver(v -> descVal[0] = v);
        descField.getTextFieldStyle().placeholder(Component.literal("Optional"));
        descField.layout(l -> l.height(18));
        card.addChildren(
                new Label().setText("§6§lCreate a Faction"),
                new Label().setText("§7Claim land, build a vault and go to war together."),
                new Label().setText("§e⚠ Place a §6Barracks§e block first. §8It becomes your spawn point."),
                new Label().setText("§fFaction name"),
                nameField,
                new Label().setText("§fDescription"),
                descField,
                new Button().setText("§aCreate Faction")
                        .setOnClick(e -> {
                            String name = nameVal[0].trim();
                            if (name.length() < 3) { statusVal[0] = "§cName must be at least 3 characters!"; return; }
                            PacketDistributor.sendToServer(new CreateFactionPacket(name, descVal[0].trim()));
                            statusVal[0] = "§7Creating...";
                        })
                        .layout(l -> l.alignSelf(YogaAlign.CENTER).width(160).height(20)),
                new Label().bindDataSource(SupplierDataSource.of(() -> Component.literal(statusVal[0])))
        );
        wrapper.addChildren(card);
        return wrapper;
    }

    // Management shell: header + sidebar + page

    private UIElement buildManagePanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(6));
        if (currentTab == Tab.PERMISSIONS && !isAdmin(player)) currentTab = Tab.OVERVIEW;
        if (currentTab == Tab.WARS && !canWageWar(player)) currentTab = Tab.OVERVIEW;
        if (currentTab == Tab.VASSAL && !vassalIsVassal && !vassalIsSuzerain) currentTab = Tab.OVERVIEW;

        tabPanels = new UIElement[]{
                buildOverviewPanel(player),
                members.buildMembersPanel(player),
                members.buildPermissionsPanel(player),
                territory.buildTerritoryPanel(player),
                buildUpkeepPanel(player),
                wars.buildWarsPanel(player),
                vault.buildVaultPanel(player),
                buildLeaderboardPanel(),
                buildVassalPanel(player),
                alliances.buildAlliancesPanel(player),
                upgrades.buildPanel()
        };

        // Header: faction name + live stats
        var header = new UIElement();
        header.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).height(16));
        var stats = new Label();
        stats.bindDataSource(SupplierDataSource.of(() -> {
            Faction f = factionRef.get();
            if (f == null) return Component.empty();
            boolean protectedLand = factionVaultRef.get() > 0;
            return Component.literal("§7Members §f" + f.getMembers().size()
                    + "   §7Land §f" + f.getLandClaims().size()
                    + "   §7Vault §a" + Currency.format(factionVaultRef.get())
                    + "   " + (protectedLand ? "§a● Protected" : "§c● Unprotected"));
        }));
        stats.lss("horizontal-align", "right");
        stats.layout(l -> l.width(340));
        header.addChildren(new Label().bindDataSource(SupplierDataSource.of(() -> {
            Faction f = factionRef.get();
            return Component.literal(f != null ? "§6§l" + f.getName() : "");
        })).layout(l -> l.flex(1)), stats);

        var body = new UIElement();
        body.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.ROW).gapAll(6));

        sidebarRef = UiKit.background(new UIElement(), UiKit.CARD);
        sidebarRef.layout(l -> l.width(SIDEBAR_WIDTH).paddingAll(4).gapAll(2).flexDirection(YogaFlexDirection.COLUMN));
        fillSidebar(player);

        contentAreaRef = new UIElement();
        contentAreaRef.layout(l -> l.width(CONTENT_WIDTH).flex(1));
        contentAreaRef.addChildren(tabPanels[currentTab.ordinal()]);

        if (panelHeight < DESIGN_HEIGHT) {
            // Small window / large GUI scale: let the page scroll instead of spilling out.
            var scroller = new ScrollerView();
            scroller.layout(l -> l.width(CONTENT_WIDTH).flex(1));
            contentAreaRef.layout(l -> l.width(CONTENT_WIDTH - 10).height(DESIGN_HEIGHT - 60));
            scroller.addScrollViewChild(contentAreaRef);
            body.addChildren(sidebarRef, scroller);
        } else {
            body.addChildren(sidebarRef, contentAreaRef);
        }
        panel.addChildren(header, body);
        return panel;
    }

    /** Grouped navigation: Faction / Land / Economy / Diplomacy. */
    private void fillSidebar(Player player) {
        if (sidebarRef == null) return;
        sidebarRef.clearAllChildren();
        sidebarRef.addChildren(navSection("Faction"), navButton(Tab.OVERVIEW, player), navButton(Tab.MEMBERS, player));
        if (isAdmin(player)) sidebarRef.addChildren(navButton(Tab.PERMISSIONS, player));
        sidebarRef.addChildren(navSection("Land"), navButton(Tab.TERRITORY, player), navButton(Tab.UPKEEP, player),
                navSection("Economy"), navButton(Tab.VAULT, player), navButton(Tab.LEADERBOARD, player),
                navSection("Upgrades"), navButton(Tab.UPGRADES, player));
        boolean wars = canWageWar(player);
        boolean vassal = vassalIsVassal || vassalIsSuzerain;
        sidebarRef.addChildren(navSection("Diplomacy"));
        if (wars) sidebarRef.addChildren(navButton(Tab.WARS, player));
        sidebarRef.addChildren(navButton(Tab.ALLIANCES, player));
        if (vassal) sidebarRef.addChildren(navButton(Tab.VASSAL, player));
    }

    private UIElement navSection(String text) {
        return new Label().setText("§6§l" + text).layout(l -> l.height(12).paddingTop(4).paddingLeft(2));
    }

    private UIElement navButton(Tab tab, Player player) {
        boolean active = tab == currentTab;
        var btn = new Button().setText((active ? "§f§l" : "§7") + tabLabel(tab)).setOnClick(e -> switchTab(tab, player));
        btn.layout(l -> l.height(18));
        if (active) btn.lss("base-background", ACTIVE_BG);
        String hint = tabHint(tab);
        btn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                Component.literal("§e" + tabLabel(tab)), Component.literal("§7" + hint)));
        return btn;
    }

    private void switchTab(Tab tab, Player player) {
        if (tabPanels == null || contentAreaRef == null) return;
        currentTab = tab;
        disbandArmed = false;
        leaveArmed = false;
        members.kickArmedFor = null;
        contentAreaRef.clearAllChildren();
        contentAreaRef.addChildren(tabPanels[tab.ordinal()]);
        fillSidebar(player);
    }

    /** Rebuilds one page (e.g. after a confirm button arms) and shows it. */
    void reloadTab(Tab tab, Player player) {
        if (tabPanels == null || contentAreaRef == null) return;
        tabPanels[tab.ordinal()] = switch (tab) {
            case OVERVIEW -> buildOverviewPanel(player);
            case MEMBERS -> members.buildMembersPanel(player);
            default -> tabPanels[tab.ordinal()];
        };
        if (currentTab == tab) {
            contentAreaRef.clearAllChildren();
            contentAreaRef.addChildren(tabPanels[tab.ordinal()]);
        }
    }

    private static String tabLabel(Tab t) {
        return switch (t) {
            case OVERVIEW    -> "Overview";
            case MEMBERS     -> "Members";
            case PERMISSIONS -> "Permissions";
            case TERRITORY   -> "Territory";
            case UPKEEP      -> "Upkeep";
            case WARS        -> "Wars";
            case VAULT       -> "Vault";
            case LEADERBOARD -> "Leaderboard";
            case VASSAL      -> "Vassals";
            case ALLIANCES   -> "Alliances";
            case UPGRADES    -> "Faction Upgrades";
        };
    }

    private static String tabHint(Tab t) {
        return switch (t) {
            case OVERVIEW    -> "Faction summary and quick actions.";
            case MEMBERS     -> "Invite, promote and kick members.";
            case PERMISSIONS -> "What each role is allowed to do.";
            case TERRITORY   -> "Claim chunks, chunkloading and outposts.";
            case UPKEEP      -> "Daily cost of your land and how long it is funded.";
            case WARS        -> "Declare wars and negotiate.";
            case VAULT       -> "Your wallet and the shared faction vault.";
            case LEADERBOARD -> "Richest factions on the server.";
            case VASSAL      -> "Vassal factions and tribute.";
            case ALLIANCES   -> "Non-aggression pacts with other factions.";
            case UPGRADES    -> "Spend warbonds on various faction upgrades.";
        };
    }

    // Overview

    private UIElement buildOverviewPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        Faction faction = factionRef.get();
        if (faction == null) return panel;
        FactionMember self = faction.getMember(player.getUUID());
        boolean isOwner = faction.getOwnerId().equals(player.getUUID());

        var about = card();
        about.addChildren(
                new Label().setText("§6§l" + faction.getName()),
                new Label().setText(faction.getDescription().isEmpty() ? "§8No description." : "§7" + faction.getDescription()),
                new Label().setText("§7Your role: " + (self == null ? "§8?" : roleColor(self.getRole()) + capitalize(self.getRole().getId()))));

        // 2 × 3 stat grid
        var grid = new UIElement();
        grid.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6));
        var left = card();
        left.layout(l -> l.flex(1));
        var right = card();
        right.layout(l -> l.flex(1));
        left.addChildren(
                statLine("Members", () -> "§f" + (factionRef.get() == null ? 0 : factionRef.get().getMembers().size())),
                statLine("Territory", () -> "§f" + (factionRef.get() == null ? 0 : factionRef.get().getLandClaims().size()) + " chunks"),
                statLine("Wars", () -> {
                    Faction f = factionRef.get();
                    int count = f == null ? 0 : f.getWars().size();
                    return count == 0 ? "§aAt peace" : "§c" + count + " active";
                }));
        right.addChildren(
                statLine("Vault", () -> "§a" + Currency.format(factionVaultRef.get())),
                statLine("Upkeep", () -> "§e" + Currency.format(dailyUpkeep()) + "§7/day"),
                statLine("Funded for", this::fundedForText));
        grid.addChildren(left, right);

        // Quick actions
        var actions = new UIElement();
        actions.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(20));
        actions.addChildren(quickButton("§b▶ Claim Land", () -> switchTab(Tab.TERRITORY, player), "Open the territory map."),
                quickButton("§a▶ Deposit", () -> switchTab(Tab.VAULT, player), "Fund the vault to keep land protected."));
        if (isOfficer(player)) {
            actions.addChildren(quickButton("§e▶ Invite", () -> switchTab(Tab.MEMBERS, player), "Invite players to your faction."));
        }
        if (canWageWar(player)) {
            actions.addChildren(quickButton("§c▶ Wars", () -> switchTab(Tab.WARS, player), "Declare war or negotiate."));
        }

        // Faction actions (with confirmation for destructive ones)
        var danger = card();
        danger.addChildren(new Label().setText("§7Faction actions"));
        var dangerRow = new UIElement();
        dangerRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).height(20));
        if (isOwner) {
            var moveBtn = new Button().setText("Move Table")
                    .setOnClick(e -> PacketDistributor.sendToServer(new RequestMoveTablePacket()));
            moveBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                    Component.literal("§eMove Faction Table"),
                    Component.literal("§7Click this, then right-click a new empty"),
                    Component.literal("§7block position to relocate your table.")));
            moveBtn.layout(l -> l.flex(1).height(20));
            var disbandBtn = new Button().setText(disbandArmed ? "§f§lClick again to disband" : "§cDisband Faction")
                    .setOnClick(e -> {
                        if (!disbandArmed) {
                            disbandArmed = true;
                            reloadTab(Tab.OVERVIEW, player);
                            return;
                        }
                        disbandArmed = false;
                        PacketDistributor.sendToServer(new DisbandFactionPacket());
                    });
            if (disbandArmed) disbandBtn.lss("base-background", DANGER_BG);
            disbandBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                    Component.literal("§cDisband Faction"),
                    Component.literal("§7Permanently deletes the faction and releases"),
                    Component.literal("§7all claimed land. §cThis cannot be undone!")));
            disbandBtn.layout(l -> l.flex(1).height(20));
            dangerRow.addChildren(moveBtn, disbandBtn);
        } else {
            var leaveBtn = new Button().setText(leaveArmed ? "§f§lClick again to leave" : "§cLeave Faction")
                    .setOnClick(e -> {
                        if (!leaveArmed) {
                            leaveArmed = true;
                            reloadTab(Tab.OVERVIEW, player);
                            return;
                        }
                        leaveArmed = false;
                        PacketDistributor.sendToServer(new MemberActionPacket(
                                MemberActionPacket.Action.LEAVE, player.getUUID(), "", FactionRole.MEMBER));
                    });
            if (leaveArmed) leaveBtn.lss("base-background", DANGER_BG);
            leaveBtn.layout(l -> l.flex(1).height(20));
            dangerRow.addChildren(leaveBtn);
        }
        danger.addChildren(dangerRow);
        if (disbandArmed || leaveArmed) {
            var cancel = new Button().setText("Cancel").setOnClick(e -> {
                disbandArmed = false;
                leaveArmed = false;
                reloadTab(Tab.OVERVIEW, player);
            });
            cancel.layout(l -> l.width(80).height(18));
            danger.addChildren(new Label().setText(disbandArmed ? "§cThis deletes the faction for everyone." : "§cYou will lose access to the faction."), cancel);
        }

        panel.addChildren(about, grid, actions, danger);
        return panel;
    }

    long dailyUpkeep() {
        Faction f = factionRef.get();
        return f == null ? 0L : f.getLandClaims().stream().mapToLong(LandClaim::effectiveDailyCost).sum();
    }

    String fundedForText() {
        long daily = dailyUpkeep();
        long vault = factionVaultRef.get();
        if (daily <= 0) return "§aNo upkeep";
        if (vault <= 0) return "§cVault empty!";
        long seconds = vault * 86400L / daily;
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        String color = seconds > 86400 ? "§a" : seconds > 3600 ? "§e" : "§c";
        return color + (days > 0 ? days + "d " : "") + hours + "h";
    }

    UIElement statLine(String label, Supplier<String> value) {
        var row = new UIElement();
        row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).height(12));
        var valueLabel = new Label();
        valueLabel.bindDataSource(SupplierDataSource.of(() -> Component.literal(value.get())));
        row.addChildren(new Label().setText("§7" + label).layout(l -> l.width(76)), valueLabel);
        return row;
    }

    private UIElement quickButton(String text, Runnable action, String hint) {
        var btn = new Button().setText(text).setOnClick(e -> action.run());
        btn.layout(l -> l.flex(1).height(20));
        btn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                Component.literal("§7" + hint)));
        return btn;
    }

    static UIElement card() {
        var box = UiKit.background(new UIElement(), UiKit.CARD);
        box.layout(l -> l.paddingAll(6).gapAll(4).flexDirection(YogaFlexDirection.COLUMN));
        return box;
    }

    // Outpost list receiver (called by SyncOutpostsPacket handler)

    public void updateOutpostList(
            List<SyncOutpostsPacket.OutpostItem> items,
            long tpCostCopper) {
        territory.outpostListRef.set(items);
        territory.outpostTpCostRef.set(tpCostCopper);
        // If the outpost list panel is already visible, refresh it
        if (territory.showingOutpostList) territory.showOutpostList();
    }

    public void updateClaimRate(double claimRateMultiplier) {
        claimRateRef.set(Math.max(1.0, claimRateMultiplier));
    }

    /** Called by SyncAlliancesPacket. */
    /** Called by SyncFactionUpgradesPacket. */
    public void updateUpgrades(SyncFactionUpgradesPacket sync) {
        upgrades.update(sync);
    }

    public void updateAlliances(List<SyncAlliancesPacket.Entry> entries) {
        alliances.allianceEntries = entries;
        if (playerRef == null) return;
        if (alliances.alliancesPanel != null) alliances.fillAlliancesPanel(playerRef);
        if (wars.warsListArea != null && wars.warsSubView == WarsSubView.LIST) wars.fillWarsFactionList(wars.warsListArea, playerRef);
    }

    // Helpers

    /** Whether the player's rank has this permission (owners always do), matching the server's checks. */
    boolean hasPermission(Player player, FactionPermission permission) {
        Faction f = factionRef.get(); if (f == null) return false;
        FactionMember m = f.getMember(player.getUUID());
        return m != null && f.getRolePermission(m.getRole(), permission);
    }

    boolean isOfficer(Player player) {
        Faction f = factionRef.get(); if (f == null) return false;
        FactionMember m = f.getMember(player.getUUID());
        return m != null && m.getRole().getLevel() >= FactionRole.OFFICER.getLevel();
    }

    private boolean isAdmin(Player player) {
        Faction f = factionRef.get(); if (f == null) return false;
        FactionMember m = f.getMember(player.getUUID());
        return m != null && m.getRole().getLevel() >= FactionRole.ADMIN.getLevel();
    }

    boolean canWageWar(Player player) {
        Faction f = factionRef.get(); if (f == null) return false;
        FactionMember m = f.getMember(player.getUUID());
        return m != null && f.getRolePermission(m.getRole(), FactionPermission.OFFICER_DECLARE_WAR);
    }

    boolean canWithdrawFromVault(Player player) {
        Faction f = factionRef.get(); if (f == null) return false;
        FactionMember m = f.getMember(player.getUUID());
        return m != null && f.getRolePermission(m.getRole(), FactionPermission.VAULT_WITHDRAW);
    }

    static String roleColor(FactionRole role) {
        return switch (role) {
            case OWNER   -> "§6";
            case ADMIN   -> "§5";
            case OFFICER -> "§9";
            case MEMBER  -> "§f";
        };
    }

    static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String permLabel(FactionPermission p) {
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

    static String permDescription(FactionPermission p) {
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

    // Upkeep

    private UIElement buildUpkeepPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        Faction faction = factionRef.get();
        if (faction == null) {
            panel.addChildren(new Label().setText("§7You are not in a faction."));
            return panel;
        }
        var claims = faction.getLandClaims();

        // Summary cards
        var summary = new UIElement();
        summary.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6));
        summary.addChildren(
                summaryCard("Daily upkeep", () -> "§e" + Currency.format(dailyUpkeep())),
                summaryCard("Faction vault", () -> "§a" + Currency.format(factionVaultRef.get())),
                summaryCard("Funded for", this::fundedForText));

        // Deed list
        var list = card();
        list.layout(l -> l.flex(1));
        var listHeader = new UIElement();
        listHeader.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(12).paddingHorizontal(4));
        listHeader.addChildren(
                new Label().setText("§8Chunk").layout(l -> l.flex(1)),
                new Label().setText("§8Dimension").layout(l -> l.width(80)),
                new Label().setText("§8Upkeep/day").layout(l -> l.width(80)));
        list.addChildren(new Label().setText("§6Land deeds §7(" + claims.size() + ")"), listHeader);

        int[] scroll = {0};
        final int deedRows = 7;
        var deedArea = new UIElement();
        deedArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(1));
        var pageLabel = new Label();
        Runnable fillDeeds = () -> {
            deedArea.clearAllChildren();
            if (claims.isEmpty()) {
                deedArea.addChildren(new Label().setText("§7No claimed chunks yet."));
                pageLabel.setText("");
                return;
            }
            for (int i = 0; i < deedRows; i++) {
                int idx = i + scroll[0];
                if (idx >= claims.size()) break;
                var claim = claims.get(idx);
                var row = UiKit.background(new UIElement(), UiKit.ROW);
                row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).height(15).paddingHorizontal(4));
                row.addChildren(
                        new Label().setText("§f" + claim.chunkX() + ", " + claim.chunkZ()
                                + (claim.isChunkloading() ? "  §d⚡ loaded" : "")).layout(l -> l.flex(1)),
                        new Label().setText("§7" + simplifyDim(claim.dimension().toString())).layout(l -> l.width(80)),
                        new Label().setText("§e" + Currency.format(claim.effectiveDailyCost())).layout(l -> l.width(80)));
                deedArea.addChildren(row);
            }
            pageLabel.setText("§7" + (scroll[0] + 1) + "-" + Math.min(claims.size(), scroll[0] + deedRows) + " of " + claims.size());
        };
        fillDeeds.run();
        var pager = new UIElement();
        pager.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(16));
        pager.addChildren(
                new Button().setText("‹").setOnClick(e -> {
                    scroll[0] = Math.max(0, scroll[0] - deedRows); fillDeeds.run();
                }).layout(l -> l.width(18).height(14)),
                pageLabel.layout(l -> l.flex(1)),
                new Button().setText("›").setOnClick(e -> {
                    scroll[0] = Math.min(Math.max(0, claims.size() - deedRows), scroll[0] + deedRows);
                    fillDeeds.run();
                }).layout(l -> l.width(18).height(14)));
        list.addChildren(deedArea, pager);

        panel.addChildren(summary, list,
                new Label().setText("§8Upkeep drains from the vault over the day. If it runs out, land stays"),
                new Label().setText("§8claimed but becomes unprotected until the vault is refilled."));
        return panel;
    }

    private UIElement summaryCard(String label, Supplier<String> value) {
        var box = card();
        box.layout(l -> l.flex(1));
        var valueLabel = new Label();
        valueLabel.bindDataSource(SupplierDataSource.of(() -> Component.literal(value.get())));
        box.addChildren(new Label().setText("§7" + label), valueLabel);
        return box;
    }

    /** Converts a dimension resource location string to a short readable name. */
    static String simplifyDim(String dim) {
        if (dim == null) return "Overworld";
        if (dim.contains("overworld")) return "Overworld";
        if (dim.contains("nether"))    return "Nether";
        if (dim.contains("end"))       return "The End";
        int colon = dim.lastIndexOf(':');
        return colon >= 0 ? dim.substring(colon + 1) : dim;
    }

    // Leaderboard

    private UIElement buildLeaderboardPanel() {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        var board = card();
        board.layout(l -> l.flex(1));
        board.addChildren(new Label().setText("§6Richest factions §8(by vault)"));

        var header = new UIElement();
        header.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(12).gapAll(4).paddingHorizontal(4));
        header.addChildren(
                new Label().setText("§8#").layout(l -> l.width(22)),
                new Label().setText("§8Faction").layout(l -> l.flex(1)),
                new Label().setText("§8Members").layout(l -> l.width(54)),
                new Label().setText("§8Vault").layout(l -> l.width(90)));
        board.addChildren(header);

        var listArea = new UIElement();
        listArea.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(1));
        // Rebuild once per second, sorting a small list every tick wastes CPU
        int[] lbTick = {19};
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
            Faction mine = factionRef.get();
            for (int i = 0; i < sorted.size(); i++) {
                FactionSummary fs = sorted.get(i);
                boolean own = mine != null && mine.getName().equals(fs.name());
                String rank = switch (i) {
                    case 0 -> "§6§l1";
                    case 1 -> "§f§l2";
                    case 2 -> "§c§l3";
                    default -> "§7" + (i + 1);
                };
                var row = UiKit.background(new UIElement(), own ? 0x5533BBFF : UiKit.ROW);
                row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4)
                        .height(16).paddingHorizontal(4));
                row.addChildren(
                        new Label().setText(rank).layout(l -> l.width(22)),
                        new Label().setText((own ? "§b§l" : "§e") + fs.name() + (own ? " §7(you)" : "")).layout(l -> l.flex(1)),
                        new Label().setText("§f" + fs.memberCount()).layout(l -> l.width(54)),
                        new Label().setText("§a" + Currency.format(fs.totalWealth())).layout(l -> l.width(90)));
                listArea.addChildren(row);
            }
        });
        board.addChildren(listArea);
        panel.addChildren(board);
        return panel;
    }

    // Vassal

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
            // Vassal view
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
                    .setText("§a💰 Buy Independence (§e" + Currency.format(SyncServerConfigPacket.get(Config.VASSAL_BUYOUT_COPPER)) + "§a)")
                    .setOnClick(e -> PacketDistributor.sendToServer(
                            new ConquestActionPacket(ConquestActionPacket.Action.BUYOUT, myFaction.getId())))
                    .layout(l -> l.flex(1).height(30));
            btnRow.addChildren(buyoutBtn);
            panel.addChildren(btnRow);

        } else if (vassalIsSuzerain) {
            // Suzerain view
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
