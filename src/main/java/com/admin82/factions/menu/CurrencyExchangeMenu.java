package com.admin82.factions.menu;

import com.admin82.factions.client.ClientPlayer;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.ExchangeManager;
import com.admin82.factions.network.packet.ExchangeActionPacket;
import com.admin82.factions.registry.ModItems;
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
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;


import java.util.*;
import javax.annotation.Nullable;

/**
 * Currency Exchange.
 * <ul>
 *   <li><b>Sell</b>: everything the exchange accepts, with how many you carry. Pick an item, choose an
 *       amount, and get coins or wallet money. "Sell everything" sells all of it at once.</li>
 *   <li><b>Buy</b> (when two-way exchange is on): buy any amount, paying with coins or the wallet.</li>
 *   <li><b>Wallet</b>: deposit the coins you carry, or withdraw wallet money as coins.</li>
 *   <li><b>Manage</b> (operators): search, edit and remove rates, and switch buying on or off.</li>
 * </ul>
 */
public class CurrencyExchangeMenu extends AbstractContainerMenu {

    private static final String ACTIVE_BG = "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)";
    private static final int SELECTED_ROW = 0x60CC7700;

    private final BlockPos pos;
    private final boolean isOp;
    private boolean twoWay;
    private long wallet;
    /** key = item registry id, value = copper per item */
    private final Map<String, Long> rates = new HashMap<>();

    private enum Tab { SELL, BUY, WALLET, MANAGE }
    private Tab currentTab = Tab.SELL;

    // Shared list state
    private String search = "";
    private boolean onlyHave = true;
    private int listScroll;
    private int listRows = 8;
    @Nullable private String sellSelected;
    @Nullable private String buySelected;
    @Nullable private String manageSelected;
    private int amount = 1;
    private boolean useWallet = false;
    private boolean sellAllArmed;
    @Nullable private String removeArmed;
    private String manageItemText = "";
    private String managePriceText = "";
    private String withdrawText = "";
    /** Coin type picked for withdrawing. */
    private String withdrawCoin = "Gold";

    // Live areas
    @Nullable private UIElement content;
    @Nullable private UIElement tabBar;
    @Nullable private UIElement listArea;
    @Nullable private Label pagerLabel;
    @Nullable private UIElement detailArea;
    private int contentWidth = 480;
    private boolean pendingRefresh;
    private int inventoryHash;

    // Constructors

    public CurrencyExchangeMenu(int containerId, Inventory inv, BlockPos pos) {
        super(ModMenuTypes.CURRENCY_EXCHANGE.get(), containerId);
        this.pos = pos;
        this.isOp = false;
        addInventorySlots(inv);
    }

    public CurrencyExchangeMenu(int containerId, Inventory inv, FriendlyByteBuf buf) {
        super(ModMenuTypes.CURRENCY_EXCHANGE.get(), containerId);
        this.pos = buf.readBlockPos();
        this.isOp = buf.readBoolean();
        this.twoWay = buf.readBoolean();
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) rates.put(buf.readUtf(256), buf.readLong());
        if (pos.equals(BlockPos.ZERO) && isOp) currentTab = Tab.MANAGE; // opened with /faction exchange
        addInventorySlots(inv);
        if (FMLEnvironment.dist == Dist.CLIENT) rebuildClientUi(inv.player);
    }

    private void addInventorySlots(Inventory inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            addSlot(new Slot(inventory, slot, -10000, -10000));
        }
    }

    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }

    @Override public boolean stillValid(Player p) {
        if (pos.equals(BlockPos.ZERO)) return true; // opened from command
        return p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    // Live data

    /** Called by SyncExchangePacket after opening and after every action. */
    public void updateData(Map<String, Long> newRates, boolean newTwoWay, long newWallet) {
        boolean tabsChanged = newTwoWay != twoWay;
        rates.clear();
        rates.putAll(newRates);
        twoWay = newTwoWay;
        wallet = newWallet;
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        if (tabsChanged) {
            if (!twoWay && currentTab == Tab.BUY) currentTab = Tab.SELL;
            rebuildTabs();
            return;
        }
        refresh();
    }

    /**
     * Client only. Kept out of updateData: a dedicated server loads this class to open the menu, and any
     * mention of the client player (LocalPlayer) in a method that isn't client-only crashes it.
     */
    @OnlyIn(Dist.CLIENT)
    private void rebuildTabs() {
        var mc = Minecraft.getInstance();
        if (mc.player != null) { renderTabBar(mc.player); showTab(mc.player); }
    }

    /** Called every client tick by the screen. */
    @OnlyIn(Dist.CLIENT)
    public void clientTick() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int hash = inventoryHash(mc.player);
        if (hash != inventoryHash) { inventoryHash = hash; pendingRefresh = true; }
        if (pendingRefresh && !isTyping()) refresh();
    }

    @OnlyIn(Dist.CLIENT)
    private void refresh() {
        if (isTyping()) { pendingRefresh = true; return; }
        pendingRefresh = false;
        renderList();
        renderDetail();
    }

    @OnlyIn(Dist.CLIENT)
    private boolean isTyping() {
        if (!(this instanceof IModularUIHolderMenu h) || h.getModularUI() == null) return false;
        for (var el = h.getModularUI().getFocusedElement(); el != null; el = el.getParent()) {
            if (el instanceof TextField) return true;
        }
        return false;
    }

    private static int inventoryHash(Player player) {
        int hash = 1;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            hash = hash * 31 + (s.isEmpty() ? 0 : BuiltInRegistries.ITEM.getId(s.getItem()) * 100 + s.getCount());
        }
        return hash;
    }

    // Root UI

    @OnlyIn(Dist.CLIENT)
    public void rebuildClientUi(Player player) {
        if (this instanceof IModularUIHolderMenu h) h.setModularUI(buildUI(player));
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI buildUI(Player player) {
        int[] size = UiKit.panelSize(500, 320, 380, 250);
        int width = size[0], height = size[1];
        contentWidth = width - 16;
        listRows = Math.max(4, (height - 150) / 21);

        var frame = new UIElement();
        frame.layout(l -> l.width(width).height(height).paddingAll(2));
        frame.addClass("preview_bg");
        var root = new UIElement();
        root.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).paddingAll(6).gapAll(5));
        root.addClass("panel_bg");

        var header = new UIElement();
        header.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).height(14));
        var balances = new Label();
        balances.bindDataSource(SupplierDataSource.of(() -> {
            Player me = ClientPlayer.get();
            long coins = me != null ? EconomyManager.countCoinsInInventory(me) : 0;
            return Component.literal("§7Coins §r" + Currency.format(coins) + "   §7Wallet §r" + Currency.format(wallet));
        }));
        balances.lss("horizontal-align", "right");
        balances.layout(l -> l.flex(1));
        header.addChildren(new Label().setText("§6§l⚖ Currency Exchange").layout(l -> l.width(150)), balances);

        tabBar = new UIElement();
        tabBar.layout(l -> l.flexDirection(FlexDirection.ROW).height(20).gapAll(3));
        content = new UIElement();
        content.layout(l -> l.flex(1).flexDirection(FlexDirection.ROW).gapAll(6));

        root.addChildren(header, tabBar, content);
        frame.addChildren(root);
        renderTabBar(player);
        showTab(player);
        inventoryHash = inventoryHash(player);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderTabBar(Player player) {
        if (tabBar == null) return;
        tabBar.clearAllChildren();
        tabBar.addChildren(tabButton(Tab.SELL, "Sell items", player, "§7Turn items into coins or wallet money."));
        if (twoWay) tabBar.addChildren(tabButton(Tab.BUY, "Buy items", player, "§7Spend coins or wallet money on items."));
        tabBar.addChildren(tabButton(Tab.WALLET, "Wallet", player, "§7Deposit coins, or withdraw them."));
        if (isOp) tabBar.addChildren(tabButton(Tab.MANAGE, "§cManage rates", player, "§7Operators: set what the exchange pays."));
    }

    @OnlyIn(Dist.CLIENT)
    private Button tabButton(Tab tab, String text, Player player, String hint) {
        boolean active = tab == currentTab;
        var btn = new Button().setText((active ? "§f§l" : "§7") + text.replaceAll("§.", "")).setOnClick(e -> {
            currentTab = tab;
            listScroll = 0;
            amount = 1;
            sellAllArmed = false;
            removeArmed = null;
            renderTabBar(player);
            showTab(player);
        });
        if (tab == Tab.MANAGE && !active) btn.setText(text);
        btn.layout(l -> l.flex(1).height(20));
        if (active) btn.lss("base-background", ACTIVE_BG);
        tooltip(btn, hint);
        return btn;
    }

    @OnlyIn(Dist.CLIENT)
    private void showTab(Player player) {
        if (content == null) return;
        content.clearAllChildren();
        listArea = null;
        detailArea = null;
        pagerLabel = null;
        switch (currentTab) {
            case WALLET -> buildWalletTab(player);
            default -> buildListTab(player);
        }
    }

    // SELL / BUY / MANAGE: item list on the left, detail card on the right

    private static final int DETAIL_WIDTH = 190;

    @OnlyIn(Dist.CLIENT)
    private void buildListTab(Player player) {
        var left = new UIElement();
        left.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).gapAll(3));

        var filterRow = new UIElement();
        filterRow.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(16));
        var searchField = UiKit.textField("Search items");
        searchField.setValue(search);
        searchField.bindObserver(v -> {
            search = v.trim().toLowerCase(Locale.ROOT);
            listScroll = 0;
            renderList();
        });
        searchField.layout(l -> l.flex(1).height(16));
        tooltip(searchField, "§7Search by item name or id");
        filterRow.addChildren(new Label().setText("§7🔍").layout(l -> l.width(10)), searchField);
        if (currentTab == Tab.SELL) {
            var have = new Button().setText(onlyHave ? "§aItems I have" : "§7All accepted").setOnClick(e -> {
                onlyHave = !onlyHave;
                listScroll = 0;
                showTab(player);
            });
            have.layout(l -> l.width(84).height(16));
            tooltip(have, "§7Show only items you carry,", "§7or everything the exchange accepts.");
            filterRow.addChildren(have);
        }
        left.addChildren(filterRow);

        listArea = new UIElement();
        listArea.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).gapAll(2));
        listArea.addEventListener(UIEvents.MOUSE_WHEEL, ev -> {
            listScroll = Math.max(0, listScroll + (ev.deltaY > 0 ? -1 : 1));
            renderList();
        });
        left.addChildren(listArea);

        var pager = new UIElement();
        pager.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(16));
        pagerLabel = new Label();
        pagerLabel.lss("horizontal-align", "center");
        pagerLabel.layout(l -> l.flex(1));
        var prev = new Button().setText("‹").setOnClick(e -> { listScroll = Math.max(0, listScroll - listRows); renderList(); });
        prev.layout(l -> l.width(20).height(14));
        var next = new Button().setText("›").setOnClick(e -> { listScroll += listRows; renderList(); });
        next.layout(l -> l.width(20).height(14));
        pager.addChildren(prev, pagerLabel, next);
        left.addChildren(pager);

        if (currentTab == Tab.SELL) left.addChildren(sellAllRow(player));

        detailArea = UiKit.background(new UIElement(), UiKit.CARD);
        detailArea.layout(l -> l.width(DETAIL_WIDTH).flexDirection(FlexDirection.COLUMN).gapAll(4).paddingAll(6));

        content.addChildren(left, detailArea);
        renderList();
        renderDetail();
    }

    /** Items for the current tab, filtered and sorted. */
    @OnlyIn(Dist.CLIENT)
    private List<String> visibleItems(Player player) {
        var ids = new ArrayList<String>();
        for (String id : rates.keySet()) {
            if (!search.isEmpty() && !id.contains(search) && !itemName(id).toLowerCase(Locale.ROOT).contains(search)) continue;
            if (currentTab == Tab.SELL && onlyHave && countSellable(player, id) == 0) continue;
            ids.add(id);
        }
        Comparator<String> byName = Comparator.comparing(CurrencyExchangeMenu::itemName, String.CASE_INSENSITIVE_ORDER);
        if (currentTab == Tab.SELL) {
            ids.sort(Comparator.comparingLong((String id) -> -rates.getOrDefault(id, 0L) * countSellable(player, id)).thenComparing(byName));
        } else {
            ids.sort(byName);
        }
        return ids;
    }

    @OnlyIn(Dist.CLIENT)
    private void renderList() {
        if (listArea == null) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Player player = mc.player;
        listArea.clearAllChildren();
        List<String> ids = visibleItems(player);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, ids.size() - listRows)));

        if (ids.isEmpty()) {
            if (rates.isEmpty()) {
                listArea.addChildren(new Label().setText(isOp ? "§7No rates yet. Add one on the right." : "§7The exchange has no rates set yet."));
            } else if (currentTab == Tab.SELL && onlyHave && search.isEmpty()) {
                listArea.addChildren(new Label().setText("§7You carry nothing the exchange accepts."),
                        new Label().setText("§8Click \"Items I have\" to see everything it buys."));
            } else {
                listArea.addChildren(new Label().setText("§7No items match your search."));
            }
        }
        for (int i = listScroll; i < Math.min(ids.size(), listScroll + listRows); i++) {
            listArea.addChildren(itemRow(player, ids.get(i)));
        }
        if (pagerLabel != null) {
            pagerLabel.setText(ids.isEmpty() ? "" : "§8" + (listScroll + 1) + "–" + Math.min(ids.size(), listScroll + listRows) + " of " + ids.size());
        }
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement itemRow(Player player, String id) {
        long rate = rates.getOrDefault(id, 0L);
        Item item = resolve(id);
        boolean selected = id.equals(selectedForTab());
        var row = UiKit.background(new UIElement(), selected ? SELECTED_ROW : UiKit.ROW);
        row.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(5).height(19).paddingHorizontal(3));
        var icon = new ItemSlot();
        icon.setItem(item != null ? new ItemStack(item) : ItemStack.EMPTY);
        icon.layout(l -> l.width(18).height(18));
        row.addChildren(icon, new Label().setText((selected ? "§e" : "§f") + itemName(id)).layout(l -> l.flex(1)));

        switch (currentTab) {
            case SELL -> {
                int have = countSellable(player, id);
                row.addChildren(UiKit.rightLabel(have > 0 ? "§7×" + have + "  §a" + Currency.format(rate * have).replaceAll("§.", "") : "§8none"),
                        UiKit.rightLabel("§8" + Currency.format(rate).replaceAll("§.", "") + " ea"));
            }
            case BUY -> row.addChildren(UiKit.rightLabel("§a" + Currency.format(rate).replaceAll("§.", "") + " §8each"));
            case MANAGE -> {
                row.addChildren(UiKit.rightLabel("§a" + Currency.format(rate).replaceAll("§.", "")));
                boolean armed = id.equals(removeArmed);
                var remove = new Button().setText(armed ? "§c§lSure?" : "§c✕").setOnClick(e -> {
                    if (id.equals(removeArmed)) {
                        removeArmed = null;
                        PacketDistributor.sendToServer(ExchangeActionPacket.removeRate(id));
                        if (id.equals(manageSelected)) manageSelected = null;
                    } else {
                        removeArmed = id;
                    }
                    renderList();
                });
                remove.layout(l -> l.width(armed ? 40 : 16).height(14));
                tooltip(remove, "§7Remove this rate (click twice).");
                row.addChildren(remove);
            }
            default -> { }
        }
        UiKit.onClick(row, ev -> {
            if (currentTab == Tab.MANAGE) {
                manageSelected = id;
                manageItemText = id;
                managePriceText = Currency.format(rate).replaceAll("§.", "").replace(" ", "");
            } else if (currentTab == Tab.BUY) {
                buySelected = id;
                amount = 1;
            } else {
                sellSelected = id;
                amount = Math.max(1, countSellable(player, id));
            }
            renderList();
            renderDetail();
        });
        tooltip(row, "§f" + itemName(id), "§8" + id, "§7Rate: §a" + Currency.format(rate).replaceAll("§.", "") + " §7each");
        return row;
    }

    @Nullable
    private String selectedForTab() {
        return switch (currentTab) {
            case SELL -> sellSelected;
            case BUY -> buySelected;
            case MANAGE -> manageSelected;
            default -> null;
        };
    }

    // Detail card

    @OnlyIn(Dist.CLIENT)
    private void renderDetail() {
        if (detailArea == null) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        detailArea.clearAllChildren();
        switch (currentTab) {
            case SELL -> sellDetail(mc.player);
            case BUY -> buyDetail(mc.player);
            case MANAGE -> manageDetail(mc.player);
            default -> { }
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void sellDetail(Player player) {
        if (sellSelected == null || !rates.containsKey(sellSelected)) {
            sellSelected = null;
            long total = totalSellableValue(player);
            detailArea.addChildren(new Label().setText("§6§lSell"),
                    new Label().setText("§7Pick an item on the left."),
                    new Label().setText("§7Everything you carry is worth"),
                    new Label().setText("§a§l" + Currency.format(total).replaceAll("§.", "")),
                    new Label().setText("§8Enchanted, renamed and damaged"),
                    new Label().setText("§8items are never sold."));
            return;
        }
        String id = sellSelected;
        long rate = rates.get(id);
        int have = countSellable(player, id);
        detailArea.addChildren(itemHeader(id), new Label().setText("§7Pays §a" + Currency.format(rate).replaceAll("§.", "") + " §7each"),
                new Label().setText("§7You have §f" + have));
        if (have <= 0) {
            detailArea.addChildren(new Label().setText("§8You don't carry any right now."));
            return;
        }
        amount = Math.max(1, Math.min(amount, have));
        var total = new Label();
        Runnable updateTotal = () -> total.setText("§7Total §a§l" + Currency.format(rate * amount).replaceAll("§.", ""));
        int stack = resolve(id) != null ? resolve(id).getDefaultMaxStackSize() : 64;
        detailArea.addChildren(amountRow(have, updateTotal), quickAmounts(have, updateTotal,
                new int[]{1, Math.min(stack, have), have}, new String[]{"1", "Stack", "All"}), total, payoutRow("§7Pay to"));
        updateTotal.run();
        var sell = new Button().setText("§a✔ Sell").setOnClick(e ->
                PacketDistributor.sendToServer(ExchangeActionPacket.sell(id, amount, useWallet)));
        sell.layout(l -> l.height(20));
        detailArea.addChildren(sell);
    }

    @OnlyIn(Dist.CLIENT)
    private void buyDetail(Player player) {
        if (buySelected == null || !rates.containsKey(buySelected)) {
            buySelected = null;
            detailArea.addChildren(new Label().setText("§6§lBuy"), new Label().setText("§7Pick an item on the left."),
                    new Label().setText("§8Pay with the coins you carry"), new Label().setText("§8or with your wallet."));
            return;
        }
        String id = buySelected;
        long rate = rates.get(id);
        long funds = useWallet ? wallet : EconomyManager.countCoinsInInventory(player);
        int max = (int) Math.max(0, Math.min(ExchangeActionPacket.MAX_BUY, funds / Math.max(1, rate)));
        detailArea.addChildren(itemHeader(id), new Label().setText("§7Costs §a" + Currency.format(rate).replaceAll("§.", "") + " §7each"),
                new Label().setText("§7You can afford §f" + max));
        int limit = Math.max(1, max);
        amount = Math.max(1, Math.min(amount, ExchangeActionPacket.MAX_BUY));
        var total = new Label();
        Runnable updateTotal = () -> total.setText((rate * amount <= funds ? "§7Total §a§l" : "§7Total §c§l")
                + Currency.format(rate * amount).replaceAll("§.", ""));
        detailArea.addChildren(amountRow(ExchangeActionPacket.MAX_BUY, updateTotal),
                quickAmounts(ExchangeActionPacket.MAX_BUY, updateTotal, new int[]{1, 16, 64, limit}, new String[]{"1", "16", "64", "Max"}),
                total, payoutRow("§7Pay with"));
        updateTotal.run();
        var buy = new Button().setText("§a✔ Buy").setOnClick(e ->
                PacketDistributor.sendToServer(ExchangeActionPacket.buy(id, amount, useWallet)));
        buy.layout(l -> l.height(20));
        detailArea.addChildren(buy);
    }

    @OnlyIn(Dist.CLIENT)
    private void manageDetail(Player player) {
        var twoWayRow = new UIElement();
        twoWayRow.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(16));
        var toggle = UiKit.toggle();
        toggle.setOn(twoWay);
        toggle.setOnToggleChanged(on -> PacketDistributor.sendToServer(ExchangeActionPacket.setTwoWay(on)));
        toggle.layout(l -> l.width(14).height(14));
        twoWayRow.addChildren(toggle, new Label().setText("§7Players can buy items too").layout(l -> l.flex(1)));
        tooltip(twoWayRow, "§7When on, the Buy tab lets players", "§7buy items back at the same rates.");
        detailArea.addChildren(new Label().setText(manageSelected != null ? "§6§lEdit rate" : "§6§lAdd a rate"), twoWayRow);

        // Item
        var preview = new ItemSlot();
        preview.layout(l -> l.width(18).height(18));
        var itemStatus = new Label();
        var priceStatus = new Label();
        Runnable validate = () -> {
            Item item = resolveLoose(manageItemText);
            preview.setItem(item != null ? new ItemStack(item) : ItemStack.EMPTY);
            itemStatus.setText(manageItemText.isBlank() ? "§8Type an id, use Held or pick below"
                    : item == null ? "§c✖ Unknown item"
                    : EconomyManager.isCoin(item) ? "§c✖ Coins can't have a rate"
                    : rates.containsKey(BuiltInRegistries.ITEM.getKey(item).toString())
                        ? "§e✎ Currently " + Currency.format(rates.get(BuiltInRegistries.ITEM.getKey(item).toString())).replaceAll("§.", "")
                        : "§a✔ " + item.getDescription().getString());
            long price = Currency.parse(managePriceText.replace(" ", ""));
            priceStatus.setText(managePriceText.isBlank() ? "§8e.g. 5g, 2g50s, 1p, 300"
                    : price > 0 ? "§7= §r" + Currency.format(price) + " §7each" : "§c✖ Not a valid price");
        };
        var itemRow = new UIElement();
        itemRow.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(3).height(18));
        var itemField = UiKit.textField("Item id, e.g. diamond");
        itemField.setValue(manageItemText);
        itemField.bindObserver(v -> { manageItemText = v.trim(); validate.run(); });
        itemField.layout(l -> l.flex(1).height(16));
        var held = new Button().setText("§7Held").setOnClick(e -> {
            ItemStack hand = player.getMainHandItem();
            if (hand.isEmpty()) return;
            pickForManage(BuiltInRegistries.ITEM.getKey(hand.getItem()).toString());
        });
        held.layout(l -> l.width(34).height(16));
        tooltip(held, "§7Use the item in your main hand.");
        itemRow.addChildren(preview, itemField, held);
        detailArea.addChildren(itemRow, itemStatus);

        // Price
        var priceRow = new UIElement();
        priceRow.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(16));
        var priceField = UiKit.textField("Price, e.g. 2g50s");
        priceField.setValue(managePriceText);
        priceField.bindObserver(v -> { managePriceText = v; validate.run(); });
        priceField.layout(l -> l.flex(1).height(16));
        priceRow.addChildren(new Label().setText("§7Pays").layout(l -> l.width(28)), priceField);
        detailArea.addChildren(priceRow, priceStatus);

        var buttons = new UIElement();
        buttons.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).height(18));
        var save = new Button().setText("§a✔ Save rate").setOnClick(e -> {
            Item item = resolveLoose(manageItemText);
            long price = Currency.parse(managePriceText.replace(" ", ""));
            if (item == null || price <= 0 || EconomyManager.isCoin(item)) return;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            PacketDistributor.sendToServer(ExchangeActionPacket.setRate(id, price));
            manageSelected = id;
        });
        save.layout(l -> l.flex(1).height(18));
        var clear = new Button().setText("§7New").setOnClick(e -> {
            manageSelected = null;
            manageItemText = "";
            managePriceText = "";
            renderList();
            renderDetail();
        });
        clear.layout(l -> l.width(36).height(18));
        tooltip(clear, "§7Clear the form to add another rate.");
        buttons.addChildren(save, clear);
        detailArea.addChildren(buttons);

        // Pick from inventory
        detailArea.addChildren(new Label().setText("§8Or click an item you carry:"));
        int[][] rowsDef = {{9, 18}, {18, 27}, {27, 36}, {0, 9}};
        for (int[] range : rowsDef) {
            var gridRow = new UIElement();
            gridRow.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(1).height(18));
            for (int slot = range[0]; slot < range[1]; slot++) {
                final int s = slot;
                var cell = new ItemSlot();
                cell.layout(l -> l.width(18).height(18));
                cell.setItem(player.getInventory().getItem(s));
                UiKit.onClick(cell, ev -> {
                    ItemStack stack = player.getInventory().getItem(s);
                    if (!stack.isEmpty()) pickForManage(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                });
                gridRow.addChildren(cell);
            }
            detailArea.addChildren(gridRow);
        }
        validate.run();
    }

    @OnlyIn(Dist.CLIENT)
    private void pickForManage(String id) {
        manageItemText = id;
        manageSelected = rates.containsKey(id) ? id : null;
        if (rates.containsKey(id)) managePriceText = Currency.format(rates.get(id)).replaceAll("§.", "").replace(" ", "");
        renderList();
        renderDetail();
    }

    // Detail helpers

    @OnlyIn(Dist.CLIENT)
    private UIElement itemHeader(String id) {
        var row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(5).height(20));
        var icon = new ItemSlot();
        Item item = resolve(id);
        icon.setItem(item != null ? new ItemStack(item) : ItemStack.EMPTY);
        icon.layout(l -> l.width(18).height(18));
        row.addChildren(icon, new Label().setText("§f§l" + itemName(id)).layout(l -> l.flex(1)));
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement amountRow(int max, Runnable onChange) {
        var row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(3).height(16));
        var field = UiKit.textField("");
        field.setValue(Integer.toString(amount));
        field.bindObserver(v -> {
            try { amount = Math.max(1, Math.min(max, Integer.parseInt(v.trim()))); }
            catch (NumberFormatException ignored) { }
            onChange.run();
        });
        field.layout(l -> l.flex(1).height(16));
        var minus = new Button().setText("-").setOnClick(e -> { amount = Math.max(1, amount - 1); field.setValue(Integer.toString(amount)); onChange.run(); });
        minus.layout(l -> l.width(16).height(16));
        var plus = new Button().setText("+").setOnClick(e -> { amount = Math.min(max, amount + 1); field.setValue(Integer.toString(amount)); onChange.run(); });
        plus.layout(l -> l.width(16).height(16));
        row.addChildren(new Label().setText("§7Amount").layout(l -> l.width(40)), minus, field, plus);
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement quickAmounts(int max, Runnable onChange, int[] values, String[] labels) {
        var row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3).height(16));
        for (int i = 0; i < values.length; i++) {
            int value = Math.max(1, Math.min(max, values[i]));
            var btn = new Button().setText("§7" + labels[i]).setOnClick(e -> { amount = value; renderDetail(); onChange.run(); });
            btn.layout(l -> l.flex(1).height(16));
            tooltip(btn, "§7" + value);
            row.addChildren(btn);
        }
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement payoutRow(String label) {
        var row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(3).height(16));
        var coins = new Button().setText(useWallet ? "§7Coins" : "§f§lCoins").setOnClick(e -> { useWallet = false; renderDetail(); });
        coins.layout(l -> l.flex(1).height(16));
        if (!useWallet) coins.lss("base-background", ACTIVE_BG);
        var walletBtn = new Button().setText(useWallet ? "§f§lWallet" : "§7Wallet").setOnClick(e -> { useWallet = true; renderDetail(); });
        walletBtn.layout(l -> l.flex(1).height(16));
        if (useWallet) walletBtn.lss("base-background", ACTIVE_BG);
        tooltip(coins, "§7Coin items in your inventory.");
        tooltip(walletBtn, "§7Your wallet balance (no items needed).");
        row.addChildren(new Label().setText(label).layout(l -> l.width(40)), coins, walletBtn);
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement sellAllRow(Player player) {
        var row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(18));
        long total = totalSellableValue(player);
        var btn = new Button().setText(total <= 0 ? "§8Sell everything"
                : sellAllArmed ? "§a§lConfirm: sell all for " + Currency.format(total).replaceAll("§.", "")
                : "§eSell everything §7(" + Currency.format(total).replaceAll("§.", "") + ")");
        btn.setOnClick(e -> {
            if (totalSellableValue(player) <= 0) return;
            if (sellAllArmed) {
                sellAllArmed = false;
                PacketDistributor.sendToServer(ExchangeActionPacket.sellAll(useWallet));
            } else {
                sellAllArmed = true;
            }
            showTab(player);
        });
        btn.layout(l -> l.flex(1).height(18));
        tooltip(btn, "§7Sell every accepted item you carry", "§7(click twice). Paid as " + (useWallet ? "wallet money." : "coins."),
                "§8Enchanted, renamed and damaged items are kept.");
        row.addChildren(btn);
        return row;
    }

    // WALLET

    @OnlyIn(Dist.CLIENT)
    private void buildWalletTab(Player player) {
        var coinsCard = UiKit.background(new UIElement(), UiKit.CARD);
        coinsCard.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).gapAll(4).paddingAll(8));
        coinsCard.addChildren(new Label().setText("§6§lCoins you carry"),
                new Label().setText("§8Coins are items: they drop when you die."));
        Item[] coinItems = {ModItems.PLATINUM_COIN.get(), ModItems.GOLD_COIN.get(), ModItems.SILVER_COIN.get(), ModItems.COPPER_COIN.get()};
        long[] coinValues = {Currency.COPPER_PER_PLATINUM, Currency.COPPER_PER_GOLD, Currency.COPPER_PER_SILVER, 1};
        for (int i = 0; i < coinItems.length; i++) {
            Item coin = coinItems[i];
            long value = coinValues[i];
            var row = UiKit.background(new UIElement(), UiKit.ROW);
            row.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(5).height(20).paddingHorizontal(2));
            var icon = new ItemSlot();
            icon.setItem(new ItemStack(coin));
            icon.layout(l -> l.width(18).height(18));
            var count = new Label();
            count.bindDataSource(SupplierDataSource.of(() -> {
                int n = player.getInventory().countItem(coin);
                return Component.literal((n > 0 ? "§f" : "§8") + "×" + n);
            }));
            count.lss("horizontal-align", "right").layout(l -> l.width(34));
            var worth = new Label();
            worth.bindDataSource(SupplierDataSource.of(() -> {
                int n = player.getInventory().countItem(coin);
                return Component.literal(n > 0 ? "§a" + Currency.format(n * value).replaceAll("§.", "") : "§8-");
            }));
            worth.lss("horizontal-align", "right").layout(l -> l.width(46));
            row.addChildren(icon, new Label().setText("§7" + coin.getDescription().getString()).layout(l -> l.flex(1)), count, worth);
            tooltip(row, "§f" + coin.getDescription().getString(), "§7Worth " + Currency.format(value).replaceAll("§.", "") + " each.");
            coinsCard.addChildren(row);
        }
        var coinTotal = new Label();
        coinTotal.bindDataSource(SupplierDataSource.of(() ->
                Component.literal("§7Total §a§l" + Currency.format(EconomyManager.countCoinsInInventory(player)).replaceAll("§.", ""))));
        var rates = new Label().setText("§8100c = 1s · 100s = 1g · 100g = 1p");
        var deposit = new Button().setText("§a⬇ Deposit all coins to wallet").setOnClick(e ->
                PacketDistributor.sendToServer(ExchangeActionPacket.depositCoins()));
        deposit.layout(l -> l.height(20));
        tooltip(deposit, "§7Turns every coin you carry into wallet money.");
        coinsCard.addChildren(coinTotal, rates, new UIElement().layout(l -> l.flex(1)), deposit);

        var walletCard = UiKit.background(new UIElement(), UiKit.CARD);
        walletCard.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).gapAll(4).paddingAll(8));
        var balance = new Label();
        balance.bindDataSource(SupplierDataSource.of(() -> Component.literal("§a§l" + Currency.format(wallet).replaceAll("§.", ""))));
        walletCard.addChildren(new Label().setText("§6§lYour wallet"), balance,
                new Label().setText("§8Safe money: never dropped or stolen."),
                new Label().setText("§8Pays for the market, deeds and the vault."),
                new UIElement().layout(l -> l.height(4)),
                new Label().setText("§7§lWithdraw as coins"));

        // How many of which coin
        var status = new Label();
        var withdraw = new Button();
        Runnable validate = () -> {
            long count = withdrawCount();
            long value = count * Currency.perCoin(withdrawCoin);
            long max = wallet / Currency.perCoin(withdrawCoin);
            status.setText(withdrawText.isBlank() ? "§8Type how many, or pick one below."
                    : count <= 0 ? "§c✖ Type a whole number above 0"
                    : count > max ? "§c✖ Your wallet has enough for " + max + " " + withdrawCoin
                    : "§7= §r" + Currency.format(value));
            withdraw.setText(count > 0 && count <= max ? "§e⬆ Withdraw " + count + " " + withdrawCoin : "§8⬆ Withdraw");
        };
        var amountRow = new UIElement();
        amountRow.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3).height(16));
        var field = UiKit.textField("How many");
        field.setValue(withdrawText);
        field.bindObserver(v -> { withdrawText = v; validate.run(); });
        field.layout(l -> l.flex(1).height(16));
        var coinPick = new Selector<String>();
        coinPick.setCandidates(Currency.COINS);
        coinPick.setValue(withdrawCoin);
        coinPick.setOnValueChanged(v -> { withdrawCoin = v; validate.run(); });
        coinPick.layout(l -> l.width(68).height(16));
        tooltip(coinPick, "§fCoin type", "§7100 Copper = 1 Silver", "§7100 Silver = 1 Gold", "§7100 Gold = 1 Platinum");
        amountRow.addChildren(field, coinPick);

        var quick = new UIElement();
        quick.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3).height(16));
        for (String preset : new String[]{"1", "10", "64", "Max"}) {
            var btn = new Button().setText("§7" + preset).setOnClick(e -> {
                withdrawText = preset.equals("Max") ? Long.toString(wallet / Currency.perCoin(withdrawCoin)) : preset;
                showTab(player);
            });
            btn.layout(l -> l.flex(1).height(16));
            quick.addChildren(btn);
        }
        tooltip(quick, "§7Max: as many of that coin as your wallet can pay for.");
        withdraw.setOnClick(e -> {
            long count = withdrawCount();
            if (count <= 0 || count * Currency.perCoin(withdrawCoin) > wallet) return;
            PacketDistributor.sendToServer(ExchangeActionPacket.withdrawCoins(withdrawCoin, (int) count));
            withdrawText = "";
            showTab(player);
        });
        withdraw.layout(l -> l.height(20));
        tooltip(withdraw, "§7Takes that many coins of that type out of your wallet.",
                "§8Any that don't fit in your inventory stay in the wallet.");
        walletCard.addChildren(amountRow, status, quick, new UIElement().layout(l -> l.flex(1)), withdraw);
        validate.run();

        content.addChildren(coinsCard, walletCard);
    }

    // Utilities

    /** The number typed in the withdraw box (up to a full inventory of coins), or -1 if it isn't a whole number. */
    private long withdrawCount() {
        try {
            long n = Long.parseLong(withdrawText.trim().replace(",", ""));
            return n <= 0 ? -1 : Math.min(n, 64L * 36);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int countSellable(Player player, String itemId) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ExchangeManager.isSellable(stack) && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private long totalSellableValue(Player player) {
        long total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!ExchangeManager.isSellable(stack)) continue;
            total += rates.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), 0L) * stack.getCount();
        }
        return total;
    }

    @Nullable
    private static Item resolve(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id == null ? "" : id);
        Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }

    /** Like {@link #resolve} but accepts ids without "minecraft:". */
    @Nullable
    private static Item resolveLoose(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = raw.trim().toLowerCase(Locale.ROOT);
        return resolve(id.contains(":") ? id : "minecraft:" + id);
    }

    private static String itemName(String id) {
        Item item = resolve(id);
        return item != null ? item.getDescription().getString() : id;
    }

    private static void tooltip(UIElement element, String... lines) {
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> {
            var tips = HoverTooltips.empty();
            for (String line : lines) tips = tips.append(Component.literal(line));
            ev.hoverTooltips = tips;
        });
    }
}
