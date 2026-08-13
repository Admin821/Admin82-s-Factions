package com.admin82.factions.menu;

import com.admin82.factions.blockentity.MonumentControllerBlockEntity;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.admin82.factions.monument.MonumentView;
import com.admin82.factions.network.packet.MonumentActionPacket;
import com.admin82.factions.network.packet.MonumentLootActionPacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.supplydrop.SupplyDropPool;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.slot.ItemHandlerSlot;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;
import org.appliedenergistics.yoga.YogaJustify;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.IObserver;

public class MonumentMenu extends AbstractContainerMenu {
    public static final int LOOT_SLOTS = 54;
    private static final int PLAYER_INV_OFFSET = LOOT_SLOTS;

    private List<MonumentView> monuments;
    @Nullable private UUID selectedId;
    @Nullable private MinecraftServer server;
    private boolean saveEnabled;
    @Nullable private ServerPlayer serverPlayer;
    @Nullable private String currentLootPoolName;
    private List<String> lootPoolNames = new ArrayList<>();
    private int[] lootMinCounts = defaultLootCounts();
    private int[] lootMaxCounts = defaultLootCounts();
    private int[] lootRarityLevels = new int[LOOT_SLOTS];
    private int selectedLootSettingsSlot = -1;
    private int browserPage;
    private int controllerTab;
    private int mapOffsetX;
    private int mapOffsetZ;
    private int monumentMapViewSize = 11;
    private boolean monumentMapDragging;
    private float monumentMapDragDistance;
    private float monumentMapLastX;
    private float monumentMapLastY;
    private float monumentMapAccumX;
    private float monumentMapAccumY;
    @Nullable private MonumentTerrainTexture monumentTerrainTexture;

    private final ItemStackHandler staging = new ItemStackHandler(LOOT_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
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
        ServerLevel level = levelFor(server, entry.dimension);
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
        } else if (selectedId == null || !moveItemStackTo(stack, 0, LOOT_SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return stack.getCount() == result.getCount() ? ItemStack.EMPTY : result;
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI createModularUI(Player player) {
        var frame = new UIElement();
        frame.layout(l -> l.width(704).height(430).paddingAll(2));
        frame.addClass("preview_bg");
        var root = new UIElement();
        root.layout(l -> l.width(700).height(426).paddingAll(8).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        root.addClass("panel_bg");
        root.addChildren(new Label().setText("§6§lMonument Administration"));
        var content = new UIElement();
        content.layout(l -> l.flex(1).width(684));
        root.addChildren(content);
        rebuildContent(content);
        frame.addChildren(root);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    @OnlyIn(Dist.CLIENT)
    private void rebuildContent(UIElement content) {
        content.clearAllChildren();
        MonumentView selected = selectedView();
        if (selected == null) content.addChildren(buildBrowser(content));
        else content.addChildren(buildControllerTabs(content, selected));
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildBrowser(UIElement content) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(5).paddingAll(6));
        panel.addChildren(new Label().setText("§7Select a monument to open its controller panel."));
        if (monuments.isEmpty()) {
            panel.addChildren(new Label().setText("§8No monuments have been created."));
            return panel;
        }
        int pageCount = Math.max(1, (monuments.size() + 11) / 12);
        browserPage = Math.clamp(browserPage, 0, pageCount - 1);
        int first = browserPage * 12;
        int last = Math.min(monuments.size(), first + 12);
        for (MonumentView monument : monuments.subList(first, last)) {
            long seconds = Math.max(0L, Math.round(monument.remainingRespawnTicks() / 20.0));
            String row = "§e" + monument.name() + "  §7Tier " + monument.tier() + "  §8| §7"
                    + monument.crateCount() + " crates  §8| §7" + monument.designatedChunks().size() + " chunks  §8| §7"
                    + monument.x() + ", " + monument.y() + ", "
                    + monument.z() + "  §8| §a" + formatDuration(seconds);
            panel.addChildren(new Button().setText(row).setOnClick(event -> {
                selectedId = monument.id();
                controllerTab = 0;
                mapOffsetX = 0;
                mapOffsetZ = 0;
                PacketDistributor.sendToServer(new MonumentActionPacket(
                        MonumentActionPacket.Action.SELECT, monument.id(), "", 0, 0, 0));
                rebuildContent(content);
            }).layout(l -> l.width(660).height(24)));
        }
        if (pageCount > 1) {
            var pageRow = new UIElement();
            pageRow.layout(l -> l.width(660).height(24).flexDirection(YogaFlexDirection.ROW)
                    .gapAll(8).alignItems(YogaAlign.CENTER));
            pageRow.addChildren(
                    new Button().setText("< Previous").setOnClick(event -> {
                        if (browserPage > 0) browserPage--;
                        rebuildContent(content);
                    }).layout(l -> l.width(110).height(22)),
                    new Label().setText("§7Page " + (browserPage + 1) + " / " + pageCount)
                            .layout(l -> l.width(110)),
                    new Button().setText("Next >").setOnClick(event -> {
                        if (browserPage + 1 < pageCount) browserPage++;
                        rebuildContent(content);
                    }).layout(l -> l.width(110).height(22)));
            panel.addChildren(pageRow);
        }
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildControllerTabs(UIElement content, MonumentView selected) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(5));

        var navigation = new UIElement();
        navigation.layout(l -> l.width(684).height(24).flexDirection(YogaFlexDirection.ROW).gapAll(4));
        var body = new UIElement();
        body.layout(l -> l.flex(1).width(684));

        var settingsButton = new Button().setText("Settings & Loot")
                .setOnClick(event -> {
                    controllerTab = 0;
                    body.clearAllChildren();
                    body.addChildren(buildControllerPanel(content, selected));
                }).layout(l -> l.width(150).height(22));
        var mapButton = new Button().setText("Chunk Map")
                .setOnClick(event -> {
                    controllerTab = 1;
                    body.clearAllChildren();
                    body.addChildren(buildChunkMap(selected));
                }).layout(l -> l.width(150).height(22));
        var backButton = new Button().setText("< All Monuments")
                .setOnClick(event -> {
                    selectedId = null;
                    rebuildContent(content);
                }).layout(l -> l.width(150).height(22));
        navigation.addChildren(backButton, settingsButton, mapButton);
        body.addChildren(controllerTab == 1 ? buildChunkMap(selected) : buildControllerPanel(content, selected));
        panel.addChildren(navigation, body);
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildControllerPanel(UIElement content, MonumentView selected) {
        var split = new UIElement();
        split.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.ROW).gapAll(12));
        var left = new UIElement();
        left.layout(l -> l.width(250).flexDirection(YogaFlexDirection.COLUMN).gapAll(5));

        String[] name = {selected.name()};
        int[] tier = {selected.tier()};
        int[] respawnSeconds = {(int) (selected.baseRespawnTicks() / 20L)};
        var nameField = field(selected.name(), value -> name[0] = value);
        var tierField = field(Integer.toString(tier[0]), value -> tier[0] = parse(value, tier[0]));
        var respawnField = field(Integer.toString(respawnSeconds[0]), value -> respawnSeconds[0] = parse(value, respawnSeconds[0]));
        nameField.layout(l -> l.width(110).height(18));
        tierField.layout(l -> l.width(110).height(18));
        respawnField.layout(l -> l.width(110).height(18));
        left.addChildren(
                new Label().setText("§e§l" + selected.name()),
                new Label().setText("§8" + selected.dimension() + "  " + selected.x() + ", " + selected.y() + ", " + selected.z()),
                labeled("§7Name", nameField), labeled("§7Tier (1-5)", tierField),
                labeled("§7Base respawn (seconds)", respawnField),
                new Label().setText("§7Designated chunks: §f" + selected.designatedChunks().size()),
                new Button().setText("§aSave Settings").setOnClick(event -> PacketDistributor.sendToServer(
                        new MonumentActionPacket(MonumentActionPacket.Action.SAVE, selected.id(), name[0],
                        tier[0], selected.radius(), respawnSeconds[0]))).layout(l -> l.width(180).height(24)),
                new Button().setText("§eRefill Loot Now").setOnClick(event -> PacketDistributor.sendToServer(
                        new MonumentActionPacket(MonumentActionPacket.Action.REFILL, selected.id(), "", 0, 0, 0)))
                        .layout(l -> l.width(180).height(24)),
                new Button().setText("§cDelete Monument").setOnClick(event -> PacketDistributor.sendToServer(
                        new MonumentActionPacket(MonumentActionPacket.Action.DELETE, selected.id(), "", 0, 0, 0)))
                        .layout(l -> l.width(180).height(24)));

        var right = new UIElement();
        right.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(3)
            .justifyContent(YogaJustify.FLEX_END));
        if (lootPoolNames.isEmpty()) lootPoolNames = new ArrayList<>(selected.lootPoolNames());
        if (currentLootPoolName == null && !lootPoolNames.isEmpty()) currentLootPoolName = lootPoolNames.getFirst();

        String[] newPoolName = {""};
        var poolNameField = field("", value -> newPoolName[0] = value);
        poolNameField.layout(l -> l.width(130).height(18));
        var createRow = new UIElement();
        createRow.layout(l -> l.height(22).flexDirection(YogaFlexDirection.ROW).gapAll(4));
        createRow.addChildren(
                new Label().setText("§6§lLoot Pools").layout(l -> l.width(75)),
                poolNameField,
                new Button().setText("Create").setOnClick(event -> {
                    if (!newPoolName[0].isBlank()) PacketDistributor.sendToServer(new MonumentLootActionPacket(
                            MonumentLootActionPacket.Action.CREATE_POOL, selected.id(), newPoolName[0], 0, 0, 0, 0));
                }).layout(l -> l.width(58).height(20)),
                new Button().setText("§cDelete").setOnClick(event -> {
                    if (currentLootPoolName != null) PacketDistributor.sendToServer(new MonumentLootActionPacket(
                            MonumentLootActionPacket.Action.DELETE_POOL, selected.id(), currentLootPoolName, 0, 0, 0, 0));
                }).layout(l -> l.width(58).height(20)));
        right.addChildren(createRow);

        for (int first = 0; first < lootPoolNames.size(); first += 4) {
            var poolRow = new UIElement();
            poolRow.layout(l -> l.height(21).flexDirection(YogaFlexDirection.ROW).gapAll(3));
            for (String poolName : lootPoolNames.subList(first, Math.min(first + 4, lootPoolNames.size()))) {
                boolean selectedPool = poolName.equalsIgnoreCase(currentLootPoolName);
                poolRow.addChildren(new Button().setText((selectedPool ? "§a> " : "§7") + poolName)
                        .setOnClick(event -> {
                            currentLootPoolName = poolName;
                            selectedLootSettingsSlot = -1;
                            PacketDistributor.sendToServer(new MonumentLootActionPacket(
                                    MonumentLootActionPacket.Action.SELECT_POOL, selected.id(), poolName, 0, 0, 0, 0));
                        }).layout(l -> l.width(82).height(19)));
            }
            right.addChildren(poolRow);
        }

        right.addChildren(new Label().setText("§8Selected: §f" + (currentLootPoolName == null ? "None" : currentLootPoolName)
                + "  §8| Right-click an item for rarity"));
        var lootEditor = new UIElement();
        lootEditor.layout(l -> l.height(145).flexDirection(YogaFlexDirection.ROW).gapAll(8));
        var lootGrid = new UIElement();
        lootGrid.layout(l -> l.width(180).flexDirection(YogaFlexDirection.COLUMN).gapAll(1));
        for (int row = 0; row < 6; row++) {
            var slotRow = new UIElement();
            slotRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(18).gapAll(1));
            for (int col = 0; col < 9; col++) slotRow.addChildren(displaySlot(row * 9 + col));
            lootGrid.addChildren(slotRow);
        }
        lootEditor.addChildren(lootGrid, buildLootSettingsEditor(selected));
        right.addChildren(lootEditor);
        right.addChildren(new Label().setText("§7Player Inventory"));
        for (int row = 0; row < 4; row++) {
            var slotRow = new UIElement();
            slotRow.layout(l -> l.flexDirection(YogaFlexDirection.ROW).height(18).gapAll(1));
            int start = row < 3 ? PLAYER_INV_OFFSET + row * 9 : PLAYER_INV_OFFSET + 27;
            for (int col = 0; col < 9; col++) slotRow.addChildren(displaySlot(start + col));
            right.addChildren(slotRow);
        }
        split.addChildren(left, right);
        return split;
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildLootSettingsEditor(MonumentView selected) {
        var editor = new UIElement();
        editor.layout(l -> l.width(215).flexDirection(YogaFlexDirection.COLUMN).gapAll(3));
        int slot = selectedLootSettingsSlot;
        if (slot < 0 || slot >= LOOT_SLOTS || slots.get(slot).getItem().isEmpty()) {
            editor.addChildren(new Label().setText("§6§lRarity §8- appearance chance"));
            for (int rarity = 0; rarity <= 10; rarity++) {
                editor.addChildren(monumentRarityRow(rarity));
            }
            return editor;
        }

        ItemStack stack = slots.get(slot).getItem();
        int[] min = {Math.max(1, lootMinCounts[slot])};
        int[] max = {Math.max(min[0], lootMaxCounts[slot])};
        int[] rarity = {Math.clamp(lootRarityLevels[slot], 0, 10)};
        var minField = field(Integer.toString(min[0]), value -> min[0] = parse(value, min[0]));
        var maxField = field(Integer.toString(max[0]), value -> max[0] = parse(value, max[0]));
        var rarityField = field(Integer.toString(rarity[0]), value -> rarity[0] = parse(value, rarity[0]));
        minField.layout(l -> l.width(55).height(17));
        maxField.layout(l -> l.width(55).height(17));
        rarityField.layout(l -> l.width(55).height(17));
        editor.addChildren(
                new Label().setText("§6" + stack.getHoverName().getString()),
                compactLabeled("§7Minimum", minField),
                compactLabeled("§7Maximum", maxField),
                compactLabeled("§7Rarity 0-10", rarityField),
                new Button().setText("§aSave Item Settings").setOnClick(event -> {
                    if (currentLootPoolName == null) return;
                    PacketDistributor.sendToServer(new MonumentLootActionPacket(
                            MonumentLootActionPacket.Action.SAVE_SLOT_SETTINGS, selected.id(), currentLootPoolName,
                            slot, min[0], max[0], rarity[0]));
                }).layout(l -> l.width(145).height(19)));
        return editor;
    }

    @OnlyIn(Dist.CLIENT)
    private static UIElement monumentRarityRow(int rarity) {
        return new Label().setText("§7" + rarity + " §8- §f" + SupplyDropPool.getRarityName(rarity)
                + " §8- §a" + SupplyDropPool.getAppearanceChanceText(rarity))
                .layout(l -> l.width(215).height(11));
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement buildChunkMap(MonumentView selected) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.ROW).gapAll(12).paddingAll(4));

        int controllerChunkX = Math.floorDiv(selected.x(), 16);
        int controllerChunkZ = Math.floorDiv(selected.z(), 16);
        int[] centerX = {controllerChunkX + mapOffsetX};
        int[] centerZ = {controllerChunkZ + mapOffsetZ};
        var mapArea = new UIElement();
        mapArea.layout(l -> l.width(286).height(286).flexDirection(YogaFlexDirection.COLUMN));
        if (monumentTerrainTexture == null) monumentTerrainTexture = new MonumentTerrainTexture(286);
        mapArea.style(style -> style.background(monumentTerrainTexture));
        var viewLabel = new Label();

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            MonumentView current = selectedView();
            if (current == null) current = selected;
            mapArea.clearAllChildren();
            int halfView = monumentMapViewSize / 2;
            int cellSize = Math.max(10, (286 - monumentMapViewSize + 1) / monumentMapViewSize);
            int actualSize = cellSize * monumentMapViewSize;
            viewLabel.setText("§7Visible grid: §f" + monumentMapViewSize + "x" + monumentMapViewSize);
            MonumentTerrainTexture texture = monumentTerrainTexture;
            Level level = Minecraft.getInstance().level;
                boolean matchingDimension = level != null
                    && level.dimension().location().toString().equals(current.dimension());
            texture.clear();
            for (int row = 0; row < monumentMapViewSize; row++) {
                for (int col = 0; col < monumentMapViewSize; col++) {
                    int chunkX = centerX[0] - halfView + col;
                    int chunkZ = centerZ[0] - halfView + row;
                    boolean loaded = matchingDimension && level.hasChunk(chunkX, chunkZ);
                    for (int pixelY = 0; pixelY < cellSize; pixelY++) {
                        for (int pixelX = 0; pixelX < cellSize; pixelX++) {
                            int imageX = col * cellSize + pixelX;
                            int imageY = row * cellSize + pixelY;
                            int color = loaded
                                    ? computeBlockColor(level, chunkX * 16 + pixelX * 16 / cellSize,
                                            chunkZ * 16 + pixelY * 16 / cellSize)
                                    : 0x404040;
                            texture.setPixel(imageX, imageY, color);
                        }
                    }
                }
            }
            for (int line = 0; line <= monumentMapViewSize; line++) {
                int pixel = line * cellSize;
                if (pixel < 286) {
                    texture.drawHLine(pixel, 0, actualSize - 1);
                    texture.drawVLine(pixel, 0, actualSize - 1);
                }
            }
            int border = Math.max(1, cellSize / 7);
            for (int row = 0; row < monumentMapViewSize; row++) {
                for (int col = 0; col < monumentMapViewSize; col++) {
                    int chunkX = centerX[0] - halfView + col;
                    int chunkZ = centerZ[0] - halfView + row;
                    long key = ChunkPos.asLong(chunkX, chunkZ);
                    boolean controller = chunkX == controllerChunkX && chunkZ == controllerChunkZ;
                    boolean designated = current.designatedChunks().contains(key);
                    boolean factionClaim = current.factionClaims().contains(key);
                    int imageX = col * cellSize;
                    int imageY = row * cellSize;
                    if (factionClaim) texture.drawChunkBorder(imageX, imageY, cellSize, 0xFF4444, border);
                    if (designated && !controller) {
                        int inset = factionClaim ? border : 0;
                        texture.drawChunkBorder(imageX + inset, imageY + inset,
                                cellSize - inset * 2, 0x22CC66, Math.max(1, border));
                    }
                    if (controller) texture.drawChunkBorder(imageX, imageY, cellSize, 0xFFCC00, border + 1);
                }
            }
            texture.upload();
            for (int row = -halfView; row <= halfView; row++) {
                var rowElement = new UIElement();
                rowElement.layout(l -> l.height(cellSize).flexDirection(YogaFlexDirection.ROW));
                for (int col = -halfView; col <= halfView; col++) {
                    int chunkX = centerX[0] + col;
                    int chunkZ = centerZ[0] + row;
                    var cell = new UIElement();
                    cell.addEventListener(UIEvents.MOUSE_UP, event -> {
                        if (monumentMapDragDistance > 3f) return;
                        PacketDistributor.sendToServer(new MonumentActionPacket(
                                MonumentActionPacket.Action.TOGGLE_CHUNK, selected.id(), "", chunkX, chunkZ, 0));
                    });
                    cell.layout(l -> l.width(cellSize).height(cellSize));
                    rowElement.addChildren(cell);
                }
                mapArea.addChildren(rowElement);
            }
        };
        mapArea.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            monumentMapDragging = true;
            monumentMapDragDistance = 0f;
            monumentMapLastX = event.x;
            monumentMapLastY = event.y;
            monumentMapAccumX = 0f;
            monumentMapAccumY = 0f;
        });
        mapArea.addEventListener(UIEvents.MOUSE_UP, event -> monumentMapDragging = false);
        mapArea.addEventListener(UIEvents.MOUSE_MOVE, event -> {
            if (!monumentMapDragging) return;
            float deltaX = monumentMapLastX - event.x;
            float deltaY = monumentMapLastY - event.y;
            monumentMapLastX = event.x;
            monumentMapLastY = event.y;
            monumentMapDragDistance += Math.abs(deltaX) + Math.abs(deltaY);
            monumentMapAccumX += deltaX;
            monumentMapAccumY += deltaY;

            boolean moved = false;
            int pixelsPerChunk = Math.max(1, 286 / monumentMapViewSize);
            while (monumentMapAccumX >= pixelsPerChunk) {
                mapOffsetX++;
                centerX[0]++;
                monumentMapAccumX -= pixelsPerChunk;
                moved = true;
            }
            while (monumentMapAccumX <= -pixelsPerChunk) {
                mapOffsetX--;
                centerX[0]--;
                monumentMapAccumX += pixelsPerChunk;
                moved = true;
            }
            while (monumentMapAccumY >= pixelsPerChunk) {
                mapOffsetZ++;
                centerZ[0]++;
                monumentMapAccumY -= pixelsPerChunk;
                moved = true;
            }
            while (monumentMapAccumY <= -pixelsPerChunk) {
                mapOffsetZ--;
                centerZ[0]--;
                monumentMapAccumY += pixelsPerChunk;
                moved = true;
            }
            if (moved) refresh[0].run();
        });
        mapArea.addEventListener(UIEvents.MOUSE_WHEEL, event -> {
            if (event.deltaY > 0) monumentMapViewSize = Math.max(7, monumentMapViewSize - 2);
            else if (event.deltaY < 0) monumentMapViewSize = Math.min(25, monumentMapViewSize + 2);
            refresh[0].run();
        });
        mapArea.addEventListener(UIEvents.TICK, event -> {
            if (!monumentMapDragging) return;
            long window = Minecraft.getInstance().getWindow().getWindow();
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT)
                    != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                monumentMapDragging = false;
            }
        });
        refresh[0].run();

        var controls = new UIElement();
        controls.layout(l -> l.width(260).flexDirection(YogaFlexDirection.COLUMN).gapAll(6));
        var zoomRow = new UIElement();
        zoomRow.layout(l -> l.width(250).height(24).flexDirection(YogaFlexDirection.ROW).gapAll(5));
        zoomRow.addChildren(
                new Button().setText("-").setOnClick(event -> {
                    monumentMapViewSize = Math.min(25, monumentMapViewSize + 2);
                    refresh[0].run();
                }).layout(l -> l.width(30).height(22)),
                viewLabel.layout(l -> l.width(150)),
                new Button().setText("+").setOnClick(event -> {
                    monumentMapViewSize = Math.max(7, monumentMapViewSize - 2);
                    refresh[0].run();
                }).layout(l -> l.width(30).height(22)));
        controls.addChildren(
                new Label().setText("§6§lMonument Chunk Map"),
                new Label().setText("§7Click a chunk to add or remove it."),
                new Label().setText("§6Gold §7Controller chunk (required)"),
                new Label().setText("§aGreen §7Designated monument chunk"),
                new Label().setText("§cRed §7Faction-claimed chunk"),
                new Label().setText("§7Selected: §f" + selected.designatedChunks().size() + " chunks"),
                new Label().setText("§8The map is centered on chunk " + centerX[0] + ", " + centerZ[0]),
                zoomRow,
                new Button().setText("Center on Controller").setOnClick(event -> {
                    mapOffsetX = 0;
                    mapOffsetZ = 0;
                    centerX[0] = controllerChunkX;
                    centerZ[0] = controllerChunkZ;
                    refresh[0].run();
                }).layout(l -> l.width(180).height(22)),
                new Label().setText("§8Entry alerts, protection, loot crates,"),
                new Label().setText("§8and ore generators use these chunks."));
        panel.addChildren(mapArea, controls);
        return panel;
    }

    @OnlyIn(Dist.CLIENT)
    private static int computeBlockColor(Level level, int worldX, int worldZ) {
        try {
            int floorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, worldX, worldZ) - 1;
            int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
            floorY = Math.max(floorY, level.getMinBuildHeight());
            surfaceY = Math.max(surfaceY, level.getMinBuildHeight());
            if (surfaceY > floorY) {
                BlockPos surfacePos = new BlockPos(worldX, surfaceY, worldZ);
                if (!level.getFluidState(surfacePos).isEmpty()) {
                    int base;
                    try {
                        base = net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(level, surfacePos);
                    } catch (Exception ignored) {
                        base = 0x3F76E4;
                    }
                    float depth = Math.max(0.20f, 1.0f - (surfaceY - floorY) * 0.05f);
                    return ((int) (((base >> 16) & 0xFF) * depth) << 16)
                            | ((int) (((base >> 8) & 0xFF) * depth) << 8)
                            | (int) ((base & 0xFF) * depth);
                }
            }

            int topY = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ) - 1,
                    level.getMinBuildHeight());
            BlockPos pos = new BlockPos(worldX, topY, worldZ);
            int color = saturateColor(getBlockRenderColor(level.getBlockState(pos), level, pos), 1.35f);
            long hash = worldX * 374761393L ^ worldZ * 668265263L;
            float jitter = 1.0f + (((hash >> 16) & 0xFF) - 128) / 3200.0f;
            int northY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ - 1) - 1;
            int eastY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX + 1, worldZ) - 1;
            if (northY <= level.getMinBuildHeight() || northY > level.getMaxBuildHeight()) northY = topY;
            if (eastY <= level.getMinBuildHeight() || eastY > level.getMaxBuildHeight()) eastY = topY;
            float hill = Math.clamp(1.0f + (topY - northY) * 0.12f + (topY - eastY) * 0.06f, 0.40f, 1.60f);
            float altitude = Math.clamp(1.0f + (topY - 64) * 0.004f, 0.70f, 1.25f);
            float brightness = hill * altitude * jitter;
            int red = Math.clamp((int) (((color >> 16) & 0xFF) * brightness), 0, 255);
            int green = Math.clamp((int) (((color >> 8) & 0xFF) * brightness), 0, 255);
            int blue = Math.clamp((int) ((color & 0xFF) * brightness), 0, 255);
            return red << 16 | green << 8 | blue;
        } catch (Exception ignored) {
            return 0x404040;
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static int getBlockRenderColor(net.minecraft.world.level.block.state.BlockState state,
                                           Level level, BlockPos pos) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            var quads = minecraft.getBlockRenderer().getBlockModel(state)
                    .getQuads(state, Direction.UP, net.minecraft.util.RandomSource.create(42L));
            if (!quads.isEmpty()) {
                var contents = quads.getFirst().getSprite().contents();
                long red = 0, green = 0, blue = 0;
                int samples = 0;
                for (int y = 0; y < contents.height(); y += 2) {
                    for (int x = 0; x < contents.width(); x += 2) {
                        int pixel = contents.getOriginalImage().getPixelRGBA(x, y);
                        if (((pixel >> 24) & 0xFF) < 64) continue;
                        red += pixel & 0xFF;
                        green += pixel >> 8 & 0xFF;
                        blue += pixel >> 16 & 0xFF;
                        samples++;
                    }
                }
                if (samples > 0) {
                    int texture = (int) (red / samples) << 16 | (int) (green / samples) << 8 | (int) (blue / samples);
                    int tint = minecraft.getBlockColors().getColor(state, level, pos, 0);
                    if (tint != -1) {
                        texture = ((texture >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255) << 16
                                | ((texture >> 8 & 0xFF) * (tint >> 8 & 0xFF) / 255) << 8
                                | (texture & 0xFF) * (tint & 0xFF) / 255;
                    }
                    return texture;
                }
            }
        } catch (Exception ignored) {
        }
        int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        if (tint != -1) return tint;
        int mapColor = state.getMapColor(level, pos).col;
        return mapColor == 0 || mapColor == -1 ? 0x707070 : mapColor;
    }

    private static int saturateColor(int color, float factor) {
        float red = (color >> 16 & 0xFF) / 255f;
        float green = (color >> 8 & 0xFF) / 255f;
        float blue = (color & 0xFF) / 255f;
        float luminance = 0.299f * red + 0.587f * green + 0.114f * blue;
        int saturatedRed = Math.clamp((int) ((luminance + (red - luminance) * factor) * 255), 0, 255);
        int saturatedGreen = Math.clamp((int) ((luminance + (green - luminance) * factor) * 255), 0, 255);
        int saturatedBlue = Math.clamp((int) ((luminance + (blue - luminance) * factor) * 255), 0, 255);
        return saturatedRed << 16 | saturatedGreen << 8 | saturatedBlue;
    }

    @OnlyIn(Dist.CLIENT)
    private static final class MonumentTerrainTexture
            extends com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture {
        private final com.mojang.blaze3d.platform.NativeImage image;
        private final net.minecraft.client.renderer.texture.DynamicTexture texture;
        private final net.minecraft.resources.ResourceLocation location;
        private final int size;

        MonumentTerrainTexture(int size) {
            super(0x00000000);
            this.size = size;
            image = new com.mojang.blaze3d.platform.NativeImage(
                    com.mojang.blaze3d.platform.NativeImage.Format.RGBA, size, size, false);
            texture = new net.minecraft.client.renderer.texture.DynamicTexture(image);
            location = Minecraft.getInstance().getTextureManager().register("adminsfactions_monument_terrain", texture);
            clear();
        }

        void setPixel(int x, int y, int color) {
            if (x >= 0 && x < size && y >= 0 && y < size) image.setPixelRGBA(x, y, abgr(color));
        }

        void clear() {
            int gray = abgr(0x404040);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) image.setPixelRGBA(x, y, gray);
            }
        }

        void drawHLine(int y, int startX, int endX) {
            if (y < 0 || y >= size) return;
            for (int x = Math.max(0, startX); x <= Math.min(size - 1, endX); x++) darken(x, y);
        }

        void drawVLine(int x, int startY, int endY) {
            if (x < 0 || x >= size) return;
            for (int y = Math.max(0, startY); y <= Math.min(size - 1, endY); y++) darken(x, y);
        }

        private void darken(int x, int y) {
            int value = image.getPixelRGBA(x, y);
            int red = value & 0xFF;
            int green = value >> 8 & 0xFF;
            int blue = value >> 16 & 0xFF;
            image.setPixelRGBA(x, y, 0xFF000000 | blue / 2 << 16 | green / 2 << 8 | red / 2);
        }

        void drawChunkBorder(int x, int y, int cellSize, int color, int thickness) {
            if (cellSize <= 0) return;
            int packed = abgr(color);
            for (int border = 0; border < thickness; border++) {
                int left = x + border;
                int right = x + cellSize - 1 - border;
                int top = y + border;
                int bottom = y + cellSize - 1 - border;
                for (int pixelX = left; pixelX <= right; pixelX++) {
                    if (pixelX >= 0 && pixelX < size && top >= 0 && top < size) image.setPixelRGBA(pixelX, top, packed);
                    if (pixelX >= 0 && pixelX < size && bottom >= 0 && bottom < size) image.setPixelRGBA(pixelX, bottom, packed);
                }
                for (int pixelY = top; pixelY <= bottom; pixelY++) {
                    if (left >= 0 && left < size && pixelY >= 0 && pixelY < size) image.setPixelRGBA(left, pixelY, packed);
                    if (right >= 0 && right < size && pixelY >= 0 && pixelY < size) image.setPixelRGBA(right, pixelY, packed);
                }
            }
        }

        void upload() { texture.upload(); }

        private static int abgr(int color) {
            return 0xFF000000 | (color & 0xFF) << 16 | (color >> 8 & 0xFF) << 8 | color >> 16 & 0xFF;
        }

        @Override
        protected void drawInternal(net.minecraft.client.gui.GuiGraphics graphics,
                                    float mouseX, float mouseY, float screenX, float screenY,
                                    float width, float height, float partialTick) {
            graphics.blit(location, (int) screenX, (int) screenY, 0f, 0f,
                    (int) width, (int) height, size, size);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private ItemSlot displaySlot(int index) {
        var display = new ItemSlot();
        display.addEventListener(UIEvents.TICK, event -> display.setItem(slots.get(index).getItem().copy()));
        display.addEventListener(UIEvents.MOUSE_UP, event -> {
            if (!(Minecraft.getInstance().screen instanceof com.admin82.factions.screen.MonumentScreen screen)) return;
            if (index < LOOT_SLOTS && event.button == 1 && !slots.get(index).getItem().isEmpty()) {
                selectedLootSettingsSlot = index;
                screen.refreshContent();
                return;
            }
            ClickType type = net.minecraft.client.gui.screens.Screen.hasShiftDown() ? ClickType.QUICK_MOVE : ClickType.PICKUP;
            screen.interactWithSlot(index, event.button, type);
        });
        return display;
    }

    @OnlyIn(Dist.CLIENT)
    private static TextField field(String value, IObserver<String> observer) {
        var field = new TextField();
        field.setValue(value);
        field.bindObserver(observer);
        field.layout(l -> l.width(180).height(18));
        return field;
    }

    @OnlyIn(Dist.CLIENT)
    private static UIElement labeled(String label, UIElement field) {
        var row = new UIElement();
        row.layout(l -> l.width(240).height(22).flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER));
        row.addChildren(new Label().setText(label).layout(l -> l.width(125)), field);
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    private static UIElement compactLabeled(String label, UIElement field) {
        var row = new UIElement();
        row.layout(l -> l.width(205).height(19).flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER));
        row.addChildren(new Label().setText(label).layout(l -> l.width(105)), field);
        return row;
    }

    private static int[] defaultLootCounts() {
        int[] counts = new int[LOOT_SLOTS];
        java.util.Arrays.fill(counts, 1);
        return counts;
    }

    @Nullable
    private MonumentView selectedView() {
        return selectedId == null ? null : monuments.stream().filter(view -> view.id().equals(selectedId)).findFirst().orElse(null);
    }

    private static int parse(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static String formatDuration(long seconds) {
        long minutes = seconds / 60;
        return minutes > 0 ? minutes + "m " + seconds % 60 + "s" : seconds + "s";
    }

    @Nullable
    private static ServerLevel levelFor(MinecraftServer server, String dimension) {
        var id = net.minecraft.resources.ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, id));
    }
}