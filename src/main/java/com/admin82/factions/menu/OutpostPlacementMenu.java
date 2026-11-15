package com.admin82.factions.menu;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.network.packet.OpenOutpostPlacementPacket;
import com.admin82.factions.network.packet.PlaceOutpostPacket;
import com.admin82.factions.outpost.OutpostEntry;
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
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.appliedenergistics.yoga.YogaJustify;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Confirmation screen shown when an Outpost item is used on a block: where it goes, what it claims, what it costs
 * per day, and (if it can't be placed) why. Opened on the client from {@link OpenOutpostPlacementPacket}.
 */
public class OutpostPlacementMenu extends AbstractContainerMenu implements IModularUIHolderMenu {

    private static final int WIDTH = 270;
    private static final int TEXT_WIDTH = WIDTH - 32;

    @Nullable private ModularUI modularUI;
    private final OpenOutpostPlacementPacket data;
    /** Whether an answer was sent (so closing the screen doesn't send a second "cancel"). */
    private boolean answered;

    public OutpostPlacementMenu(int id, Inventory inv, OpenOutpostPlacementPacket data) {
        super(null, id);
        this.data = data;
        if (FMLEnvironment.dist == Dist.CLIENT) setModularUI(createModularUI(inv.player));
    }

    @Override @Nullable public ModularUI getModularUI() { return modularUI; }
    @Override public void setModularUI(ModularUI ui) { this.modularUI = ui; }
    @Override public ModularUI ldlib2$getModularUI() { return modularUI; }
    @Override public void ldlib2$setModularUI(ModularUI ui) { this.modularUI = ui; }
    @Override @Nullable public ItemSlot ldlib2$getItemSlot(Slot slot) { return null; }
    @Override public void ldlib2$addSlot(ItemSlot slot) { }
    @Override public boolean stillValid(Player p) { return true; }
    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }

    /** Sends the answer once: confirmed places the outpost, anything else cancels. */
    public void answer(boolean confirmed) {
        if (answered) return;
        answered = true;
        PacketDistributor.sendToServer(new PlaceOutpostPacket(confirmed));
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI createModularUI(Player player) {
        boolean ok = data.problem().isEmpty();
        List<String> problemLines = ok ? List.of() : wrap(data.problem());
        int chunks = OutpostEntry.chunkOffsets(1).size();
        List<String> buildLines = wrapAll(
                "§7• A §fflag §7(2 blocks tall) with a §fstorage box §7beside it",
                "§7• §f" + chunks + " chunk" + (chunks == 1 ? "" : "s") + " §7of land claimed for your faction",
                "§7• Outpost slots: " + (data.slotsUsed() >= data.slots() ? "§c" : "§f") + data.slotsUsed() + " / " + data.slots()
                        + " §7used" + (ok ? " §8(this makes " + (data.slotsUsed() + 1) + ")" : ""));
        List<String> upkeepLines = wrapAll(
                "§e" + Currency.format(data.upkeep()).replaceAll("§.", "").trim() + "§7 per day, paid from the outpost's own coin storage. Fill it at the flag.",
                "§cIf it runs out and isn't refilled in time, it falls apart.",
                "§7Any faction except your allies can capture it at any time by breaking the flag.");
        // Height: padding, title, the two cards (heading + lines), the problem box, buttons, and the gaps between them
        int height = 4 + 16 + 12 + 6 + cardHeight(buildLines.size()) + 6 + cardHeight(upkeepLines.size()) + 6 + 18
                + (ok ? 0 : problemLines.size() * 11 + 12 + 6);
        var frame = new UIElement();
        frame.layout(l -> l.width(WIDTH).height(height).paddingAll(2));
        frame.addClass("preview_bg");
        var root = new UIElement();
        root.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).paddingAll(8).gapAll(6));
        root.addClass("panel_bg");

        // Title
        var title = new UIElement();
        title.layout(l -> l.flexDirection(YogaFlexDirection.ROW).justifyContent(YogaJustify.SPACE_BETWEEN).alignItems(YogaAlign.CENTER));
        title.addChildren(new Label().setText("§d§l⚑ Place an outpost"),
                UiKit.rightLabel("§7" + data.pos().getX() + ", " + data.pos().getY() + ", " + data.pos().getZ()));
        root.addChildren(title);

        // What gets built and claimed
        var builds = card("§f§lWhat you get");
        for (String l : buildLines) builds.addChildren(line(l));
        tip(builds, "§7Upgrade it in Faction Upgrades for more land,", "§7a stronger flag and a bigger storage box.");
        root.addChildren(builds);

        // Upkeep
        var upkeep = card("§6§lUpkeep");
        for (String l : upkeepLines) upkeep.addChildren(line(l));
        tip(upkeep, "§7Costs more the further it is from your faction table.");
        root.addChildren(upkeep);

        // Why it can't be placed
        if (!ok) {
            var problem = UiKit.background(new UIElement(), 0x60801818);
            problem.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(1).paddingAll(6));
            for (String s : problemLines) problem.addChildren(line("§c" + s));
            root.addChildren(problem);
        }

        // Buttons
        var buttons = new UIElement();
        buttons.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6));
        var cancel = new Button().setText("§c✘ Cancel").setOnClick(e -> close(false));
        cancel.layout(l -> l.flex(1).height(18));
        var confirm = new Button().setText(ok ? "§a✔ Place outpost" : "§8✔ Place outpost").setOnClick(e -> {
            if (ok) close(true);
        });
        confirm.layout(l -> l.flex(1).height(18));
        confirm.setActive(ok);
        tip(confirm, ok ? "§7Uses up one Outpost item." : "§cCan't be placed here.");
        buttons.addChildren(cancel, confirm);
        root.addChildren(buttons);

        frame.addChildren(root);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    @OnlyIn(Dist.CLIENT)
    private void close(boolean confirmed) {
        answer(confirmed);
        Minecraft.getInstance().setScreen(null);
    }

    private static UIElement card(String heading) {
        var c = UiKit.background(new UIElement(), UiKit.CARD);
        c.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(2).paddingAll(6));
        c.addChildren(new Label().setText(heading));
        return c;
    }

    private static UIElement line(String text) {
        return new Label().setText(text).layout(l -> l.height(9));
    }

    private static int cardHeight(int lines) {
        return 12 + 11 + lines * 11;
    }

    /**
     * Wraps each text to the panel's width, carrying its colour onto the next line; a wrapped bullet point's
     * following lines are indented under its text.
     */
    @OnlyIn(Dist.CLIENT)
    private static List<String> wrapAll(String... texts) {
        List<String> out = new ArrayList<>();
        for (String text : texts) {
            List<String> parts = wrap(text);
            String color = "";
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                boolean bullet = text.replaceAll("§.", "").startsWith("•");
                out.add(i == 0 ? part : color + (bullet ? "  " : "") + part);
                color = lastColor(color, part);
            }
        }
        return out;
    }

    /** The last colour code in a line (or {@code current} when it has none). */
    private static String lastColor(String current, String line) {
        String color = current;
        for (int i = 0; i < line.length() - 1; i++) {
            if (line.charAt(i) == '§') color = "§" + line.charAt(i + 1);
        }
        return color;
    }

    /** Splits text into lines that fit the panel. */
    @OnlyIn(Dist.CLIENT)
    private static List<String> wrap(String text) {
        var font = Minecraft.getInstance().font;
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && font.width(next) > TEXT_WIDTH) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(next);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    private static void tip(UIElement element, String... lines) {
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> {
            var tips = HoverTooltips.empty();
            for (String line : lines) tips = tips.append(Component.literal(line));
            ev.hoverTooltips = tips;
        });
    }
}
