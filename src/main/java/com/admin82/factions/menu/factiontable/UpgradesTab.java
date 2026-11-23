package com.admin82.factions.menu.factiontable;

import com.admin82.factions.faction.UpgradeService;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.network.packet.FactionUpgradePacket;
import com.admin82.factions.network.packet.SyncFactionUpgradesPacket;
import com.admin82.factions.registry.ModItems;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.vfyjxf.taffy.style.AlignItems;


import javax.annotation.Nullable;

/**
 * The Faction Upgrades tab: one node per upgrade. Click a node to see what it does next and buy it with warbonds.
 */
final class UpgradesTab {

    private static final int NODE_WIDTH = 200;
    private static final int NODE_HEIGHT = 34;
    private static final int SELECTED = 0x80CC7700;
    private static final int AFFORDABLE = 0x40308030;
    private static final int MAXED = 0x40A08020;

    private final FactionTableMenu menu;
    @Nullable private SyncFactionUpgradesPacket data;
    @Nullable private UIElement panel;
    /** The selected node: "track:outpostId". */
    @Nullable private String selected;
    private boolean armed;

    UpgradesTab(FactionTableMenu menu) {
        this.menu = menu;
    }

    @OnlyIn(Dist.CLIENT)
    UIElement buildPanel() {
        panel = new UIElement();
        panel.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).gapAll(4));
        PacketDistributor.sendToServer(new FactionUpgradePacket(FactionUpgradePacket.REFRESH, ""));
        render();
        return panel;
    }

    void update(SyncFactionUpgradesPacket sync) {
        data = sync;
        armed = false;
        render();
    }

    private static String key(SyncFactionUpgradesPacket.Row row) {
        return row.track() + ":" + row.outpostId();
    }

    private int warbonds() {
        return data == null ? 0 : data.warbondsInVault() + data.warbondsCarried();
    }

    private static boolean unlimited(SyncFactionUpgradesPacket.Row row) { return row.maxLevel() == 0; }

    /** A seam path the faction can't take because it picked another one (sent as maxLevel -1). */
    private static boolean locked(SyncFactionUpgradesPacket.Row row) { return row.maxLevel() < 0; }

    private static boolean maxed(SyncFactionUpgradesPacket.Row row) { return !unlimited(row) && !locked(row) && row.level() >= row.maxLevel(); }

    /** The sub-tabs the upgrades are split into. */
    private enum Section {
        FACTION("Faction"), OUTPOSTS("Outposts"), SEAMS("Seam Mining");
        final String label;
        Section(String label) { this.label = label; }
    }

    private Section section = Section.FACTION;

    private static Section sectionOf(SyncFactionUpgradesPacket.Row row) {
        return switch (track(row)) {
            case ITEM_VAULT, LAND, METER_EFFICIENCY -> Section.FACTION;
            case OUTPOST_SLOTS, OUTPOST -> Section.OUTPOSTS;
            case SEAM_MINERS, SEAM_SPEED, SEAM_QUANTITY, SEAM_EFFICIENCY -> Section.SEAMS;
        };
    }

    private static boolean isPath(SyncFactionUpgradesPacket.Row row) {
        var t = track(row);
        return t == UpgradeService.Track.SEAM_SPEED || t == UpgradeService.Track.SEAM_QUANTITY || t == UpgradeService.Track.SEAM_EFFICIENCY;
    }

    @OnlyIn(Dist.CLIENT)
    private void render() {
        if (panel == null) return;
        panel.clearAllChildren();
        var title = new UIElement();
        title.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).height(14));
        title.addChildren(new Label().setText("§6§lFaction Upgrades").layout(l -> l.flex(1)));
        if (data != null) {
            var have = warbondAmount(warbonds(), warbonds() > 0 ? "§d" : "§8", 14);
            tip(have, "§dWarbonds: " + warbonds(), "§7" + data.warbondsInVault() + " in the item vault, " + data.warbondsCarried() + " on you.",
                    "§8Won in wars. Upgrades use the vault's first.");
            title.addChildren(have);
        }
        panel.addChildren(title);
        if (data == null) {
            panel.addChildren(new Label().setText("§7Loading…"));
            return;
        }
        if (!data.canUpgrade()) panel.addChildren(new Label().setText("§8Only officers can buy upgrades."));

        // Sub-tabs
        var tabs = new UIElement();
        tabs.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).height(18));
        for (Section sec : Section.values()) {
            int ready = 0;
            for (var row : data.rows()) {
                if (sectionOf(row) == sec && !maxed(row) && !locked(row) && warbonds() >= row.cost()) ready++;
            }
            String text = (sec == section ? "§e§l" : "§7") + sec.label + (ready > 0 ? " §a(" + ready + ")" : "");
            var b = new Button().setText(text).setOnClick(e -> {
                if (section == sec) return;
                section = sec;
                selected = null;
                armed = false;
                render();
            });
            b.layout(l -> l.flex(1).height(18));
            if (ready > 0) tip(b, "§a" + ready + " upgrade" + (ready == 1 ? "" : "s") + " you can afford here");
            tabs.addChildren(b);
        }
        panel.addChildren(tabs);

        java.util.List<SyncFactionUpgradesPacket.Row> rows = new java.util.ArrayList<>();
        for (var row : data.rows()) if (sectionOf(row) == section) rows.add(row);
        SyncFactionUpgradesPacket.Row chosen = null;
        for (var row : rows) if (key(row).equals(selected)) chosen = row;
        if (chosen == null && !rows.isEmpty()) {
            chosen = rows.get(0);
            selected = key(chosen);
        }

        if (rows.isEmpty()) {
            panel.addChildren(new Label().setText("§8Nothing to upgrade here yet."));
        } else if (section == Section.SEAMS) {
            panel.addChildren(seamSection(rows));
        } else {
            panel.addChildren(tiles(rows));
        }
        if (chosen != null) panel.addChildren(details(chosen));
    }

    /** Tiles two to a row. */
    @OnlyIn(Dist.CLIENT)
    private UIElement tiles(java.util.List<SyncFactionUpgradesPacket.Row> rows) {
        var nodes = new UIElement();
        nodes.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(5));
        UIElement line = null;
        for (int i = 0; i < rows.size(); i++) {
            if (i % 2 == 0) {
                line = new UIElement();
                line.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6));
                nodes.addChildren(line);
            }
            line.addChildren(node(rows.get(i)));
        }
        return nodes;
    }

    /** Extractor quantity, then the three seam paths as a "pick one" tree. */
    @OnlyIn(Dist.CLIENT)
    private UIElement seamSection(java.util.List<SyncFactionUpgradesPacket.Row> rows) {
        var box = new UIElement();
        box.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(4));
        var paths = new java.util.ArrayList<SyncFactionUpgradesPacket.Row>();
        for (var row : rows) {
            if (isPath(row)) paths.add(row);
            else box.addChildren(node(row));
        }
        if (paths.isEmpty()) return box;

        SyncFactionUpgradesPacket.Row taken = null;
        for (var row : paths) if (!locked(row) && row.level() > 0) taken = row;

        var tree = UiKit.background(new UIElement(), UiKit.CARD);
        tree.layout(l -> l.flexDirection(FlexDirection.COLUMN).paddingAll(4));
        tree.addChildren(new Label().setText(taken == null
                ? "§6§lMining path §7· pick §fONE§7, the other two lock for good"
                : "§6§lMining path §7· your faction took §e" + taken.name().replace("Seam ", "")).layout(l -> l.height(11)));
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) {
                var or = new UIElement();
                or.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(4).height(9).paddingLeft(11));
                var bar = UiKit.background(new UIElement(), 0x40FFFFFF);
                bar.layout(l -> l.width(2).height(9));
                or.addChildren(bar, fitted("§8or"));
                tree.addChildren(or);
            }
            tree.addChildren(pathRow(paths.get(i), taken));
        }
        box.addChildren(tree);
        return box;
    }

    private static final int PATH_LEVELS = 4;

    /** One path: icon and name, its four levels joined like a track, and its state. */
    @OnlyIn(Dist.CLIENT)
    private UIElement pathRow(SyncFactionUpgradesPacket.Row row, @Nullable SyncFactionUpgradesPacket.Row taken) {
        boolean isSelected = key(row).equals(selected);
        boolean isLocked = locked(row);
        boolean isTaken = row == taken;
        int bg = isSelected ? SELECTED : isLocked ? 0x30000000 : isTaken ? MAXED : UiKit.ROW;
        var line = UiKit.background(new UIElement(), bg);
        line.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(6).height(20).paddingHorizontal(3));

        var icon = new ItemSlot().setItem(iconFor(row));
        icon.layout(l -> l.width(16).height(16));
        String name = row.name().replace("Seam ", "");
        line.addChildren(icon, new Label().setText((isLocked ? "§8" : isSelected ? "§e§l" : "§f§l") + name).layout(l -> l.width(70)));

        // The track: I━II━III━IV
        var track = new UIElement();
        track.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        int max = isLocked ? PATH_LEVELS : Math.max(1, row.maxLevel());
        for (int lvl = 1; lvl <= max; lvl++) {
            if (lvl > 1) {
                var link = UiKit.background(new UIElement(), row.level() >= lvl ? 0xFF4CD04C : isLocked ? 0x30FFFFFF : 0x60FFFFFF);
                link.layout(l -> l.width(8).height(2));
                track.addChildren(link);
            }
            boolean owned = !isLocked && row.level() >= lvl;
            boolean next = !isLocked && lvl == row.level() + 1;
            int fill = isLocked ? 0xFF3A2424 : owned ? 0xFF3FAF3F : next ? 0xFF6A6A6A : 0xFF3C3C3C;
            var pip = UiKit.background(new UIElement(), fill);
            pip.layout(l -> l.width(18).height(12).alignItems(AlignItems.CENTER).justifyContent(dev.vfyjxf.taffy.style.AlignContent.CENTER));
            String r = roman(lvl);
            pip.addChildren(fitted((owned ? "§f" : isLocked ? "§8" : "§7") + r));
            track.addChildren(pip);
        }
        line.addChildren(track);

        UIElement state;
        if (isLocked) state = fitted("§c🔒 Locked");
        else if (maxed(row)) state = fitted("§6✔ Max");
        else state = warbondAmount(row.cost(), warbonds() >= row.cost() ? "§a" : "§d", 12);
        var spacer = new UIElement();
        spacer.layout(l -> l.flex(1));
        line.addChildren(spacer, state);

        UiKit.onClick(line, e -> { selected = key(row); armed = false; render(); });
        line.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(tileTooltip(row)));
        return line;
    }

    private static String roman(int n) {
        return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; default -> String.valueOf(n); };
    }

    /** One upgrade as a clickable tile: icon, name, then level dots and price on one line. */
    @OnlyIn(Dist.CLIENT)
    private UIElement node(SyncFactionUpgradesPacket.Row row) {
        boolean isSelected = key(row).equals(selected);
        boolean done = maxed(row);
        boolean affordable = !done && !locked(row) && warbonds() >= row.cost();
        var tile = UiKit.background(new UIElement(), isSelected ? SELECTED : done ? MAXED : affordable ? AFFORDABLE : UiKit.ROW);
        tile.layout(l -> l.width(NODE_WIDTH).height(NODE_HEIGHT).flexDirection(FlexDirection.ROW)
                .alignItems(AlignItems.CENTER).gapAll(6).paddingAll(4));
        var icon = new ItemSlot().setItem(iconFor(row));
        var text = new UIElement();
        text.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).gapAll(2));
        String name = row.name().startsWith("Outpost: ") ? row.name().substring(9) : row.name();
        var bottom = new UIElement();
        bottom.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(6).height(10));
        bottom.addChildren(fitted(levelText(row)),
                locked(row) ? fitted("§c🔒 Locked") : done ? fitted("§6✔ Max")
                        : warbondAmount(row.cost(), affordable ? "§a" : "§d", 10));
        text.addChildren(new Label().setText((isSelected ? "§e§l" : "§f§l") + shorten(name, NODE_WIDTH - 36)), bottom);
        tile.addChildren(icon, text);
        UiKit.onClick(tile, e -> { selected = key(row); armed = false; render(); });
        tile.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty()
                .append(tileTooltip(row)));
        return tile;
    }

    /** Dots for capped upgrades (●●○), a plain level for unlimited ones. */
    private static String levelText(SyncFactionUpgradesPacket.Row row) {
        if (locked(row)) return "§8○○○○";
        if (unlimited(row)) return "§7level §f" + row.level();
        StringBuilder dots = new StringBuilder("§a");
        for (int i = 1; i <= row.maxLevel(); i++) {
            if (i == row.level() + 1) dots.append("§8");
            dots.append(i <= row.level() ? "●" : "○");
        }
        return dots.toString();
    }

    /** The selected node: what it does now, what the next level does, and the buy button. */
    @OnlyIn(Dist.CLIENT)
    private UIElement details(SyncFactionUpgradesPacket.Row row) {
        var card = UiKit.background(new UIElement(), UiKit.CARD);
        card.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(2).paddingAll(5));
        card.addChildren(new Label().setText("§e§l" + row.name() + (locked(row) ? " §c· locked" : " §7· level " + row.level()
                + (unlimited(row) ? " §8(no limit)" : "/" + row.maxLevel()))));
        for (String line : description(row)) {
            for (String part : wrap(line, DETAILS_TEXT_WIDTH)) card.addChildren(new Label().setText("§7• " + part));
        }
        card.addChildren(stats(row));
        if (maxed(row)) {
            card.addChildren(new Label().setText("§6✔ Fully upgraded."));
            return card;
        }
        if (locked(row)) {
            card.addChildren(new Label().setText("§c🔒 Locked: your faction already took another mining path."));
            return card;
        }
        var buyRow = new UIElement();
        buyRow.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(6).height(18));
        boolean affordable = warbonds() >= row.cost();
        boolean canBuy = affordable && data.canUpgrade();
        if (!canBuy) armed = false;
        var buy = new Button().setText(armed ? "§f§lClick again to buy" : (canBuy ? "§a" : "§8") + "Upgrade");
        buy.setActive(canBuy); // greyed out and unclickable until there are enough warbonds
        buy.setOnClick(e -> {
            if (data == null || !data.canUpgrade() || warbonds() < row.cost()) return;
            if (!armed) { armed = true; render(); return; }
            armed = false;
            PacketDistributor.sendToServer(new FactionUpgradePacket(row.track(), row.outpostId()));
        });
        buy.layout(l -> l.width(120).height(18));
        String note = !data.canUpgrade() ? "§8Officers only"
                : affordable ? "§8Click twice to buy" : "§cNeed " + (row.cost() - warbonds()) + " more";
        buyRow.addChildren(buy, warbondAmount(row.cost(), affordable ? "§a" : "§d", 14), fitted(note));
        card.addChildren(buyRow);
        return card;
    }

    private static final int DETAILS_TEXT_WIDTH = 380;

    private static UpgradeService.Track track(SyncFactionUpgradesPacket.Row row) {
        UpgradeService.Track[] tracks = UpgradeService.Track.values();
        return row.track() >= 0 && row.track() < tracks.length ? tracks[row.track()] : UpgradeService.Track.LAND;
    }

    /** What an upgrade is for, as short points. */
    private static String[] description(SyncFactionUpgradesPacket.Row row) {
        return switch (track(row)) {
            case ITEM_VAULT -> new String[]{"Your faction's shared chest, in the faction table.",
                    "Each level adds slots and one secure slot.",
                    "Secure slots are always saved if your core is taken."};
            case LAND -> new String[]{"How far from the faction table you can claim land.",
                    "Each level reaches one chunk further in every direction."};
            case OUTPOST_SLOTS -> new String[]{"How many outposts your faction can have at once."};
            case OUTPOST -> new String[]{"More land around this outpost's flag.",
                    "More flag HP before it's captured.",
                    "A bigger storage box."};
            case SEAM_MINERS -> new String[]{"How many Seam Extractors your faction can run on one seam."};
            case SEAM_SPEED -> new String[]{"Extractors mine faster, but seams deplete faster.",
                    "Each level doubles the power all your extractors use."};
            case SEAM_QUANTITY -> new String[]{"Bonus ore per extraction, without faster depletion.",
                    "Each level doubles the power all your extractors use."};
            case SEAM_EFFICIENCY -> new String[]{"Seams last much longer; extractors mine a bit slower.",
                    "Each level doubles the power all your extractors use."};
            case METER_EFFICIENCY -> new String[]{"Makes your members' power and fluid meters better.",
                    "They buy faster from networks and hold more.",
                    "Their coin storage holds more before it's full.",
                    "Each level adds the same bonus again."};
        };
    }

    @OnlyIn(Dist.CLIENT)
    private static Component[] tileTooltip(SyncFactionUpgradesPacket.Row row) {
        var lines = new java.util.ArrayList<Component>();
        lines.add(Component.literal("§f" + row.name()));
        for (String line : description(row)) lines.add(Component.literal("§7" + line));
        lines.add(Component.literal("§8Click for details"));
        return lines.toArray(Component[]::new);
    }

    /** "Now" and "next level" side by side, one stat per line. */
    @OnlyIn(Dist.CLIENT)
    private static UIElement stats(SyncFactionUpgradesPacket.Row row) {
        String[] now = row.now().split(" · ");
        boolean hasNext = !maxed(row) && !row.next().isEmpty();
        String[] next = hasNext ? row.next().split(" · ") : new String[0];
        var table = new UIElement();
        table.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(1));
        table.addChildren(statRow("§8Now", hasNext ? "§8Next level" : "", hasNext));
        for (int i = 0; i < Math.max(now.length, next.length); i++) {
            String a = i < now.length ? now[i] : "";
            String b = i < next.length ? next[i] : "";
            table.addChildren(statRow("§f" + a, b.equals(a) ? "§7" + b : "§a" + b, hasNext));
        }
        return table;
    }

    @OnlyIn(Dist.CLIENT)
    private static UIElement statRow(String now, String next, boolean arrow) {
        var line = new UIElement();
        line.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).height(10));
        line.addChildren(new Label().setText(now).layout(l -> l.width(STAT_WIDTH)));
        if (arrow) {
            line.addChildren(new Label().setText(now.startsWith("§8") ? "" : "§8→").layout(l -> l.width(14)),
                    new Label().setText(next).layout(l -> l.width(STAT_WIDTH)));
        }
        return line;
    }

    private static final int STAT_WIDTH = 180;

    /** A label exactly as wide as its text, for rows where LDLib would otherwise give it no room. */
    @OnlyIn(Dist.CLIENT)
    private static Label fitted(String text) {
        int width = net.minecraft.client.Minecraft.getInstance().font.width(text) + 2;
        Label label = new Label();
        label.setText(text);
        label.layout(l -> l.width(width));
        return label;
    }

    /** The warbond item with an amount next to it. */
    @OnlyIn(Dist.CLIENT)
    private static UIElement warbondAmount(int amount, String color, int iconSize) {
        var box = new UIElement();
        box.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(2).height(iconSize));
        var icon = new ItemSlot().setItem(new ItemStack(ModItems.WARBOND.get()));
        icon.slotStyle(st -> st.showItemTooltips(true)); // hover the icon to see the Warbond item
        icon.style(st -> st.background(com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY));
        icon.layout(l -> l.width(iconSize).height(iconSize));
        box.addChildren(icon, fitted(color + "×" + amount));
        return box;
    }

    @OnlyIn(Dist.CLIENT)
    private static void tip(UIElement element, String... lines) {
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> {
            var tips = HoverTooltips.empty();
            for (String line : lines) tips = tips.append(Component.literal(line));
            ev.hoverTooltips = tips;
        });
    }

    /** Splits text into lines that fit {@code maxPixels}, breaking at spaces. */
    @OnlyIn(Dist.CLIENT)
    private static java.util.List<String> wrap(String text, int maxPixels) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        var lines = new java.util.ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (font.width(next) > maxPixels && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static ItemStack iconFor(SyncFactionUpgradesPacket.Row row) {
        return switch (track(row)) {
            case ITEM_VAULT -> new ItemStack(Items.CHEST);
            case LAND -> new ItemStack(Items.GRASS_BLOCK);
            case OUTPOST_SLOTS -> new ItemStack(Items.WHITE_BANNER);
            case OUTPOST -> new ItemStack(ModItems.OUTPOST.get());
            case METER_EFFICIENCY -> new ItemStack(Items.COMPARATOR);
            case SEAM_MINERS -> new ItemStack(ModItems.SEAM_EXTRACTOR.get());
            case SEAM_SPEED -> new ItemStack(Items.GOLDEN_PICKAXE);
            case SEAM_QUANTITY -> new ItemStack(Items.RAW_IRON);
            case SEAM_EFFICIENCY -> new ItemStack(Items.DIAMOND_PICKAXE);
        };
    }

    @OnlyIn(Dist.CLIENT)
    private static String shorten(String text, int maxPixels) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        if (font.width(text) <= maxPixels) return text;
        return font.plainSubstrByWidth(text, Math.max(10, maxPixels - font.width("…"))) + "…";
    }
}
