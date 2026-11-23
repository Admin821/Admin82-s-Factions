package com.admin82.factions.menu;

import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.screen.FactionVaultScreen;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
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
import org.appliedenergistics.yoga.YogaAlign;


import java.util.UUID;

/**
 * The faction item vault: a normal inventory shared by the whole faction. Everyone with it open sees
 * changes straight away (it's one container on the server). The first slots are secure.
 */
public class FactionVaultMenu extends AbstractContainerMenu {

    private static final int COLUMNS = 9;
    private static final int VISIBLE_ROWS = 6;

    public final UUID factionId;
    private final Container container;
    private final int size;
    private final int secure;
    private final int level;
    private final int maxLevel;
    private final int upgradeCost;
    private final boolean canTake;
    private final boolean canUpgrade;

    /** Server side. */
    public FactionVaultMenu(int containerId, Inventory inventory, UUID factionId, Container container, int secure,
                            int level, int maxLevel, int upgradeCost, boolean canTake, boolean canUpgrade) {
        super(ModMenuTypes.FACTION_VAULT.get(), containerId);
        this.factionId = factionId;
        this.container = container;
        this.size = container.getContainerSize();
        this.secure = secure;
        this.level = level;
        this.maxLevel = maxLevel;
        this.upgradeCost = upgradeCost;
        this.canTake = canTake;
        this.canUpgrade = canUpgrade;
        addSlots(inventory);
    }

    /** Client side, from the open-menu buffer (the contents arrive through normal container syncing). */
    public FactionVaultMenu(int containerId, Inventory inventory, FriendlyByteBuf buf) {
        super(ModMenuTypes.FACTION_VAULT.get(), containerId);
        this.factionId = buf.readUUID();
        this.size = buf.readVarInt();
        this.secure = buf.readVarInt();
        this.level = buf.readVarInt();
        this.maxLevel = buf.readVarInt();
        this.upgradeCost = buf.readVarInt();
        this.canTake = buf.readBoolean();
        this.canUpgrade = buf.readBoolean();
        this.container = new SimpleContainer(size);
        addSlots(inventory);
        if (FMLEnvironment.dist == Dist.CLIENT) rebuildClientUi(inventory.player);
    }

    /** What the client constructor reads, in the same order. */
    public static void writeOpenData(FriendlyByteBuf buf, UUID factionId, int size, int secure, int level, int maxLevel,
                                     int upgradeCost, boolean canTake, boolean canUpgrade) {
        buf.writeUUID(factionId);
        buf.writeVarInt(size);
        buf.writeVarInt(secure);
        buf.writeVarInt(level);
        buf.writeVarInt(maxLevel);
        buf.writeVarInt(upgradeCost);
        buf.writeBoolean(canTake);
        buf.writeBoolean(canUpgrade);
    }

    private void addSlots(Inventory inventory) {
        for (int i = 0; i < size; i++) {
            addSlot(new Slot(container, i, -10000, -10000) {
                @Override
                public boolean mayPickup(Player player) {
                    return canTake; // both need the "use storage" permission
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    return canTake && com.admin82.factions.storage.StorageRules.allowed(stack);
                }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, -10000, -10000));
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, -10000, -10000));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < size) {
            if (!moveItemStackTo(stack, size, size + 36, true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, secure, size, false) && !moveItemStackTo(stack, 0, secure, false)) {
            return ItemStack.EMPTY; // regular slots first, then secure ones
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    /** Says why an item that holds other items won't go in (the slots just refuse it otherwise). */
    @Override
    public void clicked(int slotId, int button, net.minecraft.world.inventory.ClickType clickType, Player player) {
        if (player instanceof ServerPlayer sp && slotId >= 0 && slotId < slots.size()) {
            ItemStack moving = clickType == net.minecraft.world.inventory.ClickType.QUICK_MOVE && slotId >= size
                    ? slots.get(slotId).getItem() : slotId < size ? getCarried() : ItemStack.EMPTY;
            if (!com.admin82.factions.storage.StorageRules.allowed(moving)) {
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal("§c" + com.admin82.factions.storage.StorageRules.MESSAGE), true);
            }
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public boolean stillValid(Player player) {
        if (!(player instanceof ServerPlayer sp)) return true;
        var faction = FactionManager.get(sp.server).getFactionForPlayer(sp.getUUID());
        return faction != null && faction.getId().equals(factionId);
    }

    // UI

    @OnlyIn(Dist.CLIENT)
    public void rebuildClientUi(Player player) {
        if (this instanceof IModularUIHolderMenu h) h.setModularUI(buildUi(player));
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI buildUi(Player player) {
        int gridWidth = COLUMNS * 18 + (COLUMNS - 1) * 2;   // 9 slots and the gaps between them
        int scrollerWidth = gridWidth + 16;                  // room for the scrollbar so no column is cut off
        int width = scrollerWidth + 24;
        // Header 16, info 9, secure row 22, the vault, label 9, inventory 4 × 18 + gaps 10; root gaps 20, padding 16
        int height = 16 + 9 + 22 + VISIBLE_ROWS * 20 + 9 + 82 + 20 + 16 + 4;
        var frame = new UIElement();
        frame.layout(l -> l.width(width).height(height).paddingAll(2));
        frame.addClass("preview_bg");
        var root = new UIElement();
        root.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).paddingAll(8).gapAll(4));
        root.addClass("panel_bg");

        var header = row();
        var back = new Button().setText("§7← Back").setOnClick(e -> PacketDistributor.sendToServer(
                new com.admin82.factions.network.packet.ItemVaultActionPacket(0, factionId,
                        com.admin82.factions.network.packet.ItemVaultActionPacket.BACK_TO_TABLE, 0, 0)));
        back.layout(l -> l.width(44).height(16));
        tip(back, "§7Back to the faction table");
        header.addChildren(back, new Label().setText("§6§lItem Vault §7lv " + level + "/" + maxLevel).layout(l -> l.flex(1)));
        var info = new Label().setText("§7" + size + " slots · §6" + secure + " secure");
        tip(info, "§6Secure slots §7(gold) are never lost or looted,", "§7not even when your core is taken.");
        root.addChildren(header, info);

        // Secure slots on their own row, each in a gold frame
        var secureRow = row();
        secureRow.layout(l -> l.height(22).gapAll(3));
        var secureLabel = new Label().setText("§6§lSECURE");
        secureLabel.layout(l -> l.width(42));
        tip(secureLabel, "§6Secure slots §7are never lost or looted,", "§7not even when your core is taken.",
                "§8Each item vault upgrade adds one.");
        secureRow.addChildren(secureLabel);
        for (int i = 0; i < secure; i++) secureRow.addChildren(goldFrame(cell(i, true)));
        root.addChildren(secureRow);

        // The rest of the vault, scrollable
        var grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(2).width(gridWidth));
        for (int start = secure; start < size; start += COLUMNS) {
            var r = row();
            for (int i = start; i < Math.min(size, start + COLUMNS); i++) r.addChildren(cell(i, false));
            grid.addChildren(r);
        }
        var scroller = new ScrollerView();
        scroller.layout(l -> l.width(scrollerWidth).height(VISIBLE_ROWS * 20));
        scroller.addScrollViewChild(grid);
        root.addChildren(scroller);

        // Your inventory
        var invLabel = new Label().setText("§7Your inventory");
        tip(invLabel, "§7Shift-click moves a whole stack across.");
        root.addChildren(invLabel);
        var inv = new UIElement();
        inv.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(2));
        for (int r0 = 0; r0 < 4; r0++) {
            var r = row();
            int top = r0 == 3 ? 4 : 0; // hotbar a little apart, like vanilla
            r.layout(l -> l.marginTop(top));
            for (int c = 0; c < 9; c++) r.addChildren(cell(size + r0 * 9 + c, false));
            inv.addChildren(r);
        }
        root.addChildren(inv);
        frame.addChildren(root);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    /** A slot shown in the UI; clicks go to the real slot like in a chest. */
    @OnlyIn(Dist.CLIENT)
    private UIElement cell(int slotIndex, boolean secureSlot) {
        var wrap = new UIElement();
        wrap.layout(l -> l.width(18).height(18).alignItems(YogaAlign.CENTER));
        if (secureSlot) tip(wrap, "§6Secure slot", "§7Never lost or looted, not even when your core is taken.");
        var display = new ItemSlot();
        display.addEventListener(UIEvents.TICK, ev -> display.setItem(slots.get(slotIndex).getItem().copy()));
        UiKit.onClick(display, ev -> {
            if (!(Minecraft.getInstance().screen instanceof FactionVaultScreen screen)) return;
            screen.interactWithSlot(slotIndex, ev.button, Screen.hasShiftDown() ? ClickType.QUICK_MOVE : ClickType.PICKUP);
        });
        wrap.addChildren(display);
        return wrap;
    }

    /** A solid gold frame round a slot, marking it secure. */
    private static UIElement goldFrame(UIElement slot) {
        var frame = UiKit.background(new UIElement(), 0xFFD4A020);
        frame.layout(l -> l.width(22).height(22).paddingAll(2).alignItems(YogaAlign.CENTER).justifyContent(org.appliedenergistics.yoga.YogaJustify.CENTER));
        frame.addChildren(slot);
        return frame;
    }

    private static UIElement row() {
        var r = new UIElement();
        r.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(2));
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
