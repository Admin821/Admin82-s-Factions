package com.admin82.factions.screen;

import com.admin82.factions.network.packet.TerritoryClaimActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Client-only screen shown to the winning faction after a {@code TERRITORY} war victory.
 * Displays the defeated faction's non-core chunks as a paginated toggle-button list.
 * The player picks which chunks they want and submits the selection.
 */
public class TerritoryClaimScreen extends Screen {

    // ── Data ──────────────────────────────────────────────────────────────────

    private final UUID         defeatedFactionId;
    private final String       defeatedFactionName;
    /** All claimable chunk keys: "chunkX,chunkZ,dimensionId" */
    private final List<String> allKeys;
    private final Set<String>  selected = new LinkedHashSet<>();

    // ── Pagination ────────────────────────────────────────────────────────────

    private static final int COLS       = 3;
    private static final int ROWS       = 5;
    private static final int PAGE_SIZE  = COLS * ROWS;  // 15 per page
    private int page = 0;

    public TerritoryClaimScreen(UUID defeatedFactionId, String defeatedFactionName,
                                List<String> chunkKeys) {
        super(Component.literal("Territory Spoils"));
        this.defeatedFactionId   = defeatedFactionId;
        this.defeatedFactionName = defeatedFactionName;
        this.allKeys             = chunkKeys;
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        buildPageWidgets();
    }

    private void buildPageWidgets() {
        clearWidgets();

        int cx    = this.width  / 2;
        int top   = 44;
        int btnW  = 178;
        int btnH  = 20;
        int gapX  = 4;
        int gapY  = 4;
        int totalW = COLS * btnW + (COLS - 1) * gapX;
        int startX = cx - totalW / 2;

        int totalPages  = Math.max(1, (allKeys.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int startIdx    = page * PAGE_SIZE;
        int endIdx      = Math.min(startIdx + PAGE_SIZE, allKeys.size());

        for (int i = startIdx; i < endIdx; i++) {
            String key = allKeys.get(i);
            int col    = (i - startIdx) % COLS;
            int row    = (i - startIdx) / COLS;
            int bx     = startX + col * (btnW + gapX);
            int by     = top    + row * (btnH  + gapY);

            boolean isSel = selected.contains(key);
            String label  = (isSel ? "§a✔ " : "§8○ ") + formatKey(key);

            final String fKey = key;
            addRenderableWidget(
                    Button.builder(Component.literal(label), btn -> {
                        if (selected.contains(fKey)) selected.remove(fKey);
                        else                         selected.add(fKey);
                        buildPageWidgets();
                    }).bounds(bx, by, btnW, btnH).build()
            );
        }

        // ── Navigation ────────────────────────────────────────────────────────
        int navY = top + ROWS * (btnH + gapY) + 6;
        if (page > 0) {
            addRenderableWidget(Button.builder(Component.literal("◀ Prev"),
                    btn -> { page--; buildPageWidgets(); })
                    .bounds(cx - 130, navY, 60, 20).build());
        }
        if (page < totalPages - 1) {
            addRenderableWidget(Button.builder(Component.literal("Next ►"),
                    btn -> { page++; buildPageWidgets(); })
                    .bounds(cx + 70, navY, 60, 20).build());
        }

        // ── Confirm / Skip ────────────────────────────────────────────────────
        int bottomY = navY + 28;
        int selCount = selected.size();
        String claimLabel = selCount > 0
                ? "§aClaim " + selCount + " Chunk" + (selCount == 1 ? "" : "s")
                : "§7Select chunks above";

        addRenderableWidget(Button.builder(Component.literal(claimLabel), btn -> {
            if (!selected.isEmpty()) {
                PacketDistributor.sendToServer(new TerritoryClaimActionPacket(
                        defeatedFactionId, new ArrayList<>(selected)));
            }
            this.onClose();
        }).bounds(cx - 110, bottomY, 110, 22).build());

        addRenderableWidget(Button.builder(Component.literal("§7Skip"),
                btn -> this.onClose())
                .bounds(cx + 4, bottomY, 60, 22).build());
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        renderBackground(g, mx, my, partialTick);

        int cx = this.width / 2;
        int totalPages = Math.max(1, (allKeys.size() + PAGE_SIZE - 1) / PAGE_SIZE);

        g.drawCenteredString(this.font,
                "§6§l⚑ Territory Spoils: §e" + defeatedFactionName,
                cx, 10, 0xFFFFFF);
        g.drawCenteredString(this.font,
                "§7Click chunks to select  —  cannot claim the core chunk",
                cx, 22, 0xAAAAAA);
        g.drawCenteredString(this.font,
                "§8Page " + (page + 1) + " / " + totalPages
                + "  §8( §e" + allKeys.size() + " §8claimable chunks )",
                cx, 34, 0x888888);

        super.render(g, mx, my, partialTick);
    }

    // ── No pause ──────────────────────────────────────────────────────────────

    @Override
    public boolean isPauseScreen() { return false; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Formats "5,-3,minecraft:overworld" → "X:5 Z:-3" (drops dimension for brevity). */
    private static String formatKey(String key) {
        String[] parts = key.split(",", 3);
        if (parts.length < 2) return key;
        return "X:" + parts[0] + " Z:" + parts[1];
    }
}
