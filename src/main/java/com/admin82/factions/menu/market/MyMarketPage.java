package com.admin82.factions.menu.market;

import com.admin82.factions.client.ClientPlayer;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.MarketDelivery;
import com.admin82.factions.economy.MarketListing;
import com.admin82.factions.economy.SoldListing;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.network.packet.HistoryRequestPacket;
import com.admin82.factions.network.packet.MarketActionPacket;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nullable;

/** My Market: your listings and orders, and the steps for creating a new one. */
final class MyMarketPage {

    private final MarketMenu menu;

    MyMarketPage(MarketMenu menu) {
        this.menu = menu;
    }

    // Create-flow state
    enum CreateStep { LIST, INV_SELECT, ITEM_SEARCH, TYPE_SELECT, DURATION_SELECT, PRICE_INPUT, CONFIRM }
    CreateStep createStep    = CreateStep.LIST;
    int        createSlot    = -1;
    ItemStack  createItem    = ItemStack.EMPTY;
    boolean    createIsAuction = false;
    private int        createDuration  = 4;      // hours
    MarketListing.ListingKind createKind = MarketListing.ListingKind.PLAYER_SELL;
    String itemSearchText = "";
    int itemSearchScroll = 0;
    int requestedCount = 1;
    /** Total price in copper (set from number + coin dropdown in the price step). */
    long priceTotal = 0;

    @Nullable UUID pendingCancelId;

    // MY MARKET + CREATE WIZARD

    @OnlyIn(Dist.CLIENT)
    UIElement buildMyPage(Player player) {
        var page = MarketUi.vbox(4);
        page.layout(l -> l.flex(1));
        menu.myContentArea = MarketUi.vbox(4);
        menu.myContentArea.layout(l -> l.flex(1));
        page.addChildren(menu.myContentArea);
        showStep(player);
        return page;
    }

    @OnlyIn(Dist.CLIENT)
    void showStep(Player player) {
        if (menu.myContentArea == null) return;
        menu.myContentArea.clearAllChildren();
        menu.myListArea = null;
        if (createStep == CreateStep.LIST) {
            stepList(player);
            return;
        }
        menu.myContentArea.addChildren(wizardHeader(player));
        switch (createStep) {
            case INV_SELECT      -> stepInvSelect(player);
            case ITEM_SEARCH     -> stepItemSearch(player);
            case TYPE_SELECT     -> stepTypeSelect(player);
            case DURATION_SELECT -> stepDurationSelect(player);
            case PRICE_INPUT     -> stepPriceInput(player);
            case CONFIRM         -> stepConfirm(player);
            default -> { }
        }
    }

    /** Title + "Item › Type › Price › Confirm" indicator + cancel button. */
    @OnlyIn(Dist.CLIENT)
    private UIElement wizardHeader(Player player) {
        boolean playerSell = createKind == MarketListing.ListingKind.PLAYER_SELL;
        String title = switch (createKind) {
            case PLAYER_SELL -> "Sell an Item";
            case PLAYER_BUY_ORDER -> "Create a Buy Order";
            case ADMIN_SELL -> "New Server Shop Listing";
            case ADMIN_BUY_ORDER -> "New Server Buy Order";
        };
        List<String> steps = playerSell ? List.of("Item", "Type", "Price", "Confirm") : List.of("Item", "Price", "Confirm");
        int current = switch (createStep) {
            case INV_SELECT, ITEM_SEARCH -> 0;
            case TYPE_SELECT, DURATION_SELECT -> 1;
            case PRICE_INPUT -> playerSell ? 2 : 1;
            case CONFIRM -> playerSell ? 3 : 2;
            default -> 0;
        };
        StringBuilder indicator = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) indicator.append(" §8› ");
            indicator.append(i == current ? "§e§l" : i < current ? "§a" : "§8").append(i + 1).append(". ").append(steps.get(i));
        }
        var cancel = new Button().setText("§7Cancel").setOnClick(e -> menu.returnToCreateHome(player));
        cancel.layout(l -> l.width(56).height(16));
        var top = MarketUi.hbox(6);
        top.layout(l -> l.height(16));
        top.addChildren(new Label().setText("§6§l" + title).layout(l -> l.flex(1)), cancel);
        var header = MarketUi.vbox(2);
        header.addChildren(top, new Label().setText(indicator.toString()));
        return header;
    }

    // My Market overview

    @OnlyIn(Dist.CLIENT)
    private void stepList(Player player) {
        menu.myContentArea.addChildren(new Label().setText("§6§lMy Market"),
                new Label().setText("§8Collect what you bought and earned, and manage your listings."));
        menu.myListArea = MarketUi.vbox(4);
        menu.myListArea.layout(l -> l.flex(1));
        menu.myContentArea.addChildren(menu.myListArea);
        renderMyList();
    }

    @OnlyIn(Dist.CLIENT)
    void renderMyList() {
        if (menu.myListArea == null) return;
        menu.myListArea.clearAllChildren();
        UUID me = MarketMenu.myId();
        var deliveries = menu.deliveriesRef.get().stream().filter(d -> me != null && me.equals(d.playerUUID)).toList();
        var sales = menu.soldRef.get().stream().filter(s -> me != null && me.equals(s.sellerUUID)).toList();
        var mine = menu.listingsRef.get().stream()
                .filter(l -> me != null && me.equals(l.sellerUUID) && !l.isAdminListing()).toList();

        int claimRows = Math.max(1, Math.min(3, menu.rows / 3));
        int listRows = Math.max(2, menu.rows - (deliveries.isEmpty() && sales.isEmpty() ? 0 : claimRows + 2));

        if (!deliveries.isEmpty() || !sales.isEmpty()) {
            var claimAll = new Button().setText("§a✦ Claim All").setOnClick(e -> {
                for (MarketDelivery delivery : deliveries) PacketDistributor.sendToServer(new MarketActionPacket(
                        MarketActionPacket.Action.CLAIM_DELIVERY, delivery.deliveryId, -1, 0, 0, false));
                for (SoldListing sale : sales) PacketDistributor.sendToServer(new MarketActionPacket(
                        MarketActionPacket.Action.CLAIM_SOLD, sale.saleId, -1, 0, 0, false));
            });
            claimAll.layout(l -> l.width(80).height(16));
            MarketUi.tooltipOn(claimAll, "§7Collect every purchase and sale payment.");
            var head = MarketUi.hbox(6);
            head.layout(l -> l.height(16));
            head.addChildren(new Label().setText("§eReady to claim §7(" + (deliveries.size() + sales.size()) + ")")
                    .layout(l -> l.flex(1)), claimAll);
            menu.myListArea.addChildren(head);
            int shown = 0;
            for (MarketDelivery delivery : deliveries) {
                if (shown++ >= claimRows) break;
                menu.myListArea.addChildren(simpleRow(delivery.item, "§7" + delivery.reason, "",
                        MarketUi.smallButton("§aClaim", 50, () -> PacketDistributor.sendToServer(new MarketActionPacket(
                                MarketActionPacket.Action.CLAIM_DELIVERY, delivery.deliveryId, -1, 0, 0, false)))));
            }
            for (SoldListing sale : sales) {
                if (shown++ >= claimRows) break;
                menu.myListArea.addChildren(simpleRow(sale.item, "§7Sold to §e" + sale.buyerName,
                        "§a+" + Currency.format(sale.proceeds),
                        MarketUi.smallButton("§aClaim", 50, () -> PacketDistributor.sendToServer(new MarketActionPacket(
                                MarketActionPacket.Action.CLAIM_SOLD, sale.saleId, -1, 0, 0, false)))));
            }
            int hidden = deliveries.size() + sales.size() - claimRows;
            if (hidden > 0) menu.myListArea.addChildren(new Label().setText("§8...and " + hidden + " more. Use Claim All."));
        }

        var head = MarketUi.hbox(6);
        head.layout(l -> l.height(16));
        head.addChildren(new Label().setText("§eYour listings §7(" + mine.size() + ")").layout(l -> l.flex(1)));
        menu.myListArea.addChildren(head);
        if (mine.isEmpty()) {
            var empty = UiKit.background(MarketUi.vbox(4), UiKit.ROW);
            empty.layout(l -> l.paddingAll(8));
            empty.addChildren(new Label().setText("§7You have no active listings."),
                    new Label().setText("§7Use §a+ Sell an Item§7 or §b+ Buy Order§7 on the left to start."));
            menu.myListArea.addChildren(empty);
            return;
        }
        menu.myScroll = Math.max(0, Math.min(Math.max(0, mine.size() - listRows), menu.myScroll));
        long now = System.currentTimeMillis();
        for (MarketListing listing : mine.subList(menu.myScroll, Math.min(mine.size(), menu.myScroll + listRows))) {
            String type = listing.isBuyOrder() ? "§bBuying §f" + listing.orderFilled + "/" + listing.orderQuantity
                    + " §7· §a" + Currency.format(listing.price) + " §7ea" 
                    : listing.isAuction ? (listing.highestBid > 0 ? "§dAuction §7· bid §a" + Currency.format(listing.highestBid)
                                                                  : "§dAuction §7· no bids yet")
                    : "§aBuy Now §7· §a" + Currency.format(listing.price);
            boolean confirming = listing.listingId.equals(pendingCancelId);
            var cancel = MarketUi.smallButton(confirming ? "§c§lSure?" : "§cCancel", 50, () -> {
                if (!listing.listingId.equals(pendingCancelId)) {
                    pendingCancelId = listing.listingId;
                } else {
                    pendingCancelId = null;
                    PacketDistributor.sendToServer(new MarketActionPacket(
                            MarketActionPacket.Action.CANCEL, listing.listingId, -1, 0, 0, false));
                }
                renderMyList();
            });
            MarketUi.tooltipOn(cancel, listing.isBuyOrder() ? "§7Cancel this order and refund the unused money."
                    : "§7Cancel this listing and get the item back.", "§8Click twice to confirm.");
            menu.myListArea.addChildren(simpleRow(listing.item, type,
                    listing.expiresAt > now ? "§7" + MarketUi.timeLeft(listing.expiresAt - now) : "§cEnded", cancel));
        }
        if (mine.size() > listRows) {
            menu.myListArea.addChildren(new Label().setText("§8Showing " + (menu.myScroll + 1) + "-"
                    + Math.min(mine.size(), menu.myScroll + listRows) + " of " + mine.size() + " · scroll for more"));
        }
    }

    /** Row with icon · item name · detail · extra · action (used on My Market). */
    @OnlyIn(Dist.CLIENT)
    private UIElement simpleRow(ItemStack stack, String detail, String extra, UIElement action) {
        int nameWidth = Math.max(60, (menu.contentWidth - 8 - 18 - 70 - 54 - 16) / 2);
        var row = UiKit.background(MarketUi.hbox(4), UiKit.ROW);
        row.layout(l -> l.height(22).paddingHorizontal(4));
        var icon = new ItemSlot();
        icon.setItem(stack);
        icon.layout(l -> l.width(18).height(18));
        row.addChildren(icon,
                new Label().setText(MarketUi.itemLabel(stack, nameWidth)).layout(l -> l.width(nameWidth)),
                new Label().setText(detail).layout(l -> l.flex(1)),
                new Label().setText(extra).layout(l -> l.width(70)),
                action);
        return row;
    }

    // Step: pick from inventory

    @OnlyIn(Dist.CLIENT)
    private void stepInvSelect(Player player) {
        menu.myContentArea.addChildren(new Label().setText(createKind == MarketListing.ListingKind.PLAYER_SELL
                ? "§7Click the item you want to sell. The whole stack is listed."
                : "§7Click the item (and stack size) for this listing."));
        var grid = UiKit.background(MarketUi.vbox(2), UiKit.ROW);
        grid.layout(l -> l.paddingAll(6).alignSelf(YogaAlign.FLEX_START));
        int[][] rowDefs = {{9, 18}, {18, 27}, {27, 36}, {0, 9}};
        for (int r = 0; r < rowDefs.length; r++) {
            int[] range = rowDefs[r];
            if (r == 3) grid.addChildren(MarketUi.spacer(3));
            var rowEl = MarketUi.hbox(2);
            rowEl.layout(l -> l.height(20));
            for (int s = range[0]; s < range[1]; s++) {
                final int slot = s;
                var cell = new ItemSlot();
                cell.layout(l -> l.width(20).height(20));
                cell.addEventListener(UIEvents.TICK, ev -> {
                    Player me = ClientPlayer.get();
                    if (me != null) cell.setItem(me.getInventory().getItem(slot));
                });
                UiKit.onClick(cell, ev -> {
                    Player me = ClientPlayer.get();
                    if (me == null) return;
                    ItemStack stack = me.getInventory().getItem(slot);
                    if (stack.isEmpty()) return;
                    createSlot = slot;
                    createItem = stack.copy();
                    requestedCount = createItem.getCount();
                    createStep = createKind == MarketListing.ListingKind.PLAYER_SELL ? CreateStep.TYPE_SELECT : CreateStep.PRICE_INPUT;
                    showStep(player);
                });
                rowEl.addChildren(cell);
            }
            grid.addChildren(rowEl);
        }
        menu.myContentArea.addChildren(grid, new Label().setText("§8Hotbar is the bottom row."));
    }

    // Step: search any item (buy orders)

    @OnlyIn(Dist.CLIENT)
    private void stepItemSearch(Player player) {
        boolean serverOrder = createKind == MarketListing.ListingKind.ADMIN_BUY_ORDER;
        menu.myContentArea.addChildren(new Label().setText(serverOrder
                ? "§7Search for the item the server should buy, then click §aPick§7."
                : "§7Search for the item, set how many you want, then click §aPick§7."));
        var searchRow = MarketUi.hbox(4);
        searchRow.layout(l -> l.height(20));
        TextField searchField = UiKit.textField("");
        searchField.setValue(itemSearchText);
        searchField.getTextFieldStyle().placeholder(Component.literal("Item name or id, then Enter"));
        searchField.bindObserver(value -> itemSearchText = value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
        searchField.addEventListener(UIEvents.KEY_DOWN, event -> {
            if (event.keyCode == GLFW.GLFW_KEY_ENTER || event.keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                itemSearchScroll = 0;
                showStep(player);
                event.stopImmediatePropagation();
            }
        });
        searchField.layout(l -> l.flex(1).height(18));
        TextField countField = UiKit.textField("");
        countField.setValue(String.valueOf(Math.max(1, requestedCount)));
        countField.bindObserver(value -> {
            try { requestedCount = Math.max(1, Math.min(MarketListing.MAX_ORDER_QUANTITY, Integer.parseInt(value.trim()))); }
            catch (RuntimeException ignored) {}
        });
        countField.layout(l -> l.width(44).height(18));
        MarketUi.tooltipOn(countField, "§7How many you want (1-" + MarketListing.MAX_ORDER_QUANTITY + ")",
                "§8Sellers can fill it a few at a time.");
        searchRow.addChildren(searchField);
        if (!serverOrder) searchRow.addChildren(new Label().setText("§7Quantity"), countField);
        searchRow.addChildren(MarketUi.smallButton("§eSearch", 52, () -> { itemSearchScroll = 0; showStep(player); }));
        menu.myContentArea.addChildren(searchRow);

        var results = MarketUi.findItemResults(itemSearchText);
        if (results.isEmpty()) {
            menu.myContentArea.addChildren(new Label().setText(itemSearchText.isBlank()
                    ? "§8Type a name or id, then press Enter." : "§7No matching items."));
            return;
        }
        itemSearchScroll = Math.max(0, Math.min(Math.max(0, results.size() - MarketMenu.ITEM_SEARCH_ROWS), itemSearchScroll));
        for (Item item : results.stream().skip(itemSearchScroll).limit(MarketMenu.ITEM_SEARCH_ROWS).toList()) {
            ItemStack stack = new ItemStack(item);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            var row = UiKit.background(MarketUi.hbox(4), UiKit.ROW);
            row.layout(l -> l.height(22).paddingHorizontal(4));
            var icon = new ItemSlot();
            icon.setItem(stack);
            icon.layout(l -> l.width(18).height(18));
            row.addChildren(icon,
                    new Label().setText("§f" + MarketUi.truncate(stack.getHoverName().getString(), 26)).layout(l -> l.width(150)),
                    new Label().setText("§8" + id).layout(l -> l.flex(1)),
                    MarketUi.smallButton("§aPick", 40, () -> {
                        createSlot = -1;
                        createItem = new ItemStack(item);
                        createStep = CreateStep.PRICE_INPUT;
                        showStep(player);
                    }));
            menu.myContentArea.addChildren(row);
        }
        menu.myContentArea.addChildren(new Label().setText("§8" + (itemSearchScroll + 1) + "-"
                + Math.min(results.size(), itemSearchScroll + MarketMenu.ITEM_SEARCH_ROWS) + " of " + results.size()
                + (results.size() >= 200 ? "+" : "") + " · scroll for more"));
    }

    // Step: fixed price or auction

    @OnlyIn(Dist.CLIENT)
    private void stepTypeSelect(Player player) {
        menu.myContentArea.addChildren(selectedItemRow(), new Label().setText("§7How do you want to sell it?"));
        var typeRow = MarketUi.hbox(8);
        typeRow.layout(l -> l.height(44));
        var binBtn = new Button().setText("§aBuy Now (fixed price)");
        MarketUi.tooltipOn(binBtn, "§aFixed price", "§7Listed for 7 days. The first buyer gets it.", "§8Tax: 10%");
        binBtn.setOnClick(e -> { createIsAuction = false; createStep = CreateStep.PRICE_INPUT; showStep(player); });
        binBtn.layout(l -> l.flex(1).height(40));
        var aucBtn = new Button().setText("§dAuction");
        MarketUi.tooltipOn(aucBtn, "§dAuction", "§7The highest bidder wins when time runs out.", "§8Tax: 10-24% depending on length.");
        aucBtn.setOnClick(e -> { createIsAuction = true; createStep = CreateStep.DURATION_SELECT; showStep(player); });
        aucBtn.layout(l -> l.flex(1).height(40));
        typeRow.addChildren(binBtn, aucBtn);
        menu.myContentArea.addChildren(typeRow, MarketUi.backButton(() -> { createStep = CreateStep.INV_SELECT; showStep(player); }));
    }

    @OnlyIn(Dist.CLIENT)
    private void stepDurationSelect(Player player) {
        menu.myContentArea.addChildren(selectedItemRow(), new Label().setText("§7How long should the auction run?"),
                new Label().setText("§8Longer auctions reach more buyers but cost more tax."));
        var durRow = MarketUi.hbox(6);
        durRow.layout(l -> l.height(40));
        int[][] options = {{4, 10}, {8, 12}, {12, 16}, {16, 20}, {24, 24}};
        for (int[] option : options) {
            final int hours = option[0], tax = option[1];
            var btn = new Button().setText(hours + "h §8" + tax + "%");
            MarketUi.tooltipOn(btn, "§e" + hours + " hour auction", "§8Tax: §7" + tax + "%");
            btn.setOnClick(e -> { createDuration = hours; createStep = CreateStep.PRICE_INPUT; showStep(player); });
            btn.layout(l -> l.flex(1).height(36));
            durRow.addChildren(btn);
        }
        menu.myContentArea.addChildren(durRow, MarketUi.backButton(() -> { createStep = CreateStep.TYPE_SELECT; showStep(player); }));
    }

    // Step: price

    @OnlyIn(Dist.CLIENT)
    private void stepPriceInput(Player player) {
        priceTotal = 0;
        menu.myContentArea.addChildren(selectedItemRow(), new Label().setText(switch (createKind) {
            case PLAYER_SELL -> createIsAuction ? "§7Starting bid for the auction:" : "§7Price buyers will pay:";
            case PLAYER_BUY_ORDER -> "§7Price you pay §fper item§7 (for " + requestedCount + " items):";
            case ADMIN_BUY_ORDER -> "§7Price the server pays §fper item§7:";
            default -> "§7Price:";
        }));
        long[] qty = {1};
        String[] coin = {"Copper"};
        var inputRow = MarketUi.hbox(6);
        inputRow.layout(l -> l.height(22));
        TextField qtyField = UiKit.textField("");
        qtyField.setValue("1");
        qtyField.bindObserver(v -> {
            try { qty[0] = Math.max(1, Long.parseLong(v.trim())); }
            catch (NumberFormatException ignored) {}
            priceTotal = qty[0] * MarketUi.priceCoinMult(coin[0]);
        });
        qtyField.layout(l -> l.width(100).height(20));
        var coinSel = new Selector<String>();
        menu.selectors.add(coinSel);
        coinSel.setCandidates(List.of("Copper", "Silver", "Gold", "Platinum"));
        coinSel.setValue("Copper");
        coinSel.setOnValueChanged(v -> { coin[0] = v; priceTotal = qty[0] * MarketUi.priceCoinMult(v); });
        coinSel.layout(l -> l.width(90).height(20));
        inputRow.addChildren(qtyField, coinSel);

        var totalLabel = new Label();
        totalLabel.addEventListener(UIEvents.TICK, ev -> {
            priceTotal = qty[0] * MarketUi.priceCoinMult(coin[0]);
            if (priceTotal <= 0) totalLabel.setText("§cPrice must be above zero.");
            else if (createKind == MarketListing.ListingKind.PLAYER_BUY_ORDER) totalLabel.setText("§7" + requestedCount
                    + " × " + Currency.format(priceTotal) + " = §a" + Currency.format(priceTotal * requestedCount)
                    + " §7held from your wallet");
            else totalLabel.setText("§7Total: §a" + Currency.format(priceTotal));
        });
        var next = new Button().setText("§aReview →").setOnClick(e -> {
            if (menu.computePrice() <= 0) return;
            createStep = CreateStep.CONFIRM;
            showStep(player);
        });
        next.layout(l -> l.width(100).height(20));
        var buttons = MarketUi.hbox(8);
        buttons.layout(l -> l.height(22));
        buttons.addChildren(MarketUi.backButton(() -> {
            createStep = createKind == MarketListing.ListingKind.PLAYER_SELL
                    ? (createIsAuction ? CreateStep.DURATION_SELECT : CreateStep.TYPE_SELECT)
                    : createKind == MarketListing.ListingKind.ADMIN_SELL ? CreateStep.INV_SELECT : CreateStep.ITEM_SEARCH;
            showStep(player);
        }), next);
        menu.historyPage.priceStatsUse = value -> {
            qty[0] = Math.max(1, value);
            coin[0] = "Copper";
            qtyField.setValue(Long.toString(qty[0]));
            coinSel.setValue("Copper");
            priceTotal = qty[0];
        };
        menu.historyPage.priceStatsArea = MarketUi.vbox(0);
        menu.myContentArea.addChildren(inputRow, totalLabel, buttons, menu.historyPage.priceStatsArea);

        String itemId = BuiltInRegistries.ITEM.getKey(createItem.getItem()).toString();
        if (!itemId.equals(menu.historyPage.priceStatsItem)) {
            menu.historyPage.priceStatsItem = itemId;
            menu.historyPage.priceStats = null;
        }
        menu.historyPage.renderPriceStats();
        PacketDistributor.sendToServer(new HistoryRequestPacket(
                HistoryRequestPacket.Scope.PRICES, itemId));
    }

    // Step: confirm

    @OnlyIn(Dist.CLIENT)
    private void stepConfirm(Player player) {
        long totalPrice = menu.computePrice();
        boolean serverSell = createKind == MarketListing.ListingKind.ADMIN_SELL;
        boolean buyOrder = createKind == MarketListing.ListingKind.PLAYER_BUY_ORDER || createKind == MarketListing.ListingKind.ADMIN_BUY_ORDER;
        double taxFrac = serverSell || buyOrder ? 0.0 : createIsAuction ? switch (createDuration) {
            case 8 -> 0.12; case 12 -> 0.16; case 16 -> 0.20; case 24 -> 0.24; default -> 0.10;
        } : 0.10;
        long taxAmount = (long) Math.ceil(totalPrice * taxFrac);
        long netSeller = Math.max(0, totalPrice - taxAmount);
        String typeStr = switch (createKind) {
            case PLAYER_SELL -> createIsAuction ? "Auction (" + createDuration + "h)" : "Buy Now (fixed price)";
            case PLAYER_BUY_ORDER -> "Player buy order";
            case ADMIN_SELL -> "Server shop listing";
            case ADMIN_BUY_ORDER -> "Server buy order";
        };

        var summary = UiKit.background(MarketUi.vbox(3), UiKit.ROW);
        summary.layout(l -> l.paddingAll(8));
        summary.addChildren(selectedItemRow(), MarketUi.spacer(2),
                MarketUi.infoRow("Type", "§f" + typeStr),
                MarketUi.infoRow(createIsAuction ? "Starting bid" : buyOrder ? "Price each" : "Price", "§a" + Currency.format(totalPrice)));
        if (serverSell) {
            summary.addChildren(MarketUi.infoRow("Duration", "§6Permanent"));
        } else if (createKind == MarketListing.ListingKind.ADMIN_BUY_ORDER) {
            summary.addChildren(MarketUi.infoRow("Quantity", "§6Unlimited"), MarketUi.infoRow("Paid by", "§6Server"));
        } else if (buyOrder) {
            summary.addChildren(MarketUi.infoRow("Quantity", "§f" + requestedCount),
                    MarketUi.infoRow("Total held", "§a" + Currency.format(totalPrice * requestedCount)),
                    MarketUi.infoRow("", "§8Sellers can fill part of it; unused money is refunded."));
        } else {
            summary.addChildren(MarketUi.infoRow("Tax (" + Math.round(taxFrac * 100) + "%)", "§c-" + Currency.format(taxAmount)),
                    MarketUi.infoRow("You receive", "§a" + Currency.format(netSeller)));
        }

        var create = new Button().setText(serverSell ? "§6Create Server Listing"
                : createKind == MarketListing.ListingKind.PLAYER_SELL ? "§a✦ Create Listing" : "§a✦ Create Order")
                .setOnClick(e -> {
                    PacketDistributor.sendToServer(new MarketActionPacket(
                            MarketActionPacket.Action.CREATE, new UUID(0, 0), createSlot, totalPrice,
                            createIsAuction ? createDuration : 0, createIsAuction, createKind.ordinal(),
                            createItem.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(createItem.getItem()).toString(),
                            buyOrder ? requestedCount : createItem.getCount()));
                    menu.returnToCreateHome(player);
                });
        create.layout(l -> l.width(150).height(22));
        var buttons = MarketUi.hbox(8);
        buttons.layout(l -> l.height(24));
        buttons.addChildren(MarketUi.backButton(() -> { createStep = CreateStep.PRICE_INPUT; showStep(player); }), create);
        menu.myContentArea.addChildren(summary, buttons);
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement selectedItemRow() {
        var row = MarketUi.hbox(6);
        row.layout(l -> l.height(20));
        var icon = new ItemSlot();
        icon.setItem(createItem);
        icon.layout(l -> l.width(18).height(18));
        boolean buyOrder = createKind == MarketListing.ListingKind.PLAYER_BUY_ORDER;
        row.addChildren(icon, new Label().setText(createItem.isEmpty() ? "§8No item"
                : createKind == MarketListing.ListingKind.ADMIN_BUY_ORDER ? "§f" + createItem.getHoverName().getString()
                : "§f" + createItem.getHoverName().getString() + " §7×" + (buyOrder ? requestedCount : createItem.getCount())));
        return row;
    }
}
