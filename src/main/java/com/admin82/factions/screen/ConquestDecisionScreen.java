package com.admin82.factions.screen;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.network.packet.ConquestActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Client-only conquest decision dialog.
 * Opened by {@link com.admin82.factions.network.packet.OpenConquestGuiPacket}.
 * Sends back a {@link ConquestActionPacket} when the player chooses.
 */
public class ConquestDecisionScreen extends Screen {

    private final UUID   defeatedFactionId;
    private final String defeatedFactionName;
    private final int    defenderClaims;
    private final long   defenderVault;

    public ConquestDecisionScreen(UUID defeatedFactionId, String defeatedFactionName,
                                  int defenderClaims, long defenderVault) {
        super(Component.literal("Conquest Decision"));
        this.defeatedFactionId   = defeatedFactionId;
        this.defeatedFactionName = defeatedFactionName;
        this.defenderClaims      = defenderClaims;
        this.defenderVault       = defenderVault;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        this.addRenderableWidget(
                Button.builder(Component.literal("⚑ Make Vassal State"),
                        btn -> {
                            PacketDistributor.sendToServer(new ConquestActionPacket(
                                    ConquestActionPacket.Action.MAKE_VASSAL, defeatedFactionId));
                            this.onClose();
                        })
                        .bounds(cx - 200, cy + 10, 190, 30)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("💰 Take Everything"),
                        btn -> {
                            PacketDistributor.sendToServer(new ConquestActionPacket(
                                    ConquestActionPacket.Action.TAKE_ALL, defeatedFactionId));
                            this.onClose();
                        })
                        .bounds(cx + 10, cy + 10, 190, 30)
                        .build()
        );
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        this.renderBackground(g, mx, my, partial);

        int cx = this.width / 2;
        int cy = this.height / 2;

        g.drawCenteredString(this.font, "§c§l⚔ Conquest Decision", cx, cy - 55, 0xFFFFFF);
        g.drawCenteredString(this.font, "§eDefeated: §f" + defeatedFactionName, cx, cy - 38, 0xFFFFFF);
        g.drawCenteredString(this.font,
                "§7Claims: §e" + defenderClaims + "  §7Vault: §e" + Currency.format(defenderVault),
                cx, cy - 22, 0xAAAAAA);
        g.drawCenteredString(this.font, "§7What will you do with the defeated faction?", cx, cy - 6, 0xAAAAAA);

        g.drawCenteredString(this.font, "§8They pay % tax on sales/claims. Can buy freedom.",
                cx - 105, cy + 46, 0x888888);
        g.drawCenteredString(this.font, "§8Seize their vault and release all claims.",
                cx + 105, cy + 46, 0x888888);

        super.render(g, mx, my, partial);
    }

    /** Prevent closing with Escape — the server will resend this until a choice is made. */
    @Override
    public boolean isPauseScreen() { return false; }
}
