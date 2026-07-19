package com.admin82.factions.screen;

import com.admin82.factions.network.packet.PlaceOutpostPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Shown when the player right-clicks with an Outpost item.
 * Asks for confirmation before placing the structure.
 */
public class OutpostPlacementScreen extends Screen {

    private final BlockPos pos;
    private final String   dimension;

    public OutpostPlacementScreen(BlockPos pos, String dimension) {
        super(Component.literal("Place Outpost"));
        this.pos       = pos;
        this.dimension = dimension;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        addRenderableWidget(
                Button.builder(Component.literal("§a✔ Confirm"),
                        btn -> { PacketDistributor.sendToServer(new PlaceOutpostPacket(true)); onClose(); })
                        .bounds(cx - 106, cy + 20, 100, 20).build());

        addRenderableWidget(
                Button.builder(Component.literal("§c✘ Cancel"),
                        btn -> { PacketDistributor.sendToServer(new PlaceOutpostPacket(false)); onClose(); })
                        .bounds(cx + 6, cy + 20, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g, mx, my, partial);
        int cx = this.width / 2;
        int cy = this.height / 2;

        // Dialog box
        g.fill(cx - 130, cy - 50, cx + 130, cy + 52, 0xCC111122);
        g.fill(cx - 130, cy - 50, cx + 130, cy - 49, 0xFF8866CC);
        g.fill(cx - 130, cy + 51, cx + 130, cy + 52, 0xFF8866CC);
        g.fill(cx - 130, cy - 50, cx - 129, cy + 52, 0xFF8866CC);
        g.fill(cx + 129, cy - 50, cx + 130, cy + 52, 0xFF8866CC);

        g.drawCenteredString(font, "§d§l⚑ Place Outpost?", cx, cy - 42, 0xFFFFFF);
        g.drawCenteredString(font,
                "§7X: " + pos.getX() + "  Y: " + pos.getY() + "  Z: " + pos.getZ(),
                cx, cy - 28, 0xAAAAAA);
        g.drawCenteredString(font,
                "§8A 5×5 cobblestone platform will be placed here.",
                cx, cy - 14, 0x888888);
        g.drawCenteredString(font,
                "§cUpkeep: §e5 silver§c/day from your faction vault.",
                cx, cy, 0xFFFFFF);

        super.render(g, mx, my, partial);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void renderBlurredBackground(float partialTick) { /* no blur */ }

    @Override
    public void renderTransparentBackground(GuiGraphics g) { /* no dark overlay */ }
}
