package com.admin82.factions.menu;

import com.admin82.factions.admin.AdminData;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.network.packet.AdminPanelPacket.Action;
import com.admin82.factions.network.packet.AdminPanelPacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;
import org.appliedenergistics.yoga.YogaFlexDirection;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Operator admin panel (/faction admin): every server setting in one place, faction tools,
 * shortcuts to the other editors, and export / import of settings and admin-made content.
 */
public class AdminPanelMenu extends AbstractContainerMenu {

    private static final String ACTIVE_BG = "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)";
    private static final String DANGER_BG = "built-in(ui-mc:RECT_BORDER) color(#aa2222ff)";
    private static final int SIDEBAR_WIDTH = 120;

    private static final String PAGE_FACTIONS = "§Factions";
    private static final String PAGE_TOOLS = "§Tools";
    private static final String PAGE_MONUMENTS = "§Monuments";
    private static final int MONUMENTS_PER_PAGE = 8;
    private static final String PAGE_TRANSFER = "§Transfer";
    private static final String PAGE_SEAMS = "§Seams";

    // Seams page drafts
    private String seamOre = "";
    private String seamOreSearch = "";
    private String seamTier = "Rich";
    private boolean seamEndless;
    private String blacklistDraft = "";
    /** Chunk picked on the seam map ("x,z"), or null for the admin's own. */
    @Nullable private String seamSel;
    @Nullable private SeamMapView seamView;

    @Nullable private AdminPanelPacket.Sync data;
    private String page = "";
    private String search = "";
    /** Text typed into setting fields, kept across refreshes until saved. */
    private final Map<String, String> drafts = new HashMap<>();

    // Factions page
    @Nullable private String armed;
    private String movePlayer = "";
    private String moveFaction = "";
    // Monuments page
    private int monumentPage = 0;
    // Transfer page
    private int exportMask = (1 << AdminData.Section.values().length) - 1;
    private String exportName = "";
    @Nullable private String selectedFile;
    private int filePage;
    private int importMask = 0;

    @Nullable private UIElement sidebar;
    @Nullable private UIElement content;
    private int contentWidth = 480;
    private boolean pendingRender;

    public AdminPanelMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        super(ModMenuTypes.ADMIN_PANEL.get(), id);
        if (FMLEnvironment.dist == Dist.CLIENT) rebuildClientUi(inventory.player);
    }

    public AdminPanelMenu(int id, Inventory inventory, Player player) {
        super(ModMenuTypes.ADMIN_PANEL.get(), id);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return player.hasPermissions(2); }

    // Data

    /** Every seam around a spot, for the seam map (after it was dragged or zoomed). */
    public void addSeamArea(int x, int z, int radius, List<com.admin82.factions.seam.SeamCell> cells) {
        if (seamView != null) seamView.addArea(x, z, radius, cells);
    }

    public void updateData(AdminPanelPacket.Sync sync) {
        boolean first = data == null;
        data = sync;
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        if (first && page.isEmpty() && !sync.settings().isEmpty()) page = sync.settings().get(0).group();
        if (selectedFile != null && sync.exports().stream().noneMatch(f -> f.name().equals(selectedFile))) selectedFile = null;
        renderSidebar();
        if (isTyping()) pendingRender = true;
        else renderPage();
    }

    @OnlyIn(Dist.CLIENT)
    public void clientTick() {
        if (pendingRender && !isTyping()) { pendingRender = false; renderPage(); }
    }

    @OnlyIn(Dist.CLIENT)
    private boolean isTyping() {
        if (!(this instanceof IModularUIHolderMenu h) || h.getModularUI() == null) return false;
        for (var el = h.getModularUI().getFocusedElement(); el != null; el = el.getParent()) {
            if (el instanceof TextField) return true;
        }
        return false;
    }

    private static void send(AdminPanelPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    // Shell

    @OnlyIn(Dist.CLIENT)
    public void rebuildClientUi(Player player) {
        if (this instanceof IModularUIHolderMenu h) h.setModularUI(buildUi(player));
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI buildUi(Player player) {
        int[] size = UiKit.panelSize(660, 400, 480, 260);
        int width = size[0], height = size[1];
        contentWidth = width - SIDEBAR_WIDTH - 26;
        settingScroller = null; // sized for the old window

        var frame = new UIElement();
        frame.layout(l -> l.width(width).height(height).paddingAll(2));
        frame.addClass("preview_bg");
        var root = new UIElement();
        root.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).paddingAll(6).gapAll(5));
        root.addClass("panel_bg");

        var header = row(6, 14);
        header.addChildren(new Label().setText("§6§l⚙ Admin Panel").layout(l -> l.flex(1)),
                UiKit.rightLabel("§8Operators only · changes apply instantly"));

        var body = new UIElement();
        body.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.ROW).gapAll(6));
        sidebar = UiKit.background(new UIElement(), UiKit.CARD);
        sidebar.layout(l -> l.width(SIDEBAR_WIDTH).flexDirection(YogaFlexDirection.COLUMN).paddingAll(4).gapAll(2));
        content = new UIElement();
        content.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        body.addChildren(sidebar, content);
        root.addChildren(header, body);
        frame.addChildren(root);

        renderSidebar();
        renderPage();
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderSidebar() {
        if (sidebar == null) return;
        sidebar.clearAllChildren();
        sidebar.addChildren(section("Settings"));
        if (data == null) {
            sidebar.addChildren(new Label().setText("§7Loading…"));
        } else {
            new LinkedHashSet<>(data.settings().stream().map(AdminPanelPacket.Sync.SettingView::group).toList())
                    .forEach(group -> sidebar.addChildren(navButton(group, group)));
        }
        sidebar.addChildren(section("Server"), navButton(PAGE_FACTIONS, "Factions"),
                navButton(PAGE_MONUMENTS, "Monuments"), navButton(PAGE_SEAMS, "Seams"), navButton(PAGE_TOOLS, "Editors & tools"),
                navButton(PAGE_TRANSFER, "Import / Export"));
    }

    @OnlyIn(Dist.CLIENT)
    private Button navButton(String target, String text) {
        boolean active = target.equals(page) && search.isEmpty();
        var btn = new Button().setText((active ? "§f§l" : "§7") + text).setOnClick(e -> {
            page = target;
            search = "";
            armed = null;
            renderSidebar();
            renderPage();
        });
        btn.layout(l -> l.height(16));
        if (active) btn.lss("base-background", ACTIVE_BG);
        return btn;
    }

    @OnlyIn(Dist.CLIENT)
    private void renderPage() {
        if (content == null) return;
        content.clearAllChildren();
        if (data == null) {
            content.addChildren(new Label().setText("§7Loading…"));
            return;
        }
        switch (page) {
            case PAGE_FACTIONS -> renderFactions();
            case PAGE_MONUMENTS -> renderMonuments();
            case PAGE_TOOLS -> renderTools();
            case PAGE_SEAMS -> renderSeams();
            case PAGE_TRANSFER -> renderTransfer();
            default -> renderSettings();
        }
    }

    // Settings

    @OnlyIn(Dist.CLIENT)
    private void renderSettings() {
        var top = row(4, 16);
        var searchField = UiKit.textField("");
        searchField.setValue(search);
        searchField.bindObserver(v -> {
            String next = v.trim().toLowerCase(Locale.ROOT);
            if (next.equals(search)) return;
            search = next;
            renderSettingRows();
        });
        searchField.layout(l -> l.width(160).height(16));
        tip(searchField, "§7Search every setting by name or description.");
        top.addChildren(new Label().setText("§6§l" + page).layout(l -> l.flex(1)), SeamMap.fit("§7Search"), searchField);
        content.addChildren(top);
        // One scroller per page, kept across re-renders so saving a value doesn't jump back to the top.
        if (settingScroller == null || !page.equals(settingScrollerPage)) {
            settingScroller = new ScrollerView();
            settingScroller.layout(l -> l.flex(1).width(contentWidth));
            settingRows = new UIElement();
            settingRows.layout(l -> l.width(contentWidth - 12).flexDirection(YogaFlexDirection.COLUMN).gapAll(2));
            settingScroller.addScrollViewChild(settingRows);
            settingScrollerPage = page;
        }
        content.addChildren(settingScroller);
        renderSettingRows();
    }

    @Nullable private UIElement settingRows;
    @Nullable private ScrollerView settingScroller;
    private String settingScrollerPage = "";

    @OnlyIn(Dist.CLIENT)
    private void renderSettingRows() {
        if (settingRows == null || data == null) return;
        settingRows.clearAllChildren();
        List<AdminPanelPacket.Sync.SettingView> shown = data.settings().stream()
                .filter(s -> search.isEmpty() ? s.group().equals(page)
                        : (s.label() + " " + s.help() + " " + s.group()).toLowerCase(Locale.ROOT).contains(search))
                .toList();
        if (shown.isEmpty()) settingRows.addChildren(new Label().setText("§7No settings match \"" + search + "\"."));
        String lastGroup = "";
        for (var s : shown) {
            if (!search.isEmpty() && !s.group().equals(lastGroup)) {
                lastGroup = s.group();
                settingRows.addChildren(new Label().setText("§e" + s.group()));
            }
            settingRows.addChildren(settingRow(s));
        }
    }

    private static String display(AdminPanelPacket.Sync.SettingView s, double v) {
        return switch (s.kind()) {
            case 2 -> v >= 0.5 ? "On" : "Off";
            case 3 -> Currency.format((long) v).replaceAll("§.", "").replace(" ", "");
            case 1 -> trim(v);
            default -> Long.toString((long) v);
        };
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Parses typed text for a setting; NaN when invalid. */
    private static double parse(AdminPanelPacket.Sync.SettingView s, String text) {
        String t = text.trim().replace(" ", "");
        if (t.isEmpty()) return Double.NaN;
        try {
            double v = switch (s.kind()) {
                case 3 -> {
                    long copper = Currency.parse(t);
                    yield copper < 0 ? Double.NaN : copper;
                }
                case 1 -> Double.parseDouble(t);
                default -> Long.parseLong(t);
            };
            return v < s.min() || v > s.max() ? Double.NaN : v;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement settingRow(AdminPanelPacket.Sync.SettingView s) {
        var row = UiKit.background(row(6, 22), UiKit.ROW);
        row.layout(l -> l.paddingHorizontal(5));
        boolean isDefault = Double.isNaN(s.defaultValue()) || Math.abs(s.defaultValue() - s.value()) < 1e-9;
        var name = new UIElement();
        name.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN));
        name.addChildren(new Label().setText("§f" + s.label() + (isDefault ? "" : " §e•")),
                new Label().setText("§8" + shorten(s.help(), contentWidth - 230)));
        row.addChildren(name);
        String range = s.kind() == 2 ? "" : "§8Allowed: " + display(s, s.min()) + " – "
                + (s.max() >= Integer.MAX_VALUE ? "no limit" : display(s, s.max()));
        tip(name, "§f" + s.label(), "§7" + s.help(), range,
                Double.isNaN(s.defaultValue()) ? "§8No default (saved with the world)" : "§8Default: " + display(s, s.defaultValue()),
                isDefault ? "" : "§e• Changed from the default");

        if (s.kind() == 2) {
            var toggle = UiKit.toggle();
            toggle.setOn(s.value() >= 0.5);
            toggle.setOnToggleChanged(on -> send(new AdminPanelPacket(Action.SET_SETTING, s.key(), "", on ? 1 : 0, 0)));
            toggle.layout(l -> l.width(18).height(16));
            row.addChildren(new Label().setText(s.value() >= 0.5 ? "§aOn" : "§8Off").layout(l -> l.width(24)), toggle);
        } else {
            String current = display(s, s.value());
            String text = drafts.getOrDefault(s.key(), current);
            var status = new Label();
            var field = UiKit.textField("");
            field.setValue(text);
            Runnable validate = () -> {
                String typed = drafts.getOrDefault(s.key(), current);
                double v = parse(s, typed);
                status.setText(typed.equals(current) ? "" : Double.isNaN(v) ? "§c✖" : "§e✎");
            };
            field.bindObserver(v -> {
                if (v.equals(current)) drafts.remove(s.key()); else drafts.put(s.key(), v);
                validate.run();
            });
            field.layout(l -> l.width(80).height(16));
            tip(field, s.kind() == 3 ? "§7Type an amount like §f5g§7, §f2g50s§7 or §f300 §8(copper)" : "§7Type a number, then press ✔", range);
            var save = new Button().setText("§a✔").setOnClick(e -> {
                double v = parse(s, drafts.getOrDefault(s.key(), current));
                if (Double.isNaN(v)) return;
                drafts.remove(s.key());
                send(new AdminPanelPacket(Action.SET_SETTING, s.key(), "", v, 0));
            });
            save.layout(l -> l.width(16).height(16));
            tip(save, "§7Save");
            row.addChildren(status.layout(l -> l.width(10)), field, save);
            validate.run();
        }
        if (!isDefault) {
            var reset = new Button().setText("§7↺").setOnClick(e -> {
                drafts.remove(s.key());
                send(new AdminPanelPacket(Action.RESET_SETTING, s.key(), "", 0, 0));
            });
            reset.layout(l -> l.width(16).height(16));
            tip(reset, "§7Back to the default (" + display(s, s.defaultValue()) + ")");
            row.addChildren(reset);
        } else {
            row.addChildren(new UIElement().layout(l -> l.width(16)));
        }
        return row;
    }

    private static String shorten(String text, int maxPixels) {
        var font = Minecraft.getInstance().font;
        if (font.width(text) <= maxPixels) return text;
        return font.plainSubstrByWidth(text, Math.max(20, maxPixels - font.width("…"))) + "…";
    }

    // Factions

    @OnlyIn(Dist.CLIENT)
    private void renderFactions() {
        content.addChildren(new Label().setText("§6§lFactions"),
                new Label().setText("§8Every faction on the server. Deleting a faction can't be undone."));
        var list = UiKit.background(new UIElement(), UiKit.CARD);
        list.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(2).paddingAll(4));
        if (data.factions().isEmpty()) list.addChildren(new Label().setText("§7No factions yet."));
        for (var f : data.factions()) {
            var r = UiKit.background(row(6, 18), UiKit.ROW);
            r.layout(l -> l.paddingHorizontal(4));
            r.addChildren(new Label().setText("§f§l" + f.name() + (f.overlord().isEmpty() ? "" : " §5(vassal of " + f.overlord() + ")"))
                            .layout(l -> l.flex(1)),
                    UiKit.rightLabel("§7" + f.members() + " members · " + f.claims() + " chunks · §a"
                            + Currency.format(f.vault()).replaceAll("§.", "")));
            if (!f.overlord().isEmpty()) r.addChildren(confirmButton("free:" + f.name(), "§dFree", "§d§lSure?", 34,
                    () -> send(AdminPanelPacket.of(Action.FREE_VASSAL, f.name())), "§7Release this vassal."));
            r.addChildren(confirmButton("delete:" + f.name(), "§cDelete", "§c§lDelete!", 44,
                    () -> send(AdminPanelPacket.of(Action.DELETE_FACTION, f.name())), "§7Disband this faction (click twice)."));
            list.addChildren(r);
        }
        content.addChildren(list);

        // Move a player
        var move = UiKit.background(new UIElement(), UiKit.CARD);
        move.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(3).paddingAll(5));
        var r = row(4, 16);
        var playerField = UiKit.textField("");
        playerField.setValue(movePlayer);
        playerField.bindObserver(v -> movePlayer = v.trim());
        playerField.layout(l -> l.width(100).height(16));
        var factionField = UiKit.textField("");
        factionField.setValue(moveFaction);
        factionField.bindObserver(v -> moveFaction = v.trim());
        factionField.layout(l -> l.flex(1).height(16));
        tip(factionField, "§7Faction name, or leave empty to remove them from their faction.");
        var go = new Button().setText("§aMove").setOnClick(e -> {
            if (movePlayer.isBlank()) return;
            send(new AdminPanelPacket(Action.SET_PLAYER_FACTION, movePlayer, moveFaction, 0, 0));
        });
        go.layout(l -> l.width(44).height(16));
        r.addChildren(SeamMap.fit("§7Player"), playerField, SeamMap.fit("§7to faction"), factionField, go);
        var chips = row(3, 14);
        data.players().stream().limit(8).forEach(n -> {
            var chip = new Button().setText("§7" + n).setOnClick(e -> { movePlayer = n; renderPage(); });
            chip.layout(l -> l.height(14).paddingHorizontal(3));
            chips.addChildren(chip);
        });
        move.addChildren(new Label().setText("§e§lMove a player §8(empty faction = remove from faction)"), r, chips);
        content.addChildren(move);
    }

    // Monuments

    @OnlyIn(Dist.CLIENT)
    private void renderMonuments() {
        content.addChildren(new Label().setText("§6§lMonuments"),
                new Label().setText("§8Move a monument by placing a new controller. Its chunks, crate spots and generators come along."));

        var moving = data.monuments().stream().filter(m -> m.id().equals(data.movingMonument())).findFirst().orElse(null);
        if (moving != null) {
            var banner = UiKit.background(row(6, 20), UiKit.CARD);
            banner.layout(l -> l.paddingHorizontal(6));
            banner.addChildren(new Label().setText("§eMoving " + moving.name() + ": §7place a Monument Controller where it should go.")
                    .layout(l -> l.flex(1)));
            var cancel = new Button().setText("§cCancel").setOnClick(e -> send(AdminPanelPacket.of(Action.CANCEL_MONUMENT_MOVE)));
            cancel.layout(l -> l.width(50).height(16));
            banner.addChildren(cancel);
            content.addChildren(banner);
        }

        var list = UiKit.background(new UIElement(), UiKit.CARD);
        list.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(2).paddingAll(4));
        var all = data.monuments();
        int pages = Math.max(1, (all.size() + MONUMENTS_PER_PAGE - 1) / MONUMENTS_PER_PAGE);
        monumentPage = Math.min(monumentPage, pages - 1);
        if (all.isEmpty()) list.addChildren(new Label().setText("§7No monuments yet. Place a Monument Controller or import some."));
        for (var m : all.subList(monumentPage * MONUMENTS_PER_PAGE, Math.min(all.size(), (monumentPage + 1) * MONUMENTS_PER_PAGE))) {
            var pos = BlockPos.of(m.pos());
            String dim = m.dimension().startsWith("minecraft:") ? m.dimension().substring(10) : m.dimension();
            var r = UiKit.background(row(6, 20), UiKit.ROW);
            r.layout(l -> l.paddingHorizontal(4));
            var info = new UIElement();
            info.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN));
            info.addChildren(new Label().setText("§f§l" + m.name() + " §7Tier " + m.tier() + "  " + m.status()),
                    new Label().setText("§8" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " in " + dim));
            var go = new Button().setText("§7Go").setOnClick(e -> send(AdminPanelPacket.of(Action.TELEPORT_TO_MONUMENT, m.id())));
            go.layout(l -> l.width(28).height(16));
            tip(go, "§7Teleport to the controller.");
            boolean isMoving = m.id().equals(data.movingMonument());
            var move = new Button().setText(isMoving ? "§eMoving…" : "§aMove").setOnClick(e ->
                    send(AdminPanelPacket.of(isMoving ? Action.CANCEL_MONUMENT_MOVE : Action.MOVE_MONUMENT, m.id())));
            move.layout(l -> l.width(50).height(16));
            if (isMoving) move.lss("base-background", ACTIVE_BG);
            if (isMoving) tip(move, "§7Click to cancel.");
            else tip(move, "§7Closes this panel. The next Monument Controller you place becomes",
                    "§7this monument's new spot. You get a controller if you don't have one.");
            r.addChildren(info, go, move);
            list.addChildren(r);
        }
        content.addChildren(list);

        if (pages > 1) {
            var pager = row(4, 16);
            var prev = new Button().setText("§7◀").setOnClick(e -> { monumentPage = Math.max(0, monumentPage - 1); renderPage(); });
            var next = new Button().setText("§7▶").setOnClick(e -> { monumentPage = Math.min(pages - 1, monumentPage + 1); renderPage(); });
            prev.layout(l -> l.width(18).height(14));
            next.layout(l -> l.width(18).height(14));
            pager.addChildren(new UIElement().layout(l -> l.flex(1)), prev,
                    new Label().setText("§7" + (monumentPage + 1) + " / " + pages), next);
            content.addChildren(pager);
        }
        content.addChildren(new Label().setText("§8Crates and generators that aren't there yet: place them in the monument area and they link themselves."));
    }

    // Seams

    @OnlyIn(Dist.CLIENT)
    private void renderSeams() {
        String me = data.playerChunkX() + "," + data.playerChunkZ();
        if (seamView == null) {
            seamView = new SeamMapView(9, 20, data.seamCenterX(), data.seamCenterZ(),
                    (x, z, r) -> send(new AdminPanelPacket(Action.SEAM_MAP, "", x + "," + z, r, 0)),
                    com.admin82.factions.network.packet.AdminSeamAreaPacket.MAX_RADIUS, true, true,
                    "Loading…", c -> { seamSel = c.chunkX() + "," + c.chunkZ(); renderPage(); });
        }
        var view = seamView;
        view.addCells(data.seamCells()); // admins always see the live seams

        // Selected chunk (defaults to yours)
        if (seamSel == null) seamSel = me;
        var at = seamSel.split(",");
        com.admin82.factions.seam.SeamCell sel = view.get(Integer.parseInt(at[0]), Integer.parseInt(at[1]));
        view.playerChunk = me;
        view.selected = seamSel;

        var top = row(3, 14);
        top.addChildren(new Label().setText("§6§lSeams §8· drag to move · scroll to zoom").layout(l -> l.flex(1)),
                smallButton("§e⌖ Me", 36, () -> { seamSel = me; view.moveTo(data.playerChunkX(), data.playerChunkZ()); },
                        "§7Centre the map on you."));
        content.addChildren(top);

        var body = new UIElement();
        body.layout(l -> l.flexDirection(YogaFlexDirection.ROW).gapAll(6).alignItems(YogaAlign.FLEX_START));
        var left = new UIElement();
        left.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(3));
        left.addChildren(view.element(), SeamMap.fit("§8North up · white: you · orange: selected"));
        var right = new UIElement();
        right.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        right.addChildren(sel != null ? seamDetails(sel) : new Label().setText("§7Loading chunk " + seamSel + "…"));
        body.addChildren(left, right);
        content.addChildren(body, SeamMap.legend());

        // Blacklist
        var black = UiKit.background(new UIElement(), UiKit.CARD);
        black.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(2).paddingAll(5));
        var add = row(4, 16);
        var field = UiKit.textField("minecraft:coal_ore or modid:*");
        field.setValue(blacklistDraft);
        field.bindObserver(x -> blacklistDraft = x.trim());
        field.layout(l -> l.width(180).height(16));
        add.addChildren(new Label().setText("§fOre blacklist").layout(l -> l.width(66)), field, smallButton("§cAdd", 36, () -> {
            send(AdminPanelPacket.of(Action.SEAM_BLACKLIST_ADD, blacklistDraft));
            blacklistDraft = "";
        }, "§7Never rolled in new seams. Seams already mined or edited keep their ore."));
        black.addChildren(add);
        if (data.seamBlacklist().isEmpty()) black.addChildren(new Label().setText("§8Nothing blacklisted."));
        var list = row(4, 14);
        for (String id : data.seamBlacklist()) {
            var chip = smallButton("§7" + id + " §c✕", Minecraft.getInstance().font.width(id + " ✕") + 8,
                    () -> send(AdminPanelPacket.of(Action.SEAM_BLACKLIST_REMOVE, id)), "§7Click to remove.");
            list.addChildren(chip);
        }
        if (!data.seamBlacklist().isEmpty()) black.addChildren(list);
        content.addChildren(black, new Label().setText("§8Chances, speeds, amounts and power: Settings → Seam settings."));
    }

    /** What's in the selected chunk, with the buttons to change it. */
    @OnlyIn(Dist.CLIENT)
    private UIElement seamDetails(com.admin82.factions.seam.SeamCell c) {
        String at = c.chunkX() + "," + c.chunkZ();
        var card = UiKit.background(new UIElement(), UiKit.CARD);
        card.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(3).paddingAll(5));
        ItemStack ore = SeamMap.ore(c.ore());
        var head = row(6, 20);
        var icon = new ItemSlot().setItem(ore);
        icon.slotStyle(st -> st.showItemTooltips(true));
        var text = new UIElement();
        text.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN));
        text.addChildren(new Label().setText("§7Chunk " + c.chunkX() + ", " + c.chunkZ() + ": §f§l" + (c.minable() ? ore.getHoverName().getString() : "no ore")),
                new Label().setText(SeamMap.describe(c) + " §8· " + (!c.minable() ? "nothing to mine" : c.endless() ? "never runs out"
                        : String.format("%,d", c.remaining()) + "/" + String.format("%,d", c.amount()) + " left")));
        head.addChildren(icon, text);
        card.addChildren(head);

        var edit = row(3, 16);
        edit.addChildren(
                smallButton("§a▲ Richer", 52, () -> send(new AdminPanelPacket(Action.SEAM_TIER_UP, "", at, 0, 0)), "§7One richness up (refills it)."),
                smallButton("§e▼ Poorer", 52, () -> send(new AdminPanelPacket(Action.SEAM_TIER_DOWN, "", at, 0, 0)), "§7One richness down (refills it)."),
                confirmButton("seamDelete" + at, "§cDelete", "§fSure?", 44, () -> send(new AdminPanelPacket(Action.SEAM_DELETE, "", at, 0, 0)),
                        "§7Removes the seam: nothing to mine here (click twice)."),
                smallButton("§7Reset", 40, () -> send(new AdminPanelPacket(Action.SEAM_RESET, "", at, 0, 0)),
                        "§7Back to what the world seed gives, with its ore refilled."));
        card.addChildren(edit);

        // Spawn / replace
        card.addChildren(new Label().setText("§fSpawn a seam here"));
        if (seamOre.isEmpty()) seamOre = c.minable() ? c.ore() : data.seamOres().isEmpty() ? "" : data.seamOres().get(0);
        var oreRow = row(3, 16);
        var oreField = UiKit.textField("modid:some_ore");
        oreField.setValue(seamOre);
        oreField.bindObserver(x -> seamOre = x.trim());
        oreField.layout(l -> l.flex(1).height(16));
        var allOres = com.admin82.factions.seam.SeamOres.all();
        tip(oreField, "§7Item id of the ore. Any item works.", "§8This dimension can roll " + data.seamOres().size() + " of the "
                + allOres.size() + " ores in the game.");
        oreRow.addChildren(oreField);
        // Blacklist (or allow again) whichever ore is picked
        if (data.seamBlacklist().contains(seamOre)) {
            oreRow.addChildren(smallButton("§a✔ Allow", 52, () -> send(AdminPanelPacket.of(Action.SEAM_BLACKLIST_REMOVE, seamOre)),
                    "§7Take " + seamOre + " off the ore blacklist."));
        } else {
            oreRow.addChildren(smallButton("§c⊘ Blacklist", 64, () -> { if (!seamOre.isEmpty()) send(AdminPanelPacket.of(Action.SEAM_BLACKLIST_ADD, seamOre)); },
                    "§7Add the picked ore to the ore blacklist: it won't be rolled in new seams."));
        }
        // Every ore in the game; ones this dimension never rolls by itself are marked
        var orePicker = SearchPicker.create(allOres, id -> (oreStack(id).isEmpty() ? id : oreStack(id).getHoverName().getString())
                        + (data.seamBlacklist().stream().anyMatch(b -> b.equals(id) || b.equals(id.substring(0, Math.max(0, id.indexOf(':'))) + ":*"))
                                ? " §c(blacklisted)" : data.seamOres().contains(id) ? "" : " §8(not rolled here)"),
                seamOre, seamOreSearch, q -> seamOreSearch = q, x -> { seamOre = x; renderPage(); }, 62);
        tip(orePicker, "§7Every ore in the game. Type to search by name or id.", "§8Marked ones never appear here by themselves, but you can still spawn them.");
        var tierRow = row(3, 16);
        var tier = new Selector<String>();
        tier.setCandidates(List.of("Overflowing", "Rich", "Middling", "Poor"));
        tier.setValue(seamTier);
        tier.setOnValueChanged(x -> seamTier = x);
        tier.layout(l -> l.width(80).height(16));
        var endless = UiKit.toggle();
        endless.setOn(seamEndless);
        endless.setOnToggleChanged(on -> seamEndless = on);
        endless.layout(l -> l.width(16).height(16));
        tip(endless, "§6Endless: §7never runs out; the richness only sets its speed:", "§7Overflowing = §dExceptional§7, Rich = §bAccelerated§7, Middling, Poor.");
        tierRow.addChildren(tier, endless, new Label().setText("§6Endless").layout(l -> l.width(40)),
                smallButton("§aSpawn", 44, () -> send(new AdminPanelPacket(Action.SEAM_SET, seamOre, at, seamEndless ? 1 : 0,
                        Math.max(0, List.of("Overflowing", "Rich", "Middling", "Poor").indexOf(seamTier)))), "§7Replaces this chunk's seam, full."));
        card.addChildren(oreRow, orePicker, tierRow);
        return card;
    }


    private static ItemStack oreStack(String id) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (id.isEmpty() || rl == null) return ItemStack.EMPTY;
        return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl));
    }

    @OnlyIn(Dist.CLIENT)
    private static Button smallButton(String text, int width, Runnable action, String help) {
        var b = new Button().setText(text).setOnClick(e -> action.run());
        b.layout(l -> l.width(width).height(16));
        if (!help.isEmpty()) tip(b, help);
        return b;
    }

    // Tools

    @OnlyIn(Dist.CLIENT)
    private void renderTools() {
        content.addChildren(new Label().setText("§6§lEditors & tools"),
                new Label().setText("§8Opens the full editor. Run /faction admin to come back here."));
        var grid = new UIElement();
        grid.layout(l -> l.flexDirection(YogaFlexDirection.COLUMN).gapAll(4));
        grid.addChildren(
                toolRow("§6Monuments", "Monuments, loot pools, chunks, guard roster, gear and drops.", "monuments"),
                toolRow("§6AI combat profiles", "How each guard class aims, reacts, notices and fights.", "ai"),
                toolRow("§6Supply drop loot", "What supply drops contain.", "supplyloot"),
                toolRow("§6Supply drop spawns", "Where and when supply drops land.", "supplyspawn"),
                toolRow("§6Currency exchange rates", "What the exchange pays for each item.", "exchange"));

        var bypass = UiKit.background(row(6, 26), UiKit.CARD);
        bypass.layout(l -> l.paddingHorizontal(6));
        var info = new UIElement();
        info.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN));
        info.addChildren(new Label().setText("§6Protection bypass " + (data.bypass() ? "§a(ON)" : "§8(off)")),
                new Label().setText("§8Build and break anywhere, including claims and monuments."));
        var toggle = new Button().setText(data.bypass() ? "§cTurn off" : "§aTurn on")
                .setOnClick(e -> send(AdminPanelPacket.of(Action.TOGGLE_BYPASS)));
        toggle.layout(l -> l.width(64).height(18));
        if (data.bypass()) toggle.lss("base-background", DANGER_BG);
        bypass.addChildren(info, toggle);

        content.addChildren(grid, bypass,
                new Label().setText("§8The Treasury (tax statistics) is in the Market under Admin."));
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement toolRow(String title, String help, String tool) {
        var r = UiKit.background(row(6, 26), UiKit.CARD);
        r.layout(l -> l.paddingHorizontal(6));
        var info = new UIElement();
        info.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN));
        info.addChildren(new Label().setText(title), new Label().setText("§8" + help));
        var open = new Button().setText("§eOpen ▶").setOnClick(e -> send(AdminPanelPacket.of(Action.OPEN_TOOL, tool)));
        open.layout(l -> l.width(64).height(18));
        r.addChildren(info, open);
        return r;
    }

    // Import / export

    @OnlyIn(Dist.CLIENT)
    private void renderTransfer() {
        content.addChildren(new Label().setText("§6§lImport / Export"),
                new Label().setText("§8Files live in config/adminsfactions/exports/ on the server. Copy them between servers."));
        var columns = new UIElement();
        columns.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.ROW).gapAll(6));

        // Export
        var export = UiKit.background(new UIElement(), UiKit.CARD);
        export.layout(l -> l.width(Math.max(170, contentWidth / 2 - 10)).flexDirection(YogaFlexDirection.COLUMN).gapAll(3).paddingAll(5));
        export.addChildren(new Label().setText("§e§lExport"), new Label().setText("§8What to include:"));
        for (AdminData.Section s : AdminData.Section.values()) {
            export.addChildren(sectionToggle(s, (exportMask & s.bit()) != 0, on -> {
                exportMask = on ? exportMask | s.bit() : exportMask & ~s.bit();
                renderPage();
            }));
        }
        var nameField = UiKit.textField("");
        nameField.setValue(exportName);
        nameField.bindObserver(v -> exportName = v.trim());
        nameField.layout(l -> l.height(16));
        tip(nameField, "§7File name. Leave empty to use the date and time.");
        var go = new Button().setText(exportMask == 0 ? "§8Pick something to export" : "§a⬇ Export").setOnClick(e -> {
            if (exportMask == 0) return;
            send(new AdminPanelPacket(Action.EXPORT, exportName, "", 0, exportMask));
            exportName = "";
        });
        go.layout(l -> l.height(18));
        export.addChildren(new Label().setText("§7File name §8(optional)"), nameField, go);

        // Files + import
        var files = UiKit.background(new UIElement(), UiKit.CARD);
        files.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(2).paddingAll(5));
        files.addChildren(new Label().setText("§e§lSaved exports §8(" + data.exports().size() + ")"));
        if (data.exports().isEmpty()) files.addChildren(new Label().setText("§7No exports yet."));
        var fmt = DateTimeFormatter.ofPattern("d MMM HH:mm");
        int filePages = Math.max(1, (data.exports().size() + 7) / 8);
        filePage = Math.min(filePage, filePages - 1);
        for (var f : data.exports().stream().skip(filePage * 8L).limit(8).toList()) {
            boolean sel = f.name().equals(selectedFile);
            var r = UiKit.background(row(4, 16), sel ? 0x60CC7700 : UiKit.ROW);
            r.layout(l -> l.paddingHorizontal(4));
            r.addChildren(new Label().setText((sel ? "§e" : "§f") + shorten(f.name(), 150)).layout(l -> l.flex(1)),
                    UiKit.rightLabel("§8" + fmt.format(Instant.ofEpochMilli(f.modified()).atZone(ZoneId.systemDefault()))));
            List<String> tips = new ArrayList<>(List.of("§f" + f.name(), "§7" + (f.size() / 1024 + 1) + " KB"
                    + (f.exportedBy().isEmpty() ? "" : " · by " + f.exportedBy()), "§7Contains:"));
            for (AdminData.Section s : AdminData.Section.values()) if ((f.sections() & s.bit()) != 0) tips.add("§8 • " + s.label);
            tip(r, tips.toArray(String[]::new));
            UiKit.onClick(r, e -> {
                selectedFile = sel ? null : f.name();
                importMask = sel ? 0 : f.sections();
                armed = null;
                renderPage();
            });
            files.addChildren(r);
        }
        if (filePages > 1) {
            var pager = row(4, 14);
            var newer = new Button().setText("§7◀").setOnClick(e -> { filePage = Math.max(0, filePage - 1); renderPage(); });
            var older = new Button().setText("§7▶").setOnClick(e -> { filePage = Math.min(filePages - 1, filePage + 1); renderPage(); });
            newer.layout(l -> l.width(18).height(14));
            older.layout(l -> l.width(18).height(14));
            tip(newer, "§7Newer files");
            tip(older, "§7Older files");
            pager.addChildren(new UIElement().layout(l -> l.flex(1)), newer,
                    new Label().setText("§7" + (filePage + 1) + " / " + filePages), older);
            files.addChildren(pager);
        }

        var chosen = selectedFile == null ? null
                : data.exports().stream().filter(f -> f.name().equals(selectedFile)).findFirst().orElse(null);
        if (chosen != null) {
            files.addChildren(new Label().setText("§e§lImport §f" + shorten(chosen.name(), 140)));
            for (AdminData.Section s : AdminData.Section.values()) {
                if ((chosen.sections() & s.bit()) == 0) continue;
                files.addChildren(sectionToggle(s, (importMask & s.bit()) != 0, on -> {
                    importMask = on ? importMask | s.bit() : importMask & ~s.bit();
                    armed = null;
                    renderPage();
                }));
            }
            var buttons = row(4, 18);
            String file = chosen.name();
            var imp = confirmButton("import:" + file, "§a⬆ Import", "§a§lReplace current data?", 0,
                    () -> send(new AdminPanelPacket(Action.IMPORT, file, "", 0, importMask)),
                    "§7Replaces the chosen parts with the file's (click twice).", "§8Player listings and faction data are never touched.");
            imp.layout(l -> l.flex(1).height(18));
            var del = confirmButton("deletefile:" + file, "§cDelete", "§c§lSure?", 48,
                    () -> send(AdminPanelPacket.of(Action.DELETE_EXPORT, file)), "§7Delete this file (click twice).");
            buttons.addChildren(imp, del);
            files.addChildren(buttons);
        } else if (!data.exports().isEmpty()) {
            files.addChildren(new Label().setText("§8Click a file to import it."));
        }

        columns.addChildren(export, files);
        content.addChildren(columns);
    }

    @OnlyIn(Dist.CLIENT)
    private UIElement sectionToggle(AdminData.Section s, boolean on, Consumer<Boolean> change) {
        var r = row(4, 14);
        var toggle = UiKit.toggle();
        toggle.setOn(on);
        toggle.setOnToggleChanged(change::accept);
        toggle.layout(l -> l.width(14).height(12));
        r.addChildren(toggle, new Label().setText((on ? "§f" : "§7") + s.label).layout(l -> l.flex(1)));
        tip(r, "§f" + s.label, "§7" + s.help);
        return r;
    }

    // Helpers

    /** A button that needs two clicks: the first arms it, the second runs the action. */
    @OnlyIn(Dist.CLIENT)
    private Button confirmButton(String id, String text, String armedText, int width, Runnable action, String... help) {
        boolean isArmed = id.equals(armed);
        var btn = new Button().setText(isArmed ? armedText : text).setOnClick(e -> {
            if (id.equals(armed)) { armed = null; action.run(); }
            else armed = id;
            renderPage();
        });
        if (width > 0) btn.layout(l -> l.width(isArmed ? Math.max(width, 60) : width).height(16));
        if (isArmed) btn.lss("base-background", DANGER_BG);
        if (help.length > 0) tip(btn, help);
        return btn;
    }

    private static UIElement row(int gap, int height) {
        var r = new UIElement();
        r.layout(l -> l.flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(gap).height(height));
        return r;
    }

    private static UIElement section(String text) {
        return new Label().setText("§6§l" + text).layout(l -> l.height(12).paddingTop(3).paddingLeft(2));
    }

    private static void tip(UIElement element, String... lines) {
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> {
            var tips = HoverTooltips.empty();
            for (String line : lines) if (line != null && !line.isEmpty()) tips = tips.append(Component.literal(line));
            ev.hoverTooltips = tips;
        });
    }
}
