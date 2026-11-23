package com.admin82.factions.menu;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.network.packet.ItemVaultActionPacket;
import com.admin82.factions.network.packet.SyncItemVaultPacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.storage.ItemVaultService;
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
import net.minecraft.client.gui.screens.Screen;
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

import org.appliedenergistics.yoga.YogaJustify;

import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Screen for an item vault: the faction item vault or an outpost storage box. Stored items on top,
 * your inventory below. Clicking moves items; the server checks everything and sends the result
 * back to everyone who has the same vault open.
 */
public class ItemVaultMenu extends AbstractContainerMenu {

    private static final int COLUMNS = 9;
    private static final int CELL = 34;

    private final ItemVaultService.Kind kind;
    private final UUID id;
    @Nullable private SyncItemVaultPacket data;
    @Nullable private UIElement storedArea;
    @Nullable private UIElement header;

    /** Server side. */
    public ItemVaultMenu(int containerId, Inventory inventory, ItemVaultService.Kind kind, UUID id) {
        super(ModMenuTypes.ITEM_VAULT.get(), containerId);
        this.kind = kind;
        this.id = id;
    }

    /** Client side, from the open-menu buffer. */
    public ItemVaultMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(ModMenuTypes.ITEM_VAULT.get(), containerId);
        int k = buf.readVarInt();
        this.kind = ItemVaultService.Kind.values()[Math.max(0, Math.min(ItemVaultService.Kind.values().length - 1, k))];
        this.id = buf.readUUID();
        if (FMLEnvironment.dist == Dist.CLIENT) rebuildClientUi(inventory.player);
    }

    public boolean matches(ItemVaultService.Kind kind, UUID id) {
        return this.kind == kind && this.id.equals(id);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }

    public void updateData(SyncItemVaultPacket sync) {
        if (sync.kind() != kind.ordinal() || !sync.id().equals(id)) return;
        data = sync;
        if (FMLEnvironment.dist == Dist.CLIENT) render();
    }

    private void send(ItemVaultService.Action action, int index, int amount) {
        PacketDistributor.sendToServer(new ItemVaultActionPacket(kind.ordinal(), id, action.ordinal(), index, amount));
    }

    // UI

    @OnlyIn(Dist.CLIENT)
    public void rebuildClientUi(Player player) {
        if (this instanceof IModularUIHolderMenu h) h.setModularUI(buildUi(player));
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI buildUi(Player player) {
        int width = COLUMNS * CELL + 24;
        int height = 330;
        var frame = new UIElement();
        frame.layout(l -> l.width(width).height(height).paddingAll(2));
        frame.addClass("preview_bg");
        var root = new UIElement();
        root.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).paddingAll(8).gapAll(5));
        root.addClass("panel_bg");

        header = new UIElement();
        header.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(2));
        storedArea = UiKit.background(new UIElement(), UiKit.CARD);
        storedArea.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).gapAll(2).paddingAll(4));

        var inventory = UiKit.background(new UIElement(), UiKit.CARD);
        inventory.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(2).paddingAll(4));
        var invLabel = new Label().setText("§7Your inventory §8(hover for help)");
        tip(invLabel, "§7Click: put in the stack", "§7Shift-click: put in all of that item");
        inventory.addChildren(invLabel);
        // Main inventory (slots 9-35) then hotbar (0-8)
        for (int row = 0; row < 4; row++) {
            var r = rowOf();
            r.layout(l -> l.alignSelf(YogaAlign.CENTER));
            for (int col = 0; col < 9; col++) {
                int slot = row < 3 ? 9 + row * 9 + col : col;
                r.addChildren(inventoryCell(player, slot));
            }
            inventory.addChildren(r);
        }

        root.addChildren(header, storedArea, inventory);
        frame.addChildren(root);
        render();
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    @OnlyIn(Dist.CLIENT)
    private void render() {
        if (header == null || storedArea == null) return;
        header.clearAllChildren();
        storedArea.clearAllChildren();
        if (data == null) {
            header.addChildren(new Label().setText("§7Loading…"));
            return;
        }
        var top = rowOf();
        top.layout(l -> l.justifyContent(YogaJustify.SPACE_BETWEEN));
        var title = new Label().setText("§6§l" + data.title() + " §7(level " + data.level() + ")");
        if (data.level() < data.maxLevel() && !data.upgradeNote().isEmpty()) tip(title, "§7" + data.upgradeNote());
        top.addChildren(title);
        if (kind == ItemVaultService.Kind.SPOILS) {
            var coinsRow = rowOf();
            coinsRow.addChildren(new Label().setText("§7Coins left behind: §e" + Currency.format(Math.max(0, data.coins()))));
            if (data.coins() > 0) {
                var take = new Button().setText("§aTake coins").setOnClick(e -> send(ItemVaultService.Action.TAKE_COINS, 0, 0));
                take.layout(l -> l.width(90).height(16));
                tip(take, "§7Goes to your faction vault.");
                coinsRow.addChildren(take);
            }
            header.addChildren(top, coinsRow, new Label().setText("§7Take what you want. When it's empty, the table crumbles."));
        } else {
            header.addChildren(top, new Label().setText("§7" + data.lines().size() + "/" + data.maxKinds() + " kinds · up to §f"
                    + data.maxPerKind() + "§7 of each"));
        }

        var storedLabel = new Label().setText(data.lines().isEmpty()
                ? "§7Empty. Click items below to put them in."
                : "§7Stored §8(hover for help)");
        tip(storedLabel, "§7Click: take a stack", "§7Shift-click: take as much as fits", "§7Right-click: take one");
        storedArea.addChildren(storedLabel);
        UIElement r = null;
        for (int i = 0; i < data.lines().size(); i++) {
            if (i % COLUMNS == 0) {
                r = rowOf();
                storedArea.addChildren(r);
            }
            r.addChildren(storedCell(i, data.lines().get(i)));
        }
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement storedCell(int index, SyncItemVaultPacket.Line line) {
        var cell = new UIElement();
        cell.layout(l -> l.width(CELL - 2).flexDirection(FlexDirection.COLUMN).alignItems(YogaAlign.CENTER));
        var slot = new ItemSlot().setItem(line.item().copyWithCount(1));
        UiKit.onClick(slot, e -> {
            if (data == null || !data.canWithdraw()) return;
            int amount = e.button == 1 ? 1 : Screen.hasShiftDown() ? 64 * 36 : line.item().getMaxStackSize();
            send(ItemVaultService.Action.WITHDRAW, index, amount);
        });
        cell.addChildren(slot, new Label().setText("§f" + shortCount(line.count())));
        return cell;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement inventoryCell(Player player, int slotIndex) {
        var slot = new ItemSlot();
        slot.addEventListener(UIEvents.TICK, e -> slot.setItem(player.getInventory().items.get(slotIndex).copy()));
        UiKit.onClick(slot, e -> {
            if (player.getInventory().items.get(slotIndex).isEmpty()) return;
            send(Screen.hasShiftDown() ? ItemVaultService.Action.DEPOSIT_ALL_OF : ItemVaultService.Action.DEPOSIT_SLOT, slotIndex, 0);
        });
        var cell = new UIElement();
        cell.layout(l -> l.width(18).height(18).alignItems(YogaAlign.CENTER));
        cell.addChildren(slot);
        return cell;
    }

    private static String shortCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 10_000) return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
        return Long.toString(n);
    }

    private static UIElement rowOf() {
        var r = new UIElement();
        r.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(2));
        return r;
    }

    private static void tip(UIElement element, String... lines) {
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> {
            var tips = HoverTooltips.empty();
            for (String line : lines) if (line != null && !line.isEmpty()) tips = tips.append(Component.literal(line));
            ev.hoverTooltips = tips;
        });
    }
}
