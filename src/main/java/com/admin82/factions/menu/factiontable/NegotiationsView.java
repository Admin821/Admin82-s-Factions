package com.admin82.factions.menu.factiontable;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.faction.*;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.menu.factiontable.WarsTab.WarsSubView;
import com.admin82.factions.network.packet.*;
import com.admin82.factions.war.WarDemand;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;

import java.util.*;
import javax.annotation.Nullable;

/** Wartime negotiations: open demands, answering them, and writing new ones. */
final class NegotiationsView {

    private final FactionTableMenu menu;

    NegotiationsView(FactionTableMenu menu) {
        this.menu = menu;
    }

    // Compose-demand working fields (arrays so lambdas can mutate)
    private final long[]    composeMoney      = {0L};
    private final String[]  composeItemId     = {""};
    private final int[]     composeItemCount  = {1};
    private final int[]     composeLandChunks = {0};
    private final boolean[] composeVassal     = {false};

    // Wartime Negotiations view

    @Nullable UUID confirmDemandId;
    boolean confirmWhitePeace;
    private final String[] composeMoneyText = {""};

    /** Demands for the war with the faction we are negotiating with. */
    private List<WarDemand> negotiationDemands() {
        if (menu.wars.warsNegotiateTarget == null) return List.of();
        UUID target = menu.wars.warsNegotiateTarget.id();
        return menu.wars.warDemandsRef.get().stream()
                .filter(d -> target.equals(d.senderFactionId) || target.equals(d.receiverFactionId))
                .toList();
    }

    private static boolean isOpen(WarDemand d) {
        return d.status == WarDemand.Status.PENDING && System.currentTimeMillis() <= d.expiresAt;
    }

    void buildNegotiationsView(Player player) {
        Faction myFaction = menu.factionRef.get();
        UUID myId = myFaction != null ? myFaction.getId() : null;
        boolean officer = menu.isOfficer(player);
        String enemy = menu.wars.warsNegotiateTarget != null ? menu.wars.warsNegotiateTarget.name() : "?";
        WarEntry war = myFaction == null || menu.wars.warsNegotiateTarget == null ? null : myFaction.getWars().stream()
                .filter(w -> w.targetFactionId().equals(menu.wars.warsNegotiateTarget.id())).findFirst().orElse(null);

        // Header
        var header = new UIElement();
        header.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(16));
        var back = new Button().setText("§7◀ Back").setOnClick(e -> { menu.wars.warsSubView = WarsSubView.LIST; menu.wars.fillWarsPanel(player); });
        back.layout(l -> l.width(54).height(16));
        var refresh = new Button().setText("§7⟳").setOnClick(e -> {
            if (menu.wars.warsNegotiateTarget != null) PacketDistributor.sendToServer(new OpenNegotiationsPacket(menu.wars.warsNegotiateTarget.id()));
        });
        refresh.layout(l -> l.width(18).height(16));
        tooltip(refresh, "§7Refresh");
        header.addChildren(back, new Label().setText("§6§l⚑ Negotiations with §c§l" + enemy).layout(l -> l.flex(1)), refresh);
        menu.wars.warsPanel.addChildren(header);
        menu.wars.warsPanel.addChildren(new Label().setText("§8" + (war != null
                ? (war.warType() != null ? war.warType().displayName : "War") + " · started " + WarsTab.warAgo(war.startTime()) + " · "
                : "") + "Accepting any demand ends the war."));

        List<WarDemand> demands = negotiationDemands();
        WarDemand incoming = demands.stream().filter(d -> isOpen(d) && myId != null && myId.equals(d.receiverFactionId))
                .findFirst().orElse(null);
        WarDemand outgoing = demands.stream().filter(d -> isOpen(d) && myId != null && myId.equals(d.senderFactionId))
                .findFirst().orElse(null);

        // Their demand
        var inCard = FactionTableMenu.card();
        inCard.addChildren(new Label().setText("§e§lTheir demand"));
        if (incoming == null) {
            inCard.addChildren(new Label().setText("§7" + enemy + " has not asked for anything."));
        } else {
            inCard.addChildren(demandHeader(incoming, "§7From §c" + incoming.senderFactionName));
            addTermLines(inCard, incoming, true);
            if (officer) {
                final UUID id = incoming.demandId;
                boolean confirming = id.equals(confirmDemandId);
                var actions = new UIElement();
                actions.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(18));
                var accept = new Button().setText(confirming ? "§a§lConfirm: end the war" : "§a✔ Accept").setOnClick(e -> {
                    if (id.equals(confirmDemandId)) {
                        confirmDemandId = null;
                        PacketDistributor.sendToServer(new RespondWarDemandPacket(id, true));
                    } else {
                        confirmDemandId = id;
                    }
                    menu.wars.fillWarsPanel(player);
                });
                accept.layout(l -> l.flex(1).height(18));
                tooltip(accept, "§7Pay the terms and end the war.", "§7Click twice to confirm.");
                var reject = new Button().setText("§c✖ Reject").setOnClick(e -> {
                    confirmDemandId = null;
                    PacketDistributor.sendToServer(new RespondWarDemandPacket(id, false));
                });
                reject.layout(l -> l.width(70).height(18));
                final WarDemand counterOf = incoming;
                var counter = new Button().setText("§e⇄ Counter").setOnClick(e -> {
                    composeMoney[0] = counterOf.moneyAmount;
                    composeMoneyText[0] = counterOf.moneyAmount > 0 ? Currency.format(counterOf.moneyAmount).replaceAll("§.", "").replace(" ", "") : "";
                    composeItemId[0] = counterOf.itemId;
                    composeItemCount[0] = Math.max(1, counterOf.itemCount);
                    composeLandChunks[0] = counterOf.landChunks;
                    composeVassal[0] = counterOf.vassalTerm;
                    menu.wars.warsSubView = WarsSubView.COMPOSE_DEMAND;
                    menu.wars.fillWarsPanel(player);
                });
                counter.layout(l -> l.width(76).height(18));
                tooltip(counter, "§7Send your own terms back,", "§7starting from theirs.");
                actions.addChildren(accept, reject, counter);
                inCard.addChildren(actions);
            } else {
                inCard.addChildren(new Label().setText("§8Only officers and above can answer demands."));
            }
        }
        menu.wars.warsPanel.addChildren(inCard);

        // Our demand
        var outCard = FactionTableMenu.card();
        outCard.addChildren(new Label().setText("§e§lYour demand"));
        if (outgoing == null) {
            outCard.addChildren(new Label().setText("§7You have no open demand."));
        } else {
            outCard.addChildren(demandHeader(outgoing, "§7Waiting for §c" + outgoing.receiverFactionName + " §7to answer"));
            addTermLines(outCard, outgoing, false);
            if (officer) {
                final UUID id = outgoing.demandId;
                var withdraw = new Button().setText("§7Withdraw").setOnClick(e ->
                        PacketDistributor.sendToServer(new RespondWarDemandPacket(id, false)));
                withdraw.layout(l -> l.width(80).height(16));
                outCard.addChildren(withdraw);
            }
        }
        if (officer) {
            var actions = new UIElement();
            actions.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(18));
            var compose = new Button().setText(outgoing == null ? "§e+ New demand" : "§e✎ Replace demand").setOnClick(e -> {
                menu.wars.warsSubView = WarsSubView.COMPOSE_DEMAND;
                menu.wars.fillWarsPanel(player);
            });
            compose.layout(l -> l.flex(1).height(18));
            tooltip(compose, "§7Ask for money, items, land or vassalage.", "§7Sending a new demand replaces your open one.");
            var peace = new Button().setText(confirmWhitePeace ? "§a§lConfirm peace offer" : "§a☮ Offer white peace").setOnClick(e -> {
                if (confirmWhitePeace && menu.wars.warsNegotiateTarget != null) {
                    PacketDistributor.sendToServer(new SendWarDemandPacket(menu.wars.warsNegotiateTarget.id(), 0, "", 0, 0, false));
                    confirmWhitePeace = false;
                } else {
                    confirmWhitePeace = true;
                }
                menu.wars.fillWarsPanel(player);
            });
            peace.layout(l -> l.flex(1).height(18));
            tooltip(peace, "§7Offer to end the war with no terms.", "§7If they accept, nobody pays anything.");
            actions.addChildren(compose, peace);
            outCard.addChildren(actions);
        }
        menu.wars.warsPanel.addChildren(outCard);

        // History
        List<WarDemand> past = demands.stream().filter(d -> !isOpen(d)).limit(4).toList();
        if (!past.isEmpty()) {
            var hist = FactionTableMenu.card();
            hist.layout(l -> l.flex(1));
            hist.addChildren(new Label().setText("§e§lEarlier"));
            for (WarDemand d : past) {
                boolean mineSent = myId != null && myId.equals(d.senderFactionId);
                WarDemand.Status status = d.status == WarDemand.Status.PENDING ? WarDemand.Status.EXPIRED : d.status;
                String badge = switch (status) {
                    case ACCEPTED -> "§aAccepted";
                    case REJECTED -> "§cRejected";
                    case WITHDRAWN -> "§7Withdrawn";
                    default -> "§8Expired";
                };
                var row = UiKit.background(new UIElement(), UiKit.ROW);
                row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4)
                        .height(14).paddingHorizontal(4));
                String terms = d.termsSummary();
                row.addChildren(new Label().setText((mineSent ? "§eYou" : "§c" + d.senderFactionName) + "§8: " + terms)
                                .layout(l -> l.flex(1)),
                        UiKit.rightLabel(badge + " §8" + WarsTab.warAgo(d.sentAt)));
                tooltip(row, (mineSent ? "§eSent by you" : "§cSent by " + d.senderFactionName), "§7" + terms);
                hist.addChildren(row);
            }
            menu.wars.warsPanel.addChildren(hist);
        }
    }

    private UIElement demandHeader(WarDemand d, String text) {
        long minutesLeft = Math.max(0, (d.expiresAt - System.currentTimeMillis()) / 60_000L);
        var row = new UIElement();
        row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).height(12));
        row.addChildren(new Label().setText(text).layout(l -> l.flex(1)),
                UiKit.rightLabel("§8sent " + WarsTab.warAgo(d.sentAt) + " · §e" + (minutesLeft < 1 ? "<1" : minutesLeft) + "m left"));
        return row;
    }

    /** One line per term; for demands we received, shows whether we can pay. */
    private void addTermLines(UIElement card, WarDemand d, boolean incoming) {
        if (d.isWhitePeace()) {
            card.addChildren(new Label().setText("§a☮ White peace §7— end the war, nobody pays anything."));
            return;
        }
        if (d.moneyAmount > 0) {
            long vault = menu.factionVaultRef.get();
            String check = !incoming ? "" : vault >= d.moneyAmount ? "  §a✔ vault has " + Currency.format(vault)
                    : "  §c✖ vault only has " + Currency.format(vault);
            card.addChildren(new Label().setText("§7• Pay §r" + Currency.format(d.moneyAmount) + " §7from the vault" + check));
        }
        if (!d.itemId.isEmpty() && d.itemCount > 0) {
            ResourceLocation rl = ResourceLocation.tryParse(d.itemId);
            Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
            var row = new UIElement();
            row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(18));
            if (item != null) {
                var slot = new ItemSlot().setItem(new ItemStack(item));
                slot.layout(l -> l.width(18).height(18));
                row.addChildren(slot);
            }
            String name = item != null ? item.getDescription().getString() : d.itemId;
            String check = "";
            if (incoming && item != null && menu.playerRef != null) {
                int have = menu.playerRef.getInventory().countItem(item);
                check = "  §8(you carry " + have + "; any online member can chip in)";
            }
            row.addChildren(new Label().setText("§7• Hand over §a" + d.itemCount + "× §f" + name + check).layout(l -> l.flex(1)));
            card.addChildren(row);
        }
        if (d.landChunks > 0) {
            card.addChildren(new Label().setText("§7• Release §c" + d.landChunks + " chunk" + (d.landChunks == 1 ? "" : "s")
                    + " §8(most recent claims, never the core)"));
        }
        if (d.vassalTerm) {
            card.addChildren(new Label().setText(incoming
                    ? "§7• Become §5" + d.senderFactionName + "'s vassal"
                    : "§7• §5" + d.receiverFactionName + " §7becomes your vassal"));
        }
    }

    static void tooltip(UIElement element, String... lines) {
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> {
            var tips = HoverTooltips.empty();
            for (String line : lines) tips = tips.append(Component.literal(line));
            ev.hoverTooltips = tips;
        });
    }

    // Compose demand view

    void buildComposeDemandView(Player player) {
        String enemy = menu.wars.warsNegotiateTarget != null ? menu.wars.warsNegotiateTarget.name() : "?";
        var header = new UIElement();
        header.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(16));
        var back = new Button().setText("§7◀ Back").setOnClick(e -> { menu.wars.warsSubView = WarsSubView.NEGOTIATIONS; menu.wars.fillWarsPanel(player); });
        back.layout(l -> l.width(54).height(16));
        header.addChildren(back, new Label().setText("§6§l⚑ New demand to §c§l" + enemy).layout(l -> l.flex(1)));
        menu.wars.warsPanel.addChildren(header,
                new Label().setText("§8Pick any mix of terms. Leave everything empty to offer white peace."));

        var summary = new Label();
        var moneyPreview = new Label();
        var itemPreview = new Label();
        var itemSlot = new ItemSlot();
        itemSlot.layout(l -> l.width(18).height(18));
        Runnable update = () -> {
            moneyPreview.setText(composeMoney[0] > 0 ? "§7= §r" + Currency.format(composeMoney[0])
                    : composeMoneyText[0].isBlank() ? "§8none" : "§cnot a valid amount");
            Item item = resolveItem(composeItemId[0]);
            itemSlot.setItem(item != null ? new ItemStack(item) : ItemStack.EMPTY);
            itemPreview.setText(composeItemId[0].isBlank() ? "§8none"
                    : item != null ? "§a✔ §f" + item.getDescription().getString() : "§c✖ unknown item");
            var preview = new WarDemand(UUID.randomUUID(), UUID.randomUUID(), "", UUID.randomUUID(), "",
                    composeMoney[0], item != null ? composeItemId[0] : "", composeItemCount[0], composeLandChunks[0], composeVassal[0]);
            summary.setText("§7Terms: " + preview.termsSummary());
        };

        // Money
        var moneyCard = FactionTableMenu.card();
        var moneyRow = new UIElement();
        moneyRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(6).height(18));
        var moneyField = UiKit.textField("");
        moneyField.setValue(composeMoneyText[0]);
        moneyField.bindObserver(v -> {
            composeMoneyText[0] = v;
            long parsed = Currency.parse(v.replace(" ", ""));
            composeMoney[0] = Math.max(0, parsed);
            update.run();
        });
        moneyField.layout(l -> l.width(110).height(16));
        tooltip(moneyField, "§7Examples: §f5g§7, §f2g50s§7, §f1p§7, §f300 §8(copper)");
        moneyRow.addChildren(new Label().setText("§eVault payment").layout(l -> l.width(90)), moneyField, moneyPreview);
        moneyCard.addChildren(moneyRow);

        // Items
        var itemRow = new UIElement();
        itemRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(6).height(18));
        var itemField = UiKit.textField("");
        itemField.setValue(composeItemId[0]);
        itemField.bindObserver(v -> { composeItemId[0] = v.trim(); update.run(); });
        itemField.layout(l -> l.width(110).height(16));
        tooltip(itemField, "§7Item id, e.g. §fdiamond §7or §fminecraft:netherite_ingot");
        var countField = UiKit.textField("");
        countField.setValue(String.valueOf(composeItemCount[0]));
        countField.bindObserver(v -> {
            try { composeItemCount[0] = Math.max(1, Math.min(6400, Integer.parseInt(v.trim()))); }
            catch (NumberFormatException ignored) {}
            update.run();
        });
        countField.layout(l -> l.width(40).height(16));
        var held = new Button().setText("§7Use held").setOnClick(e -> {
            ItemStack hand = player.getMainHandItem();
            if (hand.isEmpty()) return;
            composeItemId[0] = BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
            composeItemCount[0] = hand.getCount();
            menu.wars.fillWarsPanel(player);
        });
        held.layout(l -> l.width(56).height(16));
        tooltip(held, "§7Fill in the item in your main hand.");
        itemRow.addChildren(new Label().setText("§eItems").layout(l -> l.width(90)), itemField,
                new Label().setText("§7×"), countField, itemSlot, held);
        moneyCard.addChildren(itemRow, new UIElement().layout(l -> l.height(10).paddingLeft(96))
                .addChildren(itemPreview));

        // Land
        var landRow = new UIElement();
        landRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(6).height(18));
        var landLabel = new Label().setText("§f" + composeLandChunks[0]);
        landLabel.lss("horizontal-align", "center");
        landLabel.layout(l -> l.width(28));
        var minus = new Button().setText("-").setOnClick(e -> {
            composeLandChunks[0] = Math.max(0, composeLandChunks[0] - 1);
            landLabel.setText("§f" + composeLandChunks[0]);
            update.run();
        });
        minus.layout(l -> l.width(16).height(16));
        var plus = new Button().setText("+").setOnClick(e -> {
            composeLandChunks[0] = Math.min(999, composeLandChunks[0] + 1);
            landLabel.setText("§f" + composeLandChunks[0]);
            update.run();
        });
        plus.layout(l -> l.width(16).height(16));
        landRow.addChildren(new Label().setText("§eRelease land").layout(l -> l.width(90)), minus, landLabel, plus,
                new Label().setText("§8chunks, newest first, never their core").layout(l -> l.flex(1)));
        moneyCard.addChildren(landRow);

        // Vassal
        var vassalRow = new UIElement();
        vassalRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(6).height(18));
        var vassalToggle = UiKit.toggle();
        vassalToggle.setOn(composeVassal[0]);
        vassalToggle.setOnToggleChanged(on -> { composeVassal[0] = on; update.run(); });
        vassalToggle.layout(l -> l.width(18).height(16));
        vassalRow.addChildren(new Label().setText("§eVassalage").layout(l -> l.width(90)), vassalToggle,
                new Label().setText("§7" + enemy + " becomes your vassal").layout(l -> l.flex(1)));
        moneyCard.addChildren(vassalRow);
        menu.wars.warsPanel.addChildren(moneyCard);

        // Summary + send
        var footer = FactionTableMenu.card();
        footer.addChildren(summary, new Label().setText("§8They have 10 minutes to answer. Accepting ends the war."));
        var buttons = new UIElement();
        buttons.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(18));
        var send = new Button().setText("§a⚑ Send").setOnClick(e -> {
            if (menu.wars.warsNegotiateTarget == null) return;
            if (!composeMoneyText[0].isBlank() && composeMoney[0] <= 0) return;
            Item item = resolveItem(composeItemId[0]);
            if (!composeItemId[0].isBlank() && item == null) return;
            PacketDistributor.sendToServer(new SendWarDemandPacket(
                    menu.wars.warsNegotiateTarget.id(),
                    composeMoney[0],
                    item != null ? BuiltInRegistries.ITEM.getKey(item).toString() : "",
                    item != null ? composeItemCount[0] : 0,
                    composeLandChunks[0],
                    composeVassal[0]));
            clearCompose();
            menu.wars.warsSubView = WarsSubView.NEGOTIATIONS;
            menu.wars.fillWarsPanel(player);
        });
        send.layout(l -> l.flex(1).height(18));
        var clear = new Button().setText("§7Clear").setOnClick(e -> { clearCompose(); menu.wars.fillWarsPanel(player); });
        clear.layout(l -> l.width(60).height(18));
        buttons.addChildren(send, clear);
        footer.addChildren(buttons);
        menu.wars.warsPanel.addChildren(footer);
        update.run();
    }

    private void clearCompose() {
        composeMoney[0]      = 0L;
        composeMoneyText[0]  = "";
        composeItemId[0]     = "";
        composeItemCount[0]  = 1;
        composeLandChunks[0] = 0;
        composeVassal[0]     = false;
    }

    @Nullable
    private static Item resolveItem(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = raw.trim().toLowerCase(Locale.ROOT);
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
    }
}
