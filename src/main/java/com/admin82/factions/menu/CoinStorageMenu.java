package com.admin82.factions.menu;

import com.admin82.factions.economy.CoinSlots;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.meter.MeterService;
import com.admin82.factions.network.packet.MeterActionPacket;
import com.admin82.factions.network.packet.OutpostActionPacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.screen.CoinStorageScreen;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.vfyjxf.taffy.style.AlignItems;


import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Coin storage of a meter or an outpost: a normal chest-style screen with 9 coin slots. Coins are exchanged up
 * (copper into silver and so on) automatically by the owner of the slots.
 */
public class CoinStorageMenu extends AbstractContainerMenu {

    private static final int SIZE = CoinSlots.SIZE;

    /** What the coins belong to; decides the Back button and the help text. */
    public enum Source { METER, OUTPOST }

    private final Source source;
    private final BlockPos pos;
    private final UUID outpostId;
    private final String title;
    private final Container container;
    /** Most copper it holds (-1 = no limit) and copper held outside the slots (server: live; client: from opening). */
    private final LongSupplier cap;
    private final LongSupplier loose;
    private final boolean canTake;
    private final Predicate<Player> valid;

    private CoinStorageMenu(int containerId, Inventory inventory, Source source, BlockPos pos, UUID outpostId, String title,
                            Container container, LongSupplier cap, LongSupplier loose, boolean canTake, Predicate<Player> valid) {
        super(ModMenuTypes.COIN_STORAGE.get(), containerId);
        this.source = source;
        this.pos = pos;
        this.outpostId = outpostId;
        this.title = title;
        this.container = container;
        this.cap = cap;
        this.loose = loose;
        this.canTake = canTake;
        this.valid = valid;
        addSlots(inventory);
    }

    /** Client side, from the open-menu buffer (the slots fill in through normal container syncing). */
    public CoinStorageMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        this(containerId, inventory, readSource(buf), buf.readBlockPos(), buf.readUUID(), buf.readUtf(),
                new SimpleContainer(SIZE), constant(buf.readVarLong()), constant(buf.readVarLong()), buf.readBoolean(), p -> true);
        if (FMLEnvironment.dist == Dist.CLIENT) rebuildClientUi(inventory.player);
    }

    private static Source readSource(FriendlyByteBuf buf) {
        int i = buf.readVarInt();
        return Source.values()[Math.max(0, Math.min(Source.values().length - 1, i))];
    }

    private static LongSupplier constant(long value) {
        return () -> value;
    }

    /**
     * Opens the coin storage for {@code player}.
     *
     * @param cap     most copper it may hold, or -1 for no limit
     * @param canTake whether this player may take coins out (anyone allowed in may put coins in)
     * @param valid   checked on the server every tick; the screen closes when it fails
     */
    public static void open(ServerPlayer player, Source source, BlockPos pos, UUID outpostId, String title, Container slots,
                            LongSupplier cap, LongSupplier loose, boolean canTake, Predicate<Player> valid) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new CoinStorageMenu(id, inv, source, pos, outpostId, title,
                slots, cap, loose, canTake, valid), Component.literal(title)), buf -> {
            buf.writeVarInt(source.ordinal());
            buf.writeBlockPos(pos);
            buf.writeUUID(outpostId);
            buf.writeUtf(title);
            buf.writeVarLong(cap.getAsLong());
            buf.writeVarLong(loose.getAsLong());
            buf.writeBoolean(canTake);
            buf.writeVarInt(0); // LDLib2 UISyncManager initial pack
        });
    }

    private void addSlots(Inventory inventory) {
        for (int i = 0; i < SIZE; i++) {
            addSlot(new Slot(container, i, -10000, -10000) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return EconomyManager.isCoin(stack.getItem()) && getMaxStackSize(stack) > (hasItem() ? getItem().getCount() : 0);
                }

                @Override
                public boolean mayPickup(Player player) {
                    return canTake;
                }

                /** Only as many coins as still fit under the cap. */
                @Override
                public int getMaxStackSize(ItemStack stack) {
                    if (cap.getAsLong() < 0) return super.getMaxStackSize(stack);
                    long each = EconomyManager.stackCopper(stack.copyWithCount(1));
                    if (each <= 0) return 0;
                    int here = ItemStack.isSameItemSameComponents(getItem(), stack) ? getItem().getCount() : 0;
                    long fits = here + room() / each;
                    return (int) Math.min(super.getMaxStackSize(stack), fits);
                }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, -10000, -10000));
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, -10000, -10000));
    }

    /** Copper that still fits. */
    private long room() {
        return Math.max(0, cap.getAsLong() - loose.getAsLong() - slotsValue());
    }

    private long slotsValue() {
        long total = 0;
        for (int i = 0; i < SIZE; i++) total += EconomyManager.stackCopper(slots.get(i).getItem());
        return total;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < SIZE) {
            if (!canTake || !moveItemStackTo(stack, SIZE, SIZE + 36, true)) return ItemStack.EMPTY;
        } else if (!EconomyManager.isCoin(stack.getItem()) || !moveItemStackTo(stack, 0, SIZE, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    /**
     * A coin worth more than the room left (a platinum into a meter capped at 5 gold) is broken: what still fits goes
     * in, and the rest comes back to the player as change. Without this the slot would just refuse it.
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer sp && cap.getAsLong() >= 0 && breakCoin(sp, slotId, clickType)) return;
        super.clicked(slotId, button, clickType, player);
    }

    private boolean breakCoin(ServerPlayer sp, int slotId, ClickType clickType) {
        ItemStack coin;
        Slot from = null;
        if ((clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE) && slotId >= 0 && slotId < SIZE
                && !getCarried().isEmpty()) {
            coin = getCarried();                              // dropping the held coin onto a coin slot
        } else if (clickType == ClickType.QUICK_MOVE && slotId >= SIZE && slotId < slots.size()) {
            from = slots.get(slotId);                         // shift-clicking a coin in the inventory
            coin = from.getItem();
        } else {
            return false;
        }
        if (coin.isEmpty() || !EconomyManager.isCoin(coin.getItem())) return false;
        long each = EconomyManager.stackCopper(coin.copyWithCount(1));
        long room = room();
        if (each <= room) return false; // fits whole: the normal click handles it
        if (room <= 0) {
            sp.displayClientMessage(Component.literal("§cIt's full: it holds up to " + Currency.format(cap.getAsLong())
                    + "§c. Take some coins out first."), true);
            return true;
        }
        coin.shrink(1);
        if (from != null) from.setChanged();
        long left = CoinSlots.fill(container, slotsValue() + room); // what didn't fit in the slots (normally nothing)
        long change = each - room + left;
        EconomyManager.giveCopperToInventory(sp, change);
        sp.displayClientMessage(Component.literal("§7Only " + Currency.format(room - left) + " §7fit: the coin was broken and "
                + Currency.format(change) + " §7came back as change."), true);
        broadcastChanges();
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().isClientSide || valid.test(player);
    }

    // UI

    @OnlyIn(Dist.CLIENT)
    public void rebuildClientUi(Player player) {
        if (this instanceof IModularUIHolderMenu h) h.setModularUI(buildUi(player));
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI buildUi(Player player) {
        var frame = new UIElement();
        frame.layout(l -> l.width(9 * 20 + 20).height(4 * 20 + 104).paddingAll(2));
        frame.addClass("preview_bg");
        var root = new UIElement();
        root.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).paddingAll(8).gapAll(4));
        root.addClass("panel_bg");

        var header = row();
        var back = new Button().setText("§7← Back").setOnClick(e -> {
            if (source == Source.METER) PacketDistributor.sendToServer(MeterActionPacket.of(pos, MeterService.Action.OPEN_DIAL));
            else PacketDistributor.sendToServer(new OutpostActionPacket(OutpostActionPacket.Action.OPEN_MANAGER, outpostId));
        });
        back.layout(l -> l.width(44).height(16));
        tip(back, source == Source.METER ? "§7Back to the meter" : "§7Back to the outpost");
        header.addChildren(back, new Label().setText("§6§l" + title));
        root.addChildren(header);

        var worth = new Label();
        worth.addEventListener(UIEvents.TICK, ev -> {
            long total = slotsValue() + loose.getAsLong();
            long max = cap.getAsLong();
            boolean full = max >= 0 && total >= max;
            worth.setText((full ? "§c" : "§7") + "Holds: §e" + (total > 0 ? Currency.format(total) : "§8nothing")
                    + (max >= 0 ? " §7of §f" + Currency.format(max).replaceAll("§.", "") : "") + (full ? " §c(full)" : ""));
        });
        if (source == Source.METER) {
            tip(worth, "§7Buying: purchases are paid from these coins and change goes back in.",
                    "§7Selling: what this meter sells is paid in here. When it's full,",
                    "§7it stops selling until you take coins out.", "§8Meter Efficiency upgrades raise the limit.");
        } else {
            tip(worth, "§7The outpost's daily upkeep is paid from these coins.",
                    "§7If they run out, the outpost falls apart unless it's refilled in time.",
                    canTake ? "§7Copper is exchanged into bigger coins on its own." : "§8Only officers can take coins out.");
        }
        root.addChildren(worth);

        var coinRow = row();
        for (int i = 0; i < SIZE; i++) coinRow.addChildren(cell(i));
        root.addChildren(coinRow);

        var invLabel = new Label().setText("§7Your inventory");
        tip(invLabel, "§7Shift-click moves a whole stack across.");
        root.addChildren(invLabel);
        for (int r0 = 0; r0 < 4; r0++) {
            var r = row();
            for (int c = 0; c < 9; c++) r.addChildren(cell(SIZE + r0 * 9 + c));
            root.addChildren(r);
        }
        frame.addChildren(root);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    /** A slot shown in the UI; clicks go to the real slot like in a chest. */
    @OnlyIn(Dist.CLIENT)
    private UIElement cell(int slotIndex) {
        var wrap = new UIElement();
        wrap.layout(l -> l.width(18).height(18).alignItems(AlignItems.CENTER));
        var display = new ItemSlot();
        display.addEventListener(UIEvents.TICK, ev -> display.setItem(slots.get(slotIndex).getItem().copy()));
        UiKit.onClick(display, ev -> {
            if (!(Minecraft.getInstance().screen instanceof CoinStorageScreen screen)) return;
            screen.interactWithSlot(slotIndex, ev.button, Screen.hasShiftDown() ? ClickType.QUICK_MOVE : ClickType.PICKUP);
        });
        wrap.addChildren(display);
        return wrap;
    }

    private static UIElement row() {
        var r = new UIElement();
        r.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(2));
        return r;
    }

    private static void tip(UIElement element, String... lines) {
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> {
            var tips = HoverTooltips.empty();
            for (String line : lines) tips = tips.append(Component.literal(line));
            ev.hoverTooltips = tips;
        });
    }
}
