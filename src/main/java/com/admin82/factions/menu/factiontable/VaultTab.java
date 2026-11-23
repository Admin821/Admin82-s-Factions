package com.admin82.factions.menu.factiontable;

import com.admin82.factions.menu.UiKit;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.menu.MarketWidgets;
import com.admin82.factions.network.packet.*;
import com.admin82.factions.network.packet.VaultActionPacket;
import com.admin82.factions.storage.ItemVaultService;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.vfyjxf.taffy.style.AlignItems;


import java.util.*;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

/** The Vault tab: deposits, withdrawals and bank history. */
final class VaultTab {

    private final FactionTableMenu menu;

    VaultTab(FactionTableMenu menu) {
        this.menu = menu;
    }

    // Vault sub-view state
    @Nullable private UIElement                         vaultContentArea;
    @Nullable private VaultActionPacket.Action          pendingVaultAction;
    @Nullable private LongSupplier  pendingVaultTotal;
    @Nullable private String                            pendingVaultTitle;
    @Nullable private String                            pendingVaultVerb;

    // Bank history (requested from the server when the history view opens)
    @Nullable List<com.admin82.factions.economy.Transaction> walletHistory;
    @Nullable List<com.admin82.factions.economy.Transaction> vaultHistory;
    @Nullable HistoryRequestPacket.Scope shownHistory;
    private int historyScroll;
    @Nullable private UIElement historyArea;
    @Nullable private Label historyPager;
    private static final int HISTORY_ROWS = 10;

    // Vault

    UIElement buildVaultPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN));
        vaultContentArea = new UIElement();
        vaultContentArea.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).gapAll(8));
        var keepHistory = shownHistory;
        int keepScroll = historyScroll;
        if (keepHistory != null) {
            showBankHistory(player, keepHistory);
            historyScroll = keepScroll;
            renderBankHistory();
        } else {
            showVaultMain(player);
        }
        panel.addChildren(vaultContentArea);
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private void showVaultMain(Player player) {
        if (vaultContentArea == null) return;
        vaultContentArea.clearAllChildren();

        var cards = new UIElement();
        cards.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6));

        // Personal wallet
        var wallet = FactionTableMenu.card();
        wallet.layout(l -> l.flex(1));
        var walletBalance = new Label();
        walletBalance.bindDataSource(SupplierDataSource.of(() ->
                Component.literal("§a§l" + Currency.format(menu.playerWalletRef.get()))));
        var depWalletBtn = new Button().setText("§aDeposit coins").setOnClick(e ->
                showVaultAction(player, "Deposit coins to Wallet", "Deposit",
                        VaultActionPacket.Action.DEPOSIT_WALLET,
                        () -> EconomyManager.countCoinsInInventory(player)));
        depWalletBtn.layout(l -> l.height(18));
        depWalletBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                Component.literal("§7Turn coin items in your inventory into wallet money.")));
        var wdWalletBtn = new Button().setText("§eWithdraw as coins").setOnClick(e ->
                showVaultAction(player, "Withdraw from Wallet", "Withdraw",
                        VaultActionPacket.Action.WITHDRAW_WALLET,
                        menu.playerWalletRef::get));
        wdWalletBtn.layout(l -> l.height(18));
        wallet.addChildren(new Label().setText("§6Your wallet"), new Label().setText("§8Only you can use this."),
                walletBalance, depWalletBtn, wdWalletBtn);

        // Faction vault
        var vault = FactionTableMenu.card();
        vault.layout(l -> l.flex(1));
        var vaultBalance = new Label();
        vaultBalance.bindDataSource(SupplierDataSource.of(() ->
                Component.literal("§a§l" + Currency.format(menu.factionVaultRef.get()))));
        var depVaultBtn = new Button().setText("§aDeposit from wallet").setOnClick(e ->
                showVaultAction(player, "Deposit to Faction Vault", "Deposit",
                        VaultActionPacket.Action.DEPOSIT_FACTION,
                        menu.playerWalletRef::get));
        depVaultBtn.layout(l -> l.height(18));
        vault.addChildren(new Label().setText("§6Faction vault"), new Label().setText("§8Pays land upkeep and deeds."),
                vaultBalance, depVaultBtn);
        if (menu.canWithdrawFromVault(player)) {
            var wdVaultBtn = new Button().setText("§eWithdraw to wallet").setOnClick(e ->
                    showVaultAction(player, "Withdraw from Faction Vault", "Withdraw",
                            VaultActionPacket.Action.WITHDRAW_FACTION,
                            menu.factionVaultRef::get));
            wdVaultBtn.layout(l -> l.height(18));
            vault.addChildren(wdVaultBtn);
        } else {
            vault.addChildren(new Label().setText("§8Withdrawing needs permission."));
        }
        cards.addChildren(wallet, vault);

        var upkeep = FactionTableMenu.card();
        upkeep.addChildren(
                menu.statLine("Upkeep", () -> "§e" + Currency.format(menu.dailyUpkeep()) + "§7/day"),
                menu.statLine("Funded for", menu::fundedForText),
                new Label().setText("§8Each chunk costs its deed price per day. Core chunk = 1 silver/day."));

        var historyRow = new UIElement();
        historyRow.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6).height(18));
        var walletHistoryBtn = new Button().setText("§bWallet history").setOnClick(e ->
                showBankHistory(player, HistoryRequestPacket.Scope.WALLET));
        walletHistoryBtn.layout(l -> l.flex(1).height(18));
        var vaultHistoryBtn = new Button().setText("§bVault history").setOnClick(e ->
                showBankHistory(player, HistoryRequestPacket.Scope.VAULT));
        vaultHistoryBtn.layout(l -> l.flex(1).height(18));
        // The shared item vault opens in its own screen and updates live for everyone viewing it.
        var itemVaultBtn = new Button().setText("§6📦 Item vault").setOnClick(e -> {
            var f = menu.factionRef.get();
            if (f != null) PacketDistributor.sendToServer(new ItemVaultActionPacket(
                    ItemVaultService.Kind.FACTION.ordinal(), f.getId(),
                    ItemVaultActionPacket.OPEN, 0, 0));
        });
        itemVaultBtn.layout(l -> l.flex(1).height(18));
        itemVaultBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                Component.literal("§6Faction item vault"),
                Component.literal("§7Shared storage for the whole faction. Holds a set number of"),
                Component.literal("§7different items, each up to a limit; upgrade it for more.")));
        historyRow.addChildren(walletHistoryBtn, vaultHistoryBtn, itemVaultBtn);

        shownHistory = null;
        historyArea = null;
        vaultContentArea.addChildren(cards, historyRow, upkeep);
    }

    /** Transaction list for the personal wallet or the faction vault. */
    @OnlyIn(Dist.CLIENT)
    private void showBankHistory(Player player, HistoryRequestPacket.Scope scope) {
        if (vaultContentArea == null) return;
        vaultContentArea.clearAllChildren();
        shownHistory = scope;
        historyScroll = 0;
        boolean wallet = scope == HistoryRequestPacket.Scope.WALLET;

        var header = new UIElement();
        header.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(4).height(18));
        var back = new Button().setText("§7◀ Back").setOnClick(e -> showVaultMain(player));
        back.layout(l -> l.width(56).height(16));
        var walletTab = new Button().setText(wallet ? "§f§lWallet" : "§7Wallet").setOnClick(e ->
                showBankHistory(player, HistoryRequestPacket.Scope.WALLET));
        walletTab.layout(l -> l.width(70).height(16));
        if (wallet) walletTab.lss("base-background", FactionTableMenu.ACTIVE_BG);
        var vaultTab = new Button().setText(!wallet ? "§f§lFaction vault" : "§7Faction vault").setOnClick(e ->
                showBankHistory(player, HistoryRequestPacket.Scope.VAULT));
        vaultTab.layout(l -> l.width(90).height(16));
        if (!wallet) vaultTab.lss("base-background", FactionTableMenu.ACTIVE_BG);
        header.addChildren(back, new Label().setText("§6§lHistory").layout(l -> l.flex(1)), walletTab, vaultTab);

        historyArea = new UIElement();
        historyArea.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).gapAll(2));
        historyPager = new Label();
        historyPager.lss("horizontal-align", "center");
        historyPager.layout(l -> l.flex(1));
        var pager = new UIElement();
        pager.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(4).height(16));
        pager.addChildren(
                new Button().setText("‹").setOnClick(e -> { historyScroll = Math.max(0, historyScroll - HISTORY_ROWS); renderBankHistory(); })
                        .layout(l -> l.width(20).height(14)),
                historyPager,
                new Button().setText("›").setOnClick(e -> { historyScroll += HISTORY_ROWS; renderBankHistory(); })
                        .layout(l -> l.width(20).height(14)));

        vaultContentArea.addChildren(header, new Label().setText(wallet
                ? "§8Money in and out of your personal wallet."
                : "§8Deposits, withdrawals, upkeep, land and war payments for the faction vault."), historyArea, pager);
        renderBankHistory();
        PacketDistributor.sendToServer(new HistoryRequestPacket(scope));
    }

    @OnlyIn(Dist.CLIENT)
    void renderBankHistory() {
        if (historyArea == null || shownHistory == null) return;
        boolean wallet = shownHistory == HistoryRequestPacket.Scope.WALLET;
        List<com.admin82.factions.economy.Transaction> entries = wallet ? walletHistory : vaultHistory;
        historyScroll = MarketWidgets.fillHistory(historyArea, entries, historyScroll, HISTORY_ROWS, FactionTableMenu.CONTENT_WIDTH,
                wallet ? "§7No wallet activity yet." : "§7No vault activity yet.");
        if (historyPager != null) historyPager.setText(MarketWidgets.pageText(entries, historyScroll, HISTORY_ROWS));
    }

    /** Shows the All / Half / Custom action sub-menu. */
    @OnlyIn(Dist.CLIENT)
    private void showVaultAction(Player player, String title, String verb,
                                 VaultActionPacket.Action action,
                                 LongSupplier totalFn) {
        if (vaultContentArea == null) return;
        pendingVaultAction = action;
        pendingVaultVerb   = verb;
        pendingVaultTotal  = totalFn;
        pendingVaultTitle  = title;

        vaultContentArea.clearAllChildren();
        vaultContentArea.addChildren(
            new Label().setText("§6§l" + title),
            new Label().setText("§7How much would you like to " + verb.toLowerCase() + "?"),
            new Label().setText("§8─────────────────────────────────────────")
        );

        long total = totalFn.getAsLong();
        long half  = total / 2;

        var allBtn = new Button()
            .setText(total > 0
                    ? "§a▶ " + verb + " All  §7(§e" + Currency.format(total) + "§7)"
                    : "§8▶ " + verb + " All  §8(nothing available)")
            .setOnClick(e -> {
                long amt = totalFn.getAsLong();
                if (amt > 0) { PacketDistributor.sendToServer(new VaultActionPacket(action, amt)); showVaultMain(player); }
            });
        allBtn.layout(l -> l.width(300).height(20));

        var halfBtn = new Button()
            .setText(half > 0
                    ? "§7▶ " + verb + " Half  §8(§e" + Currency.format(half) + "§8)"
                    : "§8▶ " + verb + " Half  §8(nothing available)")
            .setOnClick(e -> {
                long amt = totalFn.getAsLong() / 2;
                if (amt > 0) { PacketDistributor.sendToServer(new VaultActionPacket(action, amt)); showVaultMain(player); }
            });
        halfBtn.layout(l -> l.width(300).height(20));

        var customBtn = new Button().setText("§b▶ Custom Amount...")
            .setOnClick(e -> showVaultCustom(player, title, action));
        customBtn.layout(l -> l.width(300).height(20));

        var backBtn = new Button().setText("§7◀ Back").setOnClick(e -> showVaultMain(player));
        backBtn.layout(l -> l.width(80).height(20));

        vaultContentArea.addChildren(
            allBtn, halfBtn, customBtn,
            new Label().setText("§8─────────────────────────────────────────"),
            backBtn
        );
    }

    /** Shows the custom-amount number + coin dropdown input view. */
    @OnlyIn(Dist.CLIENT)
    private void showVaultCustom(Player player, String title, VaultActionPacket.Action action) {
        if (vaultContentArea == null) return;
        vaultContentArea.clearAllChildren();
        vaultContentArea.addChildren(
            new Label().setText("§6§l" + title + " — Custom Amount"),
            new Label().setText("§7Enter an amount:")
        );

        long[] qty  = {0};
        String[] coin = {"Copper"};

        var inputRow = new UIElement();
        inputRow.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6).height(24));

        var field = UiKit.textField("");
        field.setValue("0");
        field.bindObserver(v -> { try { qty[0] = Math.max(0, Long.parseLong(v.trim())); } catch (NumberFormatException ignored) {} });
        field.layout(l -> l.width(100));

        @SuppressWarnings("unchecked")
        var coinSel = new Selector<String>();
        coinSel.setCandidates(List.of("Copper", "Silver", "Gold", "Platinum"));
        coinSel.setValue("Copper");
        coinSel.setOnValueChanged(v -> coin[0] = v);
        coinSel.layout(l -> l.width(90).height(24));

        inputRow.addChildren(field, coinSel);

        var confirmBtn = new Button().setText("§aConfirm").setOnClick(e -> {
            long amt = qty[0] * vaultCoinMult(coin[0]);
            if (amt > 0) PacketDistributor.sendToServer(new VaultActionPacket(action, amt));
            showVaultMain(player);
        });
        confirmBtn.layout(l -> l.width(100).height(20));

        var backBtn = new Button().setText("§7◀ Back").setOnClick(e -> {
            if (pendingVaultAction != null && pendingVaultVerb != null && pendingVaultTotal != null) {
                showVaultAction(player, title, pendingVaultVerb, pendingVaultAction, pendingVaultTotal);
            } else {
                showVaultMain(player);
            }
        });
        backBtn.layout(l -> l.width(80).height(20));

        vaultContentArea.addChildren(
            inputRow,
            new Label().setText("§8─────────────────────────────────────────"),
            confirmBtn,
            backBtn
        );
    }

    /** Converts a coin-type dropdown selection to its copper multiplier. */
    private static long vaultCoinMult(String coin) {
        return switch (coin) {
            case "Platinum" -> Currency.COPPER_PER_PLATINUM;
            case "Gold"     -> Currency.COPPER_PER_GOLD;
            case "Silver"   -> Currency.COPPER_PER_SILVER;
            default         -> 1L;
        };
    }
}
