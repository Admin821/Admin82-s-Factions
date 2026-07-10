package com.admin82.factions.screen;

import com.admin82.factions.network.packet.OpenKitSelectionPacket;
import com.admin82.factions.network.packet.SelectKitPacket;
import com.admin82.factions.barracks.KitData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen kit picker shown on respawn or when the player clicks Quick Take.
 *
 * Layout (up to 3 cards per row, 2 rows = 6 kits per page):
 *
 *   ┌─────────────────────────────────────────────────────────┐
 *   │   ⚔ Choose Your Kit                                     │
 *   │   Select a kit — it will be consumed once taken.         │
 *   ├──────────┬──────────┬──────────┐                        │
 *   │ Kit Name │ Kit Name │ Kit Name │                        │
 *   │ H C L B  │ H C L B  │ H C L B  │                        │
 *   │ items... │ items... │ items... │                        │
 *   │[Take Kit]│[Take Kit]│[Take Kit]│                        │
 *   └──────────┴──────────┴──────────┘                        │
 *   │         [< Prev]  [Close]  [Next >]                     │
 *   └─────────────────────────────────────────────────────────┘
 */
public class KitSelectionScreen extends Screen {

    private static final int CARD_W       = 175;
    private static final int CARD_H       = 190;
    private static final int CARD_GAP     = 8;
    private static final int MAX_COLS     = 3;
    private static final int MAX_ROWS     = 1;
    private static final int KITS_PER_PAGE = MAX_COLS * MAX_ROWS;

    private final List<OpenKitSelectionPacket.KitEntry> allKits;
    private int page = 0;

    // Tooltip state — populated while rendering cards, drawn once after everything else
    private ItemStack pendingTooltipItem = ItemStack.EMPTY;
    private java.util.List<net.minecraft.network.chat.Component> pendingTooltipLines = null;
    private int pendingTooltipX, pendingTooltipY;

    public KitSelectionScreen(List<OpenKitSelectionPacket.KitEntry> kits) {
        super(Component.literal("Choose Your Kit"));
        this.allKits = new ArrayList<>(kits);
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private List<OpenKitSelectionPacket.KitEntry> pageKits() {
        int from = page * KITS_PER_PAGE;
        int to   = Math.min(from + KITS_PER_PAGE, allKits.size());
        return from < allKits.size() ? allKits.subList(from, to) : List.of();
    }

    private int cols() { return Math.min(MAX_COLS, Math.max(1, pageKits().size())); }
    private int rows() { return (int) Math.ceil((double) pageKits().size() / cols()); }

    private int gridWidth()  { return cols() * CARD_W + (cols() - 1) * CARD_GAP; }
    private int gridHeight() { return rows() * CARD_H + (rows() - 1) * CARD_GAP; }

    private int gridStartX() { return (width  - gridWidth())  / 2; }
    private int gridStartY() { return (height - gridHeight()) / 2 - 16; }

    private int cardX(int col) { return gridStartX() + col * (CARD_W + CARD_GAP); }
    private int cardY(int row) { return gridStartY() + row * (CARD_H + CARD_GAP); }

    // ── Widget setup ──────────────────────────────────────────────────────────

    @Override
    protected void init() {
        repaginate();
    }

    private void repaginate() {
        clearWidgets();
        List<OpenKitSelectionPacket.KitEntry> visible = pageKits();
        int cols = cols();

        for (int i = 0; i < visible.size(); i++) {
            final OpenKitSelectionPacket.KitEntry kit = visible.get(i);
            int col = i % cols;
            int row = i / cols;
            int cx  = cardX(col);
            int cy  = cardY(row);

            addRenderableWidget(
                    Button.builder(Component.literal("▶ Take Kit"), btn -> selectKit(kit.name()))
                            .bounds(cx + 4, cy + CARD_H - 26, CARD_W - 8, 20)
                            .build());
        }

        int footerY = gridStartY() + gridHeight() + 10;
        if (page > 0) {
            addRenderableWidget(
                    Button.builder(Component.literal("◄ Prev"), btn -> { page--; repaginate(); })
                            .bounds(gridStartX(), footerY, 60, 20).build());
        }
        if ((page + 1) * KITS_PER_PAGE < allKits.size()) {
            addRenderableWidget(
                    Button.builder(Component.literal("Next ►"), btn -> { page++; repaginate(); })
                            .bounds(gridStartX() + gridWidth() - 60, footerY, 60, 20).build());
        }
        addRenderableWidget(
                Button.builder(Component.literal("§7Close (fight with what you have)"),
                        btn -> onClose())
                        .bounds(width / 2 - 110, footerY, 220, 20).build());
    }

    private void selectKit(String name) {
        PacketDistributor.sendToServer(new SelectKitPacket(name));
        onClose();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /** Prevent the Gaussian blur shader from being applied to the world background. */
    @Override
    protected void renderBlurredBackground(float partialTick) { /* no-op */ }

    /** Prevent the semi-transparent dark overlay from renderBackground overwriting our fill. */
    @Override
    public void renderTransparentBackground(net.minecraft.client.gui.GuiGraphics g) { /* no-op */ }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        // Reset tooltip state each frame
        pendingTooltipItem = ItemStack.EMPTY;
        pendingTooltipLines = null;

        // Fully-opaque dark background — no world bleed-through, no blur
        g.fill(0, 0, width, height, 0xFF0A0A18);

        // Title & subtitle
        g.drawCenteredString(font, "§d§l⚔ Choose Your Kit",
                width / 2, gridStartY() - 32, 0xFFFFFF);
        g.drawCenteredString(font, "§7Select a kit — it is consumed (removed) once taken.",
                width / 2, gridStartY() - 18, 0xAAAAAA);

        List<OpenKitSelectionPacket.KitEntry> visible = pageKits();
        int cols = cols();

        if (visible.isEmpty()) {
            g.drawCenteredString(font, "§cNo kits available in the Barracks.",
                    width / 2, height / 2 - 12, 0xFFFFFF);
            g.drawCenteredString(font, "§7Ask your faction to restock via the Barracks block.",
                    width / 2, height / 2 + 4, 0xAAAAAA);
        }

        for (int i = 0; i < visible.size(); i++) {
            OpenKitSelectionPacket.KitEntry kit = visible.get(i);
            int col = i % cols;
            int row = i / cols;
            int cx  = cardX(col);
            int cy  = cardY(row);

            renderCard(g, kit, cx, cy, mx, my);
        }

        super.render(g, mx, my, partial); // renders buttons

        // Draw any tooltip collected during card rendering (drawn last = always on top)
        if (!pendingTooltipItem.isEmpty()) {
            g.renderTooltip(font, pendingTooltipItem, pendingTooltipX, pendingTooltipY);
        } else if (pendingTooltipLines != null && !pendingTooltipLines.isEmpty()) {
            g.renderComponentTooltip(font, pendingTooltipLines, pendingTooltipX, pendingTooltipY);
        }
    }

    /** Registers an item tooltip to be drawn at the end of the frame. */
    private void hoverTooltip(ItemStack item, int mx, int my) {
        if (pendingTooltipItem.isEmpty() && pendingTooltipLines == null) {
            pendingTooltipItem = item;
            pendingTooltipX = mx;
            pendingTooltipY = my;
        }
    }

    /** Registers a text-list tooltip to be drawn at the end of the frame. */
    private void hoverLines(java.util.List<net.minecraft.network.chat.Component> lines, int mx, int my) {
        if (pendingTooltipItem.isEmpty() && pendingTooltipLines == null) {
            pendingTooltipLines = lines;
            pendingTooltipX = mx;
            pendingTooltipY = my;
        }
    }

    /** Renders an item at (ix, iy) and registers its tooltip on hover. */
    private void renderItemWithTooltip(GuiGraphics g, ItemStack item, int ix, int iy, int mx, int my) {
        g.renderItem(item, ix, iy);
        g.renderItemDecorations(font, item, ix, iy);
        if (mx >= ix && mx < ix + 16 && my >= iy && my < iy + 16) {
            hoverTooltip(item, mx, my);
        }
    }

    private void renderCard(GuiGraphics g, OpenKitSelectionPacket.KitEntry kit,
                            int cx, int cy, int mx, int my) {
        boolean hovered = mx >= cx && mx < cx + CARD_W && my >= cy && my < cy + CARD_H;
        int bgColor     = hovered ? 0xCC1a1a2e : 0xAA111111;
        int borderColor = hovered ? 0xFFBB88FF : 0xFF555566;

        // Card background
        g.fill(cx, cy, cx + CARD_W, cy + CARD_H, bgColor);
        // Border
        g.hLine(cx,         cx + CARD_W - 1, cy,             borderColor);
        g.hLine(cx,         cx + CARD_W - 1, cy + CARD_H - 1, borderColor);
        g.vLine(cx,         cy,              cy + CARD_H - 1, borderColor);
        g.vLine(cx + CARD_W - 1, cy,         cy + CARD_H - 1, borderColor);

        // Kit name (centered)
        g.drawCenteredString(font, "§e§l" + kit.name(),
                cx + CARD_W / 2, cy + 5, 0xFFFFFF);

        int itemY = cy + 18;

        // ── Armor + offhand row ──────────────────────────────────────────────
        g.drawString(font, "§8Armor:", cx + 4, itemY, 0x888888, false);
        itemY += 10;

        for (int a = 0; a < KitData.ARMOR_SLOTS; a++) {
            ItemStack armorItem = kit.items()[KitData.INV_SLOTS + a];
            int ix = cx + 4 + a * 19;
            if (!armorItem.isEmpty()) {
                renderItemWithTooltip(g, armorItem, ix, itemY, mx, my);
            } else {
                g.fill(ix, itemY, ix + 16, itemY + 16, 0x33FFFFFF);
            }
        }
        // Offhand
        {
            ItemStack offhandItem = kit.items()[KitData.OFFHAND_SLOT];
            int ix = cx + 4 + KitData.ARMOR_SLOTS * 19 + 4;
            if (!offhandItem.isEmpty()) {
                renderItemWithTooltip(g, offhandItem, ix, itemY, mx, my);
            } else {
                g.fill(ix, itemY, ix + 16, itemY + 16, 0x22FFFFFF);
            }
        }
        itemY += 20;

        // ── Inventory rows — all 4 rows by slot position ─────────────────────
        g.drawString(font, "§8Items:", cx + 4, itemY, 0x888888, false);
        itemY += 10;

        // Rows 0–2: main inventory slots 0–26
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                ItemStack item = kit.items()[row * 9 + col];
                int ix = cx + 4 + col * 19;
                if (!item.isEmpty()) {
                    renderItemWithTooltip(g, item, ix, itemY, mx, my);
                } else {
                    g.fill(ix, itemY, ix + 16, itemY + 16, 0x22FFFFFF);
                }
            }
            itemY += 20;
        }

        // Row 3: hotbar / last row (slots 27–35)
        g.drawString(font, "§8Hotbar:", cx + 4, itemY, 0x888888, false);
        itemY += 10;
        for (int col = 0; col < 9; col++) {
            ItemStack item = kit.items()[27 + col];
            int ix = cx + 4 + col * 19;
            if (!item.isEmpty()) {
                renderItemWithTooltip(g, item, ix, itemY, mx, my);
            } else {
                g.fill(ix, itemY, ix + 16, itemY + 16, 0x22FFFFFF);
            }
        }
        itemY += 20;
    }

    // ── Screen settings ───────────────────────────────────────────────────────

    @Override
    public boolean isPauseScreen() { return false; }

    /** ESC closes without selecting a kit. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
