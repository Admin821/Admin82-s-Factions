package com.admin82.factions.screen;

import com.admin82.factions.menu.OutpostPlacementMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Thin wrapper that lets LDLib2 draw {@link OutpostPlacementMenu}. Closing it any other way than its buttons
 * (Escape, E) counts as Cancel.
 */
public class OutpostPlacementScreen extends AbstractContainerScreen<OutpostPlacementMenu> {

    public OutpostPlacementScreen(OutpostPlacementMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    @Override
    public void init() {
        ModularUI mui = menu.getModularUI();
        if (mui != null) {
            this.imageWidth = (int) mui.getWidth();
            this.imageHeight = (int) mui.getHeight();
        }
        super.init();
    }

    @Override
    public void removed() {
        menu.answer(false); // does nothing if Place or Cancel was already pressed
        super.removed();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {}
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {}
}
