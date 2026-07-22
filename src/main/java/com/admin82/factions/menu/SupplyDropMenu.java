package com.admin82.factions.menu;

import com.admin82.factions.network.packet.SupplyDropActionPacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.supplydrop.SupplyDropData;
import com.admin82.factions.supplydrop.SupplyDropPool;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.slot.ItemHandlerSlot;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaFlexDirection;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class SupplyDropMenu extends AbstractContainerMenu {
    private static final int PLAYER_INV_OFFSET = SupplyDropPool.SLOT_COUNT;

    public enum Mode { EDITOR, SPAWNER }

    private final Mode mode;
    private final AtomicReference<List<String>> poolNamesRef = new AtomicReference<>(new ArrayList<>());
    private final String[] selectedPoolName = {null};
    private final int[] selectedSettingsSlot = {-1};
    private int[] minCounts = defaultCounts();
    private int[] maxCounts = defaultCounts();
    private int[] rarityLevels = new int[SupplyDropPool.SLOT_COUNT];

    @Nullable private MinecraftServer server;
    @Nullable private String currentEditingPoolName;
    private boolean stagingSaveEnabled = false;

    private final ItemStackHandler stagingHandler = new ItemStackHandler(SupplyDropPool.SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            if (stagingSaveEnabled && currentEditingPoolName != null && server != null) {
                SupplyDropData.get(server).savePoolSlot(currentEditingPoolName, slot, getStackInSlot(slot));
            }
        }
    };

    public SupplyDropMenu(int containerId, Inventory inv, Mode mode, List<String> poolNames) {
        super(ModMenuTypes.SUPPLY_DROP.get(), containerId);
        this.mode = mode;
        this.poolNamesRef.set(new ArrayList<>(poolNames));
        if (!inv.player.level().isClientSide()) {
            this.server = ((ServerLevel) inv.player.level()).getServer();
        }
        initSlots(inv);
        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu holder) {
            holder.setModularUI(createModularUI(inv.player));
        }
    }

    public SupplyDropMenu(int containerId, Inventory inv, FriendlyByteBuf buf) {
        super(ModMenuTypes.SUPPLY_DROP.get(), containerId);
        this.mode = Mode.values()[buf.readVarInt()];
        int count = buf.readVarInt();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) names.add(buf.readUtf(64));
        this.poolNamesRef.set(names);
        initSlots(inv);
        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu holder) {
            holder.setModularUI(createModularUI(inv.player));
        }
    }

    private void initSlots(Inventory playerInventory) {
        for (int i = 0; i < SupplyDropPool.SLOT_COUNT; i++) {
            addSlot(new ItemHandlerSlot(stagingHandler, i, -10000, -10000));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, -10000, -10000));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, -10000, -10000));
        }
    }

    public Mode getMode() {
        return mode;
    }

    @Nullable
    public String getCurrentEditingPoolName() {
        return currentEditingPoolName;
    }

    public void updatePoolNames(List<String> poolNames) {
        poolNamesRef.set(new ArrayList<>(poolNames));
    }

    public void updatePoolSettings(String poolName, int[] minCounts, int[] maxCounts, int[] rarityLevels) {
        if (!Objects.equals(selectedPoolName[0], poolName)) return;
        this.minCounts = copyOrDefault(minCounts, 1);
        this.maxCounts = copyOrDefault(maxCounts, 1);
        this.rarityLevels = copyOrDefault(rarityLevels, 0);
    }

    public void serverLoadPool(String poolName) {
        if (server == null) return;
        stagingSaveEnabled = false;
        currentEditingPoolName = poolName;
        SupplyDropPool pool = SupplyDropData.get(server).getPool(poolName);
        for (int i = 0; i < SupplyDropPool.SLOT_COUNT; i++) {
            stagingHandler.setStackInSlot(i, pool == null ? ItemStack.EMPTY : pool.getSlot(i).copy());
        }
        stagingSaveEnabled = true;
    }

    public void serverClearStaging() {
        stagingSaveEnabled = false;
        currentEditingPoolName = null;
        for (int i = 0; i < SupplyDropPool.SLOT_COUNT; i++) {
            stagingHandler.setStackInSlot(i, ItemStack.EMPTY);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (!slot.hasItem()) return result;
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (slotIndex < SupplyDropPool.SLOT_COUNT) {
            if (!moveItemStackTo(stack, PLAYER_INV_OFFSET, PLAYER_INV_OFFSET + 36, true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, SupplyDropPool.SLOT_COUNT, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return stack.getCount() == result.getCount() ? ItemStack.EMPTY : result;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.hasPermissions(2);
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI createModularUI(Player player) {
        var frame = new UIElement();
        frame.layout(l -> l.width(704).height(430).paddingAll(2));
        frame.addClass("preview_bg");

        var root = new UIElement();
        root.layout(l -> l.width(700).height(426).flexDirection(YogaFlexDirection.COLUMN).paddingAll(8).gapAll(6));
        root.addClass("panel_bg");
        root.addChildren(new Label().setText(mode == Mode.EDITOR ? "§6§lSupply Drop Loot Pools" : "§6§lCall Supply Drop"));
        root.addChildren(mode == Mode.EDITOR ? buildEditorPanel() : buildSpawnerPanel());
        frame.addChildren(root);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildEditorPanel() {
        var split = new UIElement();
        split.layout(l -> l.flex(1).width(684).flexDirection(YogaFlexDirection.ROW).gapAll(10));

        var left = new UIElement();
        left.layout(l -> l.width(150).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        var listArea = new UIElement();
        listArea.layout(l -> l.flex(1).width(150).flexDirection(YogaFlexDirection.COLUMN).gapAll(2));

        String[] newPoolName = {""};
        var nameField = new TextField().setValue("").bindObserver(value -> newPoolName[0] = value);
        nameField.layout(l -> l.width(150).height(18));
        var createButton = new Button().setText("§aCreate Loot Pool")
                .setOnClick(e -> {
                    String name = newPoolName[0].trim();
                    if (name.isEmpty()) return;
                    PacketDistributor.sendToServer(new SupplyDropActionPacket(SupplyDropActionPacket.Action.CREATE_POOL, name, 0, 0));
                    newPoolName[0] = "";
                    nameField.setValue("");
                })
                    .layout(l -> l.width(150).height(22));

                left.addChildren(new Label().setText("§7Saved Loot Pools"), listArea, new Label().setText("§7New pool name"), nameField, createButton);

        var right = new UIElement();
        right.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.ROW).gapAll(12));

        var editorCol = new UIElement();
        editorCol.layout(l -> l.width(350).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        var header = new Label();
        header.bindDataSource(SupplierDataSource.of(() -> Component.literal(
                selectedPoolName[0] == null ? "§7Select or create a pool." : "§6Editing: §f" + selectedPoolName[0])));
        header.layout(l -> l.height(14));
        var gridArea = new UIElement();
        gridArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(1));
        var settingsArea = buildSettingsEditor();
        var inventoryArea = buildPlayerInventorySection();

        String[] lastGridSelection = {"__INIT__"};
        gridArea.addEventListener(UIEvents.TICK, event -> {
            String selected = selectedPoolName[0];
            if (Objects.equals(selected, lastGridSelection[0])) return;
            lastGridSelection[0] = selected;
            gridArea.clearAllChildren();
            if (selected == null) {
                gridArea.addChildren(new Label().setText("§8Pool contents appear here."));
            } else {
                for (int row = 0; row < 6; row++) {
                    var rowEl = new UIElement();
                    rowEl.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(1).height(18));
                    for (int col = 0; col < 9; col++) {
                        rowEl.addChildren(makeContainerDisplaySlot(row * 9 + col));
                    }
                    gridArea.addChildren(rowEl);
                }
            }
        });

        editorCol.addChildren(header, gridArea, settingsArea, inventoryArea);
        right.addChildren(editorCol, buildRarityTable());
        split.addChildren(left, right);

        int[] lastHash = {Integer.MIN_VALUE};
        String[] lastSelected = {"__INIT__"};
        listArea.addEventListener(UIEvents.TICK, event -> {
            List<String> names = poolNamesRef.get();
            int hash = names.hashCode();
            String selected = selectedPoolName[0];
            if (hash == lastHash[0] && Objects.equals(selected, lastSelected[0])) return;
            lastHash[0] = hash;
            lastSelected[0] = selected;
            listArea.clearAllChildren();
            if (names.isEmpty()) {
                listArea.addChildren(new Label().setText("§8No pools saved."));
                return;
            }
            for (String name : names) {
                boolean active = name.equals(selectedPoolName[0]);
                var button = new Button()
                        .setText((active ? "§e" : "§7") + name)
                        .setOnClick(e -> {
                            selectedPoolName[0] = name;
                            selectedSettingsSlot[0] = -1;
                            PacketDistributor.sendToServer(new SupplyDropActionPacket(SupplyDropActionPacket.Action.LOAD_POOL, name, 0, 0));
                            lastHash[0] = Integer.MIN_VALUE;
                        });
                button.layout(l -> l.width(150).height(20));
                button.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                        Component.literal("§ePool: §f" + name),
                        Component.literal("§7Click to edit."),
                        Component.literal("§8Right-click to delete.")));
                button.addEventListener(UIEvents.MOUSE_UP, ev -> {
                    if (ev.button == 1) {
                        PacketDistributor.sendToServer(new SupplyDropActionPacket(SupplyDropActionPacket.Action.DELETE_POOL, name, 0, 0));
                        if (name.equals(selectedPoolName[0])) {
                            selectedPoolName[0] = null;
                            selectedSettingsSlot[0] = -1;
                        }
                    }
                });
                listArea.addChildren(button);
            }
        });

        return split;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildSpawnerPanel() {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).width(684).flexDirection(YogaFlexDirection.COLUMN).gapAll(8));

        var listArea = new UIElement();
        listArea.layout(l -> l.height(170).width(320).flexDirection(YogaFlexDirection.COLUMN).gapAll(3));
        int[] lastHash = {Integer.MIN_VALUE};
        String[] lastSelected = {"__INIT__"};
        listArea.addEventListener(UIEvents.TICK, event -> {
            List<String> names = poolNamesRef.get();
            int hash = names.hashCode();
            String selected = selectedPoolName[0];
            if (hash == lastHash[0] && Objects.equals(selected, lastSelected[0])) return;
            lastHash[0] = hash;
            lastSelected[0] = selected;
            listArea.clearAllChildren();
            for (String name : names) {
                boolean active = name.equals(selectedPoolName[0]);
                listArea.addChildren(new Button()
                        .setText((active ? "§e" : "§7") + name)
                        .setOnClick(e -> {
                            selectedPoolName[0] = name;
                            lastHash[0] = Integer.MIN_VALUE;
                        })
                        .layout(l -> l.width(320).height(22)));
            }
            if (names.isEmpty()) listArea.addChildren(new Label().setText("§8Create a loot pool first."));
        });

        int[] radius = {10000};
        int[] seconds = {60};
        var radiusField = new TextField().setValue("10000").bindObserver(value -> {
            try { radius[0] = Math.max(0, Integer.parseInt(value.trim())); } catch (NumberFormatException ignored) {}
        });
        radiusField.layout(l -> l.width(150).height(18));
        var secondsField = new TextField().setValue("60").bindObserver(value -> {
            try { seconds[0] = Math.max(0, Integer.parseInt(value.trim())); } catch (NumberFormatException ignored) {}
        });
        secondsField.layout(l -> l.width(150).height(18));

        var controls = new UIElement();
        controls.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(4).height(52));
        controls.addChildren(
            labeledFieldRow("§7Radius from 0,0 (blocks)", radiusField),
            labeledFieldRow("§7Countdown before drop starts (seconds)", secondsField));

        var spawnButton = new Button().setText("§6Call Supply Drop")
                .setOnClick(e -> {
                    if (selectedPoolName[0] == null) return;
                    PacketDistributor.sendToServer(new SupplyDropActionPacket(
                            SupplyDropActionPacket.Action.SPAWN_DROP, selectedPoolName[0], radius[0], seconds[0]));
                })
                .layout(l -> l.width(190).height(26));

        var selectedLabel = new Label();
        selectedLabel.bindDataSource(SupplierDataSource.of(() -> Component.literal(
                selectedPoolName[0] == null ? "§7No pool selected." : "§6Selected: §f" + selectedPoolName[0])));
        panel.addChildren(new Label().setText("§7Choose a saved loot pool, radius, and countdown time."), listArea, selectedLabel, controls, spawnButton);
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private ItemSlot makeContainerDisplaySlot(int containerSlotIdx) {
        var display = new ItemSlot();
        display.addEventListener(UIEvents.TICK, ev -> display.setItem(slots.get(containerSlotIdx).getItem().copy()));
        display.addEventListener(UIEvents.MOUSE_UP, ev -> {
            if (!(net.minecraft.client.Minecraft.getInstance().screen instanceof com.admin82.factions.screen.SupplyDropScreen screen)) return;
            if (containerSlotIdx < SupplyDropPool.SLOT_COUNT && ev.button == 1 && !slots.get(containerSlotIdx).getItem().isEmpty()) {
                selectedSettingsSlot[0] = containerSlotIdx;
                return;
            }
            ClickType type = net.minecraft.client.gui.screens.Screen.hasShiftDown() ? ClickType.QUICK_MOVE : ClickType.PICKUP;
            screen.interactWithSlot(containerSlotIdx, ev.button, type);
        });
        return display;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildSettingsEditor() {
        var area = new UIElement();
        area.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(3).height(122).paddingTop(4));

        int[] lastSlot = {Integer.MIN_VALUE};
        int[] lastHash = {Integer.MIN_VALUE};
        area.addEventListener(UIEvents.TICK, event -> {
            int slot = selectedSettingsSlot[0];
            int hash = slot >= 0 && slot < SupplyDropPool.SLOT_COUNT
                    ? Objects.hash(minCounts[slot], maxCounts[slot], rarityLevels[slot], ItemStack.hashItemAndComponents(slots.get(slot).getItem()), slots.get(slot).getItem().getCount())
                    : 0;
            if (slot == lastSlot[0] && hash == lastHash[0]) return;
            lastSlot[0] = slot;
            lastHash[0] = hash;
            area.clearAllChildren();
            if (slot < 0 || slot >= SupplyDropPool.SLOT_COUNT || slots.get(slot).getItem().isEmpty()) {
                area.addChildren(
                        new Label().setText("§7Item generation settings"),
                        new Label().setText("§8Right-click an item in the pool above to edit it."));
                return;
            }

            ItemStack stack = slots.get(slot).getItem();
            int[] minValue = {Math.max(1, minCounts[slot])};
            int[] maxValue = {Math.max(minValue[0], maxCounts[slot])};
            int[] rarityValue = {Math.max(0, Math.min(10, rarityLevels[slot]))};
            var title = new Label().setText("§6Slot " + (slot + 1) + ": §f" + stack.getHoverName().getString());
            title.layout(l -> l.height(12));

            var minField = new TextField().setValue(String.valueOf(minValue[0])).bindObserver(value -> {
                try { minValue[0] = Math.max(1, Integer.parseInt(value.trim())); } catch (NumberFormatException ignored) {}
            });
            minField.layout(l -> l.width(58).height(18));
            var maxField = new TextField().setValue(String.valueOf(maxValue[0])).bindObserver(value -> {
                try { maxValue[0] = Math.max(1, Integer.parseInt(value.trim())); } catch (NumberFormatException ignored) {}
            });
            maxField.layout(l -> l.width(58).height(18));
            var rarityField = new TextField().setValue(String.valueOf(rarityValue[0])).bindObserver(value -> {
                try { rarityValue[0] = Math.max(0, Math.min(10, Integer.parseInt(value.trim()))); } catch (NumberFormatException ignored) {}
            });
            rarityField.layout(l -> l.width(58).height(18));

            var saveButton = new Button().setText("§aSave Item Settings")
                    .setOnClick(click -> {
                        if (selectedPoolName[0] == null) return;
                        int min = Math.max(1, Math.min(minValue[0], stack.getMaxStackSize()));
                        int max = Math.max(min, Math.min(maxValue[0], stack.getMaxStackSize()));
                        int rarity = Math.max(0, Math.min(10, rarityValue[0]));
                        PacketDistributor.sendToServer(new SupplyDropActionPacket(
                                SupplyDropActionPacket.Action.SAVE_SLOT_SETTINGS, selectedPoolName[0], slot, min, max, rarity));
                    })
                        .layout(l -> l.width(140).height(20));

                    area.addChildren(
                        title,
                        labeledFieldRow("§7Minimum items to generate", minField),
                        labeledFieldRow("§7Maximum items to generate", maxField),
                        labeledFieldRow("§7Rarity level (0 common, 10 rare)", rarityField),
                        saveButton);
        });
        return area;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildRarityTable() {
        var panel = new UIElement();
        panel.layout(l -> l.width(160).flexDirection(YogaFlexDirection.COLUMN).gapAll(2).paddingTop(18));
        panel.addChildren(
                new Label().setText("§6§lRarity Table"),
                new Label().setText("§8Chance an item appears"),
                rarityRow("0", "Common", "100%"),
                rarityRow("1", "Common", "91%"),
                rarityRow("2", "Uncommon", "82%"),
                rarityRow("3", "Uncommon", "73%"),
                rarityRow("4", "Rare", "64%"),
                rarityRow("5", "Rare", "55%"),
                rarityRow("6", "Epic", "45%"),
                rarityRow("7", "Epic", "36%"),
                rarityRow("8", "Legendary", "27%"),
                rarityRow("9", "Mythic", "18%"),
                rarityRow("10", "Rarest", "9%"));
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement rarityRow(String rarity, String tier, String chance) {
        var label = new Label().setText("§7" + rarity + " §8- §f" + tier + " §8- §a" + chance);
        label.layout(l -> l.height(13).width(160));
        return label;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement labeledFieldRow(String label, UIElement field) {
        var row = new UIElement();
        row.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).height(20));
        var labelElement = new Label().setText(label);
        labelElement.layout(l -> l.width(210).height(18));
        row.addChildren(labelElement, field);
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildPlayerInventorySection() {
        var section = new UIElement();
        section.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(1).paddingTop(5));
        section.addChildren(new Label().setText("§7Your Inventory"));
        for (int row = 0; row < 3; row++) {
            var rowEl = new UIElement();
            rowEl.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(1).height(18));
            for (int col = 0; col < 9; col++) {
                rowEl.addChildren(makeContainerDisplaySlot(PLAYER_INV_OFFSET + row * 9 + col));
            }
            section.addChildren(rowEl);
        }
        var hotbar = new UIElement();
        hotbar.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(1).height(18));
        for (int col = 0; col < 9; col++) {
            hotbar.addChildren(makeContainerDisplaySlot(PLAYER_INV_OFFSET + 27 + col));
        }
        section.addChildren(hotbar);
        return section;
    }

    private static int[] defaultCounts() {
        int[] counts = new int[SupplyDropPool.SLOT_COUNT];
        java.util.Arrays.fill(counts, 1);
        return counts;
    }

    private static int[] copyOrDefault(int[] values, int defaultValue) {
        int[] copy = new int[SupplyDropPool.SLOT_COUNT];
        java.util.Arrays.fill(copy, defaultValue);
        if (values != null) {
            System.arraycopy(values, 0, copy, 0, Math.min(values.length, copy.length));
        }
        return copy;
    }
}