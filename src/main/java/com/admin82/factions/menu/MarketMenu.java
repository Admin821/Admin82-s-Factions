package com.admin82.factions.menu;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.MarketListing;
import com.admin82.factions.economy.SoldListing;
import com.admin82.factions.network.packet.MarketActionPacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;

import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Faction Market UI.
 *
 * Two tabs:
 *  Browse     — scrollable view of all active listings.
 *  My Listings — player's own listings + inline multi-step Create flow.
 *
 * Create flow steps (inside My Listings):
 *   LIST → INV_SELECT → TYPE_SELECT → [DURATION_SELECT] → PRICE_INPUT → CONFIRM
 */
public class MarketMenu extends AbstractContainerMenu {

    // ── Server-pushed data ────────────────────────────────────────────────────
    private final AtomicReference<List<MarketListing>> listingsRef    = new AtomicReference<>(List.of());
    private final AtomicReference<List<SoldListing>>   soldRef        = new AtomicReference<>(List.of());
    private final AtomicLong    walletRef       = new AtomicLong(0L);
    private final AtomicInteger myListingsCount = new AtomicInteger(0);
    private final AtomicInteger maxSlotsRef     = new AtomicInteger(1);

    // ── Create-flow state ─────────────────────────────────────────────────────
    private enum CreateStep { LIST, INV_SELECT, ITEM_SEARCH, TYPE_SELECT, DURATION_SELECT, PRICE_INPUT, CONFIRM }
    private CreateStep createStep    = CreateStep.LIST;
    private int        createSlot    = -1;
    private ItemStack  createItem    = ItemStack.EMPTY;
    private boolean    createIsAuction = false;
    private int        createDuration  = 4;      // hours
    private MarketListing.ListingKind createKind = MarketListing.ListingKind.PLAYER_SELL;
    private String itemSearchText = "";
    private int itemSearchScroll = 0;
    private int requestedCount = 1;

    // Single total price in copper (set from number+dropdown in PRICE_INPUT step)
    private long priceTotal = 0;

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private enum Tab { BROWSE, BUY_ORDERS, MY_LISTINGS, ADMIN_SHOP }
    private Tab currentTab  = Tab.BROWSE;
    private int browseScroll = 0;
    private int buyOrderScroll = 0;
    private int adminScroll = 0;
    private int myScroll     = 0;
    private static final int ROWS = 6;
    private static final int MY_LISTING_ROWS = 10;
    private static final int ITEM_SEARCH_ROWS = 5;
    private String searchText = "";
    private String modFilterText = "All Mods";
    private String pendingModFilterText = "All Mods";
    private String categoryFilter = "All";
    private String pendingCategoryFilter = "All";
    private boolean filtersOpen = false;
    private boolean createFromAdminShop = false;

    // Live element refs
    private UIElement browseListArea;
    private UIElement buyOrderListArea;
    private UIElement adminListArea;
    private UIElement adminContentArea;
    private UIElement myListingsContentArea;
    private UIElement myContentArea;
    private UIElement myListArea;

    private final BlockPos pos;

    // ── Constructors ──────────────────────────────────────────────────────────

    public MarketMenu(int containerId, Inventory inv, BlockPos pos) {
        super(ModMenuTypes.MARKET.get(), containerId);
        this.pos = pos;
        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu h) h.setModularUI(createModularUI(inv.player));
    }

    public MarketMenu(int containerId, Inventory inv, FriendlyByteBuf buf) {
        this(containerId, inv, buf.readBlockPos());
    }

    // ── Public sync API ───────────────────────────────────────────────────────

    public void updateListings(List<MarketListing> listings, long wallet, int myCount, int maxSlots) {
        listingsRef.set(listings);
        walletRef.set(wallet);
        myListingsCount.set(myCount);
        maxSlotsRef.set(maxSlots);
        if (currentTab == Tab.BROWSE && browseListArea != null) {
            browseListArea.clearAllChildren(); fillBrowseList(browseListArea);
        } else if (currentTab == Tab.BUY_ORDERS && buyOrderListArea != null) {
            buyOrderListArea.clearAllChildren(); fillBuyOrderList(buyOrderListArea);
        } else if (currentTab == Tab.ADMIN_SHOP && adminListArea != null) {
            adminListArea.clearAllChildren(); fillAdminList(adminListArea);
        } else if (currentTab == Tab.MY_LISTINGS && createStep == CreateStep.LIST && myListArea != null) {
            myListArea.clearAllChildren(); fillMyList(myListArea);
        }
    }

    public void updateSoldListings(List<SoldListing> sold) {
        soldRef.set(sold);
        if (currentTab == Tab.MY_LISTINGS && createStep == CreateStep.LIST && myListArea != null) {
            myListArea.clearAllChildren(); fillMyList(myListArea);
        }
    }

    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player p) {
        return p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    // ── Root UI ───────────────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    private ModularUI createModularUI(Player player) {
        var frame = new UIElement();
        frame.layout(l -> l.width(456).height(348).paddingAll(2));
        frame.addClass("preview_bg");

        var root = new UIElement();
        root.layout(l -> l.width(452).height(344).flexDirection(YogaFlexDirection.COLUMN));
        root.addClass("panel_bg");

        // Title + wallet
        var titleRow = new UIElement();
        titleRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(22).paddingHorizontal(8).paddingTop(4));
        titleRow.addChildren(
                new Label().setText("§6§lFaction Market").layout(l -> l.flex(1)),
                new Label().bindDataSource(SupplierDataSource.of(() ->
                        Component.literal("§7Wallet: §a" + Currency.format(walletRef.get()))))
                        .layout(l -> l.width(140))
        );

        // Tab bar
        var tabBar = new UIElement();
        tabBar.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(24).width(452).gapAll(2).paddingHorizontal(4));

        // Content
        var contentArea = new UIElement();
        contentArea.layout(l -> l.flex(1).width(452));

        UIElement browsePanel = buildBrowsePanel();
        UIElement buyOrdersPanel = buildBuyOrdersPanel();
        UIElement myPanel     = buildMyListingsPanel(player);
        UIElement adminPanel = buildAdminPanel(player);
        contentArea.addChildren(panelForTab(browsePanel, buyOrdersPanel, myPanel, adminPanel));
        buildTabBar(tabBar, browsePanel, buyOrdersPanel, myPanel, adminPanel, contentArea, player);

        root.addChildren(titleRow, tabBar, contentArea);
        frame.addChildren(root);
        return ModularUI.of(
                UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)),
                player);
    }

    private UIElement panelForTab(UIElement browsePanel, UIElement buyOrdersPanel, UIElement myPanel, UIElement adminPanel) {
        return switch (currentTab) {
            case BROWSE -> browsePanel;
            case BUY_ORDERS -> buyOrdersPanel;
            case MY_LISTINGS -> myPanel;
            case ADMIN_SHOP -> adminPanel;
        };
    }

    private void buildTabBar(UIElement bar, UIElement browsePanel, UIElement buyOrdersPanel, UIElement myPanel, UIElement adminPanel,
                              UIElement content, Player player) {
        bar.clearAllChildren();
        Tab[]    tabs   = Tab.values();
        String[] labels = { "Sell Listings", "Buy Orders", "My Listings", "Server Shop" };
        UIElement[] panels = { browsePanel, buyOrdersPanel, myPanel, adminPanel };
        for (int i = 0; i < tabs.length; i++) {
            final int fi = i;
            boolean active = tabs[i] == currentTab;
            var btn = new Button()
                    .setText(labels[i])
                    .setOnClick(e -> {
                        currentTab = tabs[fi];
                        filtersOpen = false;
                        pendingModFilterText = modFilterText;
                        pendingCategoryFilter = categoryFilter;
                        if (currentTab == Tab.MY_LISTINGS) {
                            resetCreateFlowState();
                            if (myListingsContentArea != null) {
                                myContentArea = myListingsContentArea;
                                showStep(player);
                            }
                        }
                        content.clearAllChildren();
                        content.addChildren(panels[fi]);
                        buildTabBar(bar, browsePanel, buyOrdersPanel, myPanel, adminPanel, content, player);
                        // Request a fresh sync from the server
                        PacketDistributor.sendToServer(new MarketActionPacket(
                                MarketActionPacket.Action.REFRESH, new UUID(0, 0), 0, 0L, 0, false));
                    })
                    .layout(l -> l.flex(1).height(22));
            if (active) btn.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
            bar.addChildren(btn);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BROWSE TAB
    // ══════════════════════════════════════════════════════════════════════════

    private UIElement buildBrowsePanel() {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(6).gapAll(4).flexDirection(YogaFlexDirection.COLUMN));
        panel.addChildren(new Label().setText("§7Player sales and auctions — click Buy or enter a Bid."));

        browseListArea = new UIElement();
        browseListArea.layout(l -> l.flex(1).width(440));
        fillBrowseList(browseListArea);

        panel.addChildren(browseListArea, buildBottomControls(
            () -> scrollBrowse(-1),
            () -> scrollBrowse(1),
            () -> { browseScroll = 0; browseListArea.clearAllChildren(); fillBrowseList(browseListArea); }));
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private void fillBrowseList(UIElement area) {
        var listings = listingsRef.get().stream()
            .filter(l -> l.kind == MarketListing.ListingKind.PLAYER_SELL && matchesFilters(l.item))
            .toList();
        if (listings.isEmpty()) { area.addChildren(new Label().setText("§7No matching sell listings.")); return; }
        long now = System.currentTimeMillis();
        for (int i = 0; i < ROWS; i++) {
            int idx = i + browseScroll;
            if (idx >= listings.size()) break;
            MarketListing l = listings.get(idx);
            boolean expired = l.expiresAt <= now;
            var row = new UIElement();
            row.layout(r -> r.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(34).width(440));

            var icon = new ItemSlot(); icon.setItem(l.item); icon.layout(r -> r.width(20).height(20));
            String priceStr = l.isAuction
                    ? (l.highestBid > 0 ? "Bid: §a" + Currency.format(l.highestBid)
                                        : "Start: §a" + Currency.format(l.price))
                    : "§a" + Currency.format(l.price);
            long remaining = Math.max(0, (l.expiresAt - now) / 1000);
            String timeStr = expired ? "§cExp" : remaining > 3600 ? "§7" + (remaining / 3600) + "h" : "§c" + (remaining / 60) + "m";

            row.addChildren(
                    icon,
                    itemNameLabel(l.item, 138),
                    new Label().setText(l.isAuction ? "§dAuct" : "§aBIN").layout(r -> r.width(36)),
                    new Label().setText(priceStr).layout(r -> r.flex(1)),
                    new Label().setText(timeStr).layout(r -> r.width(30))
            );

            if (!expired) {
                if (!l.isAuction) {
                    // Don't show Buy for own listings
                    var mc2 = Minecraft.getInstance();
                    UUID myId2 = mc2.player != null ? mc2.player.getUUID() : null;
                    if (myId2 != null && myId2.equals(l.sellerUUID)) {
                        row.addChildren(new Label().setText("§8(yours)").layout(r -> r.width(48)));
                    } else {
                        var buyBtn = new Button().setText("§aBuy");
                    buyBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty()
                            .append(Component.literal("§aBuy for §e" + Currency.format(l.price))));
                    buyBtn.setOnClick(e -> PacketDistributor.sendToServer(new MarketActionPacket(
                            MarketActionPacket.Action.BUY, l.listingId, -1, 0, 0, false)));
                    buyBtn.layout(r -> r.width(30));
                    row.addChildren(buyBtn);
                    } // end else (not own listing)
                } else {
                    String[] bidStr = {""};
                    TextField bidField = new TextField();
                    bidField.setValue("");
                    bidField.bindObserver(v -> bidStr[0] = v);
                    bidField.layout(lo -> lo.width(72));
                    var bidBtn = new Button().setText("§dBid")
                            .setOnClick(e -> {
                                long amt = Currency.parse(bidStr[0]);
                                if (amt > 0) PacketDistributor.sendToServer(new MarketActionPacket(
                                        MarketActionPacket.Action.BID, l.listingId, -1, amt, 0, true));
                            })
                            .layout(r -> r.width(28));
                    row.addChildren(bidField, bidBtn);
                }
            }
            area.addChildren(row);
        }
    }

        private UIElement buildBuyOrdersPanel() {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(6).gapAll(4).flexDirection(YogaFlexDirection.COLUMN));
        panel.addChildren(new Label().setText("§7Players and server buy orders — sell matching items for the listed price."));

        buyOrderListArea = new UIElement();
        buyOrderListArea.layout(l -> l.flex(1).width(440));
        fillBuyOrderList(buyOrderListArea);
        panel.addChildren(buyOrderListArea, buildBottomControls(
            () -> scrollBuyOrders(-1),
            () -> scrollBuyOrders(1),
            () -> { buyOrderScroll = 0; buyOrderListArea.clearAllChildren(); fillBuyOrderList(buyOrderListArea); }));
        return panel;
        }

        @OnlyIn(Dist.CLIENT)
        private void fillBuyOrderList(UIElement area) {
        var orders = listingsRef.get().stream()
            .filter(l -> l.isBuyOrder() && matchesFilters(l.item))
            .toList();
        if (orders.isEmpty()) { area.addChildren(new Label().setText("§7No matching buy orders.")); return; }
        var mc = Minecraft.getInstance();
        UUID myId = mc.player != null ? mc.player.getUUID() : null;
        for (int i = 0; i < ROWS; i++) {
            int idx = i + buyOrderScroll;
            if (idx >= orders.size()) break;
            MarketListing l = orders.get(idx);
            var row = new UIElement();
            row.layout(r -> r.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(34).width(440));
            var icon = new ItemSlot(); icon.setItem(l.item); icon.layout(r -> r.width(20).height(20));
            row.addChildren(
                icon,
                itemNameLabel(l.item, 158),
                new Label().setText(l.kind == MarketListing.ListingKind.ADMIN_BUY_ORDER ? "§6Server" : "§bPlayer").layout(r -> r.width(48)),
                new Label().setText("§a" + Currency.format(l.price)).layout(r -> r.flex(1)));
            if (myId != null && myId.equals(l.sellerUUID)) {
                row.addChildren(new Label().setText("§8(yours)").layout(r -> r.width(38)));
            } else {
                row.addChildren(new Button().setText("§aSell")
                    .setOnClick(e -> PacketDistributor.sendToServer(new MarketActionPacket(
                        MarketActionPacket.Action.FULFILL_BUY_ORDER, l.listingId, -1, 0, 0, false)))
                    .layout(r -> r.width(38)));
            }
            area.addChildren(row);
        }
        }

        private UIElement buildAdminPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(6).gapAll(4).flexDirection(YogaFlexDirection.COLUMN));
        adminContentArea = new UIElement();
        adminContentArea.layout(l -> l.flex(1).width(440).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        panel.addChildren(adminContentArea);
        renderAdminContent(player);
        return panel;
        }

        private void renderAdminContent(Player player) {
        if (adminContentArea == null) return;
        adminContentArea.clearAllChildren();
        if (createFromAdminShop && createKind == MarketListing.ListingKind.ADMIN_SELL && createStep != CreateStep.LIST) {
            myContentArea = adminContentArea;
            showStep(player);
            return;
        }

        adminContentArea.addChildren(new Label().setText("§7Permanent server shop listings created by operators."));

        adminListArea = new UIElement();
        adminListArea.layout(l -> l.flex(1).width(440));
        fillAdminList(adminListArea);

        var actionRow = new UIElement();
        actionRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).width(440));
        actionRow.addChildren(new UIElement().layout(l -> l.flex(1)));
        if (player.hasPermissions(2)) {
            actionRow.addChildren(new Button().setText("§6Server Listing")
                .setOnClick(e -> {
                    createFromAdminShop = true;
                    createKind = MarketListing.ListingKind.ADMIN_SELL;
                    createIsAuction = false;
                    createStep = CreateStep.INV_SELECT;
                    renderAdminContent(player);
                })
                .layout(l -> l.width(112).height(24)));
        }

        adminContentArea.addChildren(adminListArea, buildBottomControls(
            () -> scrollAdmin(-1),
            () -> scrollAdmin(1),
            () -> { adminScroll = 0; adminListArea.clearAllChildren(); fillAdminList(adminListArea); }),
            actionRow);
        }

        @OnlyIn(Dist.CLIENT)
        private void fillAdminList(UIElement area) {
        var listings = listingsRef.get().stream()
            .filter(l -> l.kind == MarketListing.ListingKind.ADMIN_SELL && matchesFilters(l.item))
            .toList();
        if (listings.isEmpty()) { area.addChildren(new Label().setText("§7No matching server shop listings.")); return; }
        for (int i = 0; i < ROWS; i++) {
            int idx = i + adminScroll;
            if (idx >= listings.size()) break;
            MarketListing l = listings.get(idx);
            var row = new UIElement();
            row.layout(r -> r.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(34).width(440));
            var icon = new ItemSlot(); icon.setItem(l.item); icon.layout(r -> r.width(20).height(20));
            var buyBtn = new Button().setText("§aBuy")
                .setOnClick(e -> PacketDistributor.sendToServer(new MarketActionPacket(
                    MarketActionPacket.Action.BUY, l.listingId, -1, 0, 0, false)))
                .layout(r -> r.width(38));
            row.addChildren(
                icon,
                itemNameLabel(l.item, 178),
                new Label().setText("§6Server Shop").layout(r -> r.width(74)),
                new Label().setText("§a" + Currency.format(l.price)).layout(r -> r.flex(1)),
                buyBtn);
            area.addChildren(row);
        }
        }

    // ══════════════════════════════════════════════════════════════════════════
    // MY LISTINGS TAB  +  CREATE FLOW
    // ══════════════════════════════════════════════════════════════════════════

    private UIElement buildMyListingsPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(6).gapAll(4).flexDirection(YogaFlexDirection.COLUMN));
        panel.addChildren(new Label().bindDataSource(SupplierDataSource.of(() ->
                Component.literal("§7Listings: §e" + myListingsCount.get() + " / " + maxSlotsRef.get()
                        + "  §8(slots = faction size)"))));

        myListingsContentArea = new UIElement();
        myListingsContentArea.layout(l -> l.flex(1).width(440));
        myContentArea = myListingsContentArea;
        showStep(player);

        panel.addChildren(myListingsContentArea);
        return panel;
    }

    private void showStep(Player player) {
        myContentArea.clearAllChildren();
        switch (createStep) {
            case LIST            -> stepList(player);
            case INV_SELECT      -> stepInvSelect(player);
            case ITEM_SEARCH     -> stepItemSearch(player);
            case TYPE_SELECT     -> stepTypeSelect(player);
            case DURATION_SELECT -> stepDurationSelect(player);
            case PRICE_INPUT     -> stepPriceInput(player);
            case CONFIRM         -> stepConfirm(player);
        }
    }

    // ─ Step LIST ──────────────────────────────────────────────────────────────

    private void stepList(Player player) {
        myContentArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        myListArea = new UIElement();
        myListArea.layout(l -> l.flex(1).width(440));
        fillMyList(myListArea);

        var scrollRow = makeScrollRow(
            () -> scrollMyListings(-1),
            () -> scrollMyListings(1)
        );
        var createBtn = new Button().setText("§a✦ Create Sale Listing");
        createBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty()
                .append(Component.literal("§aList an item on the market."),
                        Component.literal("§7Opens your inventory to choose the item.")));
        createBtn.setOnClick(e -> { createKind = MarketListing.ListingKind.PLAYER_SELL; createStep = CreateStep.INV_SELECT; showStep(player); });
        createBtn.layout(l -> l.width(142).height(24));

        var buyOrderBtn = new Button().setText("§bCreate Buy Order")
            .setOnClick(e -> { createKind = MarketListing.ListingKind.PLAYER_BUY_ORDER; createIsAuction = false; createStep = CreateStep.ITEM_SEARCH; showStep(player); })
            .layout(l -> l.width(134).height(24));

        var buttonRow = new UIElement();
        buttonRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).alignSelf(YogaAlign.CENTER));
        buttonRow.addChildren(createBtn, buyOrderBtn);

        if (player.hasPermissions(2)) {
            buttonRow.addChildren(
                new Button().setText("§6Server Buy")
                    .setOnClick(e -> { createKind = MarketListing.ListingKind.ADMIN_BUY_ORDER; createIsAuction = false; createStep = CreateStep.ITEM_SEARCH; showStep(player); })
                    .layout(l -> l.width(90).height(24)));
        }

        myContentArea.addChildren(myListArea, scrollRow, buttonRow);
    }

    @OnlyIn(Dist.CLIENT)
    private void fillMyList(UIElement area) {
        var mc = Minecraft.getInstance();
        UUID myId = mc.player != null ? mc.player.getUUID() : null;

        // ── Sold listings (unclaimed proceeds) ─────────────────────────────────
        var mySold = soldRef.get().stream()
                .filter(s -> myId != null && myId.equals(s.sellerUUID)).toList();
        for (SoldListing sale : mySold) {
            var row = new UIElement();
            row.layout(r -> r.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(22).width(440));
            var icon = new ItemSlot(); icon.setItem(sale.item); icon.layout(r -> r.width(20).height(20));
            var claimBtn = new Button().setText("§a✦ Claim")
                    .setOnClick(e -> PacketDistributor.sendToServer(new MarketActionPacket(
                            MarketActionPacket.Action.CLAIM_SOLD, sale.saleId, -1, 0, 0, false)))
                    .layout(r -> r.width(56));
            row.addChildren(
                    icon,
                    itemNameLabel(sale.item, 116),
                    new Label().setText("§7Buyer: §e" + sale.buyerName).layout(r -> r.width(90)),
                    new Label().setText("§a" + Currency.format(sale.proceeds)).layout(r -> r.width(70)),
                    claimBtn
            );
            area.addChildren(row);
        }
        if (!mySold.isEmpty()) {
            area.addChildren(new Label().setText("§8─────────────────────────────────────────────"));
        }

        // ── Active listings ────────────────────────────────────────────────────
        var mine = listingsRef.get().stream()
            .filter(l -> myId != null && myId.equals(l.sellerUUID) && !l.isAdminListing()).toList();
        if (mine.isEmpty() && mySold.isEmpty()) {
            area.addChildren(new Label().setText("§7No active listings. Click §aCreate New Listing§7 below."));
            return;
        }
        if (mine.isEmpty()) return;
        myScroll = Math.max(0, Math.min(Math.max(0, mine.size() - MY_LISTING_ROWS), myScroll));
        for (int i = 0; i < MY_LISTING_ROWS; i++) {
            int idx = i + myScroll;
            if (idx >= mine.size()) break;
            MarketListing l = mine.get(idx);
            var row = new UIElement();
            row.layout(r -> r.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(22).width(440));
            var icon = new ItemSlot(); icon.setItem(l.item); icon.layout(r -> r.width(20).height(20));
            String priceStr = l.isAuction
                    ? (l.highestBid > 0 ? "Bid: §a" + Currency.format(l.highestBid) : "No bids yet")
                    : "BIN: §a" + Currency.format(l.price);
            var cancelBtn = new Button().setText("§cCancel")
                    .setOnClick(e -> PacketDistributor.sendToServer(new MarketActionPacket(
                            MarketActionPacket.Action.CANCEL, l.listingId, -1, 0, 0, false)))
                    .layout(r -> r.width(52));
            row.addChildren(icon,
                    itemNameLabel(l.item, 150),
                    new Label().setText(priceStr).layout(r -> r.width(110)),
                    cancelBtn);
            area.addChildren(row);
        }
    }

    // ─ Step INV_SELECT ────────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    private void stepInvSelect(Player player) {
        myContentArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(6));
        myContentArea.addChildren(new Label().setText(createKind == MarketListing.ListingKind.PLAYER_SELL
            ? "§6Step 1 §7— Click the item you want to sell."
            : "§6Step 1 §7— Click the item template and stack count."));

        int[][] rowDefs = { {9,18}, {18,27}, {27,36}, {0,9} };
        for (int[] range : rowDefs) {
            var rowEl = new UIElement();
            rowEl.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(2).height(22));
            for (int s = range[0]; s < range[1]; s++) {
                final int slot = s;
                var cell = new ItemSlot();
                cell.layout(l -> l.width(20).height(20));
                cell.addEventListener(UIEvents.TICK, ev -> {
                    var mc = Minecraft.getInstance();
                    if (mc.player != null) cell.setItem(mc.player.getInventory().getItem(slot));
                });
                cell.addEventListener(UIEvents.MOUSE_UP, ev -> {
                    var mc = Minecraft.getInstance();
                    if (mc.player == null) return;
                    ItemStack stack = mc.player.getInventory().getItem(slot);
                    if (stack.isEmpty()) return;
                    createSlot = slot; createItem = stack.copy();
                    requestedCount = createItem.getCount();
                    createStep = createKind == MarketListing.ListingKind.PLAYER_SELL ? CreateStep.TYPE_SELECT : CreateStep.PRICE_INPUT;
                    showStep(player);
                });
                rowEl.addChildren(cell);
            }
            myContentArea.addChildren(rowEl);
        }
        var backBtn = new Button().setText("§7← Back")
            .setOnClick(e -> returnToCreateHome(player))
                .layout(l -> l.width(80));
        myContentArea.addChildren(backBtn);
    }

    // ─ Step ITEM_SEARCH ──────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    private void stepItemSearch(Player player) {
        myContentArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        myContentArea.addChildren(new Label().setText("§6Step 1 §7— Search any item or block for this buy order."));

        var searchRow = new UIElement();
        searchRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(22).width(430));
        TextField searchField = new TextField();
        searchField.setValue(itemSearchText);
        searchField.getTextFieldStyle().placeholder(Component.literal("Search item or block"));
        searchField.bindObserver(value -> itemSearchText = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT));
        searchField.layout(l -> l.width(190).height(20));
        TextField countField = new TextField();
        countField.setValue(String.valueOf(Math.max(1, requestedCount)));
        countField.getTextFieldStyle().placeholder(Component.literal("Count"));
        countField.bindObserver(value -> {
            try { requestedCount = Math.max(1, Math.min(64, Integer.parseInt(value.trim()))); }
            catch (RuntimeException ignored) {}
        });
        countField.layout(l -> l.width(42).height(20));
        searchRow.addChildren(searchField,
            new Label().setText("§7Amount").layout(l -> l.width(46).height(14)),
            countField,
            new Button().setText("§eSearch").setOnClick(e -> { itemSearchScroll = 0; showStep(player); }).layout(l -> l.width(56).height(20)),
            new Button().setText("▲").setOnClick(e -> scrollItemSearch(-1)).layout(l -> l.width(20).height(20)),
            new Button().setText("▼").setOnClick(e -> scrollItemSearch(1)).layout(l -> l.width(20).height(20)));
        myContentArea.addChildren(searchRow);

        var results = findItemResults(itemSearchText);
        if (results.isEmpty()) {
            myContentArea.addChildren(new Label().setText(itemSearchText.isBlank()
                    ? "§8Type a name or id, then click Search."
                    : "§7No matching items."));
        } else {
            itemSearchScroll = Math.max(0, Math.min(Math.max(0, results.size() - ITEM_SEARCH_ROWS), itemSearchScroll));
            for (Item item : results.stream().skip(itemSearchScroll).limit(ITEM_SEARCH_ROWS).toList()) {
                ItemStack stack = new ItemStack(item, Math.max(1, requestedCount));
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                var row = new UIElement();
                row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(22).width(430));
                var icon = new ItemSlot(); icon.setItem(stack); icon.layout(l -> l.width(20).height(20));
                row.addChildren(icon,
                    new Label().setText("§f" + stack.getHoverName().getString()).layout(l -> l.width(152).height(16)),
                        new Label().setText("§8" + id).layout(l -> l.flex(1)),
                        new Button().setText("§aPick")
                                .setOnClick(e -> {
                                    createSlot = -1;
                                    createItem = new ItemStack(item, Math.max(1, requestedCount));
                                    createStep = CreateStep.PRICE_INPUT;
                                    showStep(player);
                                })
                                .layout(l -> l.width(40).height(20)));
                myContentArea.addChildren(row);
            }
        }

        myContentArea.addChildren(new Button().setText("§7← Back")
                .setOnClick(e -> returnToCreateHome(player))
                .layout(l -> l.width(80)));
    }

    // ─ Step TYPE_SELECT ───────────────────────────────────────────────────────

    private void stepTypeSelect(Player player) {
        myContentArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(10).paddingTop(8));

        var itemRow = new UIElement();
        itemRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(8).height(24));
        var icon = new ItemSlot(); icon.setItem(createItem); icon.layout(l -> l.width(22).height(22));
        itemRow.addChildren(icon, new Label().setText(
                "§f" + createItem.getHoverName().getString() + " §7×" + createItem.getCount()));

        myContentArea.addChildren(itemRow);
        myContentArea.addChildren(new Label().setText("§6Step 2 §7— How do you want to list this?"));

        var typeRow = new UIElement();
        typeRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(10).width(430));

        var binBtn = new Button().setText("§aSell at Fixed Price (BIN)");
        binBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty()
                .append(Component.literal("§aFixed Price (Buy It Now)"),
                        Component.literal("§7Listed for 7 days. First buyer gets it."),
                        Component.literal("§8Tax: §710%")));
        binBtn.setOnClick(e -> { createIsAuction = false; createStep = CreateStep.PRICE_INPUT; showStep(player); });
        binBtn.layout(l -> l.flex(1).height(40));

        var aucBtn = new Button().setText("§dAuction");
        aucBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty()
                .append(Component.literal("§dAuction"),
                        Component.literal("§7Highest bidder wins when time runs out."),
                        Component.literal("§8Tax: §710–24% based on duration.")));
        aucBtn.setOnClick(e -> { createIsAuction = true; createStep = CreateStep.DURATION_SELECT; showStep(player); });
        aucBtn.layout(l -> l.flex(1).height(40));

        typeRow.addChildren(binBtn, aucBtn);
        myContentArea.addChildren(typeRow);

        var backBtn = new Button().setText("§7← Back")
                .setOnClick(e -> { createStep = CreateStep.INV_SELECT; showStep(player); })
                .layout(l -> l.width(80));
        myContentArea.addChildren(backBtn);
    }

    // ─ Step DURATION_SELECT ───────────────────────────────────────────────────

    private void stepDurationSelect(Player player) {
        myContentArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(10).paddingTop(8));
        myContentArea.addChildren(new Label().setText("§6Step 3 §7— Choose auction duration."));
        myContentArea.addChildren(new Label().setText("§8Longer durations reach more buyers — but cost more tax."));

        var durRow = new UIElement();
        durRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).width(430));
        int[][] opts = { {4,10}, {8,12}, {12,16}, {16,20}, {24,24} };
        for (int[] opt : opts) {
            final int hrs = opt[0], tax = opt[1];
            var btn = new Button().setText(hrs + "h");
            btn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty()
                    .append(Component.literal("§e" + hrs + " hour auction"),
                            Component.literal("§8Tax: §7" + tax + "%")));
            btn.setOnClick(e -> { createDuration = hrs; createStep = CreateStep.PRICE_INPUT; showStep(player); });
            btn.layout(l -> l.flex(1).height(40));
            durRow.addChildren(btn);
        }
        myContentArea.addChildren(durRow);
        myContentArea.addChildren(new Button().setText("§7← Back")
                .setOnClick(e -> { createStep = CreateStep.TYPE_SELECT; showStep(player); })
                .layout(l -> l.width(80)));
    }

    // ─ Step PRICE_INPUT ───────────────────────────────────────────────────────

    private void stepPriceInput(Player player) {
        priceTotal = 0; // reset on entry

        myContentArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(10).paddingTop(6));
        myContentArea.addChildren(new Label().setText(
            createKind == MarketListing.ListingKind.PLAYER_SELL
                ? "§6Step " + (createIsAuction ? "4" : "3") + " §7— Set your asking price."
                : "§6Step 2 §7— Set the price paid for this item stack."));

        // Number + coin dropdown row
        long[] qty    = {1};
        String[] coin = {"Copper"};

        var inputRow = new UIElement();
        inputRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(8).height(26).width(430));

        TextField qtyField = new TextField();
        qtyField.setValue("1");
        qtyField.bindObserver(v -> {
            try { qty[0] = Math.max(1, Long.parseLong(v.trim())); }
            catch (NumberFormatException ignored) {}
            priceTotal = qty[0] * priceCoinMult(coin[0]);
        });
        qtyField.layout(l -> l.width(110));

        var coinSel = new Selector<String>();
        coinSel.setCandidates(java.util.List.of("Copper", "Silver", "Gold", "Platinum"));
        coinSel.setValue("Copper");
        coinSel.setOnValueChanged(v -> {
            coin[0] = v;
            priceTotal = qty[0] * priceCoinMult(v);
        });
        coinSel.layout(l -> l.width(100).height(26));

        inputRow.addChildren(qtyField, coinSel);
        myContentArea.addChildren(inputRow);

        // Live preview
        var totalLabel = new Label();
        totalLabel.addEventListener(UIEvents.TICK, ev -> {
            priceTotal = qty[0] * priceCoinMult(coin[0]);
            totalLabel.setText(priceTotal > 0
                    ? "§7Total: §a" + Currency.format(priceTotal)
                    : "§cPrice must be greater than zero.");
        });
        myContentArea.addChildren(totalLabel);

        var btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(8).width(430));
        btnRow.addChildren(
                new Button().setText("§7← Back")
                        .setOnClick(e -> {
                                createStep = createKind == MarketListing.ListingKind.PLAYER_SELL
                                    ? (createIsAuction ? CreateStep.DURATION_SELECT : CreateStep.TYPE_SELECT)
                                    : CreateStep.ITEM_SEARCH;
                            showStep(player);
                        })
                        .layout(l -> l.width(80).height(26)),
                new Button().setText("§aPreview →")
                        .setOnClick(e -> {
                            if (computePrice() <= 0) return;
                            createStep = CreateStep.CONFIRM;
                            showStep(player);
                        })
                        .layout(l -> l.flex(1).height(26))
        );
        myContentArea.addChildren(btnRow);
    }

    // ─ Step CONFIRM ───────────────────────────────────────────────────────────

    private void stepConfirm(Player player) {
        long totalPrice = computePrice();
        boolean serverSell = createKind == MarketListing.ListingKind.ADMIN_SELL;
        boolean buyOrder = createKind == MarketListing.ListingKind.PLAYER_BUY_ORDER || createKind == MarketListing.ListingKind.ADMIN_BUY_ORDER;
        double taxFrac  = serverSell || buyOrder ? 0.0 : createIsAuction ? switch (createDuration) {
            case 4  -> 0.10; case 8  -> 0.12; case 12 -> 0.16;
            case 16 -> 0.20; case 24 -> 0.24; default -> 0.10;
        } : 0.10;
        long taxAmount = (long) Math.ceil(totalPrice * taxFrac);
        long netSeller = Math.max(0, totalPrice - taxAmount);
        int  taxPct    = (int) Math.round(taxFrac * 100);
        String typeStr = createIsAuction ? "Auction (" + createDuration + "h)" : "Fixed Price (BIN)";
        if (createKind == MarketListing.ListingKind.PLAYER_BUY_ORDER) typeStr = "Player Buy Order";
        else if (createKind == MarketListing.ListingKind.ADMIN_SELL) typeStr = "Server Shop Listing";
        else if (createKind == MarketListing.ListingKind.ADMIN_BUY_ORDER) typeStr = "Server Buy Order";

        myContentArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(7).paddingTop(6));

        // Item icon + name
        var itemRow = new UIElement();
        itemRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(8).height(24));
        var icon = new ItemSlot(); icon.setItem(createItem); icon.layout(l -> l.width(22).height(22));
        itemRow.addChildren(icon, new Label().setText(
                "§f" + createItem.getHoverName().getString() + " §7×" + createItem.getCount()));
        myContentArea.addChildren(itemRow);

        myContentArea.addChildren(new Label().setText("§6§l── Confirm Listing ──"));
        myContentArea.addChildren(new Label().setText("§7Type:         §f" + typeStr));
        myContentArea.addChildren(new Label().setText("§7List Price:   §a" + Currency.format(totalPrice)));
        if (serverSell) {
            myContentArea.addChildren(new Label().setText("§7Duration:     §6Permanent server listing"));
            myContentArea.addChildren(new Label().setText("§7Players pay:  §a" + Currency.format(totalPrice)));
        } else if (buyOrder) {
            myContentArea.addChildren(new Label().setText(createKind == MarketListing.ListingKind.ADMIN_BUY_ORDER
                    ? "§7Source:       §6Server wallet"
                    : "§7Escrow:       §a" + Currency.format(totalPrice)));
        } else {
            myContentArea.addChildren(new Label().setText("§7Tax §8(" + taxPct + "%)§7:    §c-" + Currency.format(taxAmount)));
            myContentArea.addChildren(new Label().setText("§7You Receive:  §a" + Currency.format(netSeller)));
        }

        var btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(8).width(430));
        btnRow.addChildren(
                new Button().setText("§7← Back")
                        .setOnClick(e -> { createStep = CreateStep.PRICE_INPUT; showStep(player); })
                        .layout(l -> l.width(80).height(28)),
                new Button().setText(serverSell ? "§6Create Server Listing" : createKind == MarketListing.ListingKind.PLAYER_SELL ? "§a✦ Create Listing" : "§a✦ Create Order")
                        .setOnClick(e -> {
                            PacketDistributor.sendToServer(new MarketActionPacket(
                                    MarketActionPacket.Action.CREATE,
                                    new UUID(0, 0),
                                    createSlot,
                                    totalPrice,
                                    createIsAuction ? createDuration : 0,
                            createIsAuction,
                                    createKind.ordinal(),
                                    createItem.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(createItem.getItem()).toString(),
                                    createItem.getCount()));
                            returnToCreateHome(player);
                        })
                        .layout(l -> l.flex(1).height(28))
        );
        myContentArea.addChildren(btnRow);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private long computePrice() {
        return priceTotal;
    }

    private void resetCreateFlowState() {
        createStep = CreateStep.LIST;
        createItem = ItemStack.EMPTY;
        createSlot = -1;
        itemSearchText = "";
        itemSearchScroll = 0;
        requestedCount = 1;
        priceTotal = 0;
        createKind = MarketListing.ListingKind.PLAYER_SELL;
        createIsAuction = false;
        createFromAdminShop = false;
    }

    private void returnToCreateHome(Player player) {
        boolean wasCreatingFromAdminShop = createFromAdminShop;
        resetCreateFlowState();
        if (createFromAdminShop) {
            renderAdminContent(player);
        } else if (wasCreatingFromAdminShop) {
            renderAdminContent(player);
        } else {
            showStep(player);
        }
    }

    public boolean handleMouseScroll(double delta) {
        int direction = delta > 0 ? -1 : 1;
        switch (currentTab) {
            case BROWSE -> scrollBrowse(direction);
            case BUY_ORDERS -> scrollBuyOrders(direction);
            case ADMIN_SHOP -> scrollAdmin(direction);
            case MY_LISTINGS -> {
                if (createStep == CreateStep.LIST && myListArea != null) {
                    scrollMyListings(direction);
                } else if (createStep == CreateStep.ITEM_SEARCH) {
                    scrollItemSearch(direction);
                } else return false;
            }
        }
        return true;
    }

    private void scrollBrowse(int delta) {
        if (browseListArea == null) return;
        int size = (int) listingsRef.get().stream()
                .filter(l -> l.kind == MarketListing.ListingKind.PLAYER_SELL && matchesFilters(l.item)).count();
        browseScroll = Math.max(0, Math.min(Math.max(0, size - ROWS), browseScroll + delta));
        browseListArea.clearAllChildren(); fillBrowseList(browseListArea);
    }

    private void scrollBuyOrders(int delta) {
        if (buyOrderListArea == null) return;
        int size = (int) listingsRef.get().stream().filter(l -> l.isBuyOrder() && matchesFilters(l.item)).count();
        buyOrderScroll = Math.max(0, Math.min(Math.max(0, size - ROWS), buyOrderScroll + delta));
        buyOrderListArea.clearAllChildren(); fillBuyOrderList(buyOrderListArea);
    }

    private void scrollAdmin(int delta) {
        if (adminListArea == null) return;
        int size = (int) listingsRef.get().stream()
                .filter(l -> l.kind == MarketListing.ListingKind.ADMIN_SELL && matchesFilters(l.item)).count();
        adminScroll = Math.max(0, Math.min(Math.max(0, size - ROWS), adminScroll + delta));
        adminListArea.clearAllChildren(); fillAdminList(adminListArea);
    }

    private void scrollMyListings(int delta) {
        if (myListArea == null) return;
        var mc = Minecraft.getInstance();
        UUID myId = mc.player != null ? mc.player.getUUID() : null;
        int size = (int) listingsRef.get().stream()
                .filter(l -> myId != null && myId.equals(l.sellerUUID) && !l.isAdminListing()).count();
        myScroll = Math.max(0, Math.min(Math.max(0, size - MY_LISTING_ROWS), myScroll + delta));
        myListArea.clearAllChildren(); fillMyList(myListArea);
    }

    @OnlyIn(Dist.CLIENT)
    private void scrollItemSearch(int delta) {
        int size = findItemResults(itemSearchText).size();
        itemSearchScroll = Math.max(0, Math.min(Math.max(0, size - ITEM_SEARCH_ROWS), itemSearchScroll + delta));
        var mc = Minecraft.getInstance();
        if (mc.player != null) showStep(mc.player);
    }

    private UIElement buildBottomControls(Runnable scrollUp, Runnable scrollDown, Runnable refresh) {
        var wrapper = new UIElement();
        wrapper.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(1).width(440));

        Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            wrapper.clearAllChildren();

            var controlRow = new UIElement();
            controlRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(18).width(440));
            controlRow.addChildren(
                new Button().setText("▲").setOnClick(e -> scrollUp.run()).layout(l -> l.width(20).height(18)),
                new Button().setText("▼").setOnClick(e -> scrollDown.run()).layout(l -> l.width(20).height(18)),
                new Label().setText("§8Mouse wheel scrolls listings.").layout(l -> l.flex(1).height(14)),
                new Button().setText("§bRefresh")
                    .setOnClick(e -> {
                        PacketDistributor.sendToServer(new MarketActionPacket(
                                MarketActionPacket.Action.REFRESH, new UUID(0, 0), 0, 0L, 0, false));
                        refresh.run();
                    })
                    .layout(l -> l.width(58).height(18)),
                new Button().setText("§eFilter")
                    .setOnClick(e -> { filtersOpen = !filtersOpen; redraw[0].run(); })
                    .layout(l -> l.width(64).height(18)));
            wrapper.addChildren(controlRow);

            if (filtersOpen) {
                var labelRow = new UIElement();
                labelRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(12).width(440));
                labelRow.addChildren(
                    new Label().setText("§7Mod filter").layout(l -> l.width(150).height(12)),
                    new Label().setText("§7Item category").layout(l -> l.width(150).height(12)));
                wrapper.addChildren(labelRow);

                var filterRow = new UIElement();
                filterRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(20).width(440));
                var mod = new Selector<String>();
                var modOptions = modFilterOptions();
                if (!modOptions.contains(pendingModFilterText)) pendingModFilterText = modOptions.contains(modFilterText) ? modFilterText : "All Mods";
                mod.setCandidates(modOptions);
                mod.setValue(pendingModFilterText);
                mod.setOnValueChanged(value -> {
                    pendingModFilterText = value == null || value.isBlank() ? "All Mods" : value;
                });
                mod.layout(l -> l.width(150).height(20));
                var category = new Selector<String>();
                category.setCandidates(java.util.List.of("All", "Resources", "Admin's Factions", "Minecraft"));
                category.setValue(pendingCategoryFilter);
                category.setOnValueChanged(value -> {
                    pendingCategoryFilter = value == null || value.isBlank() ? "All" : value;
                });
                category.layout(l -> l.width(150).height(20));
                var applyBtn = new Button().setText("§aApply filters")
                    .setOnClick(e -> {
                        modFilterText = pendingModFilterText;
                        categoryFilter = pendingCategoryFilter;
                        refresh.run();
                    })
                    .layout(l -> l.flex(1).height(20));
                filterRow.addChildren(mod, category, applyBtn);
                wrapper.addChildren(filterRow);
            }

            TextField search = new TextField();
            search.setValue(searchText);
            search.bindObserver(value -> {
                searchText = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
                refresh.run();
            });
            search.getTextFieldStyle().placeholder(Component.literal("Search"));
            search.layout(l -> l.width(440).height(20));
            wrapper.addChildren(search);
        };

        redraw[0].run();
        return wrapper;
    }

    private UIElement buildFilterBar(Runnable refresh) {
        var wrapper = new UIElement();
        wrapper.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(2).width(440));

        var searchRow = new UIElement();
        searchRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(20).width(440));
        var search = new TextField().setValue(searchText).bindObserver(value -> {
            searchText = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            refresh.run();
        });
        search.layout(l -> l.width(190).height(18));
        var mod = new TextField().setValue(modFilterText).bindObserver(value -> {
            modFilterText = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            refresh.run();
        });
        mod.layout(l -> l.width(116).height(18));
        searchRow.addChildren(new Label().setText("§7Search"), search, new Label().setText("§7Mod"), mod);

        var categoryRow = new UIElement();
        categoryRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(20).width(440));
        var category = new Selector<String>();
        category.setCandidates(java.util.List.of("All", "Resources", "Admin's Factions", "Minecraft"));
        category.setValue(categoryFilter);
        category.setOnValueChanged(value -> {
            categoryFilter = value;
            refresh.run();
        });
        category.layout(l -> l.width(148).height(20));
        categoryRow.addChildren(new Label().setText("§7Category"), category, new Label().setText("§8Mouse wheel scrolls listings."));

        wrapper.addChildren(searchRow, categoryRow);
        return wrapper;
    }

    private boolean matchesFilters(ItemStack stack) {
        String name = stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(java.util.Locale.ROOT);
        String namespace = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().toLowerCase(java.util.Locale.ROOT);
        if (!searchText.isBlank() && !name.contains(searchText) && !itemId.contains(searchText)) return false;
        if (!modFilterText.isBlank() && !modFilterText.equals("All Mods") && !namespace.equals(modFilterText.toLowerCase(java.util.Locale.ROOT))) return false;
        return switch (categoryFilter) {
            case "Resources" -> isResourceLike(name, itemId);
            case "Admin's Factions" -> namespace.equals("adminsfactions");
            case "Minecraft" -> namespace.equals("minecraft");
            default -> true;
        };
    }

    private static List<Item> findItemResults(String query) {
        if (query == null || query.isBlank()) return List.of();
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        return BuiltInRegistries.ITEM.stream()
                .filter(item -> {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    String name = item.getDescription().getString().toLowerCase(java.util.Locale.ROOT);
                    String key = id.toString().toLowerCase(java.util.Locale.ROOT);
                    return name.contains(needle) || key.contains(needle);
                })
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .limit(200)
                .toList();
    }

    private static List<String> modFilterOptions() {
        var namespaces = BuiltInRegistries.ITEM.stream()
                .map(item -> BuiltInRegistries.ITEM.getKey(item).getNamespace())
                .distinct()
                .sorted()
                .toList();
        var options = new java.util.ArrayList<String>();
        options.add("All Mods");
        options.addAll(namespaces);
        return options;
    }

    private static boolean isResourceLike(String name, String itemId) {
        String value = name + " " + itemId;
        return value.contains("ore") || value.contains("ingot") || value.contains("gem")
                || value.contains("log") || value.contains("plank") || value.contains("stone")
                || value.contains("wood") || value.contains("coal") || value.contains("iron")
                || value.contains("gold") || value.contains("diamond") || value.contains("emerald")
                || value.contains("redstone") || value.contains("lapis") || value.contains("copper")
                || value.contains("netherite") || value.contains("quartz");
    }

    private UIElement itemNameLabel(ItemStack stack, int width) {
        var column = new UIElement();
        column.layout(l -> l.width(width).height(32).flexDirection(YogaFlexDirection.COLUMN));
        String[] lines = wrapName(stack.getHoverName().getString(), 22);
        column.addChildren(
                new Label().setText("§f" + lines[0]).layout(l -> l.width(width).height(14)),
                new Label().setText(lines[1].isBlank() ? "" : "§f" + lines[1]).layout(l -> l.width(width).height(14)));
        return column;
    }

    private static String[] wrapName(String name, int maxChars) {
        if (name.length() <= maxChars) return new String[] { name, "" };
        int split = name.lastIndexOf(' ', maxChars);
        if (split <= 0) split = maxChars;
        String first = name.substring(0, split).trim();
        String second = name.substring(split).trim();
        if (second.length() > maxChars) second = second.substring(0, Math.max(0, maxChars - 1)) + "…";
        return new String[] { first, second };
    }

    private static long priceCoinMult(String coin) {
        return switch (coin) {
            case "Platinum" -> Currency.COPPER_PER_PLATINUM;
            case "Gold"     -> Currency.COPPER_PER_GOLD;
            case "Silver"   -> Currency.COPPER_PER_SILVER;
            default         -> 1L;
        };
    }

    private static UIElement makeScrollRow(Runnable up, Runnable down) {
        var row = new UIElement();
        row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4));
        row.addChildren(
                new Button().setText("▲").setOnClick(e -> up.run()).layout(l -> l.width(20)),
                new Button().setText("▼").setOnClick(e -> down.run()).layout(l -> l.width(20))
        );
        return row;
    }
}
