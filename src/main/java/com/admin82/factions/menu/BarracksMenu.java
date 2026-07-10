package com.admin82.factions.menu;

import com.admin82.factions.barracks.BarracksData;
import com.admin82.factions.barracks.KitData;
import com.admin82.factions.network.packet.BarracksActionPacket;
import com.admin82.factions.registry.ModMenuTypes;
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
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
public class BarracksMenu extends AbstractContainerMenu {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final int STAGING_OFFSET = 0;            // staging slots 0-39
    private static final int PLAYER_INV_OFFSET = KitData.SLOT_COUNT; // slots 40-75

    // ── Shared state (both sides) ─────────────────────────────────────────────

    private final BlockPos barrPos;
    private final UUID ownerPlayerId;
    @Nullable private UUID linkedFactionId;

    /** Client-side kit names (synced via SyncBarracksPacket). */
    final AtomicReference<List<String>> kitNamesRef = new AtomicReference<>(new ArrayList<>());

    /** Name of the kit currently loaded into staging (client-side display). */
    final String[] selectedKitName = {null};

    /** Kit currently previewed in the manager tab (client-side only). */
    final String[] selectedPreviewKitName = {null};

    /** Client-only: the editorArea element of the currently active Kit Creator panel. */
    private UIElement kitEditorArea = null;

    // ── Server-only state ─────────────────────────────────────────────────────

    @Nullable private MinecraftServer server;
    @Nullable private String currentEditingKitName;
    private boolean stagingSaveEnabled = false;

    // ── Staging inventory handler ─────────────────────────────────────────────

    private final ItemStackHandler stagingHandler = new ItemStackHandler(KitData.SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            if (stagingSaveEnabled && currentEditingKitName != null
                    && server != null) {
                BarracksData.get(server).saveKitSlot(
                        ownerPlayerId, currentEditingKitName, slot, getStackInSlot(slot));
            }
        }
    };

    /** Slot objects added to the container (reused by UI ItemSlot elements). */
    final Slot[] stagingSlots = new Slot[KitData.SLOT_COUNT];

    // ── UI tab ────────────────────────────────────────────────────────────────

    private enum Tab { KIT_CREATOR, KIT_MANAGER }
    private Tab currentTab = Tab.KIT_CREATOR;

    // ── Constructors ──────────────────────────────────────────────────────────

    /** Server-side constructor (direct open from BarracksBlock or BlockEntity). */
    public BarracksMenu(int containerId, Inventory inv, BlockPos barrPos,
                        @Nullable UUID linkedFactionId) {
        super(ModMenuTypes.BARRACKS.get(), containerId);
        this.barrPos         = barrPos;
        this.ownerPlayerId   = inv.player.getUUID();
        this.linkedFactionId = linkedFactionId;

        if (!inv.player.level().isClientSide()) {
            this.server = ((ServerLevel) inv.player.level()).getServer();
            BarracksData bData = BarracksData.get(server);
            kitNamesRef.set(new ArrayList<>(
                bData.getKits(ownerPlayerId).stream()
                    .map(KitData::getName).collect(Collectors.toList())));
        }

        initSlots(inv);

        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu h)
            h.setModularUI(createModularUI(inv.player));
    }

    /** Client-side constructor (deserialized from network buffer). */
    public BarracksMenu(int containerId, Inventory inv, FriendlyByteBuf buf) {
        super(ModMenuTypes.BARRACKS.get(), containerId);
        this.barrPos         = buf.readBlockPos();
        this.ownerPlayerId   = inv.player.getUUID();
        this.linkedFactionId = buf.readBoolean() ? buf.readUUID() : null;

        List<String> names = new ArrayList<>();
        int nameCount = buf.readVarInt();
        for (int i = 0; i < nameCount; i++) names.add(buf.readUtf(64));
        kitNamesRef.set(names);

        initSlots(inv);

        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu h)
            h.setModularUI(createModularUI(inv.player));
    }

    private void initSlots(Inventory playerInventory) {
        // Inventory staging slots (0–35): any item allowed
        for (int i = 0; i < KitData.INV_SLOTS; i++) {
            stagingSlots[i] = addSlot(new ItemHandlerSlot(stagingHandler, i, -10000, -10000));
        }
        // Armor slots (36–39): restricted to the correct equipment slot type
        EquipmentSlot[] armorEquip = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        };
        for (int a = 0; a < KitData.ARMOR_SLOTS; a++) {
            final EquipmentSlot targetEquip = armorEquip[a];
            final int slotIdx = KitData.INV_SLOTS + a;
            stagingSlots[slotIdx] = addSlot(new ItemHandlerSlot(stagingHandler, slotIdx, -10000, -10000) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    if (stack.isEmpty()) return true;
                    if (stack.getItem() instanceof net.minecraft.world.item.ArmorItem a)
                        return a.getType().getSlot() == targetEquip;
                    return false;
                }
            });
        }
        // Offhand slot (40): any item allowed
        stagingSlots[KitData.OFFHAND_SLOT] = addSlot(
                new ItemHandlerSlot(stagingHandler, KitData.OFFHAND_SLOT, -10000, -10000));
        // Player inventory rows 1–3 (indices 41–67)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, -10000, -10000));
            }
        }
        // Player hotbar (indices 68–76)
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, -10000, -10000));
        }
        // Player offhand (index 77)
        addSlot(new Slot(playerInventory, 40, -10000, -10000));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public BlockPos getBarrPos() { return barrPos; }
    @Nullable public UUID getLinkedFactionId() { return linkedFactionId; }

    /** Called by SyncBarracksPacket on the client. */
    public void updateKitData(List<String> names) {
        kitNamesRef.set(new ArrayList<>(names));
    }

    /** Server: load a kit's items into the staging handler. */
    public void serverLoadKit(String kitName) {
        if (server == null) return;
        stagingSaveEnabled = false;
        currentEditingKitName = kitName;
        KitData kit = BarracksData.get(server).getKit(ownerPlayerId, kitName);
        for (int i = 0; i < KitData.SLOT_COUNT; i++) {
            stagingHandler.setStackInSlot(i,
                    kit != null ? kit.getSlot(i).copy() : ItemStack.EMPTY);
        }
        stagingSaveEnabled = true;
    }

    /** Server: clear the staging area (no kit selected). */
    public void serverClearStaging() {
        stagingSaveEnabled = false;
        currentEditingKitName = null;
        for (int i = 0; i < KitData.SLOT_COUNT; i++) stagingHandler.setStackInSlot(i, ItemStack.EMPTY);
        stagingSaveEnabled = false;
    }

    @Nullable public String getCurrentEditingKitName() { return currentEditingKitName; }
    @Nullable public MinecraftServer getServer() { return server; }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (!slot.hasItem()) return result;
        ItemStack stack = slot.getItem();
        result = stack.copy();

        if (slotIndex < KitData.SLOT_COUNT) {
            // Kit staging → player inventory (main + hotbar + offhand)
            if (!moveItemStackTo(stack, PLAYER_INV_OFFSET, PLAYER_INV_OFFSET + 37, true))
                return ItemStack.EMPTY;
        } else {
            // Player inventory → kit staging
            if (!moveItemStackTo(stack, STAGING_OFFSET, KitData.SLOT_COUNT, false))
                return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();

        return stack.getCount() == result.getCount() ? ItemStack.EMPTY : result;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(
                barrPos.getX() + 0.5, barrPos.getY() + 0.5, barrPos.getZ() + 0.5) <= 64.0;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════════════

    @OnlyIn(Dist.CLIENT)
    private ModularUI createModularUI(Player player) {
        var frame = new UIElement();
        frame.layout(l -> l.width(424).height(344).paddingAll(2));
        frame.addClass("preview_bg");

        var root = new UIElement();
        root.layout(l -> l.width(420).height(340).flexDirection(YogaFlexDirection.COLUMN));
        root.addClass("panel_bg");

        // ── Tab bar ───────────────────────────────────────────────────────────
        var tabBar = new UIElement();
        tabBar.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(21).width(420).gapAll(2).paddingHorizontal(2));

        var contentArea = new UIElement();
        contentArea.layout(l -> l.flex(1).width(420));
        contentArea.addChildren(buildKitCreatorPanel(player));

        for (Tab tab : Tab.values()) {
            boolean active = tab == currentTab;
            var btn = new Button()
                    .setText(tabLabel(tab))
                    .setOnClick(e -> {
                        currentTab = tab;
                        if (tab == Tab.KIT_MANAGER) {
                            selectedPreviewKitName[0] = null;
                            selectedKitName[0] = null;
                            if (kitEditorArea != null) {
                                kitEditorArea.clearAllChildren();
                                kitEditorArea = null;
                            }
                        }
                        contentArea.clearAllChildren();
                        contentArea.addChildren(buildPanelForTab(tab, player));
                        rebuildTabBar(tabBar, contentArea, player);
                    })
                    .layout(l -> l.flex(1).height(19));
            if (active) btn.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
            tabBar.addChildren(btn);
        }

        root.addChildren(tabBar, contentArea);
        frame.addChildren(root);

        return ModularUI.of(
                UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)),
                player);
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildPanelForTab(Tab tab, Player player) {
        return switch (tab) {
            case KIT_CREATOR -> buildKitCreatorPanel(player);
            case KIT_MANAGER -> buildKitManagerPanel(player);
        };
    }

    @OnlyIn(Dist.CLIENT)
    private void rebuildTabBar(UIElement bar, UIElement contentArea, Player player) {
        bar.clearAllChildren();
        for (Tab tab : Tab.values()) {
            boolean active = tab == currentTab;
            var btn = new Button()
                    .setText(tabLabel(tab))
                    .setOnClick(e -> {
                        currentTab = tab;
                        if (tab == Tab.KIT_MANAGER) {
                            selectedPreviewKitName[0] = null;
                            selectedKitName[0] = null;
                            if (kitEditorArea != null) {
                                kitEditorArea.clearAllChildren();
                                kitEditorArea = null;
                            }
                        }
                        contentArea.clearAllChildren();
                        contentArea.addChildren(buildPanelForTab(tab, player));
                        rebuildTabBar(bar, contentArea, player);
                    })
                    .layout(l -> l.flex(1).height(19));
            if (active) btn.lss("base-background", "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)");
            bar.addChildren(btn);
        }
    }

    private static String tabLabel(Tab t) {
        return switch (t) {
            case KIT_CREATOR -> "Kit Creator";
            case KIT_MANAGER -> "Kit Manager";
        };
    }

    // ── Kit Creator ───────────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    private UIElement buildKitCreatorPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).width(420).flexDirection(YogaFlexDirection.COLUMN));

        // ── Main split row ────────────────────────────────────────────────────
        var splitRow = new UIElement();
        splitRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).flex(1).width(420));

        // Left: kit list
        var leftCol = new UIElement();
        leftCol.layout(l -> l.width(110).flexDirection(YogaFlexDirection.COLUMN)
                .paddingAll(4).gapAll(3));

        var kitsLabel = new Label().setText("§6§lKits");
        kitsLabel.layout(l -> l.height(14));
        leftCol.addChildren(kitsLabel);

        // Kit list area (rebuilt when names change)
        var kitListArea = new UIElement();
        kitListArea.layout(l -> l.flex(1).width(102).flexDirection(YogaFlexDirection.COLUMN).gapAll(2));
        leftCol.addChildren(kitListArea);

        // Create kit form
        var newKitNameVal = new String[]{""};
        var statusLabel = new Label();
        statusLabel.bindDataSource(SupplierDataSource.of(() ->
                Component.literal(newKitNameVal[0].isEmpty() ? "" : "")));
        statusLabel.layout(l -> l.height(0)); // hidden by default

        var nameField = new TextField().setValue("").bindObserver(v -> newKitNameVal[0] = v);
        nameField.layout(l -> l.width(102).height(18));

        var createBtn = new Button().setText("§a+ Create Kit")
                .setOnClick(e -> {
                    String name = newKitNameVal[0].trim();
                    if (name.isEmpty()) return;
                    PacketDistributor.sendToServer(
                            new BarracksActionPacket(BarracksActionPacket.Action.CREATE_KIT, name, -1, null));
                    newKitNameVal[0] = "";
                    nameField.setValue("");
                })
                .layout(l -> l.width(102).height(19));
        leftCol.addChildren(nameField, createBtn);

        // ── Rebuild kit list on tick when count changes ───────────────────────
        int[] lastKitHash = {Integer.MIN_VALUE};
        String[] lastSelected = {"__INIT__"};
        kitListArea.addEventListener(UIEvents.TICK, ev -> {
            List<String> names = kitNamesRef.get();
            int namesHash = names.hashCode();
            String selected = selectedKitName[0];
            if (namesHash != lastKitHash[0] || !Objects.equals(selected, lastSelected[0])) {
                lastKitHash[0] = namesHash;
                lastSelected[0] = selected;
                kitListArea.clearAllChildren();
                for (String kitName : names) {
                    boolean active = kitName.equals(selectedKitName[0]);
                    var kitBtn = new Button()
                            .setText((active ? "§e" : "§7") + kitName)
                            .setOnClick(ev2 -> {
                                selectedKitName[0] = kitName;
                                PacketDistributor.sendToServer(new BarracksActionPacket(
                                        BarracksActionPacket.Action.LOAD_KIT, kitName, -1, null));
                                // Rebuild list to highlight selection
                                lastKitHash[0] = Integer.MIN_VALUE;
                            });
                    kitBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, hev ->
                            hev.hoverTooltips = HoverTooltips.empty().append(
                                    Component.literal("§eKit: §f" + kitName),
                                    Component.literal("§7Click to edit this kit's items."),
                                    Component.literal("§8Shift+Right-click to delete.")));
                    kitBtn.addEventListener(UIEvents.MOUSE_UP, mev -> {
                        if (mev.button == 1) { // right click = delete
                            PacketDistributor.sendToServer(new BarracksActionPacket(
                                    BarracksActionPacket.Action.DELETE_KIT, kitName, -1, null));
                            if (kitName.equals(selectedKitName[0])) selectedKitName[0] = null;
                        }
                    });
                    kitBtn.layout(l -> l.width(102).height(18));
                    kitListArea.addChildren(kitBtn);
                }
            }
        });

        // ── Right: kit editor ─────────────────────────────────────────────────
        var rightCol = new UIElement();
        rightCol.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN)
                .paddingAll(4).gapAll(4));

        // Header (kit name)
        var kitHeader = new Label();
        kitHeader.bindDataSource(SupplierDataSource.of(() -> {
            String n = selectedKitName[0];
            return Component.literal(n != null ? "§6Editing: §f" + n : "§7§oSelect a kit or create one");
        }));
        kitHeader.layout(l -> l.height(14).width(298));

        // Editor content: only visible when a kit is selected.
        var editorArea = new UIElement();
        editorArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(3).flex(1));
        this.kitEditorArea = editorArea; // track for cleanup on tab switch
        String[] lastEditorSelection = {"__INIT__"};
        editorArea.addEventListener(UIEvents.TICK, ev -> {
            if (editorArea != kitEditorArea) return; // this is a stale/detached instance
            String selected = selectedKitName[0];
            if (Objects.equals(selected, lastEditorSelection[0])) return;
            lastEditorSelection[0] = selected;

            editorArea.clearAllChildren();
            if (selected == null) {
                var hint = new Label().setText("§8Choose a kit from the left to edit its inventory.");
                hint.layout(l -> l.height(14));
                editorArea.addChildren(hint);
            } else {
                editorArea.addChildren(buildEditableArmorRow(), buildEditableKitGrid(), buildPlayerInvSection());
            }
        });

        rightCol.addChildren(kitHeader, editorArea);

        splitRow.addChildren(leftCol, rightCol);

        // ── Quick Take bar ────────────────────────────────────────────────────
        var quickTakeBar = buildQuickTakeBar();

        panel.addChildren(splitRow, quickTakeBar);
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement labeledSlot(String label, Slot slot) {
        var wrap = new UIElement();
        wrap.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).width(18).gapAll(1));
        var lbl = new Label().setText("§8" + label);
        lbl.layout(l -> l.height(8).width(18));
        lbl.lss("font-size", "6");
        lbl.lss("horizontal-align", "center");
        wrap.addChildren(lbl, new ItemSlot(slot));
        return wrap;
    }

    // ── Kit Manager ───────────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    private UIElement buildKitManagerPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).width(420).flexDirection(YogaFlexDirection.COLUMN)
                .paddingAll(8).gapAll(6));

        panel.addChildren(
                new Label().setText("§6§lAvailable Kits"),
                new Label().setText("§7These kits can be selected on respawn or via Quick Take."),
                new Label().setText("§8Each kit is consumed when taken — restock in the Kit Creator tab.")
        );

        var body = new UIElement();
        body.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(8).flex(1));

        var listCol = new UIElement();
        listCol.layout(l -> l.width(132).flexDirection(YogaFlexDirection.COLUMN).gapAll(3));

        var previewCol = new UIElement();
        previewCol.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));

        var previewHeader = new Label();
        previewHeader.bindDataSource(SupplierDataSource.of(() -> {
            String n = selectedPreviewKitName[0];
            return Component.literal(n != null ? "§6Preview: §f" + n : "§7§oSelect a kit to preview");
        }));
        previewHeader.layout(l -> l.height(14));

        ItemSlot[] previewSlots = new ItemSlot[KitData.SLOT_COUNT];
        for (int i = 0; i < previewSlots.length; i++) {
            previewSlots[i] = new ItemSlot().setItem(ItemStack.EMPTY);
        }

        var previewArmorRow = new UIElement();
        previewArmorRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(2).height(24));
        previewArmorRow.addChildren(
                labeledPreviewSlot("Head",  previewSlots[36]),
                labeledPreviewSlot("Chest", previewSlots[37]),
                labeledPreviewSlot("Legs",  previewSlots[38]),
                labeledPreviewSlot("Boots", previewSlots[39])
        );

        var previewGrid = new UIElement();
        previewGrid.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(1));
        for (int row = 0; row < 4; row++) {
            var rowEl = new UIElement();
            rowEl.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(1).height(18));
            for (int col = 0; col < 9; col++) {
                rowEl.addChildren(previewSlots[row * 9 + col]);
            }
            previewGrid.addChildren(rowEl);
        }

        var previewHint = new Label().setText("§8Read-only preview");
        previewHint.layout(l -> l.height(12));
        previewCol.addChildren(previewHeader, previewHint, previewArmorRow, previewGrid);

        String[] lastPreviewSelection = {"__INIT__"};
        int[] lastPreviewHash = {Integer.MIN_VALUE};
        previewCol.addEventListener(UIEvents.TICK, ev -> {
            String selected = selectedPreviewKitName[0];
            int contentHash = 1;
            if (selected != null) {
                for (int i = 0; i < KitData.SLOT_COUNT; i++) {
                    ItemStack s = stagingSlots[i].getItem();
                    contentHash = 31 * contentHash + ItemStack.hashItemAndComponents(s);
                    contentHash = 31 * contentHash + s.getCount();
                }
            }
            if (Objects.equals(selected, lastPreviewSelection[0]) && contentHash == lastPreviewHash[0]) return;
            lastPreviewSelection[0] = selected;
            lastPreviewHash[0] = contentHash;

            for (int i = 0; i < KitData.SLOT_COUNT; i++) {
                previewSlots[i].setItem(selected == null ? ItemStack.EMPTY : stagingSlots[i].getItem().copy());
            }
        });

        var kitListArea = new UIElement();
        kitListArea.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(2).flex(1));
        listCol.addChildren(kitListArea);

        body.addChildren(listCol, previewCol);
        panel.addChildren(body);

        // Rebuild list when kit count changes
        int[] lastHash = {Integer.MIN_VALUE};
        String[] lastSelected = {"__INIT__"};
        kitListArea.addEventListener(UIEvents.TICK, ev -> {
            List<String> names = kitNamesRef.get();
            int namesHash = names.hashCode();
            String selected = selectedPreviewKitName[0];
            if (namesHash == lastHash[0] && Objects.equals(selected, lastSelected[0])) return;
            lastHash[0] = namesHash;
            lastSelected[0] = selected;

            kitListArea.clearAllChildren();
            if (names.isEmpty()) {
                kitListArea.addChildren(new Label()
                        .setText("§8No kits available. Create kits in the Kit Creator tab."));
            } else {
                for (String name : names) {
                    boolean active = name.equals(selectedPreviewKitName[0]);
                    var row = new Button()
                            .setText((active ? "§e▶ " : "§7") + name)
                            .setOnClick(click -> {
                                selectedPreviewKitName[0] = name;
                                PacketDistributor.sendToServer(new BarracksActionPacket(
                                        BarracksActionPacket.Action.LOAD_KIT, name, -1, null));
                                lastHash[0] = Integer.MIN_VALUE;
                            });
                    row.layout(l -> l.width(130).height(18));
                    row.addEventListener(UIEvents.HOVER_TOOLTIPS, hev ->
                            hev.hoverTooltips = HoverTooltips.empty().append(
                                    Component.literal("§eKit: §f" + name),
                                    Component.literal("§7Click to preview this kit."),
                                    Component.literal("§8Preview is read-only in Kit Manager.")));
                    kitListArea.addChildren(row);
                }
            }
        });

        panel.addChildren(buildQuickTakeBar());
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildEditableArmorRow() {
        var armorRow = new UIElement();
        armorRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(2).height(24));
        var offhandSpacer = new UIElement();
        offhandSpacer.layout(l -> l.width(16));
        armorRow.addChildren(
                labeledInteractiveSlot("Head",    KitData.INV_SLOTS + 0),
                labeledInteractiveSlot("Chest",   KitData.INV_SLOTS + 1),
                labeledInteractiveSlot("Legs",    KitData.INV_SLOTS + 2),
                labeledInteractiveSlot("Boots",   KitData.INV_SLOTS + 3),
                offhandSpacer,
                labeledInteractiveSlot("Offhand", KitData.OFFHAND_SLOT)
        );
        return armorRow;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildEditableKitGrid() {
        var kitGrid = new UIElement();
        kitGrid.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(1));
        for (int row = 0; row < 4; row++) {
            var rowEl = new UIElement();
            rowEl.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(1).height(18));
            for (int col = 0; col < 9; col++) {
                rowEl.addChildren(makeInteractiveDisplaySlot(row * 9 + col));
            }
            kitGrid.addChildren(rowEl);
        }
        return kitGrid;
    }

    /** Creates a display-only ItemSlot backed by any container slot index, forwarding clicks to the server. */
    @OnlyIn(Dist.CLIENT)
    private ItemSlot makeContainerDisplaySlot(int containerSlotIdx) {
        var display = new ItemSlot();
        display.addEventListener(UIEvents.TICK, ev ->
                display.setItem(slots.get(containerSlotIdx).getItem().copy()));
        display.addEventListener(UIEvents.MOUSE_UP, ev -> {
            if (!(net.minecraft.client.Minecraft.getInstance().screen
                    instanceof com.admin82.factions.screen.BarracksScreen bs)) return;
            net.minecraft.world.inventory.ClickType type =
                    net.minecraft.client.gui.screens.Screen.hasShiftDown()
                            ? net.minecraft.world.inventory.ClickType.QUICK_MOVE
                            : net.minecraft.world.inventory.ClickType.PICKUP;
            bs.interactWithSlot(containerSlotIdx, ev.button, type);
        });
        return display;
    }

    /** Creates a display-only ItemSlot that mirrors the staging handler and forwards clicks to the server. */
    @OnlyIn(Dist.CLIENT)
    private ItemSlot makeInteractiveDisplaySlot(int stagingSlotIdx) {
        return makeContainerDisplaySlot(stagingSlotIdx);
    }

    /** Like labeledSlot but backed by a display-only slot (no LDLib global registration). */
    @OnlyIn(Dist.CLIENT)
    private UIElement labeledInteractiveSlot(String label, int slotIdx) {
        var wrap = new UIElement();
        wrap.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).width(18).gapAll(1));
        var lbl = new Label().setText("\u00a78" + label);
        lbl.layout(l -> l.height(8).width(18));
        lbl.lss("font-size", "6");
        lbl.lss("horizontal-align", "center");
        wrap.addChildren(lbl, makeInteractiveDisplaySlot(slotIdx));
        return wrap;
    }

    // ── Player inventory section ──────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    private UIElement buildPlayerInventorySection() {
        var section = new UIElement();
        section.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(1)
                .paddingHorizontal(4).paddingBottom(2));

        var invLabel = new Label().setText("§7Your Inventory");
        invLabel.layout(l -> l.height(10));
        section.addChildren(invLabel);

        // 3 rows of main inventory (player slots 40-66)
        for (int row = 0; row < 3; row++) {
            var rowEl = new UIElement();
            rowEl.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(1).height(18));
            for (int col = 0; col < 9; col++) {
                int slotIndex = PLAYER_INV_OFFSET + row * 9 + col;
                var is = new ItemSlot(this.slots.get(slotIndex));
                is.slotStyle(s -> s.isPlayerSlot(true));
                rowEl.addChildren(is);
            }
            section.addChildren(rowEl);
        }
        // Hotbar (slots 67-75)
        var hotbarRow = new UIElement();
        hotbarRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(1).height(18));
        for (int col = 0; col < 9; col++) {
            int slotIndex = PLAYER_INV_OFFSET + 27 + col;
            var is = new ItemSlot(this.slots.get(slotIndex));
            is.slotStyle(s -> s.isPlayerSlot(true));
            hotbarRow.addChildren(is);
        }
        section.addChildren(hotbarRow);
        return section;
    }

    /** Ghost-free player inventory shown inside Kit Creator when a kit is selected. */
    @OnlyIn(Dist.CLIENT)
    private UIElement buildPlayerInvSection() {
        var section = new UIElement();
        section.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(1).paddingTop(6));

        var label = new Label().setText("§7Your Inventory  §8(shift-click to move)");
        label.layout(l -> l.height(10));
        section.addChildren(label);

        // 3 main rows (container slots PLAYER_INV_OFFSET to PLAYER_INV_OFFSET+26)
        for (int row = 0; row < 3; row++) {
            var rowEl = new UIElement();
            rowEl.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(1).height(18));
            for (int col = 0; col < 9; col++) {
                rowEl.addChildren(makeContainerDisplaySlot(PLAYER_INV_OFFSET + row * 9 + col));
            }
            section.addChildren(rowEl);
        }

        // Hotbar + offhand in one row
        var hotbarRow2 = new UIElement();
        hotbarRow2.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(1).height(18).paddingTop(2));
        for (int col = 0; col < 9; col++) {
            hotbarRow2.addChildren(makeContainerDisplaySlot(PLAYER_INV_OFFSET + 27 + col));
        }
        var gapEl = new UIElement();
        gapEl.layout(l -> l.width(4));
        hotbarRow2.addChildren(gapEl, makeContainerDisplaySlot(PLAYER_INV_OFFSET + 36));
        section.addChildren(hotbarRow2);
        return section;
    }

    // ── Quick Take bar ────────────────────────────────────────────────────────

    @OnlyIn(Dist.CLIENT)
    private UIElement buildQuickTakeBar() {
        var bar = new UIElement();
        bar.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(22).width(416)
                .paddingHorizontal(4).paddingVertical(1).gapAll(4).alignSelf(YogaAlign.CENTER));

        var infoLabel = new Label();
        infoLabel.bindDataSource(SupplierDataSource.of(() ->
                Component.literal("§7Kits available: §e" + kitNamesRef.get().size())));
        infoLabel.layout(l -> l.flex(1).height(20));
        var quickTakeBtn = new Button()
                .setText("§d▶ Quick Take Kit")
                .setOnClick(e -> PacketDistributor.sendToServer(new BarracksActionPacket(
                        BarracksActionPacket.Action.QUICK_TAKE, null, -1, null)));
        quickTakeBtn.layout(l -> l.width(120).height(20));
        quickTakeBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev ->
                ev.hoverTooltips = HoverTooltips.empty().append(
                        Component.literal("§d§lQuick Take Kit"),
                        Component.literal("§7Opens the kit selection screen so you can"),
                        Component.literal("§7choose and receive a kit from the Barracks."),
                        Component.literal("§8The chosen kit is consumed (removed) once taken.")));

        bar.addChildren(infoLabel, quickTakeBtn);
        return bar;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement labeledPreviewSlot(String label, ItemSlot slot) {
        var wrap = new UIElement();
        wrap.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).width(18).gapAll(1));
        var lbl = new Label().setText("§8" + label);
        lbl.layout(l -> l.height(8).width(18));
        lbl.lss("font-size", "6");
        lbl.lss("horizontal-align", "center");
        wrap.addChildren(lbl, slot);
        return wrap;
    }
}
