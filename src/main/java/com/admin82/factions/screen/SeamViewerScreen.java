package com.admin82.factions.screen;

import com.admin82.factions.menu.SeamViewerMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Thin wrapper that lets LDLib2 draw {@link SeamViewerMenu}. */
public class SeamViewerScreen extends AbstractContainerScreen<SeamViewerMenu> {

    public SeamViewerScreen(SeamViewerMenu menu, Inventory inv, Component title) {
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

    @Override public boolean isPauseScreen() { return false; }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {}
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {}
}
