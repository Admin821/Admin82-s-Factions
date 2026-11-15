package com.admin82.factions.menu;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.PricePoint;
import com.admin82.factions.economy.Transaction;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.appliedenergistics.yoga.YogaJustify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import javax.annotation.Nullable;

/** Client-side building blocks shared by the market and faction table: history lists and price charts. */
@OnlyIn(Dist.CLIENT)
public final class MarketWidgets {
    private static final long DAY_MS = 86_400_000L;
    private static final int CHART_DAYS = 14;
    private static final int CHART_SALES = 24;
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT);

    private MarketWidgets() {}

    // History list

    /** Fills {@code area} with history rows {@code offset .. offset+rows}. Returns the clamped offset. */
    public static int fillHistory(UIElement area, @Nullable List<Transaction> entries, int offset, int rows, int width,
                                  String emptyText) {
        area.clearAllChildren();
        if (entries == null) {
            area.addChildren(new Label().setText("§7Loading..."));
            return 0;
        }
        if (entries.isEmpty()) {
            area.addChildren(new Label().setText(emptyText));
            return 0;
        }
        int clamped = Math.max(0, Math.min(Math.max(0, entries.size() - rows), offset));
        for (Transaction entry : entries.subList(clamped, Math.min(entries.size(), clamped + rows))) {
            area.addChildren(historyRow(entry, width));
        }
        return clamped;
    }

    public static String pageText(@Nullable List<Transaction> entries, int offset, int rows) {
        if (entries == null || entries.isEmpty()) return "";
        return "§7" + (offset + 1) + "-" + Math.min(entries.size(), offset + rows) + " of " + entries.size()
                + "  §8(newest first)";
    }

    public static UIElement historyRow(Transaction entry, int width) {
        var row = UiKit.background(new UIElement(), UiKit.ROW);
        row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4)
                .height(20).paddingHorizontal(4));
        if (entry.icon().isEmpty()) {
            var dot = new UIElement();
            dot.layout(l -> l.width(18).height(18));
            row.addChildren(dot);
        } else {
            var icon = new ItemSlot();
            icon.setItem(entry.icon());
            icon.layout(l -> l.width(18).height(18));
            row.addChildren(icon);
        }
        int textWidth = Math.max(60, width - 8 - 18 - 84 - 48 - 12);
        var font = Minecraft.getInstance().font;
        String text = entry.text();
        if (font.width(text) > textWidth) text = font.plainSubstrByWidth(text, textWidth - font.width("…")) + "…";
        var textLabel = new Label().setText("§f" + text);
        textLabel.layout(l -> l.width(textWidth));
        List<String> tip = new ArrayList<>();
        tip.add("§f" + entry.text());
        tip.add("§7" + TIME_FORMAT.format(Instant.ofEpochMilli(entry.time()).atZone(ZoneId.systemDefault())));
        if (entry.balance() >= 0) tip.add("§7Balance after: §a" + Currency.format(entry.balance()));
        tooltip(textLabel, tip.toArray(String[]::new));
        String amount = entry.amount() > 0 ? "§a+" + Currency.format(entry.amount())
                : entry.amount() < 0 ? "§c-" + Currency.format(-entry.amount()) : "§8—";
        row.addChildren(textLabel,
                new Label().setText(amount).lss("horizontal-align", "right").layout(l -> l.width(84)),
                new Label().setText("§7" + ago(entry.time())).lss("horizontal-align", "right").layout(l -> l.width(48)));
        return row;
    }

    public static String ago(long time) {
        long seconds = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        if (seconds < 60) return "now";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }

    // Price stats + chart

    /**
     * Card with recent-sale stats and a price chart for one item.
     *
     * @param stackSize   items in the stack being sold (stats are per item; "use average" multiplies by this)
     * @param useAverage  called with a suggested total price when the player clicks "Use average", or null
     */
    public static UIElement priceStats(@Nullable List<PricePoint> points, String itemName, int width, int stackSize,
                                       @Nullable LongConsumer useAverage) {
        var card = UiKit.background(new UIElement(), UiKit.CARD);
        card.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(3).paddingAll(6));
        card.addChildren(new Label().setText("§6Price history §7· " + itemName + " §8(per item)"));
        if (points == null) {
            card.addChildren(new Label().setText("§7Loading..."));
            return card;
        }
        if (points.isEmpty()) {
            card.addChildren(new Label().setText("§7No sales recorded for this item yet."),
                    new Label().setText("§8Your sale will be the first price on record."));
            return card;
        }

        long now = System.currentTimeMillis();
        List<PricePoint> recent = points.stream().filter(p -> now - p.time() <= 7 * DAY_MS).toList();
        List<PricePoint> basis = recent.isEmpty() ? points : recent;
        String basisName = recent.isEmpty() ? "all time" : "7 days";
        long average = weightedAverage(basis);
        long low = basis.stream().mapToLong(PricePoint::unitPrice).min().orElse(0);
        long high = basis.stream().mapToLong(PricePoint::unitPrice).max().orElse(0);
        long sold = basis.stream().mapToLong(PricePoint::quantity).sum();
        PricePoint last = points.get(points.size() - 1);
        long totalSold = points.stream().mapToLong(PricePoint::quantity).sum();

        card.addChildren(
                statRow("Last sale", "§a" + Currency.format(last.unitPrice()) + " §7(" + last.sourceName() + ", "
                        + ago(last.time()) + ")"),
                statRow("Average (" + basisName + ")", "§e" + Currency.format(average) + " §7· " + sold + " sold"),
                statRow("Range (" + basisName + ")", "§a" + Currency.format(low) + " §7- §a" + Currency.format(high)),
                statRow("All time", "§f" + totalSold + " §7sold in §f" + points.size() + " §7sales"));
        card.addChildren(chart(points, width - 12, 46));
        if (useAverage != null) {
            long suggested = average * Math.max(1, stackSize);
            var use = new Button().setText("§eUse average §7(" + Currency.format(suggested) + ")")
                    .setOnClick(e -> useAverage.accept(suggested));
            use.layout(l -> l.height(16).alignSelf(YogaAlign.FLEX_START).width(
                    Minecraft.getInstance().font.width("Use average (" + Currency.format(suggested) + ")") + 16));
            tooltip(use, "§7Fill in the average price" + (stackSize > 1 ? " for " + stackSize + " items." : "."));
            card.addChildren(use);
        }
        return card;
    }

    private static UIElement statRow(String label, String value) {
        var row = new UIElement();
        row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).height(11));
        row.addChildren(new Label().setText("§7" + label).layout(l -> l.width(96)), new Label().setText(value));
        return row;
    }

    private static long weightedAverage(List<PricePoint> points) {
        long quantity = 0;
        double total = 0;
        for (PricePoint point : points) {
            quantity += point.quantity();
            total += (double) point.unitPrice() * point.quantity();
        }
        return quantity == 0 ? 0 : Math.round(total / quantity);
    }

    private record Bucket(String label, long average, long quantity, int sales) {}

    /** Bar chart: daily averages for the last 14 days when there is enough history, else the latest sales. */
    public static UIElement chart(List<PricePoint> points, int width, int height) {
        long now = System.currentTimeMillis();
        boolean daily = now - points.get(0).time() >= 2 * DAY_MS;
        List<Bucket> buckets = new ArrayList<>();
        if (daily) {
            ZoneId zone = ZoneId.systemDefault();
            LocalDate today = LocalDate.now(zone);
            for (int day = CHART_DAYS - 1; day >= 0; day--) {
                LocalDate date = today.minusDays(day);
                List<PricePoint> inDay = points.stream()
                        .filter(p -> Instant.ofEpochMilli(p.time()).atZone(zone).toLocalDate().equals(date)).toList();
                buckets.add(new Bucket(DAY_FORMAT.format(date), weightedAverage(inDay),
                        inDay.stream().mapToLong(PricePoint::quantity).sum(), inDay.size()));
            }
        } else {
            for (PricePoint point : points.subList(Math.max(0, points.size() - CHART_SALES), points.size())) {
                buckets.add(new Bucket(TIME_FORMAT.format(Instant.ofEpochMilli(point.time()).atZone(ZoneId.systemDefault())),
                        point.unitPrice(), point.quantity(), 1));
            }
        }
        long max = Math.max(1, buckets.stream().mapToLong(Bucket::average).max().orElse(1));

        var wrapper = new UIElement();
        wrapper.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(1));
        var top = new UIElement();
        top.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(10));
        top.addChildren(new Label().setText("§8max " + Currency.format(max)).layout(l -> l.flex(1)),
                new Label().setText(daily ? "§8daily average" : "§8each sale").lss("horizontal-align", "right")
                        .layout(l -> l.width(80)));

        int count = buckets.size();
        int columnWidth = Math.max(2, (width - (count - 1)) / count);
        var bars = UiKit.background(new UIElement(), UiKit.BAR_TRACK);
        bars.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.FLEX_END).gapAll(1)
                .height(height).paddingHorizontal(1));
        for (Bucket bucket : buckets) {
            var column = new UIElement();
            column.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).justifyContent(
                    YogaJustify.FLEX_END).width(columnWidth).height(height));
            var bar = new UIElement();
            if (bucket.sales() == 0) {
                bar.layout(l -> l.height(1).width(columnWidth));
                UiKit.background(bar, 0x40FFFFFF);
                tooltip(column, "§7" + bucket.label(), "§8No sales");
            } else {
                int barHeight = Math.max(2, (int) Math.round((double) bucket.average() / max * (height - 2)));
                bar.layout(l -> l.height(barHeight).width(columnWidth));
                UiKit.background(bar, 0xFF4CAF50);
                tooltip(column, "§7" + bucket.label(), "§a" + Currency.format(bucket.average()) + " §7per item",
                        "§7" + bucket.quantity() + " sold" + (bucket.sales() > 1 ? " in " + bucket.sales() + " sales" : ""));
            }
            column.addChildren(bar);
            bars.addChildren(column);
        }

        var bottom = new UIElement();
        bottom.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(10));
        bottom.addChildren(new Label().setText("§8" + buckets.get(0).label()).layout(l -> l.flex(1)),
                new Label().setText("§8" + (daily ? "today" : "latest")).lss("horizontal-align", "right")
                        .layout(l -> l.width(60)));
        wrapper.addChildren(top, bars, bottom);
        return wrapper;
    }

    private static void tooltip(UIElement element, String... lines) {
        Component[] components = Arrays.stream(lines).map(Component::literal).toArray(Component[]::new);
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, event ->
                event.hoverTooltips = HoverTooltips.empty().append(components));
    }
}
