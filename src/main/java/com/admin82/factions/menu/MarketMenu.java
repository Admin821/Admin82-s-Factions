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

import java.util.List;
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
    private enum CreateStep { LIST, INV_SELECT, TYPE_SELECT, DURATION_SELECT, PRICE_INPUT, CONFIRM }
    private CreateStep createStep    = CreateStep.LIST;
    private int        createSlot    = -1;
    private ItemStack  createItem    = ItemStack.EMPTY;
    private boolean    createIsAuction = false;
    private int        createDuration  = 4;      // hours

    // Single total price in copper (set from number+dropdown in PRICE_INPUT step)
    private long priceTotal = 0;

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private enum Tab { BROWSE, MY_LISTINGS }
    private Tab currentTab  = Tab.BROWSE;
    private int browseScroll = 0;
    private int myScroll     = 0;
    private static final int ROWS = 8;

    // Live element refs
    private UIElement browseListArea;
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
        UIElement myPanel     = buildMyListingsPanel(player);
        contentArea.addChildren(currentTab == Tab.BROWSE ? browsePanel : myPanel);
        buildTabBar(tabBar, browsePanel, myPanel, contentArea, player);

        root.addChildren(titleRow, tabBar, contentArea);
        frame.addChildren(root);
        return ModularUI.of(
                UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)),
                player);
    }

    private void buildTabBar(UIElement bar, UIElement browsePanel, UIElement myPanel,
                              UIElement content, Player player) {
        bar.clearAllChildren();
        Tab[]    tabs   = Tab.values();
        String[] labels = { "Browse", "My Listings" };
        UIElement[] panels = { browsePanel, myPanel };
        for (int i = 0; i < tabs.length; i++) {
            final int fi = i;
            boolean active = tabs[i] == currentTab;
            var btn = new Button()
                    .setText(labels[i])
                    .setOnClick(e -> {
                        currentTab = tabs[fi];
                        content.clearAllChildren();
                        content.addChildren(panels[fi]);
                        buildTabBar(bar, browsePanel, myPanel, content, player);
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
        panel.addChildren(new Label().setText("§7All listings — click Buy or enter a Bid."));

        browseListArea = new UIElement();
        browseListArea.layout(l -> l.flex(1).width(440));
        fillBrowseList(browseListArea);

        panel.addChildren(browseListArea, makeScrollRow(
                () -> { browseScroll = Math.max(0, browseScroll - 1); browseListArea.clearAllChildren(); fillBrowseList(browseListArea); },
                () -> { browseScroll = Math.min(Math.max(0, listingsRef.get().size() - ROWS), browseScroll + 1); browseListArea.clearAllChildren(); fillBrowseList(browseListArea); }
        ));
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private void fillBrowseList(UIElement area) {
        var listings = listingsRef.get();
        if (listings.isEmpty()) { area.addChildren(new Label().setText("§7No listings available.")); return; }
        long now = System.currentTimeMillis();
        for (int i = 0; i < ROWS; i++) {
            int idx = i + browseScroll;
            if (idx >= listings.size()) break;
            MarketListing l = listings.get(idx);
            boolean expired = l.expiresAt <= now;
            var row = new UIElement();
            row.layout(r -> r.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(22).width(440));

            var icon = new ItemSlot(); icon.setItem(l.item); icon.layout(r -> r.width(20).height(20));
            String priceStr = l.isAuction
                    ? (l.highestBid > 0 ? "Bid: §a" + Currency.format(l.highestBid)
                                        : "Start: §a" + Currency.format(l.price))
                    : "§a" + Currency.format(l.price);
            long remaining = Math.max(0, (l.expiresAt - now) / 1000);
            String timeStr = expired ? "§cExp" : remaining > 3600 ? "§7" + (remaining / 3600) + "h" : "§c" + (remaining / 60) + "m";

            row.addChildren(
                    icon,
                    new Label().setText("§f" + l.item.getHoverName().getString()).layout(r -> r.width(104)),
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

    // ══════════════════════════════════════════════════════════════════════════
    // MY LISTINGS TAB  +  CREATE FLOW
    // ══════════════════════════════════════════════════════════════════════════

    private UIElement buildMyListingsPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(6).gapAll(4).flexDirection(YogaFlexDirection.COLUMN));
        panel.addChildren(new Label().bindDataSource(SupplierDataSource.of(() ->
                Component.literal("§7Listings: §e" + myListingsCount.get() + " / " + maxSlotsRef.get()
                        + "  §8(slots = faction size)"))));

        myContentArea = new UIElement();
        myContentArea.layout(l -> l.flex(1).width(440));
        showStep(player);

        panel.addChildren(myContentArea);
        return panel;
    }

    private void showStep(Player player) {
        myContentArea.clearAllChildren();
        switch (createStep) {
            case LIST            -> stepList(player);
            case INV_SELECT      -> stepInvSelect(player);
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
                () -> { myScroll = Math.max(0, myScroll - 1); myListArea.clearAllChildren(); fillMyList(myListArea); },
                () -> { myScroll++; myListArea.clearAllChildren(); fillMyList(myListArea); }
        );
        var createBtn = new Button().setText("§a✦ Create New Listing");
        createBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty()
                .append(Component.literal("§aList an item on the market."),
                        Component.literal("§7Opens your inventory to choose the item.")));
        createBtn.setOnClick(e -> { createStep = CreateStep.INV_SELECT; showStep(player); });
        createBtn.layout(l -> l.alignSelf(YogaAlign.CENTER).width(210).height(26));

        myContentArea.addChildren(myListArea, scrollRow, createBtn);
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
                    new Label().setText("§a[SOLD] §f" + sale.itemName).layout(r -> r.flex(1)),
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
        var mine = listingsRef.get().stream().filter(l -> myId != null && myId.equals(l.sellerUUID)).toList();
        if (mine.isEmpty() && mySold.isEmpty()) {
            area.addChildren(new Label().setText("§7No active listings. Click §aCreate New Listing§7 below."));
            return;
        }
        if (mine.isEmpty()) return;
        for (int i = 0; i < ROWS - 1; i++) {
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
                    new Label().setText("§f" + l.item.getHoverName().getString()).layout(r -> r.flex(1)),
                    new Label().setText(priceStr).layout(r -> r.width(110)),
                    cancelBtn);
            area.addChildren(row);
        }
    }

    // ─ Step INV_SELECT ────────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    private void stepInvSelect(Player player) {
        myContentArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(6));
        myContentArea.addChildren(new Label().setText("§6Step 1 §7— Click the item you want to sell."));

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
                    createStep = CreateStep.TYPE_SELECT;
                    showStep(player);
                });
                rowEl.addChildren(cell);
            }
            myContentArea.addChildren(rowEl);
        }
        var backBtn = new Button().setText("§7← Back")
                .setOnClick(e -> { createStep = CreateStep.LIST; showStep(player); })
                .layout(l -> l.width(80));
        myContentArea.addChildren(backBtn);
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
                "§6Step " + (createIsAuction ? "4" : "3") + " §7— Set your asking price."));

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
                            createStep = createIsAuction ? CreateStep.DURATION_SELECT : CreateStep.TYPE_SELECT;
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
        double taxFrac  = createIsAuction ? switch (createDuration) {
            case 4  -> 0.10; case 8  -> 0.12; case 12 -> 0.16;
            case 16 -> 0.20; case 24 -> 0.24; default -> 0.10;
        } : 0.10;
        long taxAmount = (long) Math.ceil(totalPrice * taxFrac);
        long netSeller = Math.max(0, totalPrice - taxAmount);
        int  taxPct    = (int) Math.round(taxFrac * 100);
        String typeStr = createIsAuction ? "Auction (" + createDuration + "h)" : "Fixed Price (BIN)";

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
        myContentArea.addChildren(new Label().setText("§7Tax §8(" + taxPct + "%)§7:    §c-" + Currency.format(taxAmount)));
        myContentArea.addChildren(new Label().setText("§7You Receive:  §a" + Currency.format(netSeller)));

        var btnRow = new UIElement();
        btnRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(8).width(430));
        btnRow.addChildren(
                new Button().setText("§7← Back")
                        .setOnClick(e -> { createStep = CreateStep.PRICE_INPUT; showStep(player); })
                        .layout(l -> l.width(80).height(28)),
                new Button().setText("§a✦ Create Listing")
                        .setOnClick(e -> {
                            PacketDistributor.sendToServer(new MarketActionPacket(
                                    MarketActionPacket.Action.CREATE,
                                    new UUID(0, 0),
                                    createSlot,
                                    totalPrice,
                                    createIsAuction ? createDuration : 0,
                                    createIsAuction));
                            // Reset and return to list
                            createStep = CreateStep.LIST;
                            createItem = ItemStack.EMPTY;
                            createSlot = -1;
                            priceTotal = 0;
                            showStep(player);
                        })
                        .layout(l -> l.flex(1).height(28))
        );
        myContentArea.addChildren(btnRow);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private long computePrice() {
        return priceTotal;
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
