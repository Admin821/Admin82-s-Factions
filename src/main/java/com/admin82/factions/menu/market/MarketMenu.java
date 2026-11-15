package com.admin82.factions.menu.market;

import com.admin82.factions.client.ClientPlayer;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.MarketDelivery;
import com.admin82.factions.economy.MarketListing;
import com.admin82.factions.economy.PricePoint;
import com.admin82.factions.economy.SoldListing;
import com.admin82.factions.economy.Transaction;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.menu.market.BountyPage.BountyView;
import com.admin82.factions.menu.market.MyMarketPage.CreateStep;
import com.admin82.factions.menu.market.TreasuryPage.TreasuryView;
import com.admin82.factions.network.packet.HistoryRequestPacket;
import com.admin82.factions.network.packet.MarketActionPacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaJustify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Faction Market UI.
 *
 * <p>Layout: a sidebar on the left (sections Buy / Sell / You, plus quick "+ Sell an Item" and
 * "+ Buy Order" buttons) and the selected page on the right. List pages share a toolbar at the top
 * (search, category, mod) and a pager at the bottom. Creating a listing is a short wizard with a
 * step indicator: Item → Type → Price → Confirm.
 */
public class MarketMenu extends AbstractContainerMenu {

    private final TreasuryPage treasuryPage = new TreasuryPage(this);
    private final BountyPage bountyPage = new BountyPage(this);
    final HistoryPage historyPage = new HistoryPage(this);
    final StocksPage stocksPage = new StocksPage(this);
    final MyMarketPage myMarket = new MyMarketPage(this);

    // Server-pushed data
    final AtomicReference<List<MarketListing>> listingsRef    = new AtomicReference<>(List.of());
    final AtomicReference<List<SoldListing>>   soldRef        = new AtomicReference<>(List.of());
    final AtomicReference<List<MarketDelivery>> deliveriesRef = new AtomicReference<>(List.of());
    final AtomicLong    walletRef       = new AtomicLong(0L);
    private final AtomicInteger myListingsCount = new AtomicInteger(0);
    private final AtomicInteger maxSlotsRef     = new AtomicInteger(1);

    // Pages
    enum Tab { AUCTION_HOUSE, MY_LISTINGS, HISTORY, BOUNTIES, TREASURY, STOCKS }
    Tab currentTab  = Tab.AUCTION_HOUSE;
    private int houseScroll = 0;
    /** Live data arrived while typing in a row; redraw on the next chance. */
    private boolean listRenderPending;
    int myScroll     = 0;
    static final int ITEM_SEARCH_ROWS = 6;
    private static final List<String> CATEGORIES = List.of("All", "Weapons", "Guns", "Ammo", "Armor", "Tools",
            "Food", "Potions", "Resources", "Blocks", "Redstone", "Utility", "Misc");
    /** Open dropdowns need the scroll wheel for their own list. */
    final List<Selector<?>> selectors = new ArrayList<>();
    private static final List<String> SORTS = List.of("Newest", "Price: low", "Price: high", "Ending soon", "Name");
    private String searchText = "";
    private String modFilterText = "All Mods";
    private String categoryFilter = "All";
    private String sortMode = "Newest";
    private boolean showBuyNow = true;
    private boolean showAuctions = true;
    private boolean showBuyOrders = true;
    private boolean showServer = true;
    private boolean hideMine = false;

    // Layout (recomputed on every rebuild)
    private static final int DESIGN_WIDTH = 600;
    private static final int DESIGN_HEIGHT = 360;
    private static final int SIDEBAR_WIDTH = 112;
    private int panelWidth = DESIGN_WIDTH;
    private int panelHeight = DESIGN_HEIGHT;
    int contentWidth = DESIGN_WIDTH - 20 - SIDEBAR_WIDTH - 6;
    int rows = 7;

    // Live element refs
    @Nullable private UIElement listArea;
    @Nullable private Label pagerLabel;
    @Nullable UIElement myContentArea;
    @Nullable UIElement myListArea;

    private final BlockPos pos;

    // Constructors

    public MarketMenu(int containerId, Inventory inv, BlockPos pos) {
        super(ModMenuTypes.MARKET.get(), containerId);
        this.pos = pos;
        addInventorySlots(inv);
        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu h) h.setModularUI(createModularUI(inv.player));
    }

    public MarketMenu(int containerId, Inventory inv, FriendlyByteBuf buf) {
        this(containerId, inv, buf.readBlockPos());
    }

    private void addInventorySlots(Inventory inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            addSlot(new Slot(inventory, slot, -10000, -10000));
        }
    }

    // Public sync API

    public void updateListings(List<MarketListing> listings, long wallet, int myCount, int maxSlots) {
        listingsRef.set(listings);
        walletRef.set(wallet);
        myListingsCount.set(myCount);
        maxSlotsRef.set(maxSlots);
        refreshVisibleData();
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        if (currentTab == Tab.HISTORY) {
            // Something changed on the market; fetch the latest activity.
            PacketDistributor.sendToServer(new HistoryRequestPacket(
                    HistoryRequestPacket.Scope.MARKET));
        } else if (historyPage.priceStatsArea != null && !historyPage.priceStatsItem.isEmpty()) {
            // Pricing an item: a sale may have just happened, refresh its stats and chart.
            PacketDistributor.sendToServer(new HistoryRequestPacket(
                    HistoryRequestPacket.Scope.PRICES, historyPage.priceStatsItem));
        }
    }

    public void updateSoldListings(List<SoldListing> sold) {
        soldRef.set(sold);
        refreshVisibleData();
    }

    public void updateDeliveries(List<MarketDelivery> deliveries) {
        deliveriesRef.set(deliveries);
        refreshVisibleData();
    }

    /** Re-renders only the data parts of the page, so typing in the search box is never interrupted. */
    private void refreshVisibleData() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        if (currentTab == Tab.MY_LISTINGS) {
            if (myMarket.createStep == CreateStep.LIST && myListArea != null) myMarket.renderMyList();
        } else if (currentTab == Tab.AUCTION_HOUSE) {
            // Don't redraw rows while the player is typing a turn-in amount in one of them.
            if (listArea != null && listArea.isChildFocused()) { listRenderPending = true; return; }
            renderList();
        }
    }

    /** Called by SyncHistoryPacket. */
    public void updateHistory(HistoryRequestPacket.Scope scope,
                              List<Transaction> entries) {
        if (scope != HistoryRequestPacket.Scope.MARKET) return;
        historyPage.marketHistory = entries;
        historyPage.renderHistory();
    }

    /** Called by SyncPriceStatsPacket. */
    /** Called by SyncStocksPacket. */
    public void updateStocks(List<com.admin82.factions.network.packet.SyncStocksPacket.Line> lines) {
        stocksPage.update(lines);
    }

    public void updatePriceStats(String itemId, List<PricePoint> points) {
        stocksPage.updateDetail(itemId, points);
        if (!itemId.equals(historyPage.priceStatsItem)) return;
        historyPage.priceStats = points;
        historyPage.renderPriceStats();
    }

    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player p) {
        return p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    @OnlyIn(Dist.CLIENT)
    public void rebuildClientUi(Player player) {
        selectors.clear();
        if (this instanceof IModularUIHolderMenu holder) holder.setModularUI(createModularUI(player));
    }

    @OnlyIn(Dist.CLIENT)
    static void refreshScreen() {
        if (Minecraft.getInstance().screen instanceof com.admin82.factions.screen.MarketScreen screen) {
            screen.refreshContent();
        }
    }

    // ROOT

    @OnlyIn(Dist.CLIENT)
    private ModularUI createModularUI(Player player) {
        int[] size = UiKit.panelSize(DESIGN_WIDTH, DESIGN_HEIGHT, 560, 290);
        panelWidth = size[0];
        panelHeight = size[1];
        contentWidth = panelWidth - 20 - SIDEBAR_WIDTH - 6;
        rows = Math.max(3, Math.min(10, (panelHeight - 140) / 28));
        listArea = null;
        pagerLabel = null;
        historyPage.historyArea = null;
        historyPage.historyPager = null;
        historyPage.priceStatsArea = null;
        bountyPage.bountyArea = null;
        treasuryPage.treasuryArea = null;
        myListArea = null;
        myContentArea = null;

        var frame = new UIElement();
        frame.layout(l -> l.width(panelWidth).height(panelHeight).paddingAll(2));
        frame.addClass("preview_bg");
        var root = MarketUi.vbox(6);
        root.layout(l -> l.width(panelWidth - 4).height(panelHeight - 4).paddingAll(8));
        root.addClass("panel_bg");

        var header = MarketUi.hbox(6);
        header.layout(l -> l.height(16));
        var wallet = new Label();
        wallet.bindDataSource(SupplierDataSource.of(() ->
                Component.literal("§7Wallet: §a" + Currency.format(walletRef.get()))));
        wallet.lss("horizontal-align", "right");
        wallet.layout(l -> l.width(200));
        header.addChildren(new Label().setText("§6§lFaction Market").layout(l -> l.flex(1)), wallet);

        var body = MarketUi.hbox(6);
        body.layout(l -> l.flex(1).alignItems(YogaAlign.STRETCH));
        var content = MarketUi.vbox(4);
        content.layout(l -> l.width(contentWidth));
        content.addChildren(switch (currentTab) {
            case AUCTION_HOUSE -> buildAuctionHouse();
            case MY_LISTINGS -> myMarket.buildMyPage(player);
            case HISTORY -> historyPage.buildHistoryPage();
            case BOUNTIES -> bountyPage.buildBountyPage();
            case TREASURY -> player.hasPermissions(2) ? treasuryPage.buildTreasuryPage() : buildAuctionHouse();
            case STOCKS -> stocksPage.buildPage();
        });
        body.addChildren(buildSidebar(player), content);
        root.addChildren(header, body);
        frame.addChildren(root);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    // SIDEBAR

    @OnlyIn(Dist.CLIENT)
    private UIElement buildSidebar(Player player) {
        var side = UiKit.background(MarketUi.vbox(3), UiKit.CARD);
        side.layout(l -> l.width(SIDEBAR_WIDTH).paddingAll(4));
        side.addChildren(
                MarketUi.sectionLabel("Market"),
                navButton(Tab.AUCTION_HOUSE, "Auction House", "§7Everything on the market: items for sale,",
                        "§7auctions, buy orders and the server shop."),
                navButton(Tab.MY_LISTINGS, "My Market", "§7Claim purchases and proceeds,", "§7manage your listings and orders."),
                navButton(Tab.HISTORY, "History", "§7Everything you bought, sold, bid on", "§7and ordered on the market."),
                navButton(Tab.BOUNTIES, "Bounty Board", "§7Put a price on a player's head,", "§7or hunt someone for their bounty."),
                MarketUi.sectionLabel("Stocks"),
                navButton(Tab.STOCKS, "Prices", "§7Every item's price over time:", "§7what's going up or down and what sells most."));
        var claimBadge = new Label();
        claimBadge.bindDataSource(SupplierDataSource.of(() -> {
            int claimable = claimableCount();
            return Component.literal(claimable > 0 ? "§e● " + claimable + " to claim" : "");
        }));
        claimBadge.layout(l -> l.height(10).paddingHorizontal(4));
        side.addChildren(claimBadge);

        var filler = new UIElement();
        filler.layout(l -> l.flex(1));
        side.addChildren(filler,
                actionButton("§a+ Sell an Item", () -> startCreate(MarketListing.ListingKind.PLAYER_SELL, CreateStep.INV_SELECT),
                        "§aSell an item", "§7Pick an item from your inventory", "§7and set a price or start an auction."),
                actionButton("§b+ Buy Order", () -> startCreate(MarketListing.ListingKind.PLAYER_BUY_ORDER, CreateStep.ITEM_SEARCH),
                        "§bCreate a buy order", "§7Ask for any item at a price you choose.", "§7The money is held until someone sells."));
        if (player.hasPermissions(2)) {
            side.addChildren(MarketUi.sectionLabel("Admin"),
                    navButton(Tab.TREASURY, "Treasury", "§7Operators only: every tax and fee", "§7the server has taken in, by source."),
                    actionButton("§6+ Server Sale", () -> startCreate(MarketListing.ListingKind.ADMIN_SELL, CreateStep.INV_SELECT),
                            "§6Server shop listing", "§7Operators only. Permanent, unlimited stock."),
                    actionButton("§6+ Server Buy", () -> startCreate(MarketListing.ListingKind.ADMIN_BUY_ORDER, CreateStep.ITEM_SEARCH),
                            "§6Server buy order", "§7Operators only. Paid by the server."));
        }
        var slots = new Label();
        slots.bindDataSource(SupplierDataSource.of(() -> Component.literal(
                "§7Listings §f" + myListingsCount.get() + "§7/§f" + maxSlotsRef.get())));
        MarketUi.tooltipOn(slots, "§7Listings and buy orders you have up,", "§7out of the most you can have at once.");
        side.addChildren(slots);
        return side;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement navButton(Tab tab, String text, String... help) {
        boolean active = tab == currentTab;
        var button = new Button().setText((active ? "§f§l" : "§7") + text).setOnClick(e -> switchTab(tab));
        button.layout(l -> l.height(18));
        if (active) button.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
        if (help.length > 0) MarketUi.tooltipOn(button, help);
        return button;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement actionButton(String text, Runnable action, String... help) {
        var button = new Button().setText(text).setOnClick(e -> action.run());
        button.layout(l -> l.height(18));
        if (help.length > 0) MarketUi.tooltipOn(button, help);
        return button;
    }

    @OnlyIn(Dist.CLIENT)
    private void switchTab(Tab tab) {
        currentTab = tab;
        myMarket.pendingCancelId = null;
        resetCreateFlowState();
        PacketDistributor.sendToServer(new MarketActionPacket(
                MarketActionPacket.Action.REFRESH, new UUID(0, 0), 0, 0L, 0, false));
        refreshScreen();
    }

    @OnlyIn(Dist.CLIENT)
    private void startCreate(MarketListing.ListingKind kind, CreateStep firstStep) {
        resetCreateFlowState();
        currentTab = Tab.MY_LISTINGS;
        myMarket.createKind = kind;
        myMarket.createIsAuction = false;
        myMarket.createStep = firstStep;
        refreshScreen();
    }

    // LIST PAGES (player listings / server shop / buy orders)

    @OnlyIn(Dist.CLIENT)
    private UIElement buildAuctionHouse() {
        var page = MarketUi.vbox(4);
        page.layout(l -> l.flex(1));
        page.addChildren(buildFilterBar());

        listArea = MarketUi.vbox(2);
        listArea.layout(l -> l.flex(1));

        pagerLabel = new Label();
        pagerLabel.lss("horizontal-align", "center");
        pagerLabel.layout(l -> l.flex(1));
        var previous = new Button().setText("‹").setOnClick(e -> scrollCurrent(-rows));
        previous.layout(l -> l.width(22).height(16));
        var next = new Button().setText("›").setOnClick(e -> scrollCurrent(rows));
        next.layout(l -> l.width(22).height(16));
        var pager = MarketUi.hbox(4);
        pager.layout(l -> l.height(18));
        pager.addChildren(previous, pagerLabel, next);

        page.addChildren(listArea, pager);
        renderList();
        return page;
    }

    /**
     * Row 1: search · category · mod · sort. Row 2: type chips · hide mine · clear · refresh.
     * Search filters live; the other controls apply immediately.
     */
    @OnlyIn(Dist.CLIENT)
    private UIElement buildFilterBar() {
        var bar = UiKit.background(MarketUi.vbox(3), UiKit.CARD);
        bar.layout(l -> l.paddingAll(4));

        var first = MarketUi.hbox(4);
        first.layout(l -> l.height(18));
        TextField search = UiKit.textField("");
        search.setValue(searchText);
        search.getTextFieldStyle().placeholder(Component.literal("Search items..."));
        search.bindObserver(value -> {
            String updated = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (updated.equals(searchText)) return;
            searchText = updated;
            resetScroll();
            renderList();
        });
        search.layout(l -> l.flex(1).height(16));
        first.addChildren(search,
                selector(CATEGORIES, categoryFilter, 84, value -> categoryFilter = value, "§7Category"),
                selector(modOptionsOnMarket(), modFilterText, 84, value -> modFilterText = value, "§7Mod",
                        "§8Only mods with items on the market are listed."),
                selector(SORTS, sortMode, 76, value -> sortMode = value, "§7Sort order"));

        var second = MarketUi.hbox(3);
        second.layout(l -> l.height(16));
        second.addChildren(
                chip("Buy Now", showBuyNow, () -> showBuyNow = !showBuyNow, "§aFixed-price items from players."),
                chip("Auctions", showAuctions, () -> showAuctions = !showAuctions, "§dItems you can bid on."),
                chip("Buy Orders", showBuyOrders, () -> showBuyOrders = !showBuyOrders,
                        "§bPlayers and the server want to buy these.", "§7Turn in as many as you like."),
                chip("Server", showServer, () -> showServer = !showServer, "§6The permanent server shop."));
        var filler = new UIElement();
        filler.layout(l -> l.flex(1));
        second.addChildren(filler,
                chip("Hide mine", hideMine, () -> hideMine = !hideMine, "§7Hide your own listings and orders."));
        var clear = new Button().setText("§7Clear").setOnClick(e -> {
            searchText = "";
            categoryFilter = "All";
            modFilterText = "All Mods";
            sortMode = "Newest";
            showBuyNow = showAuctions = showBuyOrders = showServer = true;
            hideMine = false;
            resetScroll();
            refreshScreen();
        });
        clear.layout(l -> l.width(38).height(16));
        MarketUi.tooltipOn(clear, "§7Reset all filters.");
        var refresh = new Button().setText("Refresh").setOnClick(e -> PacketDistributor.sendToServer(
                new MarketActionPacket(MarketActionPacket.Action.REFRESH, new UUID(0, 0), 0, 0L, 0, false)));
        refresh.layout(l -> l.width(48).height(16));
        second.addChildren(clear, refresh);

        bar.addChildren(first, second);
        return bar;
    }

    @OnlyIn(Dist.CLIENT)
    UIElement chip(String text, boolean on, Runnable toggle, String... help) {
        var button = new Button().setText((on ? "§f" : "§8") + text).setOnClick(e -> {
            toggle.run();
            resetScroll();
            refreshScreen();
        });
        int width = Minecraft.getInstance().font.width(text) + 12;
        button.layout(l -> l.width(width).height(16));
        if (on) button.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#2a6e2aff)");
        if (help.length > 0) MarketUi.tooltipOn(button, help);
        return button;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement selector(List<String> options, String current, int width,
                               Consumer<String> apply, String... help) {
        var selector = new Selector<String>();
        selectors.add(selector);
        selector.setCandidates(options);
        selector.setValue(options.contains(current) ? current : options.get(0));
        selector.setOnValueChanged(value -> {
            if (value == null || value.isBlank()) return;
            apply.accept(value);
            resetScroll();
            renderList();
        });
        selector.layout(l -> l.width(width).height(16));
        if (help.length > 0) MarketUi.tooltipOn(selector, help);
        return selector;
    }

    /** "All Mods" plus only the mods that actually have something on the market. */
    @OnlyIn(Dist.CLIENT)
    private List<String> modOptionsOnMarket() {
        var options = new ArrayList<String>();
        options.add("All Mods");
        listingsRef.get().stream()
                .map(l -> BuiltInRegistries.ITEM.getKey(l.item.getItem()).getNamespace())
                .distinct().sorted().forEach(options::add);
        if (!options.contains(modFilterText)) modFilterText = "All Mods";
        return options;
    }

    @OnlyIn(Dist.CLIENT)
    private boolean typeVisible(MarketListing listing) {
        return switch (listing.kind) {
            case PLAYER_SELL -> listing.isAuction ? showAuctions : showBuyNow;
            case PLAYER_BUY_ORDER -> showBuyOrders;
            case ADMIN_SELL -> showServer;
            case ADMIN_BUY_ORDER -> showBuyOrders || showServer;
        };
    }

    /** Price used for sorting: per item for buy orders, current bid for auctions. */
    private static long sortPrice(MarketListing listing) {
        if (listing.isAuction) return Math.max(listing.price, listing.highestBid);
        return listing.price;
    }

    @OnlyIn(Dist.CLIENT)
    private List<MarketListing> currentListings() {
        UUID me = myId();
        long now = System.currentTimeMillis();
        List<MarketListing> result = new ArrayList<>(listingsRef.get().stream()
                .filter(this::typeVisible)
                .filter(l -> !(l.kind == MarketListing.ListingKind.PLAYER_SELL && l.expiresAt <= now))
                .filter(l -> !hideMine || me == null || !me.equals(l.sellerUUID))
                .filter(l -> matchesFilters(l.item))
                .toList());
        switch (sortMode) {
            case "Price: low" -> result.sort(Comparator.comparingLong(MarketMenu::sortPrice));
            case "Price: high" -> result.sort(Comparator.comparingLong(MarketMenu::sortPrice).reversed());
            case "Ending soon" -> result.sort(Comparator.comparingLong(l -> l.expiresAt));
            case "Name" -> result.sort(Comparator.comparing(l -> l.item.getHoverName().getString(), String.CASE_INSENSITIVE_ORDER));
            default -> Collections.reverse(result); // listings are stored oldest first
        }
        return result;
    }

    @OnlyIn(Dist.CLIENT)
    /** Called every client tick by MarketScreen: applies a live update held back while typing. */
    public void clientTick() {
        // Keep the treasury page live while it is open.
        if (currentTab == Tab.TREASURY && treasuryPage.treasuryArea != null && ++treasuryPage.treasuryTick >= 100) {
            treasuryPage.treasuryTick = 0;
            treasuryPage.requestTreasury(false);
        }
        if (listRenderPending && (listArea == null || !listArea.isChildFocused())) renderList();
    }

    private void renderList() {
        if (listArea == null || currentTab != Tab.AUCTION_HOUSE) return;
        listRenderPending = false;
        listArea.clearAllChildren();
        List<MarketListing> listings = currentListings();
        houseScroll = Math.max(0, Math.min(Math.max(0, listings.size() - rows), houseScroll));
        if (pagerLabel != null) {
            pagerLabel.setText(listings.isEmpty() ? "§80 results" : "§7" + (houseScroll + 1) + "-"
                    + Math.min(listings.size(), houseScroll + rows) + " of " + listings.size() + "  §8(scroll for more)");
        }
        if (listings.isEmpty()) {
            boolean filtered = !searchText.isBlank() || !"All".equals(categoryFilter) || !"All Mods".equals(modFilterText)
                    || !showBuyNow || !showAuctions || !showBuyOrders || !showServer || hideMine;
            listArea.addChildren(new Label().setText(filtered
                    ? "§7Nothing matches your filters. §8Try §7Clear§8."
                    : "§7The market is empty. Be the first: §a+ Sell an Item§7."));
            return;
        }
        UUID me = myId();
        long now = System.currentTimeMillis();
        for (MarketListing listing : listings.subList(houseScroll, Math.min(listings.size(), houseScroll + rows))) {
            listArea.addChildren(rowFor(listing, me != null && me.equals(listing.sellerUUID), now));
        }
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement rowFor(MarketListing listing, boolean mine, long now) {
        return switch (listing.kind) {
            case PLAYER_SELL -> {
                String price = listing.isAuction
                        ? (listing.highestBid > 0 ? "§7bid §a" + Currency.format(listing.highestBid)
                                                  : "§7from §a" + Currency.format(listing.price))
                        : "§a" + Currency.format(listing.price);
                UIElement action = mine ? ownTag() : listing.isAuction ? bidControls(listing) : buyButton(listing);
                yield listingRow(listing, listing.isAuction ? "§dAuction" : "§aBuy Now", price,
                        "§7" + MarketUi.timeLeft(listing.expiresAt - now), action,
                        listing.isAuction ? "§dAuction §7- highest bid wins when it ends." : "§aBuy Now §7- fixed price.");
            }
            case ADMIN_SELL -> listingRow(listing, "§6Server", "§a" + Currency.format(listing.price), "§8∞",
                    mine ? ownTag() : buyButton(listing), "§6Server shop §7- always in stock.");
            case PLAYER_BUY_ORDER, ADMIN_BUY_ORDER -> {
                boolean server = listing.kind == MarketListing.ListingKind.ADMIN_BUY_ORDER;
                String progress = server ? "§8∞" : "§f" + listing.orderFilled + "§7/" + listing.orderQuantity;
                yield listingRow(listing, server ? "§eBuying" : "§bBuying", "§a" + Currency.format(listing.price) + " §7ea",
                        progress, mine ? ownTag() : turnInControls(listing),
                        server ? "§eServer buy order §7- buys any amount." : "§bBuy order §7- "
                                + listing.remainingQuantity() + " more wanted, ends in " + MarketUi.timeLeft(listing.expiresAt - now) + ".",
                        "§7Pays §a" + Currency.format(listing.price) + " §7per item.");
            }
        };
    }

    /** One list row: icon · name · type badge · price · time/progress · action. */
    @OnlyIn(Dist.CLIENT)
    private UIElement listingRow(MarketListing listing, String badge, String price, String extra, UIElement action,
                                 String... info) {
        int fixed = 8 + 18 + 54 + 88 + 40 + 104 + 5 * 4;
        int nameWidth = Math.max(60, contentWidth - fixed);
        var row = UiKit.background(MarketUi.hbox(4), UiKit.ROW);
        row.layout(l -> l.height(24).paddingHorizontal(4));
        var icon = new ItemSlot();
        icon.setItem(listing.item);
        icon.layout(l -> l.width(18).height(18));
        var actionBox = MarketUi.hbox(2);
        actionBox.layout(l -> l.width(104).justifyContent(YogaJustify.FLEX_END));
        actionBox.addChildren(action);
        var nameLabel = new Label().setText(MarketUi.itemLabel(listing.item, nameWidth));
        nameLabel.layout(l -> l.width(nameWidth));
        List<String> tip = new ArrayList<>();
        tip.add("§f" + listing.item.getHoverName().getString()
                + (listing.isBuyOrder() ? "" : " §7×" + listing.item.getCount()));
        tip.addAll(List.of(info));
        tip.add("§8" + BuiltInRegistries.ITEM.getKey(listing.item.getItem()));
        MarketUi.tooltipOn(nameLabel, tip.toArray(String[]::new));
        row.addChildren(icon, nameLabel,
                new Label().setText(badge).layout(l -> l.width(54)),
                new Label().setText(price).layout(l -> l.width(88)),
                new Label().setText(extra).layout(l -> l.width(40)),
                actionBox);
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buyButton(MarketListing listing) {
        var button = new Button().setText("§aBuy").setOnClick(e -> PacketDistributor.sendToServer(new MarketActionPacket(
                MarketActionPacket.Action.BUY, listing.listingId, -1, 0, 0, false)));
        button.layout(l -> l.width(44).height(18));
        MarketUi.tooltipOn(button, "§aBuy for §e" + Currency.format(listing.price), "§8Paid from your wallet.");
        return button;
    }

    /** Amount box (pre-filled with what you can turn in) and a Sell button. */
    @OnlyIn(Dist.CLIENT)
    private UIElement turnInControls(MarketListing listing) {
        int have = countInInventory(listing.item);
        int canTurnIn = Math.min(have, listing.remainingQuantity());
        if (canTurnIn <= 0) {
            var none = new Label().setText("§8You have 0");
            MarketUi.tooltipOn(none, "§7You don't have any §f" + listing.item.getHoverName().getString() + "§7 to sell.");
            return none;
        }
        int[] amount = {canTurnIn};
        var field = UiKit.textField("");
        field.setValue(Integer.toString(canTurnIn));
        field.bindObserver(v -> {
            try { amount[0] = Math.max(1, Math.min(canTurnIn, Integer.parseInt(v.trim()))); }
            catch (RuntimeException ignored) {}
        });
        field.layout(l -> l.width(36).height(18));
        MarketUi.tooltipOn(field, "§7How many to turn in", "§8You have " + have + ", up to " + canTurnIn + " accepted.");
        var button = new Button().setText("§aSell").setOnClick(e -> PacketDistributor.sendToServer(new MarketActionPacket(
                MarketActionPacket.Action.FULFILL_BUY_ORDER, listing.listingId, -1, 0, 0, false,
                listing.kind.ordinal(), "", amount[0])));
        button.layout(l -> l.width(40).height(18));
        MarketUi.tooltipOn(button, "§aSell to this order", "§7Each pays §e" + Currency.format(listing.price),
                "§8Items are taken from your inventory.");
        var box = MarketUi.hbox(2);
        box.addChildren(field, button);
        return box;
    }

    @OnlyIn(Dist.CLIENT)
    private static int countInInventory(ItemStack template) {
        var player = Minecraft.getInstance().player;
        if (player == null) return 0;
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) total += stack.getCount();
        }
        return total;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement bidControls(MarketListing listing) {
        long minimumBid = Math.max(listing.price, listing.highestBid + 1);
        String[] bid = {Long.toString(minimumBid)};
        var field = UiKit.textField("");
        field.setValue(bid[0]);
        field.bindObserver(v -> bid[0] = v == null ? "" : v);
        field.layout(l -> l.width(60).height(18));
        var button = new Button().setText("§dBid").setOnClick(e -> {
            long amount = Currency.parse(bid[0]);
            if (amount > 0) PacketDistributor.sendToServer(new MarketActionPacket(
                    MarketActionPacket.Action.BID, listing.listingId, -1, amount, 0, true));
        });
        button.layout(l -> l.width(38).height(18));
        MarketUi.tooltipOn(button, "§dPlace a bid", "§7Minimum: §e" + Currency.format(minimumBid),
                "§8Amount in copper, or e.g. 2g 50s.");
        var box = MarketUi.hbox(2);
        box.addChildren(field, button);
        return box;
    }

    @OnlyIn(Dist.CLIENT)
    private static UIElement ownTag() {
        return new Label().setText("§8Yours");
    }

    /** Called by TreasuryPacket.Sync. */
    public void updateTreasury(com.admin82.factions.economy.Treasury.Snapshot snapshot) {
        treasuryPage.treasury = snapshot;
        if (FMLEnvironment.dist == Dist.CLIENT && currentTab == Tab.TREASURY && treasuryPage.treasuryArea != null) treasuryPage.renderTreasury();
    }

    /** Called by SyncBountiesPacket. */
    public void updateBounties(List<com.admin82.factions.network.packet.SyncBountiesPacket.View> views, List<String> players,
                               int taxPercent) {
        bountyPage.bounties = views;
        bountyPage.bountyTaxPercent = taxPercent;
        bountyPage.bountyPlayers = players;
        if (FMLEnvironment.dist == Dist.CLIENT && currentTab == Tab.BOUNTIES && bountyPage.bountyArea != null && !bountyPage.bountyArea.isChildFocused()) {
            bountyPage.renderBounties();
        }
    }

    // STATE + SCROLLING

    long computePrice() {
        return myMarket.priceTotal;
    }

    private void resetCreateFlowState() {
        myMarket.createStep = CreateStep.LIST;
        myMarket.createItem = ItemStack.EMPTY;
        myMarket.createSlot = -1;
        myMarket.itemSearchText = "";
        myMarket.itemSearchScroll = 0;
        myMarket.requestedCount = 1;
        myMarket.priceTotal = 0;
        myMarket.createKind = MarketListing.ListingKind.PLAYER_SELL;
        myMarket.createIsAuction = false;
    }

    @OnlyIn(Dist.CLIENT)
    void returnToCreateHome(Player player) {
        resetCreateFlowState();
        myMarket.showStep(player);
    }

    private int claimableCount() {
        UUID me = myId();
        if (me == null) return 0;
        int count = (int) deliveriesRef.get().stream().filter(d -> me.equals(d.playerUUID)).count();
        count += (int) soldRef.get().stream().filter(s -> me.equals(s.sellerUUID)).count();
        return count;
    }

    @Nullable
    static UUID myId() {
        if (FMLEnvironment.dist != Dist.CLIENT) return null;
        Player player = ClientPlayer.get();
        return player == null ? null : player.getUUID();
    }

    public boolean handleMouseScroll(double delta) {
        selectors.removeIf(sel -> sel.getParent() == null);
        if (selectors.stream().anyMatch(Selector::isOpen)) return false; // let the dropdown scroll
        int direction = delta > 0 ? -1 : 1;
        if (currentTab == Tab.STOCKS) {
            if (stocksPage.detailItem == null) {
                stocksPage.scroll = Math.max(0, stocksPage.scroll + direction);
                stocksPage.render();
            }
            return true;
        }
        if (currentTab == Tab.HISTORY) {
            historyPage.historyScroll = Math.max(0, historyPage.historyScroll + direction);
            historyPage.renderHistory();
            return true;
        }
        if (currentTab == Tab.TREASURY) {
            if (treasuryPage.treasuryView == TreasuryView.RECENT) {
                treasuryPage.treasuryScroll = Math.max(0, treasuryPage.treasuryScroll + direction);
                treasuryPage.renderTreasury();
            }
            return true;
        }
        if (currentTab == Tab.BOUNTIES) {
            if (bountyPage.bountyView == BountyView.BOARD) {
                bountyPage.bountyScroll = Math.max(0, bountyPage.bountyScroll + direction);
                bountyPage.renderBounties();
            }
            return true;
        }
        if (currentTab == Tab.MY_LISTINGS) {
            if (myMarket.createStep == CreateStep.LIST && myListArea != null) {
                myScroll = Math.max(0, myScroll + direction);
                myMarket.renderMyList();
                return true;
            }
            if (myMarket.createStep == CreateStep.ITEM_SEARCH) {
                scrollItemSearch(direction);
                return true;
            }
            return false;
        }
        scrollCurrent(direction);
        return true;
    }

    private void scrollCurrent(int delta) {
        houseScroll = Math.max(0, houseScroll + delta);
        renderList();
    }

    private void resetScroll() {
        houseScroll = 0;
    }

    @OnlyIn(Dist.CLIENT)
    private void scrollItemSearch(int delta) {
        int size = MarketUi.findItemResults(myMarket.itemSearchText).size();
        myMarket.itemSearchScroll = Math.max(0, Math.min(Math.max(0, size - ITEM_SEARCH_ROWS), myMarket.itemSearchScroll + delta));
        var mc = Minecraft.getInstance();
        if (mc.player != null) myMarket.showStep(mc.player);
    }

    // FILTERS + HELPERS

    private boolean matchesFilters(ItemStack stack) {
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
        String namespace = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().toLowerCase(Locale.ROOT);
        if (!searchText.isBlank() && !name.contains(searchText) && !itemId.contains(searchText)) return false;
        if (!modFilterText.isBlank() && !modFilterText.equals("All Mods")
                && !namespace.equals(modFilterText.toLowerCase(Locale.ROOT))) return false;
        return categoryFilter.equals("All") || categoryFilter.equals(MarketUi.categoryOf(stack, name, itemId, namespace));
    }

}
