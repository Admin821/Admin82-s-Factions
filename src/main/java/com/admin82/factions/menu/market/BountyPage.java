package com.admin82.factions.menu.market;

import com.admin82.factions.economy.BountyEvents;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.faction.AllianceManager;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.network.packet.BountyActionPacket;
import com.admin82.factions.network.packet.SyncBountiesPacket;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;

/** The Bounties page: the board, placing a bounty and your own bounties. */
final class BountyPage {

    private final MarketMenu menu;

    BountyPage(MarketMenu menu) {
        this.menu = menu;
    }

    // BOUNTIES

    enum BountyView { BOARD, PLACE, MINE }
    BountyView bountyView = BountyView.BOARD;
    List<SyncBountiesPacket.View> bounties = List.of();
    List<String> bountyPlayers = List.of();
    int bountyScroll;
    @Nullable private UUID bountyExpanded;
    @Nullable private UUID bountyCancelArmed;
    private String bountyTarget = "";
    private String bountyRewardText = "";
    private int bountyDays = 3;
    private String bountyShownAs = "";
    int bountyTaxPercent = 15;
    @Nullable UIElement bountyArea;

    @OnlyIn(Dist.CLIENT)
    UIElement buildBountyPage() {
        var page = MarketUi.vbox(4);
        page.layout(l -> l.flex(1));
        long total = bounties.stream().mapToLong(SyncBountiesPacket.View::reward).sum();
        var header = MarketUi.hbox(4);
        header.layout(l -> l.height(14));
        header.addChildren(new Label().setText("§6§l☠ Bounty Board").layout(l -> l.flex(1)),
                UiKit.rightLabel(total > 0 ? "§7On the board: §e" + Currency.format(total) : "§8No bounties"));
        page.addChildren(header, new Label().setText(
                "§8Kill a target to claim their bounties. Not on yourself, teammates, allies, or ones you placed."));

        var views = MarketUi.hbox(3);
        views.layout(l -> l.height(18));
        int mine = (int) bounties.stream().filter(b -> b.mine()).count();
        views.addChildren(bountyViewButton(BountyView.BOARD, "Board"),
                bountyViewButton(BountyView.PLACE, "+ Place a bounty"),
                bountyViewButton(BountyView.MINE, "My bounties" + (mine > 0 ? " (" + mine + ")" : "")));
        bountyArea = MarketUi.vbox(3);
        bountyArea.layout(l -> l.flex(1));
        page.addChildren(views, bountyArea);
        renderBounties();
        return page;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement bountyViewButton(BountyView view, String text) {
        boolean active = view == bountyView;
        var btn = new Button().setText((active ? "§f§l" : "§7") + text).setOnClick(e -> {
            bountyView = view;
            bountyScroll = 0;
            bountyCancelArmed = null;
            MarketMenu.refreshScreen();
        });
        btn.layout(l -> l.flex(1).height(18));
        if (active) btn.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
        return btn;
    }

    @OnlyIn(Dist.CLIENT)
    void renderBounties() {
        if (bountyArea == null) return;
        bountyArea.clearAllChildren();
        switch (bountyView) {
            case BOARD -> renderBountyBoard();
            case PLACE -> renderPlaceBounty();
            case MINE -> renderMyBounties();
        }
    }

    /** One entry per target with everything on them added up. */
    private record TargetGroup(UUID targetId, String name, String faction, boolean online, long total,
                               List<SyncBountiesPacket.View> entries,
                               BountyEvents.ClaimStatus status) {}

    private List<TargetGroup> bountyGroups() {
        Map<UUID, List<SyncBountiesPacket.View>> byTarget = new LinkedHashMap<>();
        for (var b : bounties) byTarget.computeIfAbsent(b.targetId(), ignored -> new ArrayList<>()).add(b);
        List<TargetGroup> groups = new ArrayList<>();
        byTarget.forEach((id, list) -> {
            var first = list.get(0);
            long total = list.stream().mapToLong(SyncBountiesPacket.View::reward).sum();
            // Claimable if any single bounty on them is claimable; otherwise show the reason.
            BountyEvents.ClaimStatus status = list.stream().anyMatch(b -> b.status() == BountyEvents.ClaimStatus.CLAIMABLE)
                    ? BountyEvents.ClaimStatus.CLAIMABLE : first.status();
            groups.add(new TargetGroup(id, first.targetName(), first.targetFaction(), first.targetOnline(), total, list, status));
        });
        groups.sort(Comparator.comparingLong(TargetGroup::total).reversed());
        return groups;
    }

    @OnlyIn(Dist.CLIENT)
    private void renderBountyBoard() {
        List<TargetGroup> groups = bountyGroups();
        if (groups.isEmpty()) {
            bountyArea.addChildren(new Label().setText("§7Nobody has a bounty on them right now."),
                    new Label().setText("§8Use \"+ Place a bounty\" to put one up."));
            return;
        }
        int visible = Math.max(3, menu.rows + 1);
        bountyScroll = Math.max(0, Math.min(bountyScroll, groups.size() - visible));
        for (int i = bountyScroll; i < Math.min(groups.size(), bountyScroll + visible); i++) {
            TargetGroup g = groups.get(i);
            boolean expanded = g.targetId().equals(bountyExpanded);
            var row = UiKit.background(MarketUi.hbox(6), expanded ? 0x60CC7700 : UiKit.ROW);
            row.layout(l -> l.height(24).paddingHorizontal(4));
            var head = new ItemSlot();
            head.setItem(playerHead(g.name(), g.targetId()));
            head.layout(l -> l.width(18).height(18));
            var info = MarketUi.vbox(0);
            info.layout(l -> l.flex(1));
            info.addChildren(new Label().setText((g.online() ? "§a● " : "§8● ") + "§f§l" + g.name()
                            + (g.faction().isEmpty() ? "" : " §7[" + g.faction() + "]")),
                    new Label().setText("§8" + g.entries().size() + " bount" + (g.entries().size() == 1 ? "y" : "ies")
                            + " · click for details"));
            row.addChildren(head, info, UiKit.rightLabel(claimText(g.status())), UiKit.rightLabel("§e§l" + Currency.format(g.total()).replaceAll("§.", "")));
            UiKit.onClick(row, e -> {
                bountyExpanded = expanded ? null : g.targetId();
                renderBounties();
            });
            MarketUi.tooltipOn(row, "§f" + g.name() + (g.online() ? " §a(online)" : " §8(offline)"),
                    "§7Total reward: §e" + Currency.format(g.total()), claimHint(g.status()),
                    "§8Placed by: " + String.join(", ", g.entries().stream()
                            .map(b -> b.mine() ? "you" : b.placerName()).distinct().toList()));
            bountyArea.addChildren(row);
            if (expanded) {
                for (var b : g.entries()) bountyArea.addChildren(bountyDetailRow(b, true));
            }
        }
        if (groups.size() > visible) {
            bountyArea.addChildren(new Label().setText("§8" + (bountyScroll + 1) + "–" + Math.min(groups.size(), bountyScroll + visible)
                    + " of " + groups.size() + " targets · scroll for more"));
        }
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement bountyDetailRow(SyncBountiesPacket.View b, boolean indent) {
        var row = UiKit.background(MarketUi.hbox(6), UiKit.CARD);
        row.layout(l -> l.height(18).paddingHorizontal(4).paddingLeft(indent ? 26 : 4));
        boolean mineOrOp = b.mine() || (Minecraft.getInstance().player != null && Minecraft.getInstance().player.hasPermissions(2));
        long left = b.expiresAt() - System.currentTimeMillis();
        String who = b.mine() ? "§eYou §8(shown as §7" + b.placerName() + "§8)" : "§f" + b.placerName();
        row.addChildren(new Label().setText(indent ? who + " §8placed" : "§fOn §c" + b.targetName()).layout(l -> l.flex(1)),
                UiKit.rightLabel("§8ends in " + (left > 0 ? AllianceManager.formatDuration(left) : "now")),
                UiKit.rightLabel("§e" + Currency.format(b.reward()).replaceAll("§.", "")));
        if (mineOrOp) {
            boolean armed = b.id().equals(bountyCancelArmed);
            var cancel = new Button().setText(armed ? "§c§lRefund?" : "§7Cancel").setOnClick(e -> {
                if (b.id().equals(bountyCancelArmed)) {
                    bountyCancelArmed = null;
                    PacketDistributor.sendToServer(BountyActionPacket.cancel(b.id()));
                } else {
                    bountyCancelArmed = b.id();
                }
                renderBounties();
            });
            cancel.layout(l -> l.width(armed ? 52 : 44).height(14));
            MarketUi.tooltipOn(cancel, "§7Take the bounty down and refund", "§7the reward to its owner's wallet (click twice).");
            row.addChildren(cancel);
        }
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    private void renderMyBounties() {
        var mine = bounties.stream().filter(b -> b.mine())
                .sorted(Comparator.comparingLong(SyncBountiesPacket.View::expiresAt)).toList();
        if (mine.isEmpty()) {
            bountyArea.addChildren(new Label().setText("§7You have no bounties up."));
            return;
        }
        long total = mine.stream().mapToLong(SyncBountiesPacket.View::reward).sum();
        bountyArea.addChildren(new Label().setText("§7Your rewards on the board: §e" + Currency.format(total)
                + " §8· refunded if it expires or you cancel (tax is not)"));
        for (var b : mine) bountyArea.addChildren(bountyDetailRow(b, false));
    }

    @OnlyIn(Dist.CLIENT)
    private void renderPlaceBounty() {
        var form = UiKit.background(MarketUi.vbox(5), UiKit.CARD);
        form.layout(l -> l.paddingAll(8));

        // Target
        var targetPreview = new ItemSlot();
        targetPreview.layout(l -> l.width(18).height(18));
        var suggestions = MarketUi.hbox(3);
        suggestions.layout(l -> l.height(16));
        var targetRow = MarketUi.hbox(5);
        targetRow.layout(l -> l.height(18));
        var targetField = UiKit.textField("");
        targetField.setValue(bountyTarget);
        targetField.layout(l -> l.flex(1).height(16));
        Runnable fillSuggestions = () -> {
            suggestions.clearAllChildren();
            String q = bountyTarget.toLowerCase(Locale.ROOT);
            targetPreview.setItem(bountyTarget.isBlank() ? ItemStack.EMPTY : playerHead(bountyTarget, null));
            bountyPlayers.stream()
                    .filter(n -> q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q))
                    .filter(n -> !n.equalsIgnoreCase(bountyTarget))
                    .limit(5)
                    .forEach(n -> {
                        var chip = new Button().setText("§7" + n).setOnClick(e -> {
                            bountyTarget = n;
                            renderBounties();
                        });
                        chip.layout(l -> l.height(14).paddingHorizontal(4));
                        suggestions.addChildren(chip);
                    });
            if (bountyPlayers.isEmpty()) suggestions.addChildren(new Label().setText("§8No one else is online. Type any player's name."));
        };
        targetField.bindObserver(v -> { bountyTarget = v.trim(); fillSuggestions.run(); });
        MarketUi.tooltipOn(targetField, "§7Any player who has played here.", "§7Online players are suggested below.");
        targetRow.addChildren(new Label().setText("§eTarget").layout(l -> l.width(52)), targetPreview, targetField);
        form.addChildren(targetRow, suggestions);

        // Reward
        var rewardStatus = new Label();
        Runnable validate = () -> {
            long value = Currency.parse(bountyRewardText.replace(" ", ""));
            long wallet = menu.walletRef.get();
            rewardStatus.setText(bountyRewardText.isBlank() ? "§8e.g. 5g, 2g50s, 1p · paid from your wallet"
                    : value <= 0 ? "§c✖ Not a valid amount"
                    : value > wallet ? "§c✖ Your wallet only has " + Currency.format(wallet).replaceAll("§.", "")
                    : "§7Reward §e" + Currency.format(value - value * bountyTaxPercent / 100).replaceAll("§.", "")
                      + (bountyTaxPercent > 0 ? " §8+ " + Currency.format(value * bountyTaxPercent / 100).replaceAll("§.", "")
                         + " tax (" + bountyTaxPercent + "%)" : ""));
        };
        var rewardRow = MarketUi.hbox(5);
        rewardRow.layout(l -> l.height(18));
        var rewardField = UiKit.textField("");
        rewardField.setValue(bountyRewardText);
        rewardField.bindObserver(v -> { bountyRewardText = v; validate.run(); });
        rewardField.layout(l -> l.width(120).height(16));
        rewardRow.addChildren(new Label().setText("§eReward").layout(l -> l.width(52)), rewardField, rewardStatus);
        form.addChildren(rewardRow);

        // Shown as (anonymous unless a name is typed)
        var shownRow = MarketUi.hbox(5);
        shownRow.layout(l -> l.height(18));
        var shownField = UiKit.textField("");
        shownField.setValue(bountyShownAs);
        var shownStatus = new Label();
        Runnable shownPreview = () -> shownStatus.setText("§8Shown as §f" + BountyActionPacket.cleanShownName(bountyShownAs));
        shownField.bindObserver(v -> { bountyShownAs = v; shownPreview.run(); });
        shownField.layout(l -> l.width(120).height(16));
        MarketUi.tooltipOn(shownField, "§7The name shown as the placer.", "§7Leave empty to stay §fAnonymous§7.",
                "§8Your real name is never shown to anyone else.");
        shownRow.addChildren(new Label().setText("§eShow as").layout(l -> l.width(52)), shownField, shownStatus);
        form.addChildren(shownRow);
        shownPreview.run();

        // Duration
        var durationRow = MarketUi.hbox(4);
        durationRow.layout(l -> l.height(18));
        durationRow.addChildren(new Label().setText("§eLasts").layout(l -> l.width(52)));
        for (int days : new int[]{1, 3, 7}) {
            boolean active = days == bountyDays;
            var btn = new Button().setText((active ? "§f§l" : "§7") + days + " day" + (days == 1 ? "" : "s")).setOnClick(e -> {
                bountyDays = days;
                renderBounties();
            });
            btn.layout(l -> l.width(56).height(16));
            if (active) btn.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
            durationRow.addChildren(btn);
        }
        durationRow.addChildren(new Label().setText("§8then refunded if unclaimed"));
        form.addChildren(durationRow);

        var place = new Button().setText("§c☠ Place bounty").setOnClick(e -> {
            long value = Currency.parse(bountyRewardText.replace(" ", ""));
            if (bountyTarget.isBlank() || value <= 0) return;
            PacketDistributor.sendToServer(BountyActionPacket.place(
                    bountyTarget, value, bountyDays, bountyShownAs));
            bountyTarget = "";
            bountyRewardText = "";
            bountyView = BountyView.MINE;
            MarketMenu.refreshScreen();
        });
        place.layout(l -> l.height(20));
        form.addChildren(place,
                new Label().setText("§8You can't target teammates or allies. Anyone else who kills the target claims it."),
                new Label().setText("§8The " + bountyTaxPercent + "% tax is kept when you place it; cancelling or expiring refunds the reward only."));
        bountyArea.addChildren(form);
        fillSuggestions.run();
        validate.run();
    }

    private static String claimText(BountyEvents.ClaimStatus status) {
        return switch (status) {
            case CLAIMABLE -> "§a✔ Claimable";
            case YOU_ARE_TARGET -> "§c☠ That's you";
            case YOU_PLACED_IT -> "§8Placed by you";
            case TEAMMATE -> "§8Teammate";
            case ALLY -> "§8Allied";
            case LEFT_RECENTLY -> "§8Left recently";
        };
    }

    private static String claimHint(BountyEvents.ClaimStatus status) {
        return switch (status) {
            case CLAIMABLE -> "§aKill them to claim it.";
            case YOU_ARE_TARGET -> "§cSomeone wants you dead.";
            case YOU_PLACED_IT -> "§7You can't claim your own bounty.";
            case TEAMMATE -> "§7You can't claim bounties on teammates.";
            case ALLY -> "§7Your factions are allied (or recently were).";
            case LEFT_RECENTLY -> "§7You left their faction (or an ally of it) too recently.";
        };
    }

    private static ItemStack playerHead(String name, @Nullable UUID id) {
        var skull = new ItemStack(Items.PLAYER_HEAD);
        skull.set(DataComponents.PROFILE,
                new ResolvableProfile(
                        Optional.of(name), Optional.ofNullable(id),
                        new PropertyMap()));
        return skull;
    }
}
