package com.admin82.factions.menu.monument;

import com.admin82.factions.entity.monument.AiBehaviorData;
import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.menu.monument.MonumentMenu.EditorMode;
import com.admin82.factions.menu.monument.MonumentMenu.SlotSettingsSaver;
import com.admin82.factions.monument.MonumentView;
import com.admin82.factions.network.packet.MonumentAiActionPacket;
import com.admin82.factions.network.packet.MonumentAiLoadoutItemPacket;
import com.admin82.factions.network.packet.MonumentAiRangesPacket;
import com.admin82.factions.supplydrop.SupplyDropPool;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

/** The AI Roster, AI Gear and AI Drops tabs: how many guards spawn, what they carry and what they drop. */
final class GuardTabs {

    private final MonumentMenu menu;

    GuardTabs(MonumentMenu menu) {
        this.menu = menu;
    }

    int selectedLoadoutPickerSlot = -1;
    private String loadoutSearchText = "";
    private int loadoutSearchScroll;

    private static final String[] GEAR_LABELS = {"Weapon", "Helmet", "Chestplate", "Leggings", "Boots"};

    private static final int PICKER_PAGE_SIZE = 9;

    // AI drops tab

    @OnlyIn(Dist.CLIENT)
    UIElement buildDropsTab(MonumentView selected) {
        var panel = MonumentUi.hbox(8);
        panel.layout(l -> l.alignItems(YogaAlign.FLEX_START));
        var contents = MonumentUi.card();
        contents.layout(l -> l.width(184));
        contents.addChildren(MonumentUi.sectionTitle("Death Drops"),
                new Label().setText("§8Rolled when a guard dies."),
                menu.slotGrid(0, 6));
        var center = MonumentUi.vbox(8);
        center.layout(l -> l.width(184));
        center.addChildren(contents, menu.inventoryCard());
        panel.addChildren(center, buildSlotSettingsCard(menu.aiDropMinCounts, menu.aiDropMaxCounts, menu.aiDropRarityLevels,
                (slot, minimum, maximum, rarity) -> PacketDistributor.sendToServer(new MonumentAiActionPacket(
                        MonumentAiActionPacket.Action.SAVE_DROP_SLOT_SETTINGS, selected.id(), 0,
                        minimum, maximum, slot, rarity)),
                new DropClassSection(selected)));
        return panel;
    }

    /** "Dropped by" toggles for the selected AI drop item, and a per-class summary when nothing is selected. */
    @OnlyIn(Dist.CLIENT)
    private final class DropClassSection {
        private final MonumentView selected;

        private DropClassSection(MonumentView selected) {
            this.selected = selected;
        }

        UIElement toggles(int slot) {
            var section = MonumentUi.vbox(4);
            int mask = menu.aiDropClassMasks[slot];
            var row = MonumentUi.hbox(3);
            row.layout(l -> l.height(20));
            for (RoguePmcRole role : RoguePmcRole.values()) {
                boolean allowed = (mask & (1 << role.ordinal())) != 0;
                var toggle = new Button().setText((allowed ? "§a✔ " : "§8✗ ") + MonumentUi.shortRoleName(role).replace(" Gunner", ""))
                        .setOnClick(event -> {
                            int updated = menu.aiDropClassMasks[slot] ^ (1 << role.ordinal());
                            menu.aiDropClassMasks[slot] = updated;
                            PacketDistributor.sendToServer(new MonumentAiActionPacket(
                                    MonumentAiActionPacket.Action.SAVE_DROP_SLOT_CLASSES, selected.id(), 0,
                                    updated, 0, slot, 0));
                            MonumentMenu.refreshMonumentScreen();
                        });
                toggle.layout(l -> l.flex(1).height(18));
                if (allowed) toggle.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#2a6e2aff)");
                MonumentUi.tooltip(toggle, allowed ? "§7" + role.displayName() + " §acan§7 drop this." : "§7" + role.displayName()
                        + " §cwill not§7 drop this.", "§8Click to toggle. Saves instantly.");
                row.addChildren(toggle);
            }
            section.addChildren(new Label().setText("§6Dropped by"), row);
            if (mask == 0) section.addChildren(new Label().setText("§cNo class drops this item."));
            return section;
        }

        UIElement summary() {
            var section = MonumentUi.vbox(3);
            section.addChildren(new Label().setText("§6Drops by class"));
            int total = 0;
            for (int slot = 0; slot < MonumentMenu.LOOT_SLOTS; slot++) if (!menu.slots.get(slot).getItem().isEmpty()) total++;
            for (RoguePmcRole role : RoguePmcRole.values()) {
                List<String> names = new ArrayList<>();
                for (int slot = 0; slot < MonumentMenu.LOOT_SLOTS; slot++) {
                    ItemStack stack = menu.slots.get(slot).getItem();
                    if (!stack.isEmpty() && (menu.aiDropClassMasks[slot] & (1 << role.ordinal())) != 0) {
                        names.add(stack.getHoverName().getString());
                    }
                }
                var line = new Label().setText("§7" + MonumentUi.shortRoleName(role) + ": §f" + names.size() + " §7of §f" + total + " §7items");
                List<String> tip = new ArrayList<>();
                tip.add("§e" + role.displayName() + " can drop:");
                names.stream().limit(15).forEach(name -> tip.add("§7- " + name));
                if (names.size() > 15) tip.add("§8...and " + (names.size() - 15) + " more");
                if (names.isEmpty()) tip.add("§8Nothing");
                MonumentUi.tooltip(line, tip.toArray(String[]::new));
                section.addChildren(line);
            }
            section.addChildren(new Label().setText("§8Hover a class to see its items."));
            return section;
        }
    }

    @OnlyIn(Dist.CLIENT)
    UIElement buildSlotSettingsCard(int[] minimumCounts, int[] maximumCounts, int[] rarityLevels,
                                            SlotSettingsSaver saver, @Nullable DropClassSection classSection) {
        var editor = MonumentUi.card();
        editor.layout(l -> l.flex(1));
        editor.addChildren(MonumentUi.sectionTitle("Item Settings"));
        int slot = menu.selectedLootSettingsSlot;
        if (slot < 0 || slot >= MonumentMenu.LOOT_SLOTS || menu.slots.get(slot).getItem().isEmpty()) {
            editor.addChildren(
                    new Label().setText("§7Right-click an item in the grid to set"),
                    new Label().setText("§7how many spawn and how often it appears."));
            if (classSection != null) editor.addChildren(MonumentUi.spacer(4), classSection.summary());
        } else {
            ItemStack stack = menu.slots.get(slot).getItem();
            int[] minimum = {Math.max(1, minimumCounts[slot])};
            int[] maximum = {Math.max(minimum[0], maximumCounts[slot])};
            int[] rarity = {Math.clamp(rarityLevels[slot], 0, 10)};
            var minimumField = MonumentUi.field(Integer.toString(minimum[0]), value -> minimum[0] = MonumentUi.parse(value, minimum[0]));
            var maximumField = MonumentUi.field(Integer.toString(maximum[0]), value -> maximum[0] = MonumentUi.parse(value, maximum[0]));
            var rarityField = MonumentUi.field(Integer.toString(rarity[0]), value -> rarity[0] = MonumentUi.parse(value, rarity[0]));

            var icon = new ItemSlot();
            icon.setItem(stack.copy());
            icon.layout(l -> l.width(18).height(18));
            var title = MonumentUi.hbox(6);
            title.layout(l -> l.height(20));
            title.addChildren(icon, new Label().setText("§e" + MonumentUi.truncate(stack.getHoverName().getString(), 34)));

            var save = new Button().setText("§aSave").setOnClick(event ->
                    saver.save(slot, minimum[0], maximum[0], rarity[0]));
            save.layout(l -> l.width(80).height(20));
            var close = new Button().setText("Close").setOnClick(event -> {
                menu.selectedLootSettingsSlot = -1;
                MonumentMenu.refreshMonumentScreen();
            });
            close.layout(l -> l.width(60).height(20));
            var buttons = MonumentUi.hbox(4);
            buttons.layout(l -> l.height(20));
            buttons.addChildren(save, close);

            editor.addChildren(title,
                    MonumentUi.formRow("Min amount", minimumField, 60, "§7Smallest stack size that can spawn."),
                    MonumentUi.formRow("Max amount", maximumField, 60, "§7Largest stack size that can spawn."),
                    MonumentUi.formRow("Rarity (0-10)", rarityField, 60, "§7Higher is rarer. See the guide below."),
                    buttons);
            if (classSection != null) {
                // Keep the panel short: class toggles replace the full rarity guide here.
                editor.addChildren(MonumentUi.spacer(4), classSection.toggles(slot), MonumentUi.spacer(4),
                        new Label().setText("§8Rarity: 0 = always (100%) ... 10 = 0.1%"));
                return editor;
            }
        }
        editor.addChildren(MonumentUi.spacer(6), MonumentUi.sectionTitle("Rarity Guide"));
        for (int level = 0; level <= 10; level++) {
            editor.addChildren(new Label().setText("§7" + level + " §8- §f" + SupplyDropPool.getRarityName(level)
                    + " §8- §a" + SupplyDropPool.getAppearanceChanceText(level)).layout(l -> l.height(10)));
        }
        return editor;
    }

    // AI roster tab

    @OnlyIn(Dist.CLIENT)
    UIElement buildRosterTab(MonumentView selected) {
        var panel = MonumentUi.hbox(8);
        panel.layout(l -> l.alignItems(YogaAlign.FLEX_START));

        var table = MonumentUi.card();
        table.layout(l -> l.width(370));
        table.addChildren(MonumentUi.sectionTitle("Guards per Refill"),
                new Label().setText("§7Each refill spawns between Min and Max guards of each class."),
                MonumentUi.spacer(2));
        var head = MonumentUi.hbox(6);
        head.layout(l -> l.height(12));
        head.addChildren(MonumentUi.cell("§8Class", 110), MonumentUi.cell("§8Min", 44), MonumentUi.cell("§8Max", 44), MonumentUi.cell("§8Difficulty", 160));
        table.addChildren(head);

        for (RoguePmcRole role : RoguePmcRole.values()) {
            int index = role.ordinal();
            var minimumField = MonumentUi.field(Integer.toString(menu.aiMinimumCounts[index]), value -> {
                int parsed = MonumentUi.parse(value, menu.aiMinimumCounts[index]);
                if (parsed != menu.aiMinimumCounts[index]) {
                    menu.aiMinimumCounts[index] = parsed;
                    menu.aiRangesDirty = true;
                }
            });
            var maximumField = MonumentUi.field(Integer.toString(menu.aiMaximumCounts[index]), value -> {
                int parsed = MonumentUi.parse(value, menu.aiMaximumCounts[index]);
                if (parsed != menu.aiMaximumCounts[index]) {
                    menu.aiMaximumCounts[index] = parsed;
                    menu.aiRangesDirty = true;
                }
            });
            minimumField.layout(l -> l.width(44).height(18));
            maximumField.layout(l -> l.width(44).height(18));
            int level = Math.clamp(menu.aiDifficultyLevels[index], 1, 5);
            var easier = new Button().setText("-").setOnClick(event -> saveDifficulty(selected, role, level - 1));
            easier.layout(l -> l.width(18).height(18));
            MonumentUi.tooltip(easier, "§7Make this class easier.");
            var harder = new Button().setText("+").setOnClick(event -> saveDifficulty(selected, role, level + 1));
            harder.layout(l -> l.width(18).height(18));
            MonumentUi.tooltip(harder, "§7Make this class harder.");
            var row = MonumentUi.hbox(6);
            row.layout(l -> l.height(22));
            row.addChildren(MonumentUi.cell("§f" + MonumentUi.shortRoleName(role), 110), minimumField, maximumField, easier,
                    MonumentUi.cell("§e" + AiBehaviorData.DIFFICULTY_NAMES[level - 1] + " §8" + level + "/5", 90), harder);
            table.addChildren(row);
        }

        var save = new Button().setText("§aSave Counts").setOnClick(event -> {
            menu.aiRangesDirty = false;
            PacketDistributor.sendToServer(new MonumentAiRangesPacket(
                    selected.id(), menu.aiMinimumCounts.clone(), menu.aiMaximumCounts.clone()));
        });
        save.layout(l -> l.width(110).height(20));
        var footer = MonumentUi.hbox(8);
        footer.layout(l -> l.height(20));
        footer.addChildren(save, new Label().setText("§8Difficulty changes save instantly."));
        table.addChildren(MonumentUi.spacer(4), footer);

        var info = MonumentUi.card();
        info.layout(l -> l.flex(1));
        info.addChildren(MonumentUi.sectionTitle("This Cycle"),
                new Label().setText("§7Guards killed: §c" + menu.deadAiCount),
                new Label().setText("§8Dead guards return on the next refill."),
                MonumentUi.spacer(6), MonumentUi.sectionTitle("Difficulty"),
                new Label().setText("§7Each level shifts accuracy, reaction,"),
                new Label().setText("§7awareness and aggression by 15."),
                new Label().setText("§8Tune each class with"),
                new Label().setText("§f/faction ai edit"));
        panel.addChildren(table, info);
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private void saveDifficulty(MonumentView selected, RoguePmcRole role, int level) {
        int clamped = Math.clamp(level, 1, 5);
        menu.aiDifficultyLevels[role.ordinal()] = clamped;
        PacketDistributor.sendToServer(new MonumentAiActionPacket(
                MonumentAiActionPacket.Action.SAVE_ROLE_DIFFICULTY, selected.id(), role.ordinal(),
                clamped, 0, 0, 0));
        MonumentMenu.refreshMonumentScreen();
    }

    // AI gear tab

    @OnlyIn(Dist.CLIENT)
    UIElement buildGearTab(MonumentView selected) {
        if (selectedLoadoutPickerSlot >= 0) return buildLoadoutItemPicker(selected);
        var panel = MonumentUi.hbox(8);
        panel.layout(l -> l.alignItems(YogaAlign.FLEX_START));

        var classes = MonumentUi.card();
        classes.layout(l -> l.width(150));
        classes.addChildren(MonumentUi.sectionTitle("Guard Class"));
        for (RoguePmcRole role : RoguePmcRole.values()) {
            boolean active = role == menu.selectedAiRole;
            var button = new Button().setText((active ? "§f§l" : "§7") + MonumentUi.shortRoleName(role)).setOnClick(event -> {
                menu.selectedAiRole = role;
                menu.editorMode = EditorMode.AI_LOADOUT;
                PacketDistributor.sendToServer(new MonumentAiActionPacket(
                        MonumentAiActionPacket.Action.SELECT_ROLE, selected.id(), role.ordinal(), 0, 0, 0, 0));
            });
            button.layout(l -> l.width(138).height(20));
            if (active) button.lss("base-background", MonumentMenu.ACTIVE_BG);
            classes.addChildren(button);
        }
        int level = Math.clamp(menu.aiDifficultyLevels[menu.selectedAiRole.ordinal()], 1, 5);
        classes.addChildren(MonumentUi.spacer(6),
                new Label().setText("§7Difficulty: §e" + AiBehaviorData.DIFFICULTY_NAMES[level - 1]),
                new Label().setText("§8Change on the AI Roster tab."));

        var gear = MonumentUi.card();
        gear.layout(l -> l.flex(1));
        gear.addChildren(MonumentUi.sectionTitle(menu.selectedAiRole.displayName() + " Loadout"),
                new Label().setText("§8Slots show the class default until you change them."),
                MonumentUi.spacer(2));
        for (int slot = 0; slot < 5; slot++) {
            int index = slot;
            var itemName = new Label();
            itemName.bindDataSource(SupplierDataSource.of(() -> Component.literal(gearItemName(index))));
            itemName.layout(l -> l.flex(1));
            var change = new Button().setText("Change").setOnClick(event -> openLoadoutPicker(index));
            change.layout(l -> l.width(60).height(18));
            MonumentUi.tooltip(change, "§7Search for any item to use here.");
            var reset = new Button().setText("§7Reset").setOnClick(event -> {
                selectedLoadoutPickerSlot = index;
                selectLoadoutItem(selected, "");
            });
            reset.layout(l -> l.width(50).height(18));
            MonumentUi.tooltip(reset, "§7Remove the override and use the class default.");
            var row = MonumentUi.hbox(6);
            row.layout(l -> l.height(24));
            row.addChildren(MonumentUi.cell("§7" + GEAR_LABELS[slot], 70), menu.displaySlot(slot), itemName, change, reset);
            gear.addChildren(row);
        }
        gear.addChildren(MonumentUi.spacer(4), new Label().setText("§8Tip: click a slot or §7Change§8 to pick an item."));
        panel.addChildren(classes, gear);
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private String gearItemName(int slot) {
        ItemStack stack = menu.slots.get(slot).getItem();
        return stack.isEmpty() ? "§8(empty)" : "§f" + MonumentUi.truncate(stack.getHoverName().getString(), 30);
    }

    @OnlyIn(Dist.CLIENT)
    void openLoadoutPicker(int slot) {
        selectedLoadoutPickerSlot = slot;
        loadoutSearchText = "";
        loadoutSearchScroll = 0;
        MonumentMenu.refreshMonumentScreen();
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildLoadoutItemPicker(MonumentView selected) {
        var panel = MonumentUi.card();
        panel.layout(l -> l.flex(1));
        panel.addEventListener(UIEvents.MOUSE_WHEEL, event -> {
            int maximum = Math.max(0, findLoadoutItems().size() - PICKER_PAGE_SIZE);
            int previous = loadoutSearchScroll;
            if (event.deltaY > 0) loadoutSearchScroll = Math.max(0, loadoutSearchScroll - 1);
            else if (event.deltaY < 0) loadoutSearchScroll = Math.min(maximum, loadoutSearchScroll + 1);
            if (loadoutSearchScroll != previous) MonumentMenu.refreshMonumentScreen();
        });

        var back = new Button().setText("‹ Back").setOnClick(event -> {
            selectedLoadoutPickerSlot = -1;
            MonumentMenu.refreshMonumentScreen();
        });
        back.layout(l -> l.width(60).height(18));
        var header = MonumentUi.hbox(8);
        header.layout(l -> l.height(20));
        header.addChildren(back, new Label().setText("§6§lChoose " + GEAR_LABELS[selectedLoadoutPickerSlot]
                + " §7for §f" + menu.selectedAiRole.displayName()));
        panel.addChildren(header);

        var searchField = UiKit.textField("");
        searchField.setValue(loadoutSearchText);
        searchField.getTextFieldStyle().placeholder(Component.literal("Search by name or item id"));
        searchField.bindObserver(value -> loadoutSearchText = value == null
                ? "" : value.trim().toLowerCase(Locale.ROOT));
        Runnable runSearch = () -> {
            loadoutSearchScroll = 0;
            MonumentMenu.refreshMonumentScreen();
        };
        searchField.addEventListener(UIEvents.KEY_DOWN, event -> {
            if (event.keyCode == GLFW.GLFW_KEY_ENTER
                    || event.keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                runSearch.run();
                event.stopImmediatePropagation();
            }
        });
        searchField.layout(l -> l.width(300).height(20));
        var search = new Button().setText("Search").setOnClick(event -> runSearch.run());
        search.layout(l -> l.width(60).height(20));
        var useDefault = new Button().setText("§7Use Class Default").setOnClick(event -> selectLoadoutItem(selected, ""));
        useDefault.layout(l -> l.width(110).height(20));
        var searchRow = MonumentUi.hbox(4);
        searchRow.layout(l -> l.height(22));
        searchRow.addChildren(searchField, search, useDefault);
        panel.addChildren(searchRow);

        List<Item> results = findLoadoutItems();
        loadoutSearchScroll = Math.clamp(loadoutSearchScroll, 0, Math.max(0, results.size() - PICKER_PAGE_SIZE));
        if (results.isEmpty()) {
            panel.addChildren(new Label().setText(loadoutSearchText.isBlank()
                    ? "§8Type a name or item id, then press Enter." : "§7No matching items."));
            return panel;
        }
        for (Item item : results.stream().skip(loadoutSearchScroll).limit(PICKER_PAGE_SIZE).toList()) {
            ItemStack stack = new ItemStack(item);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            var icon = new ItemSlot();
            icon.setItem(stack);
            icon.layout(l -> l.width(18).height(18));
            var use = new Button().setText("§aUse").setOnClick(event -> selectLoadoutItem(selected, id.toString()));
            use.layout(l -> l.width(45).height(18));
            var row = MonumentUi.hbox(6);
            row.layout(l -> l.height(22).paddingHorizontal(4));
            UiKit.background(row, UiKit.ROW);
            row.addChildren(icon,
                    new Label().setText("§f" + MonumentUi.truncate(stack.getHoverName().getString(), 34)).layout(l -> l.width(220)),
                    new Label().setText("§8" + id).layout(l -> l.flex(1)),
                    use);
            panel.addChildren(row);
        }
        int shownLast = Math.min(results.size(), loadoutSearchScroll + PICKER_PAGE_SIZE);
        var previous = new Button().setText("‹").setOnClick(event -> {
            loadoutSearchScroll = Math.max(0, loadoutSearchScroll - PICKER_PAGE_SIZE);
            MonumentMenu.refreshMonumentScreen();
        });
        previous.layout(l -> l.width(24).height(18));
        var next = new Button().setText("›").setOnClick(event -> {
            int maximum = Math.max(0, findLoadoutItems().size() - PICKER_PAGE_SIZE);
            loadoutSearchScroll = Math.min(maximum, loadoutSearchScroll + PICKER_PAGE_SIZE);
            MonumentMenu.refreshMonumentScreen();
        });
        next.layout(l -> l.width(24).height(18));
        var pager = MonumentUi.hbox(8);
        pager.layout(l -> l.height(20));
        pager.addChildren(previous, new Label().setText("§7Showing " + (loadoutSearchScroll + 1) + "-" + shownLast
                + " of " + results.size() + (results.size() >= 200 ? "+" : "") + "  §8(scroll to browse)"), next);
        panel.addChildren(pager);
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private List<Item> findLoadoutItems() {
        if (loadoutSearchText.isBlank()) return List.of();
        return BuiltInRegistries.ITEM.stream()
                .filter(item -> item != Items.AIR)
                .filter(item -> {
                    ItemStack stack = new ItemStack(item);
                    String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
                    String id = BuiltInRegistries.ITEM.getKey(item).toString().toLowerCase(Locale.ROOT);
                    return name.contains(loadoutSearchText) || id.contains(loadoutSearchText);
                })
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .limit(200)
                .toList();
    }

    @OnlyIn(Dist.CLIENT)
    private void selectLoadoutItem(MonumentView selected, String itemId) {
        PacketDistributor.sendToServer(new MonumentAiLoadoutItemPacket(selected.id(), menu.selectedAiRole.ordinal(),
                selectedLoadoutPickerSlot, itemId));
        selectedLoadoutPickerSlot = -1;
        loadoutSearchText = "";
        loadoutSearchScroll = 0;
        MonumentMenu.refreshMonumentScreen();
    }
}
