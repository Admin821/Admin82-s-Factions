package com.admin82.factions.screen;

import com.admin82.factions.menu.OutpostManagerMenu;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Thin wrapper that delegates all rendering to the LDLib2 ModularUI
 * created inside OutpostManagerMenu. Same pattern as FactionTableScreen.
 */
public class OutpostManagerScreen extends AbstractContainerScreen<OutpostManagerMenu> {

    public OutpostManagerScreen(OutpostManagerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
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

    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {}
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {}
}