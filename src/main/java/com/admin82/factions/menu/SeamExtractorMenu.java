package com.admin82.factions.menu;

import com.admin82.factions.network.packet.SyncSeamExtractorPacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.screen.SeamExtractorScreen;
import com.admin82.factions.seam.SeamExtractorBlockEntity;
import com.admin82.factions.seam.SeamTier;
import com.admin82.factions.util.BypassManager;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import dev.vfyjxf.taffy.style.AlignItems;


import javax.annotation.Nullable;
import java.util.Locale;

/**
 * A Seam Extractor's screen: the seam it's mining, how fast, its power, and its output slots (take only).
 */
public class SeamExtractorMenu extends AbstractContainerMenu {

    private static final int SIZE = SeamExtractorBlockEntity.SLOTS;
    private static final int WIDTH = 250;

    public final BlockPos pos;
    private final Container container;
    @Nullable private SyncSeamExtractorPacket data;
    @Nullable private UIElement info;

    /** Server side. */
    public SeamExtractorMenu(int id, Inventory inventory, SeamExtractorBlockEntity be) {
        super(ModMenuTypes.SEAM_EXTRACTOR.get(), id);
        this.pos = be.getBlockPos();
        this.container = be.output;
        addSlots(inventory);
    }

    /** Client side, from the open-menu buffer. */
    public SeamExtractorMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        super(ModMenuTypes.SEAM_EXTRACTOR.get(), id);
        this.pos = buf.readBlockPos();
        this.container = new SimpleContainer(SIZE);
        addSlots(inventory);
        if (FMLEnvironment.dist == Dist.CLIENT) rebuildClientUi(inventory.player);
    }

    private void addSlots(Inventory inventory) {
        for (int i = 0; i < SIZE; i++) {
            addSlot(new Slot(container, i, -10000, -10000) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
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
        if (index >= SIZE || !slot.hasItem()) return ItemStack.EMPTY; // only ore comes out; nothing goes in
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (!moveItemStackTo(stack, SIZE, SIZE + 36, true)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) return true;
        if (!(player.level().getBlockEntity(pos) instanceof SeamExtractorBlockEntity be) || be.output != container) return false;
        return player.distanceToSqr(pos.getCenter()) <= 64;
    }

    /** Whether {@code player} may open it: its faction's members (or an admin with bypass). */
    public static boolean canUse(net.minecraft.server.level.ServerPlayer player, SeamExtractorBlockEntity be) {
        if (BypassManager.isBypassing(player.getUUID())) return true;
        var f = com.admin82.factions.faction.FactionManager.get(player.server).getFactionForPlayer(player.getUUID());
        return f != null && f.getId().equals(be.faction);
    }

    public void update(SyncSeamExtractorPacket sync) {
        data = sync;
        if (FMLEnvironment.dist == Dist.CLIENT) renderInfo();
    }

    // UI

    @OnlyIn(Dist.CLIENT)
    public void rebuildClientUi(Player player) {
        if (this instanceof IModularUIHolderMenu h) h.setModularUI(buildUi(player));
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI buildUi(Player player) {
        var frame = new UIElement();
        frame.layout(l -> l.width(WIDTH).height(310).paddingAll(2));
        frame.addClass("preview_bg");
        var root = new UIElement();
        root.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).paddingAll(8).gapAll(4));
        root.addClass("panel_bg");

        root.addChildren(new Label().setText("§6§l⛏ Seam Extractor"));
        info = new UIElement();
        info.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(4).height(146));
        root.addChildren(info);
        renderInfo();

        var slotsColumn = new UIElement();
        slotsColumn.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(4).width(9 * 18).alignSelf(AlignItems.CENTER));
        var outLabel = new Label().setText("§7Ore mined");
        tip(outLabel, "§7Click to take. Hoppers and pipes can empty it too.");
        slotsColumn.addChildren(outLabel);
        var out = row();
        out.layout(l -> l.gapAll(0));
        for (int i = 0; i < SIZE; i++) out.addChildren(cell(i));
        slotsColumn.addChildren(out);

        slotsColumn.addChildren(new Label().setText("§7Your inventory"));
        var inv = new UIElement();
        inv.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(0));
        for (int r0 = 0; r0 < 4; r0++) {
            var r = row();
            int top = r0 == 3 ? 4 : 0; // hotbar a little apart, like vanilla
            r.layout(l -> l.gapAll(0).marginTop(top));
            for (int c = 0; c < 9; c++) r.addChildren(cell(SIZE + r0 * 9 + c));
            inv.addChildren(r);
        }
        slotsColumn.addChildren(inv);
        root.addChildren(slotsColumn);
        frame.addChildren(root);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderInfo() {
        if (info == null) return;
        info.clearAllChildren();
        if (data == null) {
            info.addChildren(new Label().setText("§7Loading…"));
            return;
        }
        var d = data;
        info.addChildren(new Label().setText(d.status()));

        // The seam
        var seam = card();
        var head = row();
        var icon = new ItemSlot();
        ResourceLocation id = ResourceLocation.tryParse(d.ore());
        ItemStack ore = id == null || d.ore().isEmpty() ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(id));
        icon.setItem(ore);
        icon.slotStyle(st -> st.showItemTooltips(true));
        var names = new UIElement();
        names.layout(l -> l.flexDirection(FlexDirection.COLUMN).flex(1));
        names.addChildren(new Label().setText("§f§l" + (ore.isEmpty() ? "No ore" : ore.getHoverName().getString())),
                new Label().setText("§7Seam: " + d.seamText()));
        head.addChildren(icon, names);
        seam.addChildren(head);
        if (!ore.isEmpty() && d.tier() != SeamTier.DEPLETED.ordinal()) {
            if (d.endless()) {
                seam.addChildren(new Label().setText("§6Never runs out."));
            } else {
                SeamTier next = SeamTier.values()[d.tier()].next();
                seam.addChildren(bar(d.tierAmount() <= 0 ? 0 : d.remaining() / (double) d.tierAmount(), 0xFF3A9A4A),
                        new Label().setText("§f" + num(d.remaining()) + " §7of " + num(d.tierAmount()) + " left, then "
                                + next.color + next.label));
            }
        }
        tip(seam, "§7Seams aren't real blocks: only a Seam Extractor can mine them.",
                "§7Mining one out drops it to the next richness, down to Depleted.");
        info.addChildren(seam);

        // Mining speed
        var mining = card();
        String rate = d.secondsPerOre() <= 0 ? "§8Not mining" : "§f1 extraction every " + seconds(d.secondsPerOre())
                + (d.orePerExtraction() > 1 ? " §7(×" + trim(d.orePerExtraction()) + " ore)" : "");
        mining.addChildren(new Label().setText(rate), bar(d.progress(), 0xFFCC8822));
        mining.addChildren(new Label().setText("§7Upgrade: §f" + (d.pathLevel() == 0 ? "none" : d.path() + " " + d.pathLevel())
                        + " §8· §7depletion §f" + trim(d.wear()) + "§7/ore"),
                new Label().setText("§7Extractors on this seam: " + (d.miners() > d.minerLimit() ? "§c" : "§f")
                        + d.miners() + "§7/" + d.minerLimit()));
        tip(mining, "§7Seam upgrades are bought in Faction Upgrades.",
                "§7Your faction can run " + d.minerLimit() + " extractor" + (d.minerLimit() == 1 ? "" : "s") + " on each seam.");
        info.addChildren(mining);

        // Power
        var power = card();
        power.addChildren(new Label().setText("§7Power: §f" + num(d.energy()) + " §7/ " + num(d.capacity()) + " FE"),
                bar(d.capacity() <= 0 ? 0 : d.energy() / (double) d.capacity(), 0xFFCC3333),
                new Label().setText("§7Needs a steady §f" + num(d.powerPerTick()) + " FE/t §7while mining"));
        tip(power, "§7Feed it with any FE cable from any side.", "§7It needs a steady supply: while the power runs short,", "§7the extraction in progress winds back down (like a furnace).", "§7Each seam upgrade level doubles the power it uses.");
        info.addChildren(power);
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement bar(double fill, int color) {
        double f = Math.max(0, Math.min(1, fill));
        int inner = WIDTH - 36;
        var track = UiKit.background(new UIElement(), UiKit.BAR_TRACK);
        track.layout(l -> l.width(inner).height(5).flexDirection(FlexDirection.ROW));
        if (f > 0) {
            var bar = UiKit.background(new UIElement(), color);
            bar.layout(l -> l.width(Math.max(1, (int) (inner * f))).height(5));
            track.addChildren(bar);
        }
        return track;
    }

    private static String num(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
    }

    private static String seconds(double s) {
        return s >= 10 ? Math.round(s) + "s" : String.format(Locale.ROOT, "%.1fs", s);
    }

    /** A slot shown in the UI; clicks go to the real slot like in a chest. */
    @OnlyIn(Dist.CLIENT)
    private UIElement cell(int slotIndex) {
        var wrap = new UIElement();
        wrap.layout(l -> l.width(18).height(18).alignItems(AlignItems.CENTER));
        var display = new ItemSlot();
        display.addEventListener(UIEvents.TICK, ev -> display.setItem(slots.get(slotIndex).getItem().copy()));
        UiKit.onClick(display, ev -> {
            if (!(Minecraft.getInstance().screen instanceof SeamExtractorScreen screen)) return;
            screen.interactWithSlot(slotIndex, ev.button, Screen.hasShiftDown() ? ClickType.QUICK_MOVE : ClickType.PICKUP);
        });
        wrap.addChildren(display);
        return wrap;
    }

    private static UIElement card() {
        var c = UiKit.background(new UIElement(), UiKit.CARD);
        c.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(2).paddingAll(4));
        return c;
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
