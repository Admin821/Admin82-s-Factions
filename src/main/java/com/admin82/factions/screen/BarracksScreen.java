package com.admin82.factions.screen;

import com.admin82.factions.menu.BarracksMenu;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;

import java.util.List;

public class BarracksScreen extends AbstractContainerScreen<BarracksMenu> {

    public BarracksScreen(BarracksMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    public void init() {
        if (menu instanceof IModularUIHolderMenu h) {
            ModularUI mui = h.getModularUI();
            if (mui != null) {
                this.imageWidth  = (int) mui.getWidth();
                this.imageHeight = (int) mui.getHeight();
            }
        }
        super.init();
    }

    /** Called by SyncBarracksPacket when the server pushes kit data updates. */
    public void updateKitData(List<String> kitNames) {
        menu.updateKitData(kitNames);
    }

    /**
     * Routes a click from a display-only kit slot widget back to the real backing
     * staging slot, so vanilla container interaction (pick-up, place, shift-click) works.
     */
    public void interactWithSlot(int slotId, int button, ClickType clickType) {
        if (slotId >= 0 && slotId < menu.slots.size()) {
            slotClicked(menu.slots.get(slotId), slotId, button, clickType);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {}

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {}
}
