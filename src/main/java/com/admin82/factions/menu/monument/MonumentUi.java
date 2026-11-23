package com.admin82.factions.menu.monument;

import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.monument.MonumentEntry;
import com.admin82.factions.monument.MonumentView;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.IObserver;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import dev.vfyjxf.taffy.style.AlignItems;


import java.util.Arrays;
import javax.annotation.Nullable;

/** Small layout helpers and parsing shared by the monument editor tabs. */
final class MonumentUi {

    private MonumentUi() {}

    @OnlyIn(Dist.CLIENT)
    static TextField field(String value, IObserver<String> observer) {
        var field = UiKit.textField("");
        field.setValue(value);
        field.bindObserver(observer);
        field.layout(l -> l.width(180).height(18));
        return field;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement hbox(int gap) {
        var box = new UIElement();
        box.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(gap));
        return box;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement vbox(int gap) {
        var box = new UIElement();
        box.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(gap));
        return box;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement card() {
        var box = vbox(4);
        box.layout(l -> l.paddingAll(6));
        UiKit.background(box, UiKit.CARD);
        return box;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement spacer(int height) {
        var box = new UIElement();
        box.layout(l -> l.height(height));
        return box;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement sectionTitle(String text) {
        return new Label().setText("§6§l" + text);
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement cell(String text, int width) {
        return new Label().setText(text).layout(l -> l.width(width));
    }

    /** Table cell that takes a share of the row width (flex weight) instead of a fixed size. */
    @OnlyIn(Dist.CLIENT)
    static UIElement flexCell(String text, int weight) {
        return new Label().setText(text).layout(l -> l.flex(weight));
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement spacerWidth(int width) {
        var box = new UIElement();
        box.layout(l -> l.width(width));
        return box;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement infoRow(String label, String value) {
        var row = hbox(6);
        row.layout(l -> l.height(12));
        row.addChildren(cell("§7" + label, 80), new Label().setText("§f" + value));
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement formRow(String label, TextField field, int fieldWidth, String... tooltipLines) {
        field.layout(l -> l.width(fieldWidth).height(18));
        var row = hbox(6);
        row.layout(l -> l.height(20));
        row.addChildren(cell("§7" + label, 120), field);
        if (tooltipLines.length > 0) tooltip(row, tooltipLines);
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement legendRow(int color, String text) {
        var swatch = new UIElement();
        swatch.layout(l -> l.width(10).height(10));
        UiKit.background(swatch, 0xFF000000 | (color & 0xFFFFFF));
        var row = hbox(6);
        row.layout(l -> l.height(12));
        row.addChildren(swatch, new Label().setText("§7" + text));
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    static void tooltip(UIElement element, String... lines) {
        Component[] components = Arrays.stream(lines).map(Component::literal).toArray(Component[]::new);
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, event ->
                event.hoverTooltips = HoverTooltips.empty().append(components));
    }

    static String shortRoleName(RoguePmcRole role) {
        return role.displayName().replace("Rogue ", "").replace(" PMC", "");
    }

    static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    static String coords(MonumentView view) {
        return view.x() + ", " + view.y() + ", " + view.z();
    }

    static long refillSeconds(MonumentView view) {
        return Math.max(0L, Math.round(view.remainingRespawnTicks() / 20.0));
    }

    static int[] defaultClassMasks() {
        int[] masks = new int[MonumentMenu.LOOT_SLOTS];
        Arrays.fill(masks, MonumentEntry.ALL_AI_CLASSES);
        return masks;
    }

    static int[] defaultLootCounts() {
        int[] counts = new int[MonumentMenu.LOOT_SLOTS];
        Arrays.fill(counts, 1);
        return counts;
    }

    static int parse(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException ignored) { return fallback; }
    }

    static String formatDuration(long seconds) {
        long minutes = seconds / 60;
        return minutes > 0 ? minutes + "m " + seconds % 60 + "s" : seconds + "s";
    }

    @Nullable
    static ServerLevel levelFor(MinecraftServer server, String dimension) {
        var id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(
                Registries.DIMENSION, id));
    }
}
