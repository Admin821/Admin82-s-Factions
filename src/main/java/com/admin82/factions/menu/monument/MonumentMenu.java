package com.admin82.factions.menu.monument;

import com.admin82.factions.compat.tacz.Tacz;
import com.admin82.factions.blockentity.MonumentControllerBlockEntity;
import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.admin82.factions.monument.MonumentView;
import com.admin82.factions.network.packet.MonumentActionPacket;
import com.admin82.factions.network.packet.MonumentAiActionPacket;
import com.admin82.factions.network.packet.MonumentLootActionPacket;
import com.admin82.factions.network.packet.SyncMonumentAiPacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.screen.MonumentScreen;
import com.admin82.factions.supplydrop.SupplyDropPool;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.slot.ItemHandlerSlot;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.vfyjxf.taffy.style.AlignItems;

import org.appliedenergistics.yoga.YogaJustify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

public class MonumentMenu extends AbstractContainerMenu {

    private final GuardTabs guards = new GuardTabs(this);
    private final ChunkMapTab chunkMap = new ChunkMapTab(this);
    public static final int LOOT_SLOTS = 54;
    private static final int PLAYER_INV_OFFSET = LOOT_SLOTS;

    private List<MonumentView> monuments;
    @Nullable private UUID selectedId;
    @Nullable MinecraftServer server;
    private boolean saveEnabled;
    @Nullable private ServerPlayer serverPlayer;
    @Nullable private String currentLootPoolName;
    private List<String> lootPoolNames = new ArrayList<>();
    private int[] lootMinCounts = MonumentUi.defaultLootCounts();
    private int[] lootMaxCounts = MonumentUi.defaultLootCounts();
    private int[] lootRarityLevels = new int[LOOT_SLOTS];
    int selectedLootSettingsSlot = -1;
    RoguePmcRole selectedAiRole = RoguePmcRole.ASSAULT;
    EditorMode editorMode = EditorMode.MONUMENT_LOOT;
    int[] aiMinimumCounts = new int[RoguePmcRole.values().length];
    int[] aiMaximumCounts = new int[RoguePmcRole.values().length];
    int[] aiDifficultyLevels = new int[RoguePmcRole.values().length];
    int deadAiCount;
    int[] aiDropMinCounts = MonumentUi.defaultLootCounts();
    int[] aiDropMaxCounts = MonumentUi.defaultLootCounts();
    int[] aiDropRarityLevels = new int[LOOT_SLOTS];
    int[] aiDropClassMasks = MonumentUi.defaultClassMasks();
    private int browserPage;
    private int controllerTab;
    private boolean deleteMonumentArmed;
    private boolean deletePoolArmed;
    boolean aiRangesDirty;

    enum EditorMode { MONUMENT_LOOT, AI_LOADOUT, AI_DROPS }

    private final ItemStackHandler staging = new ItemStackHandler(LOOT_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            if (saveEnabled && selectedId != null && server != null && editorMode != EditorMode.MONUMENT_LOOT) {
                MonumentEntry entry = MonumentData.get(server).get(selectedId);
                if (entry != null) {
                    if (editorMode == EditorMode.AI_DROPS) {
                        entry.getAiDropPool().setSlot(slot, getStackInSlot(slot));
                        if (getStackInSlot(slot).isEmpty()) entry.setAiDropClassMask(slot, MonumentEntry.ALL_AI_CLASSES);
                    }
                    else if (slot < 5) entry.setAiLoadoutSlot(selectedAiRole, slot, getStackInSlot(slot));
                    MonumentData.get(server).changed();
                    return;
                }
            }
            if (!saveEnabled || selectedId == null || currentLootPoolName == null || server == null) return;
            MonumentEntry entry = MonumentData.get(server).get(selectedId);
            if (entry == null) return;
            SupplyDropPool pool = entry.getLootPool(currentLootPoolName);
            if (pool == null) return;
            pool.setSlot(slot, getStackInSlot(slot));
            MonumentData.get(server).changed();
        }
    };

    public MonumentMenu(int id, Inventory inventory, List<MonumentView> monuments, @Nullable UUID selectedId) {
        super(ModMenuTypes.MONUMENT.get(), id);
        this.monuments = new ArrayList<>(monuments);
        this.selectedId = selectedId;
        if (!inventory.player.level().isClientSide()) {
            this.server = ((ServerLevel) inventory.player.level()).getServer();
            if (inventory.player instanceof ServerPlayer found) this.serverPlayer = found;
        }
        initSlots(inventory);
        if (server != null && selectedId != null) serverSelect(selectedId);
        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu holder) {
            holder.setModularUI(createModularUI(inventory.player));
        }
    }

    public MonumentMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, inventory, readViews(buf), readSelected(buf));
    }

    private static List<MonumentView> readViews(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<MonumentView> views = new ArrayList<>();
        for (int i = 0; i < count; i++) views.add(MonumentView.read(buf));
        return views;
    }

    @Nullable
    private static UUID readSelected(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }

    private void initSlots(Inventory inventory) {
        for (int i = 0; i < LOOT_SLOTS; i++) addSlot(new ItemHandlerSlot(staging, i, -10000, -10000));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, -10000, -10000));
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, -10000, -10000));
    }

    public void serverSelect(UUID id) {
        if (server == null) return;
        MonumentEntry entry = MonumentData.get(server).get(id);
        if (entry == null) return;
        selectedId = id;
        ServerLevel level = MonumentUi.levelFor(server, entry.dimension);
        MonumentControllerBlockEntity controller = level != null
                && level.getBlockEntity(entry.controllerPos) instanceof MonumentControllerBlockEntity found ? found : null;
        if (entry.needsLegacyLootMigration()) {
            if (controller != null) {
                for (int poolIndex = 0; poolIndex < MonumentEntry.DEFAULT_LOOT_POOLS.size(); poolIndex++) {
                    SupplyDropPool pool = entry.getLootPool(MonumentEntry.DEFAULT_LOOT_POOLS.get(poolIndex));
                    if (pool == null) continue;
                    for (int slot = 0; slot < 18; slot++) {
                        pool.setSlot(slot, controller.getItem(poolIndex * 18 + slot));
                    }
                }
            }
            entry.markLegacyLootMigrated();
            MonumentData.get(server).changed();
        }
        String poolName = entry.getLootPool(currentLootPoolName) != null
                ? currentLootPoolName : entry.getLootPoolNames().getFirst();
        serverLoadLootPool(id, poolName);
    }

    public void serverLoadLootPool(UUID monumentId, String poolName) {
        if (server == null || !monumentId.equals(selectedId)) return;
        MonumentEntry entry = MonumentData.get(server).get(monumentId);
        SupplyDropPool pool = entry == null ? null : entry.getLootPool(poolName);
        if (entry == null || pool == null) return;
        saveEnabled = false;
        editorMode = EditorMode.MONUMENT_LOOT;
        currentLootPoolName = pool.getName();
        selectedLootSettingsSlot = -1;
        for (int slot = 0; slot < LOOT_SLOTS; slot++) staging.setStackInSlot(slot, pool.getSlot(slot).copy());
        saveEnabled = true;
        broadcastChanges();
        if (serverPlayer != null) MonumentLootActionPacket.sync(serverPlayer, entry, pool);
    }

    public void updateLootEditor(UUID monumentId, String poolName, List<String> poolNames,
                                 int[] minCounts, int[] maxCounts, int[] rarityLevels) {
        if (!monumentId.equals(selectedId)) return;
        currentLootPoolName = poolName;
        lootPoolNames = new ArrayList<>(poolNames);
        lootMinCounts = minCounts.clone();
        lootMaxCounts = maxCounts.clone();
        lootRarityLevels = rarityLevels.clone();
        editorMode = EditorMode.MONUMENT_LOOT;
        selectedLootSettingsSlot = -1;
    }

    public void serverLoadAiRole(UUID monumentId, RoguePmcRole role) {
        if (server == null || !monumentId.equals(selectedId)) return;
        MonumentEntry entry = MonumentData.get(server).get(monumentId);
        if (entry == null) return;
        saveEnabled = false;
        editorMode = EditorMode.AI_LOADOUT;
        selectedAiRole = role;
        List<ItemStack> loadout = entry.getAiLoadout(role);
        List<ItemStack> defaults = defaultAiLoadout(role);
        for (int slot = 0; slot < LOOT_SLOTS; slot++) {
            ItemStack configured = slot < loadout.size() ? loadout.get(slot) : ItemStack.EMPTY;
            ItemStack displayed = configured.isEmpty() && slot < defaults.size() ? defaults.get(slot) : configured;
            staging.setStackInSlot(slot, displayed.copy());
        }
        saveEnabled = true;
        broadcastChanges();
        syncAiEditor(entry);
    }

    private List<ItemStack> defaultAiLoadout(RoguePmcRole role) {
        List<ItemStack> defaults = new ArrayList<>(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY));
        for (ResourceLocation gunId : role.gunIds()) {
            ItemStack gun = Tacz.createGun(server.registryAccess(), gunId);
            if (!gun.isEmpty()) {
                defaults.set(0, gun);
                break;
            }
        }
        switch (role) {
            case SNIPER -> {
                defaults.set(2, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
                defaults.set(4, new ItemStack(Items.LEATHER_BOOTS));
            }
            case ASSAULT -> {
                defaults.set(1, new ItemStack(Items.IRON_HELMET));
                defaults.set(2, new ItemStack(Items.IRON_CHESTPLATE));
            }
            case HEAVY_GUNNER -> {
                defaults.set(1, new ItemStack(Items.IRON_HELMET));
                defaults.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
                defaults.set(3, new ItemStack(Items.IRON_LEGGINGS));
                defaults.set(4, new ItemStack(Items.IRON_BOOTS));
            }
            case LIGHT_GUNNER -> {
                defaults.set(2, new ItemStack(Items.LEATHER_CHESTPLATE));
                defaults.set(4, new ItemStack(Items.LEATHER_BOOTS));
            }
        }
        return defaults;
    }

    public void serverLoadAiDrops(UUID monumentId) {
        if (server == null || !monumentId.equals(selectedId)) return;
        MonumentEntry entry = MonumentData.get(server).get(monumentId);
        if (entry == null) return;
        saveEnabled = false;
        editorMode = EditorMode.AI_DROPS;
        selectedLootSettingsSlot = -1;
        for (int slot = 0; slot < LOOT_SLOTS; slot++) {
            staging.setStackInSlot(slot, entry.getAiDropPool().getSlot(slot).copy());
        }
        saveEnabled = true;
        broadcastChanges();
        syncAiEditor(entry);
    }

    public void syncAiEditor(MonumentEntry entry) {
        if (serverPlayer == null) return;
        int[] minimum = new int[RoguePmcRole.values().length];
        int[] maximum = new int[RoguePmcRole.values().length];
        int[] difficulty = new int[RoguePmcRole.values().length];
        for (RoguePmcRole role : RoguePmcRole.values()) {
            minimum[role.ordinal()] = entry.getAiMinimumCount(role);
            maximum[role.ordinal()] = entry.getAiMaximumCount(role);
            difficulty[role.ordinal()] = entry.getAiDifficultyLevel(role);
        }
        SupplyDropPool drops = entry.getAiDropPool();
        PacketDistributor.sendToPlayer(serverPlayer, new SyncMonumentAiPacket(entry.id, minimum, maximum, difficulty,
                entry.getDeadAiCount(), selectedAiRole.ordinal(), editorMode == EditorMode.AI_DROPS,
                drops.getMinCountsCopy(), drops.getMaxCountsCopy(), drops.getRarityLevelsCopy(),
                entry.getAiDropClassMasksCopy()));
    }

    public void updateAiEditor(UUID monumentId, int[] minimum, int[] maximum, int[] difficulty, int deadCount,
                               int selectedRole, boolean drops, int[] dropMinimum, int[] dropMaximum,
                               int[] dropRarity, int[] dropClassMasks) {
        if (!monumentId.equals(selectedId)) return;
        if (!aiRangesDirty) {
            aiMinimumCounts = minimum.clone();
            aiMaximumCounts = maximum.clone();
        }
        aiDifficultyLevels = difficulty.clone();
        deadAiCount = deadCount;
        selectedAiRole = RoguePmcRole.values()[Math.clamp(selectedRole, 0, RoguePmcRole.values().length - 1)];
        editorMode = drops ? EditorMode.AI_DROPS : EditorMode.AI_LOADOUT;
        aiDropMinCounts = dropMinimum.clone();
        aiDropMaxCounts = dropMaximum.clone();
        aiDropRarityLevels = dropRarity.clone();
        aiDropClassMasks = dropClassMasks.clone();
        selectedLootSettingsSlot = -1;
    }

    @Nullable public String getCurrentLootPoolName() { return currentLootPoolName; }

    public void updateViews(List<MonumentView> views, @Nullable UUID selectedId) {
        monuments = new ArrayList<>(views);
        this.selectedId = selectedId;
    }

    @OnlyIn(Dist.CLIENT)
    public void rebuildClientUi(Player player) {
        if (this instanceof IModularUIHolderMenu holder) holder.setModularUI(createModularUI(player));
    }

    public void serverClearSelection() {
        selectedId = null;
        currentLootPoolName = null;
        lootPoolNames.clear();
        saveEnabled = false;
        for (int i = 0; i < LOOT_SLOTS; i++) staging.setStackInSlot(i, ItemStack.EMPTY);
        broadcastChanges();
    }

    @Nullable public UUID getSelectedId() { return selectedId; }
    public List<MonumentView> getMonuments() { return monuments; }

    @Override public boolean stillValid(Player player) { return player.hasPermissions(2); }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack result = stack.copy();
        if (slotIndex < LOOT_SLOTS) {
            if (!moveItemStackTo(stack, PLAYER_INV_OFFSET, PLAYER_INV_OFFSET + 36, true)) return ItemStack.EMPTY;
        } else if (selectedId == null || !moveItemStackTo(stack, 0,
            editorMode == EditorMode.AI_LOADOUT ? 5 : LOOT_SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return stack.getCount() == result.getCount() ? ItemStack.EMPTY : result;
    }

    // Client UI

    private static final int TAB_OVERVIEW = 0;
    private static final int TAB_LOOT = 1;
    private static final int TAB_MAP = 2;
    private static final int TAB_ROSTER = 3;
    private static final int TAB_GEAR = 4;
    private static final int TAB_DROPS = 5;
    private static final String[] TAB_NAMES = {"Overview", "Loot Pools", "Chunk Map", "AI Roster", "AI Gear", "AI Drops"};
    private static final String[] TAB_HINTS = {
            "Name, tier, refill timer and actions.",
            "Choose what the loot crates can contain.",
            "Choose which chunks belong to the monument.",
            "How many guards spawn and how tough they are.",
            "Weapons and armor for each guard class.",
            "What guards drop when they are killed."};
    static final String ACTIVE_BG = "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)";
    private static final String DANGER_BG = "built-in(ui-mc:RECT_BORDER) color(#aa2222ff)";
    private static final int DESIGN_WIDTH = 704;
    private static final int DESIGN_HEIGHT = 430;
    /** Current panel size, recomputed from the window on every rebuild. */
    private int panelWidth = DESIGN_WIDTH;
    private int panelHeight = DESIGN_HEIGHT;
    private int contentWidth = DESIGN_WIDTH - 20;

    @FunctionalInterface
    interface SlotSettingsSaver {
        void save(int slot, int minimum, int maximum, int rarity);
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI createModularUI(Player player) {
        int[] size = UiKit.panelSize(DESIGN_WIDTH, DESIGN_HEIGHT, 560, 300);
        panelWidth = size[0];
        panelHeight = size[1];
        contentWidth = panelWidth - 20;
        var frame = new UIElement();
        frame.layout(l -> l.width(panelWidth).height(panelHeight).paddingAll(2));
        frame.addClass("preview_bg");
        var root = new UIElement();
        root.layout(l -> l.width(panelWidth - 4).height(panelHeight - 4).paddingAll(8).gapAll(6)
                .flexDirection(FlexDirection.COLUMN));
        root.addClass("panel_bg");
        MonumentView selected = selectedView();
        root.addChildren(buildHeader(selected));
        var content = MonumentUi.vbox(6);
        content.layout(l -> l.flex(1).width(contentWidth));
        if (selected == null) buildBrowser(content);
        else buildControllerView(content, selected);
        root.addChildren(content);
        frame.addChildren(root);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildHeader(@Nullable MonumentView selected) {
        var header = MonumentUi.hbox(6);
        header.layout(l -> l.width(contentWidth).height(20));
        if (selected == null) {
            header.addChildren(
                    new Label().setText("§6§lMonument Administration").layout(l -> l.flex(1)),
                    UiKit.rightLabel("§7" + monuments.size() + (monuments.size() == 1 ? " monument" : " monuments")));
            return header;
        }
        var back = new Button().setText("‹ All Monuments").setOnClick(event -> openBrowser());
        back.layout(l -> l.width(100).height(18));
        MonumentUi.tooltip(back, "§7Back to the monument list.");
        header.addChildren(back,
                new Label().setText("§6§l" + MonumentUi.truncate(selected.name(), 40)).layout(l -> l.flex(1)),
                UiKit.rightLabel("§7Tier §f" + selected.tier() + "  §8|  §7Next refill §a"
                        + MonumentUi.formatDuration(MonumentUi.refillSeconds(selected))));
        return header;
    }

    // Monument list

    @OnlyIn(Dist.CLIENT)
    private void buildBrowser(UIElement content) {
        if (monuments.isEmpty()) {
            var empty = MonumentUi.card();
            empty.layout(l -> l.width(contentWidth).paddingAll(16).alignItems(AlignItems.CENTER));
            empty.addChildren(
                    new Label().setText("§eNo monuments yet"),
                    new Label().setText("§7Place a §fMonument Controller§7 block to create one."));
            content.addChildren(empty);
            return;
        }
        content.addChildren(new Label().setText("§7Click §aOpen§7 to edit a monument's loot, area and AI guards."));

        var columns = MonumentUi.hbox(0);
        columns.layout(l -> l.width(contentWidth).height(12).paddingHorizontal(6));
        columns.addChildren(MonumentUi.flexCell("§8Name", 3), MonumentUi.flexCell("§8Tier", 1), MonumentUi.flexCell("§8Crates", 1),
                MonumentUi.flexCell("§8Chunks", 1), MonumentUi.flexCell("§8Controller", 3), MonumentUi.flexCell("§8Next refill", 2), MonumentUi.spacerWidth(54));
        content.addChildren(columns);

        int pageSize = Math.max(3, Math.min(10, (panelHeight - 120) / 30));
        int pageCount = Math.max(1, (monuments.size() + pageSize - 1) / pageSize);
        browserPage = Math.clamp(browserPage, 0, pageCount - 1);
        int first = browserPage * pageSize;
        int last = Math.min(monuments.size(), first + pageSize);
        for (MonumentView monument : monuments.subList(first, last)) {
            var row = MonumentUi.hbox(0);
            row.layout(l -> l.width(contentWidth).height(24).paddingHorizontal(6));
            UiKit.background(row, UiKit.ROW);
            var open = new Button().setText("§aOpen ›").setOnClick(event -> openMonument(monument));
            open.layout(l -> l.width(54).height(18));
            MonumentUi.tooltip(open, "§e" + monument.name(), "§7" + monument.dimension() + "  §f" + MonumentUi.coords(monument));
            row.addChildren(
                    MonumentUi.flexCell("§e" + MonumentUi.truncate(monument.name(), 28), 3),
                    MonumentUi.flexCell("§f" + monument.tier(), 1),
                    MonumentUi.flexCell("§f" + monument.crateCount(), 1),
                    MonumentUi.flexCell("§f" + monument.designatedChunks().size(), 1),
                    MonumentUi.flexCell("§7" + MonumentUi.coords(monument), 3),
                    MonumentUi.flexCell("§a" + MonumentUi.formatDuration(MonumentUi.refillSeconds(monument)), 2),
                    open);
            content.addChildren(row);
        }

        if (pageCount > 1) {
            var pager = MonumentUi.hbox(8);
            pager.layout(l -> l.width(contentWidth).height(22).justifyContent(YogaJustify.CENTER));
            var previous = new Button().setText(browserPage > 0 ? "‹ Previous" : "§8‹ Previous").setOnClick(event -> {
                if (browserPage > 0) browserPage--;
                refreshMonumentScreen();
            });
            previous.layout(l -> l.width(90).height(20));
            var next = new Button().setText(browserPage + 1 < pageCount ? "Next ›" : "§8Next ›").setOnClick(event -> {
                if (browserPage + 1 < pageCount) browserPage++;
                refreshMonumentScreen();
            });
            next.layout(l -> l.width(90).height(20));
            pager.addChildren(previous, new Label().setText("§7Page " + (browserPage + 1) + " of " + pageCount), next);
            content.addChildren(pager);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void openBrowser() {
        selectedId = null;
        resetTransientUiState();
        refreshMonumentScreen();
    }

    @OnlyIn(Dist.CLIENT)
    private void openMonument(MonumentView monument) {
        selectedId = monument.id();
        controllerTab = TAB_OVERVIEW;
        chunkMap.mapOffsetX = 0;
        chunkMap.mapOffsetZ = 0;
        currentLootPoolName = null;
        lootPoolNames = new ArrayList<>();
        aiRangesDirty = false;
        resetTransientUiState();
        PacketDistributor.sendToServer(new MonumentActionPacket(
                MonumentActionPacket.Action.SELECT, monument.id(), "", 0, 0, 0));
        refreshMonumentScreen();
    }

    private void resetTransientUiState() {
        selectedLootSettingsSlot = -1;
        guards.selectedLoadoutPickerSlot = -1;
        deleteMonumentArmed = false;
        deletePoolArmed = false;
    }

    // Controller view + tabs

    @OnlyIn(Dist.CLIENT)
    private void buildControllerView(UIElement content, MonumentView selected) {
        var tabs = MonumentUi.hbox(3);
        tabs.layout(l -> l.width(contentWidth).height(22));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int tab = i;
            boolean active = tab == controllerTab;
            var button = new Button().setText((active ? "§f§l" : "§7") + TAB_NAMES[i])
                    .setOnClick(event -> switchTab(selected, tab));
            button.layout(l -> l.flex(1).height(20));
            if (active) button.lss("base-background", ACTIVE_BG);
            MonumentUi.tooltip(button, "§e" + TAB_NAMES[i], "§7" + TAB_HINTS[i]);
            tabs.addChildren(button);
        }
        UIElement tabContent = switch (controllerTab) {
            case TAB_LOOT -> buildLootTab(selected);
            case TAB_MAP -> chunkMap.buildChunkMap(selected);
            case TAB_ROSTER -> guards.buildRosterTab(selected);
            case TAB_GEAR -> guards.buildGearTab(selected);
            case TAB_DROPS -> guards.buildDropsTab(selected);
            default -> buildOverviewTab(selected);
        };
        if (panelHeight < DESIGN_HEIGHT) {
            // Small window / large GUI scale: let the tab scroll instead of spilling out of the panel.
            var scroller = new ScrollerView();
            scroller.layout(l -> l.flex(1).width(contentWidth));
            tabContent.layout(l -> l.width(contentWidth - 10));
            scroller.addScrollViewChild(tabContent);
            content.addChildren(tabs, scroller);
        } else {
            var body = new UIElement();
            body.layout(l -> l.flex(1).width(contentWidth));
            body.addChildren(tabContent);
            content.addChildren(tabs, body);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void switchTab(MonumentView selected, int tab) {
        if (tab == controllerTab && guards.selectedLoadoutPickerSlot < 0) return;
        controllerTab = tab;
        resetTransientUiState();
        switch (tab) {
            case TAB_LOOT -> {
                editorMode = EditorMode.MONUMENT_LOOT;
                String poolName = currentLootPoolName != null ? currentLootPoolName
                        : !lootPoolNames.isEmpty() ? lootPoolNames.getFirst()
                        : !selected.lootPoolNames().isEmpty() ? selected.lootPoolNames().getFirst() : null;
                if (poolName != null) PacketDistributor.sendToServer(new MonumentLootActionPacket(
                        MonumentLootActionPacket.Action.SELECT_POOL, selected.id(), poolName, 0, 0, 0, 0));
            }
            case TAB_ROSTER, TAB_GEAR -> {
                editorMode = EditorMode.AI_LOADOUT;
                PacketDistributor.sendToServer(new MonumentAiActionPacket(
                        MonumentAiActionPacket.Action.SELECT_ROLE, selected.id(), selectedAiRole.ordinal(), 0, 0, 0, 0));
            }
            case TAB_DROPS -> {
                editorMode = EditorMode.AI_DROPS;
                PacketDistributor.sendToServer(new MonumentAiActionPacket(
                        MonumentAiActionPacket.Action.SELECT_DROPS, selected.id(), 0, 0, 0, 0, 0));
            }
            default -> { }
        }
        refreshMonumentScreen();
    }

    // Overview tab

    @OnlyIn(Dist.CLIENT)
    private UIElement buildOverviewTab(MonumentView selected) {
        var panel = MonumentUi.hbox(8);
        panel.layout(l -> l.alignItems(AlignItems.FLEX_START));

        String[] name = {selected.name()};
        int[] tier = {selected.tier()};
        int[] respawnSeconds = {(int) (selected.baseRespawnTicks() / 20L)};
        var nameField = MonumentUi.field(selected.name(), value -> name[0] = value);
        var tierField = MonumentUi.field(Integer.toString(tier[0]), value -> tier[0] = MonumentUi.parse(value, tier[0]));
        var respawnField = MonumentUi.field(Integer.toString(respawnSeconds[0]),
                value -> respawnSeconds[0] = MonumentUi.parse(value, respawnSeconds[0]));
        var save = new Button().setText("§aSave Settings").setOnClick(event -> PacketDistributor.sendToServer(
                new MonumentActionPacket(MonumentActionPacket.Action.SAVE, selected.id(), name[0],
                        tier[0], selected.radius(), respawnSeconds[0])));
        save.layout(l -> l.width(140).height(20));

        var settings = MonumentUi.card();
        settings.addChildren(MonumentUi.sectionTitle("Settings"),
                MonumentUi.formRow("Name", nameField, 170, "§7Display name of this monument."),
                MonumentUi.formRow("Tier (1-5)", tierField, 60, "§7Monument tier, from 1 to 5."),
                MonumentUi.formRow("Refill timer (sec)", respawnField, 60, "§7Seconds between loot refills.", "§8Minimum 10."),
                MonumentUi.spacer(2), save);

        var guide = MonumentUi.card();
        guide.addChildren(MonumentUi.sectionTitle("Where to find things"));
        for (int i = 1; i < TAB_NAMES.length; i++) {
            guide.addChildren(new Label().setText("§e" + TAB_NAMES[i] + " §8- §7" + TAB_HINTS[i]));
        }

        var left = MonumentUi.vbox(8);
        left.layout(l -> l.flex(1));
        left.addChildren(settings, guide);

        var refill = new Button().setText("§eRefill Loot Now").setOnClick(event -> PacketDistributor.sendToServer(
                new MonumentActionPacket(MonumentActionPacket.Action.REFILL, selected.id(), "", 0, 0, 0)));
        refill.layout(l -> l.width(160).height(20));
        MonumentUi.tooltip(refill, "§7Skips the refill timer.", "§8Crates refill once nobody is inside the monument.");

        var delete = new Button().setText(deleteMonumentArmed ? "§f§lClick again to delete" : "§cDelete Monument")
                .setOnClick(event -> {
                    if (!deleteMonumentArmed) {
                        deleteMonumentArmed = true;
                        refreshMonumentScreen();
                        return;
                    }
                    deleteMonumentArmed = false;
                    PacketDistributor.sendToServer(new MonumentActionPacket(
                            MonumentActionPacket.Action.DELETE, selected.id(), "", 0, 0, 0));
                });
        delete.layout(l -> l.width(160).height(20));
        if (deleteMonumentArmed) delete.lss("base-background", DANGER_BG);
        MonumentUi.tooltip(delete, "§cRemoves the controller, every crate and ore generator.", "§8You will be asked to confirm.");

        var status = MonumentUi.card();
        status.layout(l -> l.flex(1));
        status.addChildren(MonumentUi.sectionTitle("Status"),
                MonumentUi.infoRow("Dimension", selected.dimension()),
                MonumentUi.infoRow("Controller", MonumentUi.coords(selected)),
                MonumentUi.infoRow("Chunks", Integer.toString(selected.designatedChunks().size())),
                MonumentUi.infoRow("Loot crates", Integer.toString(selected.crateCount())),
                MonumentUi.infoRow("Next refill", MonumentUi.formatDuration(MonumentUi.refillSeconds(selected))),
                MonumentUi.spacer(6), MonumentUi.sectionTitle("Actions"), refill, delete);
        if (deleteMonumentArmed) {
            var cancel = new Button().setText("Cancel").setOnClick(event -> {
                deleteMonumentArmed = false;
                refreshMonumentScreen();
            });
            cancel.layout(l -> l.width(160).height(20));
            status.addChildren(cancel, new Label().setText("§cThis cannot be undone."));
        }
        panel.addChildren(left, status);
        return panel;
    }

    // Loot pools tab

    @OnlyIn(Dist.CLIENT)
    private UIElement buildLootTab(MonumentView selected) {
        if (lootPoolNames.isEmpty()) lootPoolNames = new ArrayList<>(selected.lootPoolNames());
        if (currentLootPoolName == null && !lootPoolNames.isEmpty()) currentLootPoolName = lootPoolNames.getFirst();
        var panel = MonumentUi.hbox(8);
        panel.layout(l -> l.alignItems(AlignItems.FLEX_START));

        var pools = MonumentUi.card();
        pools.layout(l -> l.width(140));
        pools.addChildren(MonumentUi.sectionTitle("Pools"), new Label().setText("§8" + lootPoolNames.size() + " of 8 used"));
        for (String poolName : lootPoolNames) {
            boolean active = poolName.equalsIgnoreCase(currentLootPoolName);
            var button = new Button().setText((active ? "§f§l" : "§7") + MonumentUi.truncate(poolName, 18)).setOnClick(event -> {
                currentLootPoolName = poolName;
                selectedLootSettingsSlot = -1;
                deletePoolArmed = false;
                PacketDistributor.sendToServer(new MonumentLootActionPacket(
                        MonumentLootActionPacket.Action.SELECT_POOL, selected.id(), poolName, 0, 0, 0, 0));
            });
            button.layout(l -> l.width(128).height(18));
            if (active) button.lss("base-background", ACTIVE_BG);
            pools.addChildren(button);
        }

        String[] newPoolName = {""};
        var poolNameField = UiKit.textField("");
        poolNameField.setValue("");
        poolNameField.getTextFieldStyle().placeholder(Component.literal("New pool name"));
        poolNameField.bindObserver(value -> newPoolName[0] = value == null ? "" : value);
        poolNameField.layout(l -> l.width(128).height(18));
        var create = new Button().setText("§a+ Create Pool").setOnClick(event -> {
            if (!newPoolName[0].isBlank()) PacketDistributor.sendToServer(new MonumentLootActionPacket(
                    MonumentLootActionPacket.Action.CREATE_POOL, selected.id(), newPoolName[0].trim(), 0, 0, 0, 0));
        });
        create.layout(l -> l.width(128).height(18));
        var delete = new Button().setText(deletePoolArmed ? "§f§lConfirm delete" : "§cDelete Pool").setOnClick(event -> {
            if (currentLootPoolName == null) return;
            if (!deletePoolArmed) {
                deletePoolArmed = true;
                refreshMonumentScreen();
                return;
            }
            deletePoolArmed = false;
            PacketDistributor.sendToServer(new MonumentLootActionPacket(
                    MonumentLootActionPacket.Action.DELETE_POOL, selected.id(), currentLootPoolName, 0, 0, 0, 0));
        });
        delete.layout(l -> l.width(128).height(18));
        if (deletePoolArmed) delete.lss("base-background", DANGER_BG);
        MonumentUi.tooltip(delete, "§7Deletes the selected pool: §f" + (currentLootPoolName == null ? "none" : currentLootPoolName),
                "§8A monument must keep at least one pool.");
        pools.addChildren(MonumentUi.spacer(4), poolNameField, create, delete);

        var contents = MonumentUi.card();
        contents.layout(l -> l.width(184));
        contents.addChildren(
                MonumentUi.sectionTitle(currentLootPoolName == null ? "No pool selected" : MonumentUi.truncate(currentLootPoolName, 22)),
                new Label().setText("§8Drag items in or out."),
                slotGrid(0, 6));
        var center = MonumentUi.vbox(8);
        center.layout(l -> l.width(184));
        center.addChildren(contents, inventoryCard());

        panel.addChildren(pools, center, guards.buildSlotSettingsCard(lootMinCounts, lootMaxCounts, lootRarityLevels,
                (slot, minimum, maximum, rarity) -> {
                    if (currentLootPoolName == null) return;
                    PacketDistributor.sendToServer(new MonumentLootActionPacket(
                            MonumentLootActionPacket.Action.SAVE_SLOT_SETTINGS, selected.id(), currentLootPoolName,
                            slot, minimum, maximum, rarity));
                }, null));
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    static void refreshMonumentScreen() {
        if (Minecraft.getInstance().screen instanceof MonumentScreen screen) {
            screen.refreshContent();
        }
    }

    @OnlyIn(Dist.CLIENT)
    ItemSlot displaySlot(int index) {
        var display = new ItemSlot();
        display.addEventListener(UIEvents.TICK, event -> display.setItem(slots.get(index).getItem().copy()));
        UiKit.onClick(display, event -> {
            if (!(Minecraft.getInstance().screen instanceof MonumentScreen screen)) return;
            if (editorMode == EditorMode.AI_LOADOUT && index < 5) {
                if (event.button == 0 || event.button == 1) guards.openLoadoutPicker(index);
                return;
            }
            if (index < LOOT_SLOTS && event.button == 1 && !slots.get(index).getItem().isEmpty()) {
                selectedLootSettingsSlot = index;
                screen.refreshContent();
                return;
            }
            ClickType type = Screen.hasShiftDown() ? ClickType.QUICK_MOVE : ClickType.PICKUP;
            screen.interactWithSlot(index, event.button, type);
        });
        return display;
    }

    @OnlyIn(Dist.CLIENT)
    UIElement slotGrid(int firstSlot, int rows) {
        var grid = MonumentUi.vbox(1);
        for (int row = 0; row < rows; row++) {
            var slotRow = MonumentUi.hbox(1);
            slotRow.layout(l -> l.height(18));
            int start = firstSlot + row * 9;
            for (int col = 0; col < 9; col++) slotRow.addChildren(displaySlot(start + col));
            grid.addChildren(slotRow);
        }
        return grid;
    }

    @OnlyIn(Dist.CLIENT)
    UIElement inventoryCard() {
        var inventory = MonumentUi.card();
        inventory.layout(l -> l.width(184));
        inventory.addChildren(new Label().setText("§7Your Inventory"), slotGrid(PLAYER_INV_OFFSET, 3),
                slotGrid(PLAYER_INV_OFFSET + 27, 1));
        return inventory;
    }

    @Nullable
    MonumentView selectedView() {
        return selectedId == null ? null : monuments.stream().filter(view -> view.id().equals(selectedId)).findFirst().orElse(null);
    }

}