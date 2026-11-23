package com.admin82.factions.menu;

import com.admin82.factions.seam.SeamCell;
import com.admin82.factions.seam.SeamTier;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import dev.vfyjxf.taffy.style.AlignItems;

import org.appliedenergistics.yoga.YogaJustify;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A grid of chunks coloured by seam richness, each showing its ore. Used by the Seam Viewer and the admin panel.
 */
@OnlyIn(Dist.CLIENT)
public final class SeamMap {

    private SeamMap() {}

    /** Background colour of a seam's cell. */
    public static int color(SeamCell c) {
        if (!c.minable()) return 0xC0303030;
        if (c.endless()) return 0xD0B07818;
        return switch (c.seamTier()) {
            case OVERFLOWING -> 0xD08A3CC8;
            case RICH -> 0xD02E86B8;
            case MIDDLING -> 0xD03C9A44;
            case POOR -> 0xD0A89A2C;
            default -> 0xC0303030;
        };
    }

    public static ItemStack ore(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return id.isEmpty() || rl == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(rl));
    }

    /** "Rich", "Endless (Rich speed)", "Depleted", with colour codes. */
    public static String describe(SeamCell c) {
        if (!c.hasSeam()) return "§8No seam";
        if (!c.minable()) return "§8Depleted";
        SeamTier t = c.seamTier();
        return c.endless() ? "§6Endless §7(" + t.color + t.speedLabel + "§7 speed)" : t.color + t.label;
    }

    public static List<Component> tooltip(SeamCell c, boolean here) {
        List<Component> lines = new ArrayList<>();
        ItemStack ore = ore(c.ore());
        lines.add(Component.literal("§fChunk " + c.chunkX() + ", " + c.chunkZ() + (here ? " §e(you are here)" : "")));
        if (c.hasSeam()) lines.add(Component.literal((c.minable() ? "§f" : "§7") + ore.getHoverName().getString()));
        lines.add(Component.literal(describe(c)));
        if (c.scannedAt() > 0) lines.add(Component.literal("§8Scanned " + ago(System.currentTimeMillis() - c.scannedAt()) + " ago"));
        if (c.minable()) {
            lines.add(Component.literal(c.endless() ? "§7Never runs out" : "§7" + String.format(Locale.ROOT, "%,d", c.remaining())
                    + " of " + String.format(Locale.ROOT, "%,d", c.amount()) + " left, then " + c.seamTier().next().label));
        }
        return lines;
    }

    /**
     * The grid, {@code size} × {@code size} chunks in the order {@link SeamCell#area} sends them (north at the top).
     *
     * @param playerChunk "x,z" of the viewer's chunk, outlined in white
     * @param selected    "x,z" of the selected chunk, outlined in orange (or null)
     * @param onClick     called when a chunk is clicked (or null for a map you can only look at)
     */
    public static UIElement grid(List<SeamCell> cells, int size, int cellPx, String playerChunk, @Nullable String selected,
                                 @Nullable Consumer<SeamCell> onClick) {
        var grid = new UIElement();
        grid.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(1));
        UIElement row = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % size == 0) {
                row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(1));
                grid.addChildren(row);
            }
            row.addChildren(cell(cells.get(i), cellPx, playerChunk, selected, onClick));
        }
        return grid;
    }

    public static UIElement cell(SeamCell c, int px, String playerChunk, @Nullable String selected, @Nullable Consumer<SeamCell> onClick) {
        String key = c.chunkX() + "," + c.chunkZ();
        boolean here = key.equals(playerChunk);
        boolean chosen = key.equals(selected);
        int outline = chosen ? 0xFFFF9A1A : here ? 0xFFFFFFFF : 0;
        var outer = UiKit.background(new UIElement(), outline);
        outer.layout(l -> l.width(px).height(px).paddingAll(outline != 0 ? 1 : 0));
        var inner = UiKit.background(new UIElement(), color(c));
        inner.layout(l -> l.flex(1).alignItems(AlignItems.CENTER).justifyContent(YogaJustify.CENTER));
        if (c.minable()) {
            var icon = new ItemSlot().setItem(ore(c.ore()));
            icon.style(st -> st.background(com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY));
            int icon16 = Math.min(16, px - 2);
            icon.layout(l -> l.width(icon16).height(icon16));
            inner.addChildren(icon);
        }
        outer.addChildren(inner);
        List<Component> tip = tooltip(c, here);
        if (onClick != null) tip.add(Component.literal("§8Click to select"));
        Component[] lines = tip.toArray(Component[]::new);
        outer.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(lines));
        if (onClick != null) UiKit.onClick(outer, ev -> onClick.accept(c));
        return outer;
    }

    private static String ago(long ms) {
        long m = Math.max(0, ms) / 60_000;
        if (m < 1) return "under a minute";
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 48) return h + "h";
        return (h / 24) + " days";
    }

    /** One line of colour swatches explaining the map. */
    public static UIElement legend() {
        return legend(6);
    }

    /** The colour key, {@code perRow} entries to a row (so it fits narrow screens). */
    public static UIElement legend(int perRow) {
        var col = new UIElement();
        col.layout(l -> l.flexDirection(FlexDirection.COLUMN).alignItems(AlignItems.CENTER).gapAll(2));
        String[] names = {"§dOverflowing", "§6Endless", "§bRich", "§aMiddling", "§ePoor", "§8Depleted"};
        int[] colors = {0xD08A3CC8, 0xD0B07818, 0xD02E86B8, 0xD03C9A44, 0xD0A89A2C, 0xC0303030};
        UIElement row = null;
        for (int i = 0; i < names.length; i++) {
            if (i % perRow == 0) {
                row = new UIElement();
                row.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(3).height(10));
                col.addChildren(row);
            }
            var swatch = UiKit.background(new UIElement(), colors[i]);
            swatch.layout(l -> l.width(7).height(7));
            row.addChildren(swatch, fit(names[i]));
        }
        return col;
    }

    /** A label exactly as wide as its text (so centring it works and it never spills sideways). */
    public static UIElement fit(String text) {
        int w = net.minecraft.client.Minecraft.getInstance().font.width(net.minecraft.ChatFormatting.stripFormatting(text)) + 2;
        return new com.lowdragmc.lowdraglib2.gui.ui.elements.Label().setText(text).layout(l -> l.width(w).height(10));
    }
}
