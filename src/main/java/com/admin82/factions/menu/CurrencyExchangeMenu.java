package com.admin82.factions.menu;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.network.packet.ExchangeActionPacket;
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
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;

import java.util.*;

/**
 * Currency Exchange block UI.
 *
 * Tab "Exchange" (all players):
 *   - Left: scrollable rate list.
 *   - Right: inventory grid. Click an item to select it.
 *   - Bottom strip: selected item's exchange value + Confirm button.
 *
 * Tab "Manage" (op-only):
 *   - Left: rate list with [✕] remove buttons.
 *   - Right: inventory grid to pick an item for rate setting.
 *   - Bottom: selected item + number field + coin dropdown + [Save Rate] button.
 */
public class CurrencyExchangeMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    private final boolean  isOp;
    private final boolean  bothWaysExchange;

    /** Rates received from server at open time. key = registry id, value = copper/item */
    private final Map<String, Long> rates = new HashMap<>();

    // Exchange tab state
    private int        exScroll    = 0;
    private int        selectedSlot = -1;
    private UIElement  exchangeConfirmArea;

    // Buy tab state
    private int        buyScroll = 0;
    private UIElement  buyRateArea;

    // Manage tab state
    private int        mgScroll    = 0;
    private ItemStack  mgSelected  = ItemStack.EMPTY;
    private UIElement  mgRateListArea;
    private UIElement  mgConfirmArea;

    // Tabs
    private enum Tab { EXCHANGE, BUY, MANAGE }
    private Tab currentTab = Tab.EXCHANGE;

    private static final int RATE_ROWS = 7;

    // ── Constructors ──────────────────────────────────────────────────────────

    public CurrencyExchangeMenu(int containerId, Inventory inv, BlockPos pos) {
        super(ModMenuTypes.CURRENCY_EXCHANGE.get(), containerId);
        this.pos  = pos;
        this.isOp = false;
        this.bothWaysExchange = false;
        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu h) h.setModularUI(buildUI(inv.player));
    }

    public CurrencyExchangeMenu(int containerId, Inventory inv, FriendlyByteBuf buf) {
        super(ModMenuTypes.CURRENCY_EXCHANGE.get(), containerId);
        this.pos  = buf.readBlockPos();
        this.isOp = buf.readBoolean();
        this.bothWaysExchange = buf.readBoolean();
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) rates.put(buf.readUtf(256), buf.readLong());
        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu h) h.setModularUI(buildUI(inv.player));
    }

    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player p) {
        if (pos.equals(BlockPos.ZERO)) return true; // opened from command
        return p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    // ── Root UI ───────────────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    private ModularUI buildUI(Player player) {
        var frame = new UIElement();
        frame.layout(l -> l.width(460).height(340).paddingAll(2));
        frame.addClass("preview_bg");

        var root = new UIElement();
        root.layout(l -> l.width(456).height(336).flexDirection(YogaFlexDirection.COLUMN));
        root.addClass("panel_bg");

        // Title row
        var titleRow = new UIElement();
        titleRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(22).paddingHorizontal(8).paddingTop(4));
        titleRow.addChildren(new Label().setText("§6§lCurrency Exchange").layout(l -> l.flex(1)));

        // Tab bar
        var tabBar = new UIElement();
        tabBar.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(24).paddingHorizontal(4).gapAll(2));

        // Content area
        var content = new UIElement();
        content.layout(l -> l.flex(1));

        UIElement exPanel  = buildExchangePanel(player);
        UIElement buyPanel = bothWaysExchange ? buildBuyPanel(player) : null;
        UIElement mgPanel  = isOp ? buildManagePanel(player) : null;

        content.addChildren(switch (currentTab) {
            case BUY -> buyPanel != null ? buyPanel : exPanel;
            case MANAGE -> mgPanel != null ? mgPanel : exPanel;
            default -> exPanel;
        });
        buildTabBar(tabBar, exPanel, buyPanel, mgPanel, content, player);

        root.addChildren(titleRow, tabBar, content);
        frame.addChildren(root);
        return ModularUI.of(
                UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)),
                player);
    }

    private void buildTabBar(UIElement bar, UIElement exPanel, UIElement buyPanel, UIElement mgPanel,
                              UIElement content, Player player) {
        bar.clearAllChildren();

        var exBtn = new Button().setText("Exchange for Money");
        exBtn.setOnClick(e -> { currentTab = Tab.EXCHANGE; content.clearAllChildren(); content.addChildren(exPanel); buildTabBar(bar, exPanel, buyPanel, mgPanel, content, player); });
        if (currentTab == Tab.EXCHANGE) exBtn.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
        exBtn.layout(l -> l.flex(1).height(22));
        bar.addChildren(exBtn);

        if (buyPanel != null) {
            var buyBtn = new Button().setText("Exchange for Items");
            buyBtn.setOnClick(e -> { currentTab = Tab.BUY; content.clearAllChildren(); content.addChildren(buyPanel); buildTabBar(bar, exPanel, buyPanel, mgPanel, content, player); });
            if (currentTab == Tab.BUY) buyBtn.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
            buyBtn.layout(l -> l.flex(1).height(22));
            bar.addChildren(buyBtn);
        }

        if (isOp && mgPanel != null) {
            var mgBtn = new Button().setText("§cManage Rates");
            mgBtn.setOnClick(e -> { currentTab = Tab.MANAGE; content.clearAllChildren(); content.addChildren(mgPanel); buildTabBar(bar, exPanel, buyPanel, mgPanel, content, player); });
            if (currentTab == Tab.MANAGE) mgBtn.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
            mgBtn.layout(l -> l.flex(1).height(22));
            bar.addChildren(mgBtn);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXCHANGE TAB
    // ══════════════════════════════════════════════════════════════════════════

    private UIElement buildExchangePanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.ROW).gapAll(6).paddingAll(6));

        // ── Left: rate list ──
        var left = new UIElement();
        left.layout(l -> l.width(190).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        left.addChildren(new Label().setText("§6§lExchange Rates"));
        left.addChildren(new Label().setText("§8Item › coins per unit"));

        var exRateArea = new UIElement();
        exRateArea.layout(l -> l.flex(1).width(190));
        fillExRateList(exRateArea);

        var scrollRow = new UIElement();
        scrollRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4));
        scrollRow.addChildren(
                new Button().setText("▲").setOnClick(e -> { exScroll = Math.max(0, exScroll - 1); exRateArea.clearAllChildren(); fillExRateList(exRateArea); }).layout(l -> l.width(20)),
                new Button().setText("▼").setOnClick(e -> { exScroll++; exRateArea.clearAllChildren(); fillExRateList(exRateArea); }).layout(l -> l.width(20))
        );
        left.addChildren(exRateArea, scrollRow);

        // ── Right: inventory + confirm area ──
        var right = new UIElement();
        right.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        right.addChildren(new Label().setText("§6§lInventory"));
        right.addChildren(new Label().setText("§8Click an item to select it."));

        exchangeConfirmArea = new UIElement();
        exchangeConfirmArea.layout(l -> l.width(240).height(52));
        refreshExConfirmArea(player);

        buildInvGrid(right, player, false);
        right.addChildren(exchangeConfirmArea);

        panel.addChildren(left, right);
        return panel;
    }

    private void fillExRateList(UIElement area) {
        var sorted = rates.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        if (sorted.isEmpty()) { area.addChildren(new Label().setText("§7No rates configured.")); return; }
        for (int i = 0; i < RATE_ROWS; i++) {
            int idx = i + exScroll;
            if (idx >= sorted.size()) break;
            var entry = sorted.get(idx);
            String displayName = resolveItemName(entry.getKey());
            var row = new UIElement();
            row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(18).width(190));
            row.addChildren(
                    new Label().setText("§f" + displayName).layout(l -> l.flex(1)),
                    new Label().setText("§a" + Currency.format(entry.getValue())).layout(l -> l.width(60))
            );
            area.addChildren(row);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void refreshExConfirmArea(Player player) {
        exchangeConfirmArea.clearAllChildren();
        exchangeConfirmArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(4).paddingTop(4));
        if (selectedSlot < 0) {
            exchangeConfirmArea.addChildren(new Label().setText("§8Select an item above to see its value."));
            return;
        }
        var mc = Minecraft.getInstance();
        ItemStack stack = mc.player != null ? mc.player.getInventory().getItem(selectedSlot) : ItemStack.EMPTY;
        if (stack.isEmpty()) { exchangeConfirmArea.addChildren(new Label().setText("§8Slot is empty.")); return; }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        long rate = rates.getOrDefault(itemId, 0L);

        var itemRow = new UIElement();
        itemRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).height(22));
        var icon = new ItemSlot(); icon.setItem(stack); icon.layout(l -> l.width(20).height(20));
        itemRow.addChildren(icon, new Label().setText("§f" + stack.getHoverName().getString() + " §7×" + stack.getCount()));
        exchangeConfirmArea.addChildren(itemRow);

        if (rate <= 0) {
            exchangeConfirmArea.addChildren(new Label().setText("§cNo exchange rate for this item."));
        } else {
            long total = rate * stack.getCount();
            exchangeConfirmArea.addChildren(new Label().setText(
                    "§7Value: §a" + Currency.format(total) + "§8 (§7" + Currency.format(rate) + " §8each)"));
            final int slot = selectedSlot;
            var confirmBtn = new Button().setText("§a✓ Confirm Exchange");
            confirmBtn.setOnClick(e -> {
                PacketDistributor.sendToServer(ExchangeActionPacket.exchange(slot));
                selectedSlot = -1;
                refreshExConfirmArea(player);
            });
            confirmBtn.layout(l -> l.width(180).height(22));
            exchangeConfirmArea.addChildren(confirmBtn);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BUY TAB
    // ══════════════════════════════════════════════════════════════════════════

    private UIElement buildBuyPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(6).paddingAll(6));

        panel.addChildren(new Label().setText("§6§lBuy Items"));
        panel.addChildren(new Label().setText("§8Spend coins from your inventory for configured exchange items."));

        buyRateArea = new UIElement();
        buyRateArea.layout(l -> l.flex(1).width(432));
        fillBuyRateList();

        var scrollRow = new UIElement();
        scrollRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4));
        scrollRow.addChildren(
                new Button().setText("▲").setOnClick(e -> { buyScroll = Math.max(0, buyScroll - 1); fillBuyRateList(); }).layout(l -> l.width(20)),
                new Button().setText("▼").setOnClick(e -> { buyScroll++; fillBuyRateList(); }).layout(l -> l.width(20))
        );
        panel.addChildren(buyRateArea, scrollRow);
        return panel;
    }

    private void fillBuyRateList() {
        if (buyRateArea == null) return;
        buyRateArea.clearAllChildren();
        var sorted = rates.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        if (sorted.isEmpty()) { buyRateArea.addChildren(new Label().setText("§7No rates configured.")); return; }
        for (int i = 0; i < RATE_ROWS + 4; i++) {
            int idx = i + buyScroll;
            if (idx >= sorted.size()) break;
            var entry = sorted.get(idx);
            String displayName = resolveItemName(entry.getKey());
            String finalId = entry.getKey();
            var row = new UIElement();
            row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(22).width(432));
            var buyBtn = new Button().setText("§aBuy 1");
            buyBtn.setOnClick(e -> PacketDistributor.sendToServer(ExchangeActionPacket.buyItem(finalId)));
            buyBtn.layout(l -> l.width(64).height(20));
            row.addChildren(
                    new Label().setText("§f" + displayName).layout(l -> l.flex(1)),
                    new Label().setText("§a" + Currency.format(entry.getValue())).layout(l -> l.width(90)),
                    buyBtn
            );
            buyRateArea.addChildren(row);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MANAGE TAB (op only)
    // ══════════════════════════════════════════════════════════════════════════

    private UIElement buildManagePanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.ROW).gapAll(6).paddingAll(6));

        // ── Left: rate list with remove buttons ──
        var left = new UIElement();
        left.layout(l -> l.width(190).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        left.addChildren(new Label().setText("§c§lManage Rates"));
        left.addChildren(new Label().setText("§8[✕] to remove a rate."));

        mgRateListArea = new UIElement();
        mgRateListArea.layout(l -> l.flex(1).width(190));
        fillMgRateList();

        var scrollRow = new UIElement();
        scrollRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4));
        scrollRow.addChildren(
                new Button().setText("▲").setOnClick(e -> { mgScroll = Math.max(0, mgScroll - 1); fillMgRateList(); }).layout(l -> l.width(20)),
                new Button().setText("▼").setOnClick(e -> { mgScroll++; fillMgRateList(); }).layout(l -> l.width(20))
        );
        left.addChildren(mgRateListArea, scrollRow);

        // ── Right: inventory + set-rate form ──
        var right = new UIElement();
        right.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        right.addChildren(new Label().setText("§6§lSet a Rate"));
        right.addChildren(new Label().setText("§81. Click an item below to select it."));

        mgConfirmArea = new UIElement();
        mgConfirmArea.layout(l -> l.width(240).height(68));
        refreshMgConfirmArea(player);

        buildInvGrid(right, player, true);
        right.addChildren(mgConfirmArea);

        panel.addChildren(left, right);
        return panel;
    }

    private void fillMgRateList() {
        if (mgRateListArea == null) return;
        mgRateListArea.clearAllChildren();
        var sorted = rates.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        if (sorted.isEmpty()) { mgRateListArea.addChildren(new Label().setText("§7No rates yet.")); return; }
        for (int i = 0; i < RATE_ROWS; i++) {
            int idx = i + mgScroll;
            if (idx >= sorted.size()) break;
            var entry = sorted.get(idx);
            String displayName = resolveItemName(entry.getKey());
            String finalId = entry.getKey();
            var row = new UIElement();
            row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4).height(18).width(190));
            var removeBtn = new Button().setText("§c✕");
            removeBtn.setOnClick(e -> {
                PacketDistributor.sendToServer(ExchangeActionPacket.removeRate(finalId));
                rates.remove(finalId);
                fillMgRateList();
            });
            removeBtn.layout(l -> l.width(18));
            row.addChildren(
                    removeBtn,
                    new Label().setText("§f" + displayName).layout(l -> l.flex(1)),
                    new Label().setText("§a" + Currency.format(entry.getValue())).layout(l -> l.width(56))
            );
            mgRateListArea.addChildren(row);
        }
    }

    private void refreshMgConfirmArea(Player player) {
        if (mgConfirmArea == null) return;
        mgConfirmArea.clearAllChildren();
        mgConfirmArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(5).paddingTop(2));

        if (mgSelected.isEmpty()) {
            mgConfirmArea.addChildren(new Label().setText("§8No item selected."));
            return;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(mgSelected.getItem()).toString();

        var itemRow = new UIElement();
        itemRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).height(22));
        var icon = new ItemSlot(); icon.setItem(mgSelected); icon.layout(l -> l.width(20).height(20));
        itemRow.addChildren(icon, new Label().setText(
                "§f" + mgSelected.getHoverName().getString() + " §8(" + itemId + ")"));
        mgConfirmArea.addChildren(itemRow);
        mgConfirmArea.addChildren(new Label().setText("§72. Enter the value (number + coin type):"));

        // Rate input: number field + coin dropdown
        long[] qty    = {1};
        String[] coin = {"Copper"};

        var inputRow = new UIElement();
        inputRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).height(22));

        TextField qtyField = new TextField();
        qtyField.setValue("1");
        qtyField.bindObserver(v -> { try { qty[0] = Math.max(1, Long.parseLong(v.trim())); } catch (NumberFormatException ignored) {} });
        qtyField.layout(l -> l.width(70));

        var coinSel = new Selector<String>();
        coinSel.setCandidates(List.of("Copper", "Silver", "Gold", "Platinum"));
        coinSel.setValue("Copper");
        coinSel.setOnValueChanged(v -> coin[0] = v);
        coinSel.layout(l -> l.width(80).height(22));

        inputRow.addChildren(qtyField, coinSel);
        mgConfirmArea.addChildren(inputRow);

        var saveBtn = new Button().setText("§a✓ Save Rate");
        saveBtn.setOnClick(e -> {
            long rateCopper = qty[0] * coinMultiplier(coin[0]);
            PacketDistributor.sendToServer(ExchangeActionPacket.setRate(itemId, rateCopper));
            rates.put(itemId, rateCopper);
            fillMgRateList();
            mgSelected = ItemStack.EMPTY;
            refreshMgConfirmArea(player);
        });
        saveBtn.layout(l -> l.width(120).height(22));

        var previewLabel = new Label();
        previewLabel.addEventListener(UIEvents.TICK, ev ->
                previewLabel.setText("§7" + mgSelected.getHoverName().getString()
                        + " §8→ §a" + Currency.format(qty[0] * coinMultiplier(coin[0])) + " §7each"));
        mgConfirmArea.addChildren(previewLabel, saveBtn);
    }

    // ── Shared: inventory grid ────────────────────────────────────────────────

    /**
     * Builds a 4-row inventory grid (3 main rows + hotbar).
     * {@code forManage}: if true, clicking selects mgSelected; otherwise selects for exchange.
     */
    @OnlyIn(Dist.CLIENT)
    private void buildInvGrid(UIElement parent, Player player, boolean forManage) {
        int[][] rowDefs = {{9,18},{18,27},{27,36},{0,9}};
        for (int[] range : rowDefs) {
            var rowEl = new UIElement();
            rowEl.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(2).height(20));
            for (int slot = range[0]; slot < range[1]; slot++) {
                final int s = slot;
                var cell = new ItemSlot();
                cell.layout(l -> l.width(18).height(18));
                cell.addEventListener(UIEvents.TICK, ev -> {
                    var mc = Minecraft.getInstance();
                    if (mc.player != null) cell.setItem(mc.player.getInventory().getItem(s));
                });
                if (!forManage) {
                    cell.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> {
                        var mc = Minecraft.getInstance();
                        if (mc.player == null) return;
                        ItemStack stack = mc.player.getInventory().getItem(s);
                        if (stack.isEmpty()) return;
                        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        long r = rates.getOrDefault(id, 0L);
                        ev.hoverTooltips = r > 0
                                ? HoverTooltips.empty().append(
                                        Component.literal("§aValue: §e" + Currency.format(r * stack.getCount())),
                                        Component.literal("§8(" + Currency.format(r) + " each)"),
                                        Component.literal("§7Click to select."))
                                : HoverTooltips.empty().append(Component.literal("§cNo exchange rate."));
                    });
                    cell.addEventListener(UIEvents.MOUSE_UP, ev -> {
                        selectedSlot = s;
                        refreshExConfirmArea(player);
                    });
                } else {
                    cell.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> {
                        var mc = Minecraft.getInstance();
                        if (mc.player == null) return;
                        ItemStack stack = mc.player.getInventory().getItem(s);
                        if (stack.isEmpty()) return;
                        ev.hoverTooltips = HoverTooltips.empty()
                                .append(Component.literal("§7Click to set rate for"),
                                        Component.literal("§f" + stack.getHoverName().getString()));
                    });
                    cell.addEventListener(UIEvents.MOUSE_UP, ev -> {
                        var mc = Minecraft.getInstance();
                        if (mc.player == null) return;
                        ItemStack stack = mc.player.getInventory().getItem(s);
                        if (!stack.isEmpty()) { mgSelected = stack.copy(); refreshMgConfirmArea(player); }
                    });
                }
                rowEl.addChildren(cell);
            }
            parent.addChildren(rowEl);
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String resolveItemName(String itemId) {
        Optional<Item> opt = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId));
        return opt.map(item -> item.getDescription().getString()).orElse(itemId);
    }

    private static long coinMultiplier(String coin) {
        return switch (coin) {
            case "Platinum" -> Currency.COPPER_PER_PLATINUM;
            case "Gold"     -> Currency.COPPER_PER_GOLD;
            case "Silver"   -> Currency.COPPER_PER_SILVER;
            default         -> 1L; // Copper
        };
    }
}
