package com.admin82.factions.screen;

import com.admin82.factions.menu.ItemVaultMenu;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Thin wrapper for an item vault screen; LDLib2 renders everything. */
public class ItemVaultScreen extends AbstractContainerScreen<ItemVaultMenu> {

    public ItemVaultScreen(ItemVaultMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    public void init() {
        if (menu instanceof IModularUIHolderMenu holder) {
            ModularUI ui = holder.getModularUI();
            if (ui != null) {
                imageWidth = (int) ui.getWidth();
                imageHeight = (int) ui.getHeight();
            }
        }
        super.init();
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        if (minecraft.player != null) menu.rebuildClientUi(minecraft.player);
        super.resize(minecraft, width, height);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {}
}
