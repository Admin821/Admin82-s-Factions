package com.admin82.factions.menu.market;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.PricePoint;
import com.admin82.factions.menu.MarketWidgets;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.network.packet.HistoryRequestPacket;
import com.admin82.factions.network.packet.SyncStocksPacket;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.vfyjxf.taffy.style.AlignItems;

import dev.vfyjxf.taffy.style.AlignContent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * The Stocks page: every item that has sold on the market, with its latest price, how it moved over the
 * last day and week, and how much changed hands. Click an item for its price chart over time.
 */
final class StocksPage {

    enum Sort {
        MOST_TRADED("Most traded"), RECENT("Recently sold"), GAINERS("Biggest gains"), LOSERS("Biggest drops"),
        PRICE_HIGH("Price ↓"), PRICE_LOW("Price ↑"), NAME("Name");

        final String label;
        Sort(String label) { this.label = label; }
    }

    /** Time ranges for the chart. */
    private static final long[] RANGES = {SyncStocksPacket.DAY_MS, 7 * SyncStocksPacket.DAY_MS, 30 * SyncStocksPacket.DAY_MS, Long.MAX_VALUE};
    private static final String[] RANGE_NAMES = {"24h", "7d", "30d", "All"};

    private final MarketMenu menu;
    private List<SyncStocksPacket.Line> lines = List.of();
    private boolean loaded;
    private Sort sort = Sort.MOST_TRADED;
    private String search = "";
    int scroll;
    @Nullable private UIElement area;

    // Detail view
    @Nullable String detailItem;
    @Nullable private List<PricePoint> detailPoints;
    private int range = 1;

    StocksPage(MarketMenu menu) {
        this.menu = menu;
    }

    @OnlyIn(Dist.CLIENT)
    UIElement buildPage() {
        var page = MarketUi.vbox(4);
        page.layout(l -> l.flex(1));
        area = page;
        PacketDistributor.sendToServer(new HistoryRequestPacket(HistoryRequestPacket.Scope.STOCKS, ""));
        if (detailItem != null) PacketDistributor.sendToServer(new HistoryRequestPacket(HistoryRequestPacket.Scope.PRICES, detailItem));
        render();
        return page;
    }

    void update(List<SyncStocksPacket.Line> fresh) {
        lines = fresh;
        loaded = true;
        render();
    }

    void updateDetail(String itemId, List<PricePoint> points) {
        if (!itemId.equals(detailItem)) return;
        detailPoints = points;
        render();
    }

    @OnlyIn(Dist.CLIENT)
    void render() {
        if (area == null) return;
        area.clearAllChildren();
        if (detailItem != null) renderDetail();
        else renderList();
    }

    // List

    private List<SyncStocksPacket.Line> visibleLines() {
        String needle = search.toLowerCase(Locale.ROOT).trim();
        List<SyncStocksPacket.Line> out = new ArrayList<>();
        for (var l : lines) {
            if (needle.isEmpty() || name(l.itemId()).toLowerCase(Locale.ROOT).contains(needle) || l.itemId().contains(needle)) out.add(l);
        }
        Comparator<SyncStocksPacket.Line> cmp = switch (sort) {
            case MOST_TRADED -> Comparator.comparingLong(SyncStocksPacket.Line::volume7d).reversed();
            case RECENT -> Comparator.comparingLong(SyncStocksPacket.Line::lastSoldAt).reversed();
            case GAINERS -> Comparator.comparingDouble((SyncStocksPacket.Line l) -> Double.isNaN(l.change24h()) ? Double.NEGATIVE_INFINITY : l.change24h()).reversed();
            case LOSERS -> Comparator.comparingDouble(l -> Double.isNaN(l.change24h()) ? Double.POSITIVE_INFINITY : l.change24h());
            case PRICE_HIGH -> Comparator.comparingLong(SyncStocksPacket.Line::lastPrice).reversed();
            case PRICE_LOW -> Comparator.comparingLong(SyncStocksPacket.Line::lastPrice);
            case NAME -> Comparator.comparing(l -> name(l.itemId()), String.CASE_INSENSITIVE_ORDER);
        };
        out.sort(cmp);
        return out;
    }

    @OnlyIn(Dist.CLIENT)
    private void renderList() {
        var top = MarketUi.hbox(4);
        top.layout(l -> l.height(16).alignItems(AlignItems.CENTER));
        var field = UiKit.textField("");
        field.setValue(search);
        field.bindObserver(v -> { if (!v.equals(search)) { search = v; scroll = 0; render(); } });
        field.layout(l -> l.width(110).height(14));
        MarketUi.tooltipOn(field, "§7Search items");
        top.addChildren(new Label().setText("§6§lStocks").layout(l -> l.width(48)), field);
        area.addChildren(top);

        // Sort chips
        var sorts = MarketUi.hbox(2);
        sorts.layout(l -> l.height(14));
        for (Sort s : Sort.values()) {
            var b = new Button().setText((s == sort ? "§f§l" : "§7") + s.label).setOnClick(e -> { sort = s; scroll = 0; render(); });
            b.layout(l -> l.height(14).paddingHorizontal(3));
            if (s == sort) b.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
            sorts.addChildren(b);
        }
        area.addChildren(sorts);

        if (!loaded) { area.addChildren(new Label().setText("§7Loading…")); return; }
        List<SyncStocksPacket.Line> shown = visibleLines();
        if (shown.isEmpty()) {
            area.addChildren(new Label().setText(lines.isEmpty() ? "§7Nothing has sold on the market yet." : "§7No items match."));
            return;
        }
        // Column headers
        var head = MarketUi.hbox(4);
        head.layout(l -> l.height(10).paddingHorizontal(4));
        head.addChildren(new Label().setText("§8Item").layout(l -> l.flex(1)), new Label().setText("§8Price").layout(l -> l.width(70)),
                new Label().setText("§824h").layout(l -> l.width(44)), new Label().setText("§87d").layout(l -> l.width(44)),
                new Label().setText("§8Trend").layout(l -> l.width(52)));
        area.addChildren(head);

        int perPage = Math.max(4, menu.rows + 1);
        int pages = Math.max(1, (shown.size() + perPage - 1) / perPage);
        scroll = Math.max(0, Math.min(scroll, pages - 1));
        for (var line : shown.subList(scroll * perPage, Math.min(shown.size(), (scroll + 1) * perPage))) area.addChildren(row(line));

        var pager = MarketUi.hbox(4);
        pager.layout(l -> l.height(14).justifyContent(AlignContent.FLEX_END).alignItems(AlignItems.CENTER));
        pager.addChildren(MarketUi.smallButton("§7◀", 18, () -> { scroll = Math.max(0, scroll - 1); render(); }),
                new Label().setText("§7" + (scroll + 1) + "/" + pages + " §8· " + shown.size() + " items"),
                MarketUi.smallButton("§7▶", 18, () -> { scroll = Math.min(pages - 1, scroll + 1); render(); }));
        area.addChildren(pager);
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement row(SyncStocksPacket.Line line) {
        var r = UiKit.background(MarketUi.hbox(4), UiKit.ROW);
        r.layout(l -> l.height(20).paddingHorizontal(4).alignItems(AlignItems.CENTER));
        var icon = new ItemSlot().setItem(stack(line.itemId()));
        var name = new UIElement();
        name.layout(l -> l.flex(1).flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(3));
        name.addChildren(icon, new Label().setText("§f" + MarketUi.itemLabel(stack(line.itemId()), 110)));
        r.addChildren(name,
                new Label().setText("§a" + Currency.format(line.lastPrice()).replaceAll("§.", "")).layout(l -> l.width(70)),
                new Label().setText(changeText(line.change24h())).layout(l -> l.width(44)),
                new Label().setText(changeText(line.change7d())).layout(l -> l.width(44)),
                sparkline(line.spark(), 52, 14));
        MarketUi.tooltipOn(r, "§f" + name(line.itemId()), "§7Last sale: §a" + Currency.format(line.lastPrice()) + " §7each, "
                        + ago(line.lastSoldAt()), "§7Sold in the last 7 days: §f" + line.volume7d() + " §7(" + line.sales() + " sales on record)",
                "§8Click for the price chart");
        UiKit.onClick(r, e -> openDetail(line.itemId()));
        return r;
    }

    /** A tiny bar chart of the latest sale prices; green if the last one is up on the first, red if down. */
    @OnlyIn(Dist.CLIENT)
    private static UIElement sparkline(List<Long> prices, int width, int height) {
        var box = UiKit.background(new UIElement(), UiKit.BAR_TRACK);
        box.layout(l -> l.width(width).height(height).flexDirection(FlexDirection.ROW).alignItems(AlignItems.FLEX_END).gapAll(1));
        if (prices.isEmpty()) return box;
        long max = prices.stream().mapToLong(Long::longValue).max().orElse(1);
        long min = prices.stream().mapToLong(Long::longValue).min().orElse(0);
        boolean up = prices.get(prices.size() - 1) >= prices.get(0);
        int color = up ? 0xFF4CAF50 : 0xFFE05050;
        int bar = Math.max(1, (width - prices.size()) / prices.size());
        for (long p : prices) {
            int h = max == min ? height / 2 : 2 + (int) ((p - min) * (height - 3) / (max - min));
            var b = UiKit.background(new UIElement(), color);
            b.layout(l -> l.width(bar).height(h));
            box.addChildren(b);
        }
        return box;
    }

    // Detail

    @OnlyIn(Dist.CLIENT)
    private void openDetail(String itemId) {
        detailItem = itemId;
        detailPoints = null;
        PacketDistributor.sendToServer(new HistoryRequestPacket(HistoryRequestPacket.Scope.PRICES, itemId));
        render();
    }

    @OnlyIn(Dist.CLIENT)
    private void renderDetail() {
        String itemId = detailItem;
        var top = MarketUi.hbox(6);
        top.layout(l -> l.height(20).alignItems(AlignItems.CENTER));
        top.addChildren(MarketUi.backButton(() -> { detailItem = null; detailPoints = null; render(); }),
                new ItemSlot().setItem(stack(itemId)), new Label().setText("§f§l" + name(itemId)).layout(l -> l.flex(1)));
        area.addChildren(top);
        if (detailPoints == null) { area.addChildren(new Label().setText("§7Loading…")); return; }
        if (detailPoints.isEmpty()) { area.addChildren(new Label().setText("§7No sales recorded.")); return; }

        // Range buttons
        var ranges = MarketUi.hbox(2);
        ranges.layout(l -> l.height(14));
        for (int i = 0; i < RANGES.length; i++) {
            int idx = i;
            var b = new Button().setText((i == range ? "§f§l" : "§7") + RANGE_NAMES[i]).setOnClick(e -> { range = idx; render(); });
            b.layout(l -> l.width(34).height(14));
            if (i == range) b.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
            ranges.addChildren(b);
        }
        area.addChildren(ranges);

        long now = System.currentTimeMillis();
        long window = RANGES[range];
        List<PricePoint> inRange = detailPoints.stream()
                .filter(p -> window == Long.MAX_VALUE || now - p.time() <= window).toList();
        PricePoint latest = detailPoints.get(detailPoints.size() - 1);
        if (inRange.isEmpty()) {
            area.addChildren(new Label().setText("§7No sales in the last " + RANGE_NAMES[range] + ". Last sale: §a"
                    + Currency.format(latest.unitPrice()) + " §7each, " + ago(latest.time()) + "."));
            return;
        }
        long high = inRange.stream().mapToLong(PricePoint::unitPrice).max().orElse(0);
        long low = inRange.stream().mapToLong(PricePoint::unitPrice).min().orElse(0);
        long qty = inRange.stream().mapToLong(PricePoint::quantity).sum();
        long value = inRange.stream().mapToLong(p -> p.unitPrice() * p.quantity()).sum();
        long avg = qty > 0 ? value / qty : 0;
        double change = inRange.get(0).unitPrice() > 0
                ? (latest.unitPrice() - inRange.get(0).unitPrice()) * 100.0 / inRange.get(0).unitPrice() : Double.NaN;

        var stats = MarketUi.hbox(10);
        stats.layout(l -> l.height(10));
        stats.addChildren(new Label().setText("§7Last §a" + Currency.format(latest.unitPrice())),
                new Label().setText("§7Change " + changeText(change)),
                new Label().setText("§7High §f" + Currency.format(high)),
                new Label().setText("§7Low §f" + Currency.format(low)),
                new Label().setText("§7Avg §f" + Currency.format(avg)));
        area.addChildren(stats, new Label().setText("§8" + qty + " sold in " + inRange.size() + " sales over " + RANGE_NAMES[range]
                + ("")));
        area.addChildren(MarketWidgets.chart(inRange, menu.contentWidth - 10, 70));

        // Most recent sales
        area.addChildren(new Label().setText("§7Recent sales"));
        List<PricePoint> recent = new ArrayList<>(inRange);
        java.util.Collections.reverse(recent);
        for (PricePoint p : recent.subList(0, Math.min(4, recent.size()))) {
            var r = UiKit.background(MarketUi.hbox(6), UiKit.ROW);
            r.layout(l -> l.height(12).paddingHorizontal(4));
            r.addChildren(new Label().setText("§7" + ago(p.time())).layout(l -> l.width(80)),
                    new Label().setText("§f" + p.quantity() + "×").layout(l -> l.width(40)),
                    new Label().setText("§a" + Currency.format(p.unitPrice()) + " §7each").layout(l -> l.flex(1)),
                    new Label().setText("§8" + p.sourceName()));
            area.addChildren(r);
        }
    }

    // Helpers

    private static String changeText(double pct) {
        if (Double.isNaN(pct)) return "§8–";
        if (Math.abs(pct) < 0.05) return "§70.0%";
        return (pct > 0 ? "§a▲" : "§c▼") + String.format(Locale.ROOT, "%.1f%%", Math.abs(pct));
    }

    private static String ago(long time) {
        long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86_400) return (s / 3600) + "h ago";
        return (s / 86_400) + "d ago";
    }

    private static ItemStack stack(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        var item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return new ItemStack(item == null ? Items.BARRIER : item);
    }

    private static String name(String itemId) {
        return stack(itemId).getHoverName().getString();
    }
}
