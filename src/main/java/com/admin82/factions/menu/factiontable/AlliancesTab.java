package com.admin82.factions.menu.factiontable;

import com.admin82.factions.Config;
import com.admin82.factions.faction.*;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.network.packet.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;

import java.util.*;
import javax.annotation.Nullable;

/** The Alliances tab. */
final class AlliancesTab {

    private final FactionTableMenu menu;

    AlliancesTab(FactionTableMenu menu) {
        this.menu = menu;
    }

    // Alliances

    List<SyncAlliancesPacket.Entry> allianceEntries = List.of();
    @Nullable UIElement alliancesPanel;
    @Nullable private UUID breakArmedFor;
    private int allianceOthersScroll;
    private static final int ALLIANCE_OTHER_ROWS = 5;

    @Nullable
    SyncAlliancesPacket.Entry allianceWith(UUID factionId) {
        for (var e : allianceEntries) if (e.factionId().equals(factionId)) return e;
        return null;
    }

    UIElement buildAlliancesPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        alliancesPanel = panel;
        fillAlliancesPanel(player);
        return panel;
    }

    void fillAlliancesPanel(Player player) {
        if (alliancesPanel == null) return;
        alliancesPanel.clearAllChildren();
        boolean officer = menu.isOfficer(player);
        List<SyncAlliancesPacket.Entry> allies = new ArrayList<>(), incoming = new ArrayList<>(),
                outgoing = new ArrayList<>(), cooldowns = new ArrayList<>();
        for (var e : allianceEntries) {
            switch (e.state()) {
                case ALLY -> allies.add(e);
                case INCOMING -> incoming.add(e);
                case OUTGOING -> outgoing.add(e);
                case COOLDOWN -> cooldowns.add(e);
            }
        }

        var header = new UIElement();
        header.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).height(14));
        header.addChildren(new Label().setText("§6§l🤝 Alliances").layout(l -> l.flex(1)),
                UiKit.rightLabel(allies.isEmpty() ? "§8No allies" : "§a" + allies.size() + " all" + (allies.size() == 1 ? "y" : "ies")));
        alliancesPanel.addChildren(header,
                new Label().setText("§8Allies can't declare war on each other or claim bounties on each other."),
                new Label().setText("§8Breaking an alliance keeps those rules in place for "
                        + AllianceManager.formatDuration(
                                SyncServerConfigPacket.get(Config.ALLIANCE_BREAK_COOLDOWN_HOURS) * 3_600_000L) + "."));

        // Proposals
        if (!incoming.isEmpty() || !outgoing.isEmpty()) {
            var card = FactionTableMenu.card();
            card.addChildren(new Label().setText("§e§lProposals"));
            for (var e : incoming) {
                var row = allianceRow("§b✉ §f" + e.name(), "§7wants to ally · " + WarsTab.warAgo(e.time()));
                if (officer) {
                    var accept = new Button().setText("§a✔ Accept").setOnClick(ev ->
                            PacketDistributor.sendToServer(new AllianceActionPacket(
                                    AllianceActionPacket.Action.ACCEPT, e.factionId())));
                    accept.layout(l -> l.width(62).height(16));
                    var decline = new Button().setText("§c✖").setOnClick(ev ->
                            PacketDistributor.sendToServer(new AllianceActionPacket(
                                    AllianceActionPacket.Action.DECLINE, e.factionId())));
                    decline.layout(l -> l.width(18).height(16));
                    NegotiationsView.tooltip(decline, "§7Decline");
                    row.addChildren(accept, decline);
                }
                card.addChildren(row);
            }
            for (var e : outgoing) {
                var row = allianceRow("§7➜ §f" + e.name(), "§8waiting for an answer · sent " + WarsTab.warAgo(e.time()));
                if (officer) {
                    var cancel = new Button().setText("§7Withdraw").setOnClick(ev ->
                            PacketDistributor.sendToServer(new AllianceActionPacket(
                                    AllianceActionPacket.Action.CANCEL, e.factionId())));
                    cancel.layout(l -> l.width(62).height(16));
                    row.addChildren(cancel);
                }
                card.addChildren(row);
            }
            alliancesPanel.addChildren(card);
        }

        // Allies
        var alliesCard = FactionTableMenu.card();
        alliesCard.addChildren(new Label().setText("§e§lAllies"));
        if (allies.isEmpty()) alliesCard.addChildren(new Label().setText("§7You have no allies yet. Propose one below."));
        for (var e : allies) {
            var row = allianceRow("§a🤝 §f" + e.name(), "§8" + e.members() + " member" + (e.members() == 1 ? "" : "s")
                    + " · allied " + WarsTab.warAgo(e.time()));
            if (officer) {
                boolean armed = e.factionId().equals(breakArmedFor);
                var brk = new Button().setText(armed ? "§c§lConfirm break" : "§cBreak").setOnClick(ev -> {
                    if (e.factionId().equals(breakArmedFor)) {
                        breakArmedFor = null;
                        PacketDistributor.sendToServer(new AllianceActionPacket(
                                AllianceActionPacket.Action.BREAK, e.factionId()));
                    } else {
                        breakArmedFor = e.factionId();
                    }
                    fillAlliancesPanel(player);
                });
                brk.layout(l -> l.width(armed ? 90 : 50).height(16));
                NegotiationsView.tooltip(brk, "§7End the alliance (click twice).", "§7Its protections stay for the cooldown.");
                row.addChildren(brk);
            }
            alliesCard.addChildren(row);
        }
        alliancesPanel.addChildren(alliesCard);

        // Recently broken
        if (!cooldowns.isEmpty()) {
            var card = FactionTableMenu.card();
            card.addChildren(new Label().setText("§e§lRecently broken"));
            for (var e : cooldowns) {
                card.addChildren(allianceRow("§6⏳ §f" + e.name(),
                        "§7still protected for §e" + AllianceManager.formatDuration(e.time())));
            }
            alliancesPanel.addChildren(card);
        }

        // Other factions
        var others = FactionTableMenu.card();
        others.layout(l -> l.flex(1));
        others.addChildren(new Label().setText("§e§lOther factions"));
        Faction mine = menu.factionRef.get();
        List<FactionSummary> candidates = menu.allFactions.get().stream()
                .filter(f -> allianceWith(f.id()) == null || allianceWith(f.id()).state()
                        == SyncAlliancesPacket.State.COOLDOWN)
                .toList();
        if (candidates.isEmpty()) others.addChildren(new Label().setText("§7No other factions to ally with."));
        allianceOthersScroll = Math.max(0, Math.min(allianceOthersScroll, candidates.size() - ALLIANCE_OTHER_ROWS));
        var list = new UIElement();
        list.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(2));
        list.addEventListener(UIEvents.MOUSE_WHEEL, ev -> {
            allianceOthersScroll = Math.max(0, allianceOthersScroll + (ev.deltaY > 0 ? -1 : 1));
            fillAlliancesPanel(player);
        });
        for (int i = allianceOthersScroll; i < Math.min(candidates.size(), allianceOthersScroll + ALLIANCE_OTHER_ROWS); i++) {
            FactionSummary f = candidates.get(i);
            boolean atWar = mine != null && mine.isAtWarWith(f.id());
            var row = allianceRow("§f" + f.name(), "§8" + f.memberCount() + " members · "
                    + (f.onlineCount() > 0 ? "§a" : "§8") + f.onlineCount() + " online");
            if (atWar) {
                row.addChildren(new Label().setText("§c⚔ At war").layout(l -> l.width(62)));
            } else if (officer) {
                var propose = new Button().setText("§b🤝 Propose").setOnClick(ev ->
                        PacketDistributor.sendToServer(new AllianceActionPacket(
                                AllianceActionPacket.Action.PROPOSE, f.id())));
                propose.layout(l -> l.width(62).height(16));
                NegotiationsView.tooltip(propose, "§7Ask §e" + f.name() + " §7to sign a non-aggression pact.");
                row.addChildren(propose);
            }
            list.addChildren(row);
        }
        others.addChildren(list);
        if (candidates.size() > ALLIANCE_OTHER_ROWS) {
            others.addChildren(new Label().setText("§8" + (allianceOthersScroll + 1) + "–"
                    + Math.min(candidates.size(), allianceOthersScroll + ALLIANCE_OTHER_ROWS) + " of " + candidates.size()
                    + " · scroll for more"));
        }
        if (!officer) others.addChildren(new Label().setText("§8Only officers and above can manage alliances."));
        alliancesPanel.addChildren(others);
    }

    private UIElement allianceRow(String title, String subtitle) {
        var row = UiKit.background(new UIElement(), UiKit.ROW);
        row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(6).height(22).paddingHorizontal(4));
        var info = new UIElement();
        info.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN));
        info.addChildren(new Label().setText(title), new Label().setText(subtitle));
        row.addChildren(info);
        return row;
    }
}
