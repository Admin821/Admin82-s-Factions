package com.admin82.factions.menu;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.meter.MeterKind;
import com.admin82.factions.meter.MeterNetworks;
import com.admin82.factions.meter.MeterService;
import com.admin82.factions.meter.SideMode;
import com.admin82.factions.network.packet.MeterActionPacket;
import com.admin82.factions.network.packet.SyncMeterPacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.appliedenergistics.yoga.YogaJustify;

import java.util.UUID;
import javax.annotation.Nullable;

/**
 * The meter GUI. The top block opens the dial: list this meter's power or fluid for sale at your price, or set
 * the most you'll pay and let it connect to a seller by itself; plus the public price graph and listings. The bottom block opens the storage screen:
 * what's in the battery or tank, dumping it, and what each side lets pipes do.
 */
public class MeterMenu extends AbstractContainerMenu {

    private static final int WIDTH = 360;
    private static final int HEIGHT = 350;
    /** Width inside the frame and panel padding. */
    private static final int INNER = WIDTH - 20;
    private enum View { HOME, CREATE, EDIT, BUY, MARKET, INSPECT }

    private final BlockPos pos;
    private final MeterKind kind;
    /** True for the battery/tank screen, false for the network dial. */
    private final boolean storage;
    private boolean dumpArmed;
    @Nullable private SyncMeterPacket data;
    @Nullable private UIElement body;
    private View view = View.HOME;
    @Nullable private UUID inspecting;
    private int tick;
    private boolean armed;

    // Form fields (kept while the screen refreshes)
    private String nameText = "";
    private String priceText = "1";
    private String priceCoin = "Copper";
    private String perText = "1000";
    private int access;
    private String maxText = "1";
    private String maxCoin = "Copper";
    private String maxPerText = "1000";
    private String buyFluid = "";
    private String fluidSearch = "";

    public MeterMenu(int containerId, Inventory inventory, BlockPos pos, MeterKind kind, boolean storage) {
        super(ModMenuTypes.METER.get(), containerId);
        this.pos = pos;
        this.kind = kind;
        this.storage = storage;
    }

    public MeterMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(ModMenuTypes.METER.get(), containerId);
        this.pos = buf.readBlockPos();
        int k = buf.readVarInt();
        this.kind = MeterKind.values()[Math.max(0, Math.min(MeterKind.values().length - 1, k))];
        this.storage = buf.readBoolean();
        if (FMLEnvironment.dist == Dist.CLIENT) rebuildClientUi(inventory.player);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getCenter()) <= 64;
    }

    public void updateData(SyncMeterPacket sync) {
        if (!sync.pos().equals(pos)) return;
        data = sync;
        if (FMLEnvironment.dist == Dist.CLIENT && !isTyping()) render();
    }

    /** Live numbers: ask for fresh data every 2 seconds. */
    @OnlyIn(Dist.CLIENT)
    public void clientTick() {
        if (++tick % 40 == 0) send(MeterService.Action.REFRESH);
    }

    @OnlyIn(Dist.CLIENT)
    private boolean isTyping() {
        if (!(this instanceof IModularUIHolderMenu h) || h.getModularUI() == null) return false;
        for (var el = h.getModularUI().getFocusedElement(); el != null; el = el.getParent()) if (el instanceof TextField) return true;
        return false;
    }

    private void send(MeterService.Action action) {
        PacketDistributor.sendToServer(MeterActionPacket.of(pos, action));
    }

    private void send(MeterService.Action action, @Nullable UUID network, String text, String password, long x, long y, int acc) {
        PacketDistributor.sendToServer(new MeterActionPacket(pos, action.ordinal(), network, text, password, x, y, acc));
    }

    // UI

    @OnlyIn(Dist.CLIENT)
    public void rebuildClientUi(Player player) {
        if (this instanceof IModularUIHolderMenu h) h.setModularUI(buildUi(player));
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI buildUi(Player player) {
        var frame = new UIElement();
        frame.layout(l -> l.width(WIDTH).height(HEIGHT).paddingAll(2));
        frame.addClass("preview_bg");
        var root = new UIElement();
        root.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).paddingAll(8).gapAll(4));
        root.addClass("panel_bg");
        body = new UIElement();
        body.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        root.addChildren(body);
        frame.addChildren(root);
        render();
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    @OnlyIn(Dist.CLIENT)
    private void render() {
        if (body == null) return;
        body.clearAllChildren();
        String icon = kind == MeterKind.POWER ? "§e⚡ " : "§b💧 ";
        var head = row(14);
        String title = storage ? (kind == MeterKind.POWER ? "Battery" : "Tank") : kind.label + " Meter";
        head.addChildren(new Label().setText(icon + "§l" + title).layout(l -> l.flex(1)),
                UiKit.rightLabel(data == null ? "" : "§8owner: §7" + data.ownerName()));
        body.addChildren(head);
        if (data == null) { body.addChildren(new Label().setText("§7Loading…")); return; }

        // Storage bar
        float pct = data.capacity() <= 0 ? 0 : Math.min(1f, data.stored() / (float) data.capacity());
        var barLabel = row(10);
        barLabel.addChildren(new Label().setText("§7" + (kind == MeterKind.POWER ? "Battery" : "Tank")
                        + (data.content().isEmpty() ? "" : " §8· §b" + data.content())).layout(l -> l.flex(1)),
                UiKit.rightLabel("§f" + num(data.stored()) + " §8/ §7" + num(data.capacity()) + " " + kind.unit
                        + " §8(" + (int) (pct * 100) + "%)"));
        var track = UiKit.background(new UIElement(), UiKit.BAR_TRACK);
        track.layout(l -> l.height(10).flexDirection(YogaFlexDirection.ROW));
        if (pct > 0) {
            var fill = UiKit.background(new UIElement(), kind == MeterKind.POWER ? 0xFFE0B020 : 0xFF3070E0);
            fill.layout(l -> l.width(Math.max(1, (int) (INNER * pct))).height(10));
            track.addChildren(fill);
        }
        body.addChildren(barLabel, track);

        if (storage) { renderStorage(); return; }
        switch (view) {
            case CREATE, EDIT -> renderForm();
            case BUY -> renderBuyForm();
            case MARKET -> renderMarket();
            case INSPECT -> renderInspect();
            default -> renderHome();
        }
    }

    /** As you look at the screen. The colours match the ports on the model. */
    private static final String[] SIDE_NAMES = {"§bFront", "§aLeft", "§eBack", "§dRight"};

    @OnlyIn(Dist.CLIENT)
    private void renderStorage() {
        var current = network(data.networkId());
        String what = data.role() == 1 ? "§aSold as §f" + (current != null ? current.name() : "?")
                : data.role() == 2 ? (current != null ? "§bBought from §f" + current.name() : "§bBuying, not connected to a seller")
                : "§7Not buying or selling. Right-click the meter on top to set it up.";
        int pct = data.capacity() <= 0 ? 0 : (int) (100 * data.stored() / data.capacity());
        body.addChildren(card().addChildren(
                new Label().setText("§7Holding §f" + num(data.stored()) + " " + kind.unit + " §8(" + pct + "%)"
                        + (kind == MeterKind.FLUID ? (data.content().isEmpty() ? " §8· empty" : " §8· §b" + data.content()) : "")),
                new Label().setText(what)));

        // Sides: click to cycle Any → Input → Output
        var sideCard = card();
        sideCard.addChildren(new Label().setText("§6§lSides §8(for pipes and cables)"));
        var sideRow = row(18);
        for (int i = 0; i < 4; i++) {
            int idx = i;
            SideMode mode = SideMode.of(data.sides()[i]);
            var b = button("§7" + SIDE_NAMES[i] + ": " + mode.color + mode.label, 80, () -> {
                if (!data.isOwner()) return;
                send(MeterService.Action.SET_SIDE, null, "", "", idx, mode.next().ordinal(), 0);
            });
            tip(b, "§f" + SIDE_NAMES[i] + " side: " + mode.color + mode.label,
                    "§7Any: pipes can put in and take out.", "§9Input§7: only put in.", "§6Output§7: only take out, and pushes into the block next to it.",
                    "§8NTM wires only draw power from an Output side.",
                    kind == MeterKind.POWER ? "§8Same colour as the strip on that side of the meter." : "§8Sides are named as you face the screen.",
                    data.isOwner() ? "§8Click to change" : "§8Only " + data.ownerName() + " can change this.");
            sideRow.addChildren(b);
        }
        sideCard.addChildren(sideRow, new Label().setText("§8The top and bottom faces always allow both."));
        body.addChildren(sideCard);

        if (data.isOwner()) {
            var buttons = row(18);
            var dump = button(dumpArmed ? "§c§lClick again to dump" : "§cDump contents", 130, () -> {
                if (!dumpArmed) { dumpArmed = true; render(); return; }
                dumpArmed = false;
                send(MeterService.Action.DUMP);
            });
            tip(dump, "§7Empties the " + (kind == MeterKind.POWER ? "battery" : "tank") + ". What's dumped is gone.", "§8Click twice.");
            buttons.addChildren(dump);
            body.addChildren(buttons);
        }
    }

    @Nullable
    private SyncMeterPacket.NetworkView network(@Nullable UUID id) {
        if (data == null || id == null) return null;
        for (var n : data.networks()) if (n.id().equals(id)) return n;
        return null;
    }

    private static long micro(SyncMeterPacket.NetworkView n) {
        return MeterNetworks.micro(n.priceCopper(), n.perUnits());
    }

    @OnlyIn(Dist.CLIENT)
    private void renderHome() {
        var current = network(data.networkId());
        switch (data.role()) {
            case 1 -> renderSelling(current);
            case 2 -> renderBuying(current);
            default -> {
                var card = card();
                card.addChildren(new Label().setText("§f§lNot buying or selling"));
                card.addChildren(wrapped("§7", "§aSell§7: list what's in this meter at your price. §bBuy§7: set the most you'll pay, "
                        + "and the meter connects to a seller at or under it by itself."));
                body.addChildren(card, marketGraph(graphFluid()), spacer());
                var buttons = row(20);
                if (data.isOwner()) {
                    var sell = flexButton("§a+ Sell", () -> {
                        nameText = ""; priceText = "1"; priceCoin = "Copper"; perText = "1000"; access = 0;
                        view = View.CREATE; render();
                    });
                    tip(sell, "§7List this meter's " + kind.label.toLowerCase() + " at your price.");
                    var buy = flexButton("§b+ Buy", () -> { loadBuyForm(); view = View.BUY; render(); });
                    tip(buy, "§7Set the most you'll pay. Nobody else sees it.");
                    buttons.addChildren(sell, buy);
                } else {
                    buttons.addChildren(new Label().setText("§8Only " + data.ownerName() + " can set this meter up.").layout(l -> l.flex(1)));
                }
                buttons.addChildren(marketButton());
                body.addChildren(buttons);
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void renderSelling(@Nullable SyncMeterPacket.NetworkView current) {
        var card = card();
        card.addChildren(titleRow("§a● §lSelling as §f" + (current != null ? current.name() : "a closed listing"), accessBadge(current)));
        card.addChildren(wrapped("§7", "Buyers whose max price is at or above yours connect by themselves. Fill this meter with cables "
                + "or pipes. Sales are paid into its coin storage."));
        var earned = stat("Earned", current != null && current.earned() >= 0 ? "§a" + plain(current.earned()) : "§8-");
        tip(earned, "§7Everything this listing has earned, across all its supplier meters.");
        var price = stat("Your price", "§e" + price(current));
        if (current != null && current.priceCopper() > 0) tip(price, "§7= " + MeterNetworks.per1000(micro(current)) + " per 1000 " + kind.unit);
        body.addChildren(card, statRow(price,
                stat("Sold so far", "§f" + (current != null ? num(current.unitsSold()) + " " + kind.unit : "-")),
                earned));
        body.addChildren(coinCard("Sales are paid in here. It stops selling when full."), marketGraph(graphFluid()), spacer());
        if (!data.isOwner()) return;
        var buttons = row(18);
        var edit = flexButton("§eEdit", () -> {
            if (current != null) {
                nameText = current.name();
                priceCoin = Currency.wholeCoin(current.priceCopper());
                priceText = Long.toString(current.priceCopper() / Currency.perCoin(priceCoin));
                perText = Long.toString(current.perUnits());
                access = current.access() == 0 ? 0 : 2;
            }
            view = View.EDIT; render();
        });
        tip(edit, "§7Change the name, price or who can buy.", "§8Buyers whose max is below a new price are dropped.");
        var stop = flexButton("§7Stop selling", () -> send(MeterService.Action.LEAVE));
        tip(stop, "§7This meter stops selling. The listing stays", "§7(for your other meters on it).");
        var close = flexButton(armed ? "§c§lClick to confirm" : "§cClose listing", () -> {
            if (!armed) { armed = true; render(); return; }
            armed = false;
            send(MeterService.Action.DELETE, data.networkId(), "", "", 0, 0, 0);
        });
        tip(close, "§7Removes the listing for all your meters on it.", "§8Click twice.");
        buttons.addChildren(edit, marketButton(), stop, close);
        body.addChildren(buttons);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderBuying(@Nullable SyncMeterPacket.NetworkView current) {
        var card = card();
        card.addChildren(titleRow("§b● §lBuying" + (kind == MeterKind.FLUID && !data.wantFluid().isEmpty()
                ? " §b" + fluidName(data.wantFluid()) : ""), data.maxPriceCopper() >= 0
                ? "§7up to §e" + MeterNetworks.per1000(MeterNetworks.micro(data.maxPriceCopper(), data.maxPerUnits())) + " §7/ 1000 " + kind.unit : ""));
        card.addChildren(new Label().setText(current != null
                ? "§7From §f" + current.name() + " §8by " + current.ownerName() + " §7at §e" + MeterNetworks.per1000(micro(current))
                        + " §7/ 1000 " + kind.unit
                : "§7Not connected to a seller yet."));
        if (!data.status().isEmpty()) card.addChildren(wrapped("§c", "⚠ " + data.status()));
        var rate = stat("Max speed", "§f" + num(data.rate()) + (kind == MeterKind.POWER ? " FE/t" : " mB/s"));
        tip(rate, "§7Meter efficiency level " + data.efficiency(), "§8Raised by your faction's upgrades.");
        var since = stat("Seller has", current != null ? (current.stock() > 0 ? "§a" : "§c") + num(current.stock()) + " " + kind.unit
                + (current.content().isEmpty() ? "" : " §b" + current.content()) : "§8-");
        body.addChildren(card, statRow(since, rate));
        body.addChildren(coinCard("Purchases are paid from these coins."), marketGraph(graphFluid()), spacer());
        if (!data.isOwner()) return;
        var buttons = row(18);
        var change = flexButton("§eMax price", () -> { loadBuyForm(); view = View.BUY; render(); });
        tip(change, "§7Change the most you'll pay" + (kind == MeterKind.FLUID ? " or the fluid." : "."), "§7Only you can see it.");
        var stop = flexButton("§cStop buying", () -> send(MeterService.Action.LEAVE));
        tip(stop, "§7Disconnects and stops buying.", "§7Coins stay in the meter.");
        buttons.addChildren(change, marketButton(), stop);
        body.addChildren(buttons);
    }

    @OnlyIn(Dist.CLIENT)
    private Button marketButton() {
        var b = flexButton("§6Market", () -> { view = View.MARKET; render(); });
        tip(b, "§7Prices over the last 24 hours and everyone's listings.");
        return b;
    }

    /** Fills the buy form from the meter's current limit (or 1 copper per 1000). */
    private void loadBuyForm() {
        if (data != null && data.maxPriceCopper() >= 0 && data.maxPerUnits() > 0) {
            maxCoin = Currency.wholeCoin(data.maxPriceCopper());
            maxText = Long.toString(data.maxPriceCopper() / Currency.perCoin(maxCoin));
            maxPerText = Long.toString(data.maxPerUnits());
        } else {
            maxText = "1"; maxCoin = "Copper"; maxPerText = "1000";
        }
        buyFluid = data == null ? "" : !data.wantFluid().isEmpty() ? data.wantFluid() : data.tankFluid();
    }

    /** One "number, coin, per, amount, unit" row. */
    @OnlyIn(Dist.CLIENT)
    private UIElement priceRow(String label, String count, String coin, String per, java.util.function.Consumer<String> onCount,
                               java.util.function.Consumer<String> onCoin, java.util.function.Consumer<String> onPer, Runnable changed) {
        var price = row(16);
        var countField = UiKit.textField("0");
        countField.setValue(count);
        countField.bindObserver(v -> { onCount.accept(v); changed.run(); });
        countField.layout(l -> l.flex(1).height(16));
        tip(countField, "§7How many coins.", "§70 means free.");
        var perField = UiKit.textField("1000");
        perField.setValue(per);
        perField.bindObserver(v -> { onPer.accept(v); changed.run(); });
        perField.layout(l -> l.flex(1).height(16));
        tip(perField, "§7For how many " + kind.unit + ".");
        price.addChildren(formLabel(label), countField, coinSelector(coin, v -> { onCoin.accept(v); changed.run(); }),
                new Label().setText("§7per").layout(l -> l.width(20)), perField,
                new Label().setText("§7" + kind.unit).layout(l -> l.width(24)));
        return price;
    }

    @OnlyIn(Dist.CLIENT)
    private void renderForm() {
        boolean editing = view == View.EDIT;
        var form = card();
        form.layout(l -> l.gapAll(4));
        form.addChildren(new Label().setText(editing ? "§e§lEdit your listing" : "§a§lSell " + kind.label.toLowerCase()));
        form.addChildren(field("Name", "What buyers see", nameText, v -> nameText = v, "§7The name shown on the market."));
        var preview = new Label();
        Runnable updatePreview = () -> preview.setText(previewText());
        form.addChildren(priceRow("Price", priceText, priceCoin, perText, v -> priceText = v, v -> priceCoin = v, v -> perText = v, updatePreview));

        var accessRow = row(16);
        accessRow.addChildren(formLabel("Sell to"));
        int[] values = {0, 2};
        String[] names = {"Everyone", "Faction only"};
        String[] help = {"§7Anyone can buy from you.", "§7Only your faction can buy from you."};
        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            var b = flexButton((access == value ? "§f§l" : "§7") + names[i], () -> { access = value; render(); });
            if (access == value) b.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
            tip(b, help[i]);
            accessRow.addChildren(b);
        }
        form.addChildren(accessRow);
        updatePreview.run();
        form.addChildren(preview);
        body.addChildren(form, spacer());

        var buttons = row(18);
        buttons.addChildren(button("§7← Back", 80, () -> { view = View.HOME; render(); }), spacer(),
                button(editing ? "§a§lSave" : "§a§lStart selling", 120, () -> {
                    long priceCopper = priceCopper();
                    long per = parseUnits(perText);
                    if (priceCopper < 0 || per <= 0 || nameText.isBlank()) return;
                    send(editing ? MeterService.Action.EDIT : MeterService.Action.CREATE, editing ? data.networkId() : null,
                            nameText, "", priceCopper, per, access);
                    view = View.HOME;
                }));
        body.addChildren(buttons);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderBuyForm() {
        var form = card();
        form.layout(l -> l.gapAll(4));
        form.addChildren(new Label().setText("§b§lBuy " + kind.label.toLowerCase()));
        form.addChildren(wrapped("§7", "Set the most you'll pay. The meter connects to a seller at or under it by itself, first come, "
                + "first served, and switches if their price goes above yours. §fSellers never see your limit."));
        var preview = new Label();
        Runnable updatePreview = () -> preview.setText(buyPreviewText());
        if (kind == MeterKind.FLUID) form.addChildren(fluidRow());
        form.addChildren(priceRow("Max price", maxText, maxCoin, maxPerText, v -> maxText = v, v -> maxCoin = v, v -> maxPerText = v, updatePreview));
        updatePreview.run();
        form.addChildren(preview);
        body.addChildren(form, marketGraph(kind == MeterKind.FLUID ? buyFluid : ""), spacer());

        var buttons = row(18);
        buttons.addChildren(button("§7← Back", 80, () -> { view = View.HOME; render(); }), spacer(),
                button(data.role() == 2 ? "§a§lSave" : "§a§lStart buying", 120, () -> {
                    long max = maxText.isBlank() ? 0 : coinsToCopper(maxText, maxCoin);
                    long per = parseUnits(maxPerText);
                    if (max < 0 || per <= 0 || (kind == MeterKind.FLUID && buyFluid.isEmpty())) return;
                    send(MeterService.Action.BUY, null, buyFluid, "", max, per, 0);
                    view = View.HOME;
                }));
        body.addChildren(buttons);
    }

    /** The form's price in copper, or -1 if it isn't a whole number. */
    private long priceCopper() {
        return priceText.isBlank() ? 0 : coinsToCopper(priceText, priceCoin);
    }

    /** "5" and "Gold" → 50,000 copper. -1 if it isn't a whole number of 0 or more. */
    private static long coinsToCopper(String count, String coin) {
        try {
            long n = Long.parseLong(count.trim().replace(",", ""));
            return n < 0 ? -1 : Math.multiplyExact(n, Currency.perCoin(coin));
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    /** Dropdown of coin types (Copper, Silver, Gold, Platinum). */
    @OnlyIn(Dist.CLIENT)
    private static UIElement coinSelector(String value, java.util.function.Consumer<String> onChange) {
        var selector = new Selector<String>();
        selector.setCandidates(Currency.COINS);
        selector.setValue(value);
        selector.setOnValueChanged(onChange::accept);
        selector.layout(l -> l.width(68).height(16));
        tip(selector, "§c1 Copper", "§7100 Copper = §f1 Silver", "§7100 Silver = §61 Gold", "§7100 Gold = §d1 Platinum");
        return selector;
    }

    private static long parseUnits(String text) {
        try { return Long.parseLong(text.trim().replace(",", "")); } catch (NumberFormatException e) { return -1; }
    }

    /** What buyers will pay, or what's wrong with the form. */
    private String previewText() {
        long priceCopper = priceCopper();
        long per = parseUnits(perText);
        if (nameText.isBlank()) return "§8Give the listing a name.";
        if (priceCopper < 0) return "§c✖ The price needs to be a whole number.";
        if (per <= 0) return "§c✖ The amount needs to be a whole number above 0.";
        if (priceCopper == 0) return "§7Buyers get it §afree§7.";
        return "§7Buyers pay §e" + MeterNetworks.per1000(MeterNetworks.micro(priceCopper, per)) + "§7 per 1000 " + kind.unit
                + averageNote(graphFluid());
    }

    private String buyPreviewText() {
        long max = maxText.isBlank() ? 0 : coinsToCopper(maxText, maxCoin);
        long per = parseUnits(maxPerText);
        if (kind == MeterKind.FLUID && buyFluid.isEmpty()) return "§c✖ Pick the fluid to buy.";
        if (max < 0) return "§c✖ The price needs to be a whole number.";
        if (per <= 0) return "§c✖ The amount needs to be a whole number above 0.";
        String what = kind == MeterKind.FLUID ? fluidName(buyFluid) : "power";
        if (max == 0) return "§7Only free " + what + averageNote(kind == MeterKind.FLUID ? buyFluid : "");
        return "§7At most §e" + MeterNetworks.per1000(MeterNetworks.micro(max, per)) + "§7 per 1000 " + kind.unit + " of " + what
                + averageNote(kind == MeterKind.FLUID ? buyFluid : "");
    }

    /** ". Recent average: X" when anything sold in the last 24 hours. */
    private String averageNote(String fluid) {
        long avg = latest(history(fluid), 0);
        return avg < 0 ? "§7." : "§7. §8Recent average: " + MeterNetworks.per1000(avg) + ".";
    }

    /** The price history for a fluid ("" for power), or null if it hasn't sold in the last 24 hours. */
    @Nullable
    private SyncMeterPacket.PriceHistory history(String fluid) {
        if (data == null) return null;
        for (var h : data.histories()) if (h.fluid().equals(fluid)) return h;
        return null;
    }

    /** The newest hour with sales, skipping {@code skip} such hours first; -1 if there isn't one. */
    private static long latest(@Nullable SyncMeterPacket.PriceHistory h, int skip) {
        if (h == null) return -1;
        for (int i = h.micro().length - 1; i >= 0; i--) {
            if (h.micro()[i] < 0) continue;
            if (skip-- == 0) return h.micro()[i];
        }
        return -1;
    }

    /** Which fluid's graph to show: the one being bought, else what's in the tank, else the busiest. "" for power. */
    private String graphFluid() {
        if (kind == MeterKind.POWER || data == null) return "";
        if (data.role() == 2 && !data.wantFluid().isEmpty()) return data.wantFluid();
        if (!data.tankFluid().isEmpty()) return data.tankFluid();
        long best = -1;
        String fluid = "";
        for (var h : data.histories()) {
            long sold = 0;
            for (long u : h.units()) sold += u;
            if (sold > best) { best = sold; fluid = h.fluid(); }
        }
        return fluid;
    }

    /** A fluid's display name from its id. */
    @OnlyIn(Dist.CLIENT)
    private static String fluidName(String id) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
        var fluid = rl == null ? null : net.minecraft.core.registries.BuiltInRegistries.FLUID.getOptional(rl).orElse(null);
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) return id.isEmpty() ? "?" : id;
        return new net.neoforged.neoforge.fluids.FluidStack(fluid, 1).getHoverName().getString();
    }

    /** "Fluid: [dropdown]" for the buy form. Every fluid in the game, sorted by name. */
    @OnlyIn(Dist.CLIENT)
    private UIElement fluidRow() {
        var names = new java.util.ArrayList<String>();
        var ids = new java.util.HashMap<String, String>();
        for (var fluid : net.minecraft.core.registries.BuiltInRegistries.FLUID) {
            if (fluid == net.minecraft.world.level.material.Fluids.EMPTY || !fluid.isSource(fluid.defaultFluidState())) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid).toString();
            String name = fluidName(id);
            if (ids.containsKey(name)) name += " (" + id + ")";
            ids.put(name, id);
            names.add(name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        var byName = new java.util.ArrayList<String>();
        for (String n : names) byName.add(ids.get(n));
        var col = new UIElement();
        col.layout(l -> l.flexDirection(org.appliedenergistics.yoga.YogaFlexDirection.COLUMN).gapAll(2));
        var row = row(16);
        row.addChildren(formLabel("Fluid"), new Label().setText(buyFluid.isEmpty() ? "§8none picked" : "§b" + fluidName(buyFluid)));
        var picker = SearchPicker.create(byName, MeterMenu::fluidName, buyFluid, fluidSearch, q -> fluidSearch = q,
                id -> { if (!id.equals(buyFluid)) { buyFluid = id; render(); } }, 62);
        tip(picker, "§7The fluid this meter buys: type to search.", "§7It only connects to sellers that have it.");
        col.addChildren(row, picker);
        return col;
    }

    /** The public price graph: average price paid per hour over the last 24 hours. Buyers' limits are never shown. */
    @OnlyIn(Dist.CLIENT)
    private UIElement marketGraph(String fluid) {
        var history = history(fluid);
        long[] avg = history != null ? history.micro() : new long[MeterNetworks.HISTORY_HOURS];
        long[] units = history != null ? history.units() : new long[MeterNetworks.HISTORY_HOURS];
        if (history == null) java.util.Arrays.fill(avg, -1);
        long max = 0;
        for (long a : avg) max = Math.max(max, a);
        long latest = latest(history, 0);
        long before = latest(history, 1);
        String trend = "";
        if (latest >= 0 && before > 0) {
            long pct = Math.round((latest - before) * 100.0 / before);
            trend = pct > 0 ? " §a▲ " + pct + "%" : pct < 0 ? " §c▼ " + (-pct) + "%" : " §7= 0%";
        }
        String what = kind == MeterKind.POWER || fluid.isEmpty() ? "" : " §b" + fluidName(fluid);
        var c = card();
        var title = titleRow("§6§lAverage price" + what + " §8/ 1000 " + kind.unit + ", 24 h",
                latest >= 0 ? "§7latest §e" + MeterNetworks.per1000(latest) + trend : "§8no sales yet");
        tip(title, "§7The average price paid each hour, across all sales.", "§7The ▲/▼ compares the latest hour with sales to the one before.",
                "§8Buyers' max prices are never shown.");
        c.addChildren(title);
        int n = Math.max(1, avg.length);
        int barWidth = Math.max(2, (INNER - 12 - (n - 1)) / n);
        int graphHeight = 34;
        var bars = new UIElement();
        bars.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.FLEX_END).gapAll(1).height(graphHeight));
        for (int i = 0; i < avg.length; i++) {
            long a = avg[i];
            int ago = avg.length - 1 - i;
            int h = a < 0 ? 0 : max <= 0 ? 2 : Math.max(2, (int) (graphHeight * a / max));
            var slot = new UIElement();
            slot.layout(l -> l.width(barWidth).flexShrink(0).height(graphHeight).flexDirection(YogaFlexDirection.COLUMN)
                    .justifyContent(YogaJustify.FLEX_END));
            if (h > 0) {
                var bar = UiKit.background(new UIElement(), ago == 0 ? 0xFFF0C040 : 0xFFB08830);
                bar.layout(l -> l.width(barWidth).height(h));
                slot.addChildren(bar);
            }
            tip(slot, "§f" + (ago == 0 ? "This hour" : ago == 1 ? "1 hour ago" : ago + " hours ago"),
                    a < 0 ? "§8Nothing sold" : "§7Average: §e" + MeterNetworks.per1000(a) + " §7per 1000 " + kind.unit,
                    a < 0 ? "" : "§7Sold: §f" + num(units[i]) + " " + kind.unit);
            bars.addChildren(slot);
        }
        var baseline = UiKit.background(new UIElement(), 0xFF55556A);
        baseline.layout(l -> l.height(1));
        var axis = row(9);
        axis.addChildren(new Label().setText("§824 h ago").layout(l -> l.flex(1)), UiKit.rightLabel("§8now"));
        c.addChildren(bars, baseline, axis);
        return c;
    }

    @OnlyIn(Dist.CLIENT)
    private void renderMarket() {
        var top = row(18);
        top.addChildren(button("§7← Back", 60, () -> { view = View.HOME; render(); }),
                new Label().setText("§6§l" + kind.label + " market").layout(l -> l.flex(1)),
                UiKit.rightLabel("§8" + data.networks().size() + " listing" + (data.networks().size() == 1 ? "" : "s")));
        body.addChildren(top, marketGraph(graphFluid()));
        if (data.networks().isEmpty()) {
            body.addChildren(card().addChildren(new Label().setText("§7Nobody is selling " + kind.label.toLowerCase() + " yet.")));
            return;
        }
        var header = row(10);
        header.layout(l -> l.paddingHorizontal(4));
        header.addChildren(new Label().setText("§8Seller (cheapest first)").layout(l -> l.flex(1)),
                new Label().setText("§8Per 1000 " + kind.unit).layout(l -> l.width(110)),
                new Label().setText("§8Stock").layout(l -> l.width(50)));
        body.addChildren(header);
        int shown = 4;
        for (var n : data.networks().stream().limit(shown).toList()) {
            var r = UiKit.background(row(18), UiKit.ROW);
            r.layout(l -> l.paddingHorizontal(4));
            String badge = n.access() == 2 ? "§d[faction] " : "";
            r.addChildren(new Label().setText(badge + "§f" + n.name() + (n.mine() ? " §a(yours)" : " §8by " + n.ownerName())).layout(l -> l.flex(1)),
                    new Label().setText("§e" + MeterNetworks.per1000(micro(n))
                            + (kind == MeterKind.FLUID && !n.content().isEmpty() ? " §b" + n.content() : "")).layout(l -> l.width(110)),
                    new Label().setText((n.stock() > 0 ? "§a" : "§c") + num(n.stock())).layout(l -> l.width(50)));
            tip(r, "§f" + n.name() + " §8by " + n.ownerName(), "§7Price: §e" + price(n),
                    "§7Stock: " + num(n.stock()) + " " + kind.unit + (n.content().isEmpty() ? "" : " " + n.content()),
                    "§8" + n.suppliersOnline() + " of " + n.suppliers() + " supplier meters loaded", "§8Click for details");
            UiKit.onClick(r, e -> { inspecting = n.id(); view = View.INSPECT; render(); });
            body.addChildren(r);
        }
        if (data.networks().size() > shown) body.addChildren(new Label().setText("§8…and " + (data.networks().size() - shown) + " more"));
    }

    @OnlyIn(Dist.CLIENT)
    private void renderInspect() {
        var n = network(inspecting);
        var top = row(18);
        top.addChildren(button("§7← Back", 60, () -> { view = View.MARKET; render(); }));
        body.addChildren(top);
        if (n == null) { body.addChildren(new Label().setText("§7That listing is gone.")); return; }
        var card = card();
        card.addChildren(titleRow("§f§l" + n.name() + " §8by " + n.ownerName(), accessBadge(n)),
                new Label().setText("§7Sells " + kind.label.toLowerCase() + (n.content().isEmpty() ? "" : ": §b" + n.content())),
                new Label().setText("§8" + n.suppliersOnline() + " of " + n.suppliers() + " supplier meters loaded"));
        body.addChildren(card, statRow(
                stat("Price", "§e" + MeterNetworks.per1000(micro(n)) + " §7/ 1000 " + kind.unit),
                stat("In stock", (n.stock() > 0 ? "§a" : "§c") + num(n.stock()) + " " + kind.unit),
                stat("Sold so far", "§f" + num(n.unitsSold()) + " " + kind.unit)));
        body.addChildren(spacer());
        if (!data.isOwner()) return;
        var actions = row(18);
        if (n.mine()) {
            var sell = button("§a§lSell from this meter too", 150, () -> send(MeterService.Action.SUPPLY, n.id(), "", "", 0, 0, 0));
            tip(sell, "§7This meter's stock is sold on this listing too.");
            actions.addChildren(spacer(), sell);
        } else {
            actions.addChildren(new Label().setText("§8Buyers connect to sellers automatically. Set a max price with Buy.").layout(l -> l.flex(1)));
        }
        body.addChildren(actions);
    }

    // Helpers

    private String price(@Nullable SyncMeterPacket.NetworkView n) {
        if (n == null) return "?";
        if (n.priceCopper() <= 0) return "free";
        return Currency.format(n.priceCopper()).replaceAll("§.", "") + " per " + num(n.perUnits()) + " " + kind.unit;
    }

    private static String num(long n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 10_000) return String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0);
        return Long.toString(n);
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement field(String label, String hint, String value, java.util.function.Consumer<String> onChange, String help) {
        var r = row(16);
        var f = UiKit.textField(hint);
        f.setValue(value);
        f.bindObserver(v -> { onChange.accept(v); });
        f.layout(l -> l.flex(1).height(16));
        tip(f, help);
        r.addChildren(formLabel(label), f);
        return r;
    }

    private static Label formLabel(String text) {
        var label = new Label();
        label.setText("§7" + text);
        label.layout(l -> l.width(56));
        return label;
    }

    /** Copper as plain coin text, like "2g 50s". */
    private static String plain(long copper) {
        return Currency.format(copper).replaceAll("§.", "");
    }

    private String accessBadge(@Nullable SyncMeterPacket.NetworkView n) {
        if (n == null) return "";
        return n.access() == 0 ? "§a● Everyone" : "§d● Faction only";
    }

    private static UIElement titleRow(String left, String right) {
        var r = row(12);
        r.addChildren(new Label().setText(left).layout(l -> l.flex(1)), UiKit.rightLabel(right));
        return r;
    }

    /** Coins in the meter against its limit, and (for the owner) the button to the coin slots. */
    @OnlyIn(Dist.CLIENT)
    private UIElement coinCard(String note) {
        boolean full = data.coins() >= data.coinCap();
        var coinCard = card();
        var coins = row(16);
        coins.addChildren(new Label().setText("§6§lCoins: " + (full ? "§c" : "§e") + plain(data.coins()) + " §7/ §f" + plain(data.coinCap())
                + (full ? " §c(full)" : "")).layout(l -> l.flex(1)));
        if (data.isOwner()) {
            var open = button("§6Coin storage", 90, () -> send(MeterService.Action.OPEN_COINS));
            tip(open, "§7Move coins between your inventory and the meter.", "§8Meter Efficiency upgrades raise the limit.");
            coins.addChildren(open);
        }
        coinCard.addChildren(coins);
        if (!data.coinEta().isEmpty()) {
            var eta = new Label().setText(data.coinEta());
            tip(eta, "§7At the rate coins have moved over the last half minute.",
                    "§8Coins you put in or take out by hand don't count.");
            coinCard.addChildren(eta);
        }
        coinCard.addChildren(new Label().setText("§7" + note));
        return coinCard;
    }

    /** A small box with a grey caption over a value. */
    private static UIElement stat(String caption, String value) {
        var t = UiKit.background(new UIElement(), UiKit.ROW);
        t.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(2).paddingAll(5));
        t.addChildren(new Label().setText("§8" + caption), new Label().setText(value));
        return t;
    }

    private static UIElement statRow(UIElement... stats) {
        var r = new UIElement();
        r.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(4));
        r.addChildren(stats);
        return r;
    }

    private static UIElement spacer() {
        var s = new UIElement();
        s.layout(l -> l.flex(1));
        return s;
    }

    /** Text broken into lines that fit inside a card. */
    @OnlyIn(Dist.CLIENT)
    private static UIElement wrapped(String color, String text) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        var box = new UIElement();
        box.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(1));
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (font.width(color + next) > INNER - 14 && !line.isEmpty()) {
                box.addChildren(new Label().setText(color + line));
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) box.addChildren(new Label().setText(color + line));
        return box;
    }

    private static Button flexButton(String text, Runnable action) {
        var b = new Button().setText(text).setOnClick(e -> action.run());
        b.layout(l -> l.flex(1).height(16));
        return b;
    }

    private static UIElement card() {
        var c = UiKit.background(new UIElement(), UiKit.CARD);
        c.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(2).paddingAll(6));
        return c;
    }

    private static UIElement row(int height) {
        var r = new UIElement();
        r.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(4).height(height));
        return r;
    }

    private static Button button(String text, int width, Runnable action) {
        var b = new Button().setText(text).setOnClick(e -> action.run());
        b.layout(l -> l.width(width).height(16));
        return b;
    }

    private static void tip(UIElement element, String... lines) {
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> {
            var tips = HoverTooltips.empty();
            for (String line : lines) tips = tips.append(Component.literal(line));
            ev.hoverTooltips = tips;
        });
    }
}
