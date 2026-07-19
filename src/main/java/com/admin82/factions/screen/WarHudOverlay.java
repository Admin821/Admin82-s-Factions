package com.admin82.factions.screen;

import com.admin82.factions.network.packet.SyncWarStatePacket;
import com.admin82.factions.war.WarPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import javax.annotation.Nullable;

/**
 * Client-side HUD overlay rendered while a player is committed to an active war.
 *
 * Top-centre:
 *   Phase / faction name banner
 *   Capture progress bar (ACTIVE only)
 *   Lives row (ACTIVE only)
 *
 * Top-right corner:
 *   Mini compass rose pointing toward the defending faction table
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = com.admin82.factions.AdminsFactions.MODID, value = Dist.CLIENT)
public class WarHudOverlay {

    @Nullable private static SyncWarStatePacket currentState  = null;
    private static long                         endedReceivedAt = -1L;

    // ── State management ──────────────────────────────────────────────────────

    public static void updateState(SyncWarStatePacket pkt) {
        currentState = pkt;
        endedReceivedAt = pkt.phase() == WarPhase.ENDED.ordinal() ? System.currentTimeMillis() : -1L;
    }

    public static void clearState() {
        currentState    = null;
        endedReceivedAt = -1L;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        SyncWarStatePacket state = currentState;
        if (state == null) return;

        // Auto-clear 6 s after war ends
        if (state.phase() == WarPhase.ENDED.ordinal()) {
            if (endedReceivedAt > 0 && System.currentTimeMillis() - endedReceivedAt > 6000L) {
                clearState();
                return;
            }
        }

        if (Minecraft.getInstance().screen != null) return;

        GuiGraphics gfx   = event.getGuiGraphics();
        Minecraft   mc    = Minecraft.getInstance();
        int screenW       = mc.getWindow().getGuiScaledWidth();
        int screenH       = mc.getWindow().getGuiScaledHeight();
        int barW          = 200;
        int barH          = 10;
        int x             = (screenW - barW) / 2;
        int y             = 8;

        WarPhase phase = WarPhase.values()[Math.min(state.phase(), WarPhase.values().length - 1)];

        // ── Phase / faction banner ─────────────────────────────────────────────
        String title;
        if (phase == WarPhase.GRACE) {
            title = "⚔ " + state.attackerFactionName() + " vs " + state.defenderFactionName()
                    + "  [Grace: " + state.graceSecondsLeft() + "s]";
        } else if (phase == WarPhase.ACTIVE) {
            if (state.outpostPhase()) {
                title = "⚔ " + state.attackerFactionName() + " vs " + state.defenderFactionName()
                        + "  §c⛑ Destroy the Outpost first!";
            } else {
                title = "⚔ " + state.attackerFactionName() + " vs " + state.defenderFactionName();
            }
        } else {
            title = state.isAttacker()
                    ? (state.captureProgress() >= state.captureTimeSeconds() ? "⚔ VICTORY — Base Captured!" : "⚔ DEFEAT — Eliminated.")
                    : (state.captureProgress() >= state.captureTimeSeconds() ? "⚔ DEFEAT — Base captured." : "⚔ VICTORY — Attackers eliminated!");
        }
        int titleColor = phase == WarPhase.GRACE ? 0xFFFFAA00 : phase == WarPhase.ACTIVE ? 0xFFFF4444 : 0xFFFFFFFF;
        int tw = mc.font.width(title);
        gfx.drawString(mc.font, title, (screenW - tw) / 2, y, titleColor, true);
        y += 12;

        // ── Capture progress bar ──────────────────────────────────────────────
        if (phase == WarPhase.ACTIVE || phase == WarPhase.ENDED) {
            float pct    = state.captureTimeSeconds() > 0
                    ? Math.min(1f, state.captureProgress() / state.captureTimeSeconds()) : 0f;
            int filled = (int) (barW * pct);

            gfx.fill(x - 1, y - 1, x + barW + 1, y + barH + 1, 0xCC000000);
            int barColor = pct < 0.5f
                    ? lerp(0xFF3333, 0xFFAA00, pct * 2f)
                    : lerp(0xFFAA00, 0x33FF33, (pct - 0.5f) * 2f);
            if (filled > 0) gfx.fill(x, y, x + filled, y + barH, 0xFF000000 | barColor);
            gfx.fill(x + filled, y, x + barW, y + barH, 0xFF333333);

            // Label inside/above the bar
            String atkLabel = state.outpostPhase() ? "⛑ Outpost" : state.attackerFactionName() + " → " + state.defenderFactionName() + " " + (int)(pct * 100) + "%";
            int pw = mc.font.width(atkLabel);
            gfx.drawString(mc.font, atkLabel, x + (barW - pw) / 2, y + 1,
                    state.outpostPhase() ? 0xFFFF8800 : 0xFFFFFFFF, true);
            y += barH + 4;

            // ── Defender counter-attack bar (only in main phase, not outpost) ──
            if (!state.outpostPhase() && state.defenderCaptureProgress() > 0) {
                float defPct = state.captureTimeSeconds() > 0
                        ? Math.min(1f, state.defenderCaptureProgress() / state.captureTimeSeconds()) : 0f;
                int defFilled = (int)(barW * defPct);
                gfx.fill(x - 1, y - 1, x + barW + 1, y + barH + 1, 0xCC000000);
                int defColor = defPct < 0.5f ? lerp(0xFF3333, 0xFFAA00, defPct * 2f) : lerp(0xFFAA00, 0x33FF33, (defPct - 0.5f) * 2f);
                if (defFilled > 0) gfx.fill(x, y, x + defFilled, y + barH, 0xFF000000 | defColor);
                gfx.fill(x + defFilled, y, x + barW, y + barH, 0xFF333333);
                String defLabel = state.defenderFactionName() + " → " + state.attackerFactionName() + " " + (int)(defPct * 100) + "%";
                int dw = mc.font.width(defLabel);
                gfx.drawString(mc.font, defLabel, x + (barW - dw) / 2, y + 1, 0xFFCCCCFF, true);
                y += barH + 4;
            }
        }

        // ── Lives row ─────────────────────────────────────────────────────────
        if (phase == WarPhase.ACTIVE) {
            String livesStr = (state.isAttacker() ? "Attacking" : "Defending")
                    + "  My lives: " + state.myLives()
                    + "  Atk: " + state.totalAttackerLives()
                    + "  Def: " + state.totalDefenderLives();
            int lw = mc.font.width(livesStr);
            gfx.drawString(mc.font, livesStr, (screenW - lw) / 2, y, 0xFFFFFFFF, true);
        }

        // ── Directional compass (top-right) ───────────────────────────────────
        drawCompass(gfx, mc, screenW, state);
    }

    // ── Compass drawing ───────────────────────────────────────────────────────

    private static void drawCompass(GuiGraphics gfx, Minecraft mc, int screenW, SyncWarStatePacket state) {
        var player = mc.player;
        if (player == null) return;

        int cx = screenW - 36; // compass centre X (top-right area)
        int cy = 36;           // compass centre Y
        int r  = 20;           // outer radius

        // Background circle
        drawCircleOutline(gfx, cx, cy, r, 0xAA000000);
        drawCircleOutline(gfx, cx, cy, r - 1, 0x88000000);

        // Check dimension
        String playerDim = player.level().dimension().location().toString();
        // Attackers go to defender's capture target; defenders counter-attack toward attacker's table.
        String targetDim;
        int    targetX;
        int    targetZ;
        if (state.isAttacker()) {
            targetDim = state.captureTargetDim().isEmpty() ? state.defenderDimension() : state.captureTargetDim();
            targetX   = state.captureTargetX() != 0 || state.captureTargetZ() != 0 ? state.captureTargetX() : state.defenderTableX();
            targetZ   = state.captureTargetX() != 0 || state.captureTargetZ() != 0 ? state.captureTargetZ() : state.defenderTableZ();
        } else {
            // Defenders: compass points to the attacker's base they should counter-capture
            targetDim = state.defenderDimension(); // (currently we reuse defenderDimension as placeholder)
            targetX   = state.defenderTableX();    // fallback to defender's table
            targetZ   = state.defenderTableZ();
        }

        if (!playerDim.equals(targetDim)) {
            // Different dimension — show a '?' in the compass
            String q = "?";
            gfx.drawString(mc.font, q, cx - mc.font.width(q) / 2, cy - 4, 0xFFFF8800, true);
            String dimText = "Wrong";
            gfx.drawString(mc.font, dimText, cx - mc.font.width(dimText) / 2, cy + 14, 0xFFFF8800, false);
            return;
        }

        double dx = (targetX + 0.5) - player.getX();
        double dz = (targetZ + 0.5) - player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        // Compass label below the rose: "Outpost" or "Base"
        String targetLabel = state.outpostPhase() ? "⛑ Outpost" : "Base";
        int tlw = mc.font.width(targetLabel);
        gfx.drawString(mc.font, targetLabel, cx - tlw / 2, cy + 14,
                state.outpostPhase() ? 0xFFFF8800 : 0xFFFFFF44, false);

        // Angle of target relative to North (0=North, 90=East, 180=South, 270=West)
        double targetAngleRad = Math.atan2(dx, -dz);

        // Player heading in the same convention (Minecraft yaw: 0=South, 90=West, -90=East, 180=North)
        // Convert: playerHeading (0=North) = yaw + 180
        double playerHeadingRad = Math.toRadians(player.getYRot() + 180.0);

        // Relative angle on screen (0 = ahead, clockwise)
        double relAngle = targetAngleRad - playerHeadingRad;

        // Draw cardinal ticks
        for (int tick = 0; tick < 4; tick++) {
            double tickAngle = Math.toRadians(tick * 90) - playerHeadingRad;
            int tx = cx + (int) (Math.sin(tickAngle) * (r - 3));
            int ty = cy - (int) (Math.cos(tickAngle) * (r - 3));
            gfx.fill(tx - 1, ty - 1, tx + 1, ty + 1, 0xFF888888);
        }

        // Arrow pointing at target — draw as a line from centre + arrowhead dot
        if (distance > 1) {
            double sinA = Math.sin(relAngle);
            double cosA = Math.cos(relAngle);

            // Arrow shaft
            for (int i = 4; i <= r - 3; i++) {
                int px = cx + (int) (sinA * i);
                int py = cy - (int) (cosA * i);
                gfx.fill(px, py, px + 1, py + 1, 0xFFFF4444);
            }
            // Arrowhead
            int hx = cx + (int) (sinA * (r - 3));
            int hy = cy - (int) (cosA * (r - 3));
            gfx.fill(hx - 1, hy - 1, hx + 2, hy + 2, 0xFFFF8888);
        }

        // "Base" label inside compass
        String lbl = "Enemy";
        gfx.drawString(mc.font, lbl, cx - mc.font.width(lbl) / 2, cy - 3, 0xFFCCCCCC, false);
    }

    private static void drawCircleOutline(GuiGraphics gfx, int cx, int cy, int r, int color) {
        for (int i = 0; i < 36; i++) {
            double a = Math.toRadians(i * 10);
            int px = cx + (int) (Math.sin(a) * r);
            int py = cy - (int) (Math.cos(a) * r);
            gfx.fill(px, py, px + 1, py + 1, color);
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static int lerp(int colorA, int colorB, float t) {
        int ar = (colorA >> 16) & 0xFF, ag = (colorA >> 8) & 0xFF, ab = colorA & 0xFF;
        int br = (colorB >> 16) & 0xFF, bg = (colorB >> 8) & 0xFF, bb = colorB & 0xFF;
        int r = ar + (int) ((br - ar) * t);
        int g = ag + (int) ((bg - ag) * t);
        int b = ab + (int) ((bb - ab) * t);
        return (r << 16) | (g << 8) | b;
    }
}
