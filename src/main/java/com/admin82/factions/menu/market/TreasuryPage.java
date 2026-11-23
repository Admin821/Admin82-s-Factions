package com.admin82.factions.menu.market;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.menu.MarketWidgets;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.network.packet.TreasuryPacket;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.vfyjxf.taffy.style.AlignItems;

import dev.vfyjxf.taffy.style.AlignContent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

/** The operator-only Treasury page: server tax income and who paid it. */
final class TreasuryPage {

    private final MarketMenu menu;

    TreasuryPage(MarketMenu menu) {
        this.menu = menu;
    }

    // TREASURY (operators)

    enum TreasuryView { OVERVIEW, PAYERS, RECENT }
    TreasuryView treasuryView = TreasuryView.OVERVIEW;
    @Nullable com.admin82.factions.economy.Treasury.Snapshot treasury;
    @Nullable UIElement treasuryArea;
    private boolean treasuryResetArmed;
    int treasuryScroll;
    int treasuryTick;

    @OnlyIn(Dist.CLIENT)
    void requestTreasury(boolean reset) {
        PacketDistributor.sendToServer(new TreasuryPacket(reset));
    }

    @OnlyIn(Dist.CLIENT)
    UIElement buildTreasuryPage() {
        var page = MarketUi.vbox(4);
        page.layout(l -> l.flex(1));
        var header = MarketUi.hbox(4);
        header.layout(l -> l.height(16));
        var refresh = new Button().setText("§7⟳").setOnClick(e -> requestTreasury(false));
        refresh.layout(l -> l.width(18).height(16));
        MarketUi.tooltipOn(refresh, "§7Refresh (also refreshes by itself every few seconds)");
        var reset = new Button().setText(treasuryResetArmed ? "§c§lConfirm reset" : "§7Reset").setOnClick(e -> {
            if (treasuryResetArmed) { treasuryResetArmed = false; requestTreasury(true); }
            else treasuryResetArmed = true;
            MarketMenu.refreshScreen();
        });
        reset.layout(l -> l.width(treasuryResetArmed ? 84 : 40).height(16));
        MarketUi.tooltipOn(reset, "§7Wipe all treasury statistics (click twice).", "§8Does not change anyone's money.");
        header.addChildren(new Label().setText("§6§l🏛 Server Treasury").layout(l -> l.flex(1)), refresh, reset);

        var views = MarketUi.hbox(3);
        views.layout(l -> l.height(18));
        views.addChildren(treasuryViewButton(TreasuryView.OVERVIEW, "Overview"),
                treasuryViewButton(TreasuryView.PAYERS, "Top payers"),
                treasuryViewButton(TreasuryView.RECENT, "Recent"));

        treasuryArea = MarketUi.vbox(4);
        treasuryArea.layout(l -> l.flex(1));
        page.addChildren(header, new Label().setText("§8Every tax, fee and land payment the server has taken in. Operators only."),
                views, treasuryArea);
        renderTreasury();
        requestTreasury(false);
        return page;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement treasuryViewButton(TreasuryView view, String text) {
        boolean active = view == treasuryView;
        var btn = new Button().setText((active ? "§f§l" : "§7") + text).setOnClick(e -> {
            treasuryView = view;
            treasuryScroll = 0;
            renderTreasury();
            MarketMenu.refreshScreen();
        });
        btn.layout(l -> l.flex(1).height(18));
        if (active) btn.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
        return btn;
    }

    @OnlyIn(Dist.CLIENT)
    void renderTreasury() {
        if (treasuryArea == null) return;
        treasuryArea.clearAllChildren();
        if (treasury == null) {
            treasuryArea.addChildren(new Label().setText("§7Loading…"));
            return;
        }
        switch (treasuryView) {
            case OVERVIEW -> renderTreasuryOverview();
            case PAYERS -> renderTreasuryPayers();
            case RECENT -> renderTreasuryRecent();
        }
    }

    private static String money(long copper) {
        return Currency.format(copper).replaceAll("§.", "");
    }

    @OnlyIn(Dist.CLIENT)
    private void renderTreasuryOverview() {
        var t = treasury;
        long totalIncome = 0, totalIssued = 0, day = 0, week = 0;
        for (var e : t.totals().entrySet()) {
            if (e.getKey().income) totalIncome += e.getValue()[0]; else totalIssued += e.getValue()[0];
        }
        for (var e : t.last24h().entrySet()) if (e.getKey().income) day += e.getValue();
        for (var e : t.last7d().entrySet()) if (e.getKey().income) week += e.getValue();

        var cards = MarketUi.hbox(4);
        cards.layout(l -> l.height(30).alignItems(AlignItems.STRETCH));
        cards.addChildren(statCard("Total taken in", "§a§l" + money(totalIncome),
                        t.since() > 0 ? "since " + DateTimeFormatter.ofPattern("d MMM yyyy")
                                .format(Instant.ofEpochMilli(t.since()).atZone(ZoneId.systemDefault())) : "no data yet"),
                statCard("Today", "§e§l" + money(day), "taken in today"),
                statCard("Last 7 days", "§e§l" + money(week), "taken in"),
                statCard("Money issued", "§c§l" + money(totalIssued), "exchange & server orders"));
        treasuryArea.addChildren(cards, treasuryChart(t.dailyIncome(), menu.contentWidth, 46));

        // By source, biggest first
        var table = UiKit.background(MarketUi.vbox(2), UiKit.CARD);
        table.layout(l -> l.paddingAll(5));
        var head = MarketUi.hbox(4);
        head.layout(l -> l.height(10));
        head.addChildren(new Label().setText("§e§lWhere it comes from").layout(l -> l.flex(1)),
                new Label().setText("§8today").lss("horizontal-align", "right").layout(l -> l.width(56)),
                new Label().setText("§8payments").lss("horizontal-align", "right").layout(l -> l.width(50)),
                new Label().setText("§8total").lss("horizontal-align", "right").layout(l -> l.width(70)));
        table.addChildren(head);
        long incomeForShare = Math.max(1, totalIncome);
        var sources = new ArrayList<>(Arrays.asList(com.admin82.factions.economy.Treasury.Source.values()));
        sources.sort(Comparator.comparing((com.admin82.factions.economy.Treasury.Source s) -> !s.income)
                .thenComparingLong(s -> -t.totals().getOrDefault(s, new long[2])[0]));
        boolean issuedHeader = false;
        int barWidth = Math.max(40, menu.contentWidth - 330);
        for (var source : sources) {
            long[] tot = t.totals().getOrDefault(source, new long[2]);
            if (tot[0] == 0 && tot[1] == 0) continue;
            if (!source.income && !issuedHeader) {
                issuedHeader = true;
                table.addChildren(new Label().setText("§c§lMoney issued §8(paid out by the server, not taxes)"));
            }
            long share = source.income ? tot[0] * 100 / incomeForShare : 0;
            var row = MarketUi.hbox(4);
            row.layout(l -> l.height(12));
            var track = UiKit.background(new UIElement(), UiKit.BAR_TRACK);
            track.layout(l -> l.width(barWidth).height(6));
            var fill = UiKit.background(new UIElement(), source.income ? 0xFF4CAF50 : 0xFFE05050);
            int fillWidth = source.income ? (int) Math.max(1, barWidth * share / 100) : barWidth;
            fill.layout(l -> l.width(fillWidth).height(6));
            track.addChildren(fill);
            row.addChildren(new Label().setText("§f" + source.label).layout(l -> l.width(130)), track,
                    new Label().setText(source.income ? "§7" + share + "%" : "").layout(l -> l.flex(1)),
                    new Label().setText("§7" + money(t.last24h().getOrDefault(source, 0L))).lss("horizontal-align", "right").layout(l -> l.width(56)),
                    new Label().setText("§8" + tot[1]).lss("horizontal-align", "right").layout(l -> l.width(50)),
                    new Label().setText((source.income ? "§a" : "§c") + money(tot[0])).lss("horizontal-align", "right").layout(l -> l.width(70)));
            MarketUi.tooltipOn(row, "§f" + source.label, "§7Total: " + (source.income ? "§a" : "§c") + money(tot[0]) + " §8in " + tot[1] + " payments",
                    "§7Last 7 days: §e" + money(t.last7d().getOrDefault(source, 0L)),
                    source.income ? "§7Share of all income: §e" + share + "%" : "§8Money created by the server.");
            table.addChildren(row);
        }
        if (totalIncome == 0 && totalIssued == 0) table.addChildren(new Label().setText("§7Nothing recorded yet."));
        treasuryArea.addChildren(table);
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement statCard(String label, String value, String sub) {
        var card = UiKit.background(MarketUi.vbox(0), UiKit.CARD);
        card.layout(l -> l.flex(1).paddingAll(3));
        card.addChildren(new Label().setText("§7" + label), new Label().setText(value), new Label().setText("§8" + sub));
        return card;
    }

    /** Daily income bars for the last 14 days ([epochDay, income, issued]). */
    @OnlyIn(Dist.CLIENT)
    private UIElement treasuryChart(List<long[]> days, int width, int height) {
        long max = Math.max(1, days.stream().mapToLong(d -> d[1]).max().orElse(1));
        var wrapper = MarketUi.vbox(1);
        var top = MarketUi.hbox(0);
        top.layout(l -> l.height(10));
        top.addChildren(new Label().setText("§8Daily income · max " + money(max)).layout(l -> l.flex(1)),
                new Label().setText("§8last 14 days").lss("horizontal-align", "right").layout(l -> l.width(80)));
        int count = Math.max(1, days.size());
        int columnWidth = Math.max(2, (width - (count - 1) - 2) / count);
        var bars = UiKit.background(new UIElement(), UiKit.BAR_TRACK);
        bars.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.FLEX_END).gapAll(1)
                .height(height).paddingHorizontal(1));
        var fmt = DateTimeFormatter.ofPattern("EEE d MMM");
        for (long[] d : days) {
            var column = new UIElement();
            column.layout(l -> l.flexDirection(FlexDirection.COLUMN).justifyContent(
                    AlignContent.FLEX_END).width(columnWidth).height(height));
            var bar = new UIElement();
            int barHeight = d[1] <= 0 ? 1 : Math.max(2, (int) Math.round((double) d[1] / max * (height - 2)));
            bar.layout(l -> l.height(barHeight).width(columnWidth));
            UiKit.background(bar, d[1] <= 0 ? 0x40FFFFFF : 0xFFE0B040);
            String date = fmt.format(LocalDate.ofEpochDay(d[0]));
            MarketUi.tooltipOn(column, "§7" + date, "§aTaken in: " + money(d[1]), "§cIssued: " + money(d[2]));
            column.addChildren(bar);
            bars.addChildren(column);
        }
        wrapper.addChildren(top, bars);
        return wrapper;
    }

    @OnlyIn(Dist.CLIENT)
    private void renderTreasuryPayers() {
        var top = treasury.topContributors();
        if (top.isEmpty()) {
            treasuryArea.addChildren(new Label().setText("§7Nobody has paid anything yet."));
            return;
        }
        treasuryArea.addChildren(new Label().setText("§8Players and factions who have paid the most in taxes, fees and upkeep."));
        long max = Math.max(1, top.get(0).getValue());
        int barWidth = Math.max(60, menu.contentWidth - 230);
        int rank = 1;
        for (var e : top) {
            var row = UiKit.background(MarketUi.hbox(6), UiKit.ROW);
            row.layout(l -> l.height(16).paddingHorizontal(4));
            var track = UiKit.background(new UIElement(), UiKit.BAR_TRACK);
            track.layout(l -> l.width(barWidth).height(6));
            var fill = UiKit.background(new UIElement(), 0xFFE0B040);
            int w = (int) Math.max(1, barWidth * e.getValue() / max);
            fill.layout(l -> l.width(w).height(6));
            track.addChildren(fill);
            row.addChildren(new Label().setText((rank <= 3 ? "§6" : "§7") + "#" + rank).layout(l -> l.width(22)),
                    new Label().setText("§f" + e.getKey()).layout(l -> l.width(110)), track,
                    UiKit.rightLabel("§a" + money(e.getValue())));
            treasuryArea.addChildren(row);
            rank++;
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void renderTreasuryRecent() {
        var recent = treasury.recent();
        if (recent.isEmpty()) {
            treasuryArea.addChildren(new Label().setText("§7Nothing recorded yet."));
            return;
        }
        int visible = Math.max(4, menu.rows + 3);
        treasuryScroll = Math.max(0, Math.min(treasuryScroll, recent.size() - visible));
        for (int i = treasuryScroll; i < Math.min(recent.size(), treasuryScroll + visible); i++) {
            var e = recent.get(i);
            var row = UiKit.background(MarketUi.hbox(6), UiKit.ROW);
            row.layout(l -> l.height(14).paddingHorizontal(4));
            row.addChildren(new Label().setText("§7" + e.source().label).layout(l -> l.width(130)),
                    new Label().setText("§f" + (e.payer().isBlank() ? "—" : e.payer()) + " §8" + e.detail()).layout(l -> l.flex(1)),
                    UiKit.rightLabel("§8" + MarketWidgets.ago(e.time())),
                    UiKit.rightLabel((e.source().income ? "§a+" : "§c-") + money(e.amount())));
            MarketUi.tooltipOn(row, "§f" + e.source().label, "§7" + e.detail(), "§7Paid by: §f" + (e.payer().isBlank() ? "—" : e.payer()),
                    "§8" + DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")
                            .format(Instant.ofEpochMilli(e.time()).atZone(ZoneId.systemDefault())));
            treasuryArea.addChildren(row);
        }
        treasuryArea.addChildren(new Label().setText("§8" + (treasuryScroll + 1) + "–" + Math.min(recent.size(), treasuryScroll + visible)
                + " of " + recent.size() + " · scroll for more"));
    }
}
