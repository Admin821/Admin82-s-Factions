package com.admin82.factions.screen;

import com.admin82.factions.network.packet.SyncResourceWarAccessPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Client-side HUD overlay shown to the Resource-War winner during their 10-minute
 * looting window.
 *
 * Renders top-centre (below any war HUD that may be present):
 *   [Resource War Access]
 *   ████████░░░░░░░░░  8:32 remaining
 *   Blocks: 38 / 50 remaining
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = com.admin82.factions.AdminsFactions.MODID, value = Dist.CLIENT)
public class ResourceWarHudOverlay {

    private static long expiresAt    = 0L;
    private static int  blockLimit   = 0;
    private static int  blocksBroken = 0;

    // ── State ─────────────────────────────────────────────────────────────────

    public static void update(SyncResourceWarAccessPacket pkt) {
        expiresAt    = pkt.expiresAt();
        blockLimit   = pkt.blockLimit();
        blocksBroken = pkt.blocksBroken();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (expiresAt == 0L) return;
        long now = System.currentTimeMillis();
        if (now >= expiresAt) { expiresAt = 0L; return; }

        if (Minecraft.getInstance().screen != null) return;

        GuiGraphics gfx   = event.getGuiGraphics();
        Minecraft   mc    = Minecraft.getInstance();
        int screenW       = mc.getWindow().getGuiScaledWidth();

        // Position: top-centre, below war HUD (offset 50 px down)
        int barW = 160;
        int barH = 8;
        int x    = (screenW - barW) / 2;
        int y    = 50;

        // ── Header ────────────────────────────────────────────────────────────
        String header = "§6[Resource War Access]";
        int hw = mc.font.width(header);
        gfx.drawString(mc.font, header, (screenW - hw) / 2, y, 0xFFFFAA00, true);
        y += 10;

        // ── Timer bar ─────────────────────────────────────────────────────────
        long msLeft   = expiresAt - now;
        float timePct = Math.max(0f, Math.min(1f, msLeft / (10f * 60_000f)));
        int totalSec  = (int) (msLeft / 1000L);
        int minutes   = totalSec / 60;
        int seconds   = totalSec % 60;
        String timeStr = String.format("%d:%02d remaining", minutes, seconds);

        int filled    = (int) (barW * timePct);
        int barColor  = timePct > 0.5f ? 0x3399FF : timePct > 0.25f ? 0xFFAA00 : 0xFF3333;

        gfx.fill(x - 1, y - 1, x + barW + 1, y + barH + 1, 0xCC000000);
        if (filled > 0) gfx.fill(x, y, x + filled, y + barH, 0xFF000000 | barColor);
        gfx.fill(x + filled, y, x + barW, y + barH, 0xFF333333);

        int tw = mc.font.width(timeStr);
        gfx.drawString(mc.font, timeStr, x + (barW - tw) / 2, y + 1,
                0xFFFFFFFF, true);
        y += barH + 4;

        // ── Block counter ─────────────────────────────────────────────────────
        int blocksLeft = Math.max(0, blockLimit - blocksBroken);
        String blockStr = "Blocks remaining: §c" + blocksLeft + "§r / §7" + blockLimit;
        int bw = mc.font.width(blockStr);
        gfx.drawString(mc.font, blockStr, (screenW - bw) / 2, y,
                blocksLeft > 5 ? 0xFFFFFF55 : 0xFFFF4444, true);
    }
}
