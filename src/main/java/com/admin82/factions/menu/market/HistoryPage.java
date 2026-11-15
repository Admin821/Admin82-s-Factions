package com.admin82.factions.menu.market;

import com.admin82.factions.economy.MarketListing;
import com.admin82.factions.economy.PricePoint;
import com.admin82.factions.economy.Transaction;
import com.admin82.factions.menu.MarketWidgets;
import com.admin82.factions.menu.market.MarketMenu.Tab;
import com.admin82.factions.network.packet.HistoryRequestPacket;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.function.LongConsumer;
import javax.annotation.Nullable;

/** The History page and the price stats shown while pricing a listing. */
final class HistoryPage {

    private final MarketMenu menu;

    HistoryPage(MarketMenu menu) {
        this.menu = menu;
    }

    // History + price stats (requested from the server on demand)
    @Nullable List<Transaction> marketHistory;
    int historyScroll = 0;
    @Nullable UIElement historyArea;
    @Nullable Label historyPager;
    String priceStatsItem = "";
    @Nullable List<PricePoint> priceStats;
    @Nullable UIElement priceStatsArea;
    @Nullable LongConsumer priceStatsUse;

    // HISTORY

    @OnlyIn(Dist.CLIENT)
    UIElement buildHistoryPage() {
        var page = MarketUi.vbox(4);
        page.layout(l -> l.flex(1));
        page.addChildren(new Label().setText("§6§lMarket History"),
                new Label().setText("§8Your purchases, sales, bids and buy orders. Hover a line for details."));
        historyArea = MarketUi.vbox(2);
        historyArea.layout(l -> l.flex(1));
        historyPager = new Label();
        historyPager.lss("horizontal-align", "center");
        historyPager.layout(l -> l.flex(1));
        int historyRows = Math.max(4, menu.rows + 2);
        var previous = new Button().setText("‹").setOnClick(e -> { historyScroll = Math.max(0, historyScroll - historyRows); renderHistory(); });
        previous.layout(l -> l.width(22).height(16));
        var next = new Button().setText("›").setOnClick(e -> { historyScroll += historyRows; renderHistory(); });
        next.layout(l -> l.width(22).height(16));
        var pager = MarketUi.hbox(4);
        pager.layout(l -> l.height(18));
        pager.addChildren(previous, historyPager, next);
        page.addChildren(historyArea, pager);
        historyScroll = 0;
        renderHistory();
        PacketDistributor.sendToServer(new HistoryRequestPacket(
                HistoryRequestPacket.Scope.MARKET));
        return page;
    }

    @OnlyIn(Dist.CLIENT)
    void renderHistory() {
        if (historyArea == null || menu.currentTab != Tab.HISTORY) return;
        int historyRows = Math.max(4, menu.rows + 2);
        historyScroll = MarketWidgets.fillHistory(historyArea, marketHistory, historyScroll, historyRows, menu.contentWidth,
                "§7No market activity yet. Buy, sell or place an order and it will show up here.");
        if (historyPager != null) historyPager.setText(MarketWidgets.pageText(marketHistory, historyScroll, historyRows));
    }

    @OnlyIn(Dist.CLIENT)
    void renderPriceStats() {
        if (priceStatsArea == null || menu.myMarket.createItem.isEmpty()) return;
        priceStatsArea.clearAllChildren();
        boolean buyOrder = menu.myMarket.createKind == MarketListing.ListingKind.PLAYER_BUY_ORDER
                || menu.myMarket.createKind == MarketListing.ListingKind.ADMIN_BUY_ORDER;
        priceStatsArea.addChildren(MarketWidgets.priceStats(priceStats, menu.myMarket.createItem.getHoverName().getString(),
                menu.contentWidth, buyOrder ? 1 : menu.myMarket.createItem.getCount(), priceStatsUse));
    }
}
