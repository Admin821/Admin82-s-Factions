package com.admin82.factions.screen;

import com.admin82.factions.faction.Faction;
import com.admin82.factions.menu.FactionTableMenu;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import javax.annotation.Nullable;

/**
 * Thin wrapper screen: all rendering is delegated to the LDLib2 ModularUI
 * that is created inside {@link FactionTableMenu}.
 * LDLib2''s AbstractContainerScreenMixin adds and initialises the widget
 * automatically when {@code super.init()} is called.
 */
public class FactionTableScreen extends AbstractContainerScreen<FactionTableMenu> {

    public FactionTableScreen(FactionTableMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    public void init() {
        // Set dimensions BEFORE super.init() so leftPos/topPos are computed correctly.
        if (menu instanceof IModularUIHolderMenu h) {
            ModularUI mui = h.getModularUI();
            if (mui != null) {
                this.imageWidth  = (int) mui.getWidth();
                this.imageHeight = (int) mui.getHeight();
            }
        }
        super.init(); // LDLib2 mixin adds & initialises the ModularUI widget here
    }

    /**
     * Called by SyncFactionDataPacket when the server pushes an update.
     * If the view type changes (no faction -> faction or vice-versa),
     * the whole ModularUI is rebuilt. Otherwise only the AtomicReference
     * is updated and SupplierDataSource widgets pick up the change live.
     */
    public void updateFactionData(@Nullable Faction faction) {
        @Nullable Faction prev = menu.getFaction();
        boolean viewChange = (prev == null) != (faction == null);

        if (viewChange) {
            if (faction == null) {
                // Faction was disbanded — close the screen entirely
                Minecraft.getInstance().setScreen(null);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                menu.rebuildForFaction(faction, mc.player);
                if (menu instanceof IModularUIHolderMenu h) {
                    ModularUI newMui = h.getModularUI();
                    if (newMui != null) {
                        this.imageWidth  = (int) newMui.getWidth();
                        this.imageHeight = (int) newMui.getHeight();
                    }
                }
                rebuildWidgets();
            }
        } else {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                menu.rebuildForFaction(faction, mc.player);
                if (menu instanceof IModularUIHolderMenu h) {
                    ModularUI newMui = h.getModularUI();
                    if (newMui != null) {
                        this.imageWidth  = (int) newMui.getWidth();
                        this.imageHeight = (int) newMui.getHeight();
                    }
                }
                rebuildWidgets();
            } else {
                menu.updateFaction(faction);
            }
        }
    }

    // Suppress vanilla title / inventory labels; LDLib2 handles all rendering.
    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {}

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {}

    /** Called by {@link com.admin82.factions.network.packet.SyncEconomyPacket}. */
    public void updateEconomy(long wallet, long vault) {
        menu.updateEconomy(wallet, vault);
    }

    /** Called by {@link com.admin82.factions.network.packet.SyncWarDemandsPacket}. */
    public void updateWarDemands(java.util.UUID warId,
                                 java.util.List<com.admin82.factions.war.WarDemand> demands) {
        menu.updateWarDemands(warId, demands);
    }

    /** Called by {@link com.admin82.factions.network.packet.SyncEnemyClaimsPacket}. */
    public void updateEnemyClaims(java.util.UUID targetFactionId,
                                  java.util.List<String> claimKeys,
                                  int coreCX, int coreCZ, String coreDim) {
        menu.updateEnemyClaims(targetFactionId, claimKeys, coreCX, coreCZ, coreDim);
    }
}
