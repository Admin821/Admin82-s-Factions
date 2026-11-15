package com.admin82.factions.screen;

import com.admin82.factions.menu.SeamExtractorMenu;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;

/** Thin wrapper for a Seam Extractor; LDLib2 draws it and clicks go to the real slots. */
public class SeamExtractorScreen extends AbstractContainerScreen<SeamExtractorMenu> {

    public SeamExtractorScreen(SeamExtractorMenu menu, Inventory inventory, Component title) {
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

    /** Sends a click on a drawn slot to the real slot, so it behaves like a chest. */
    public void interactWithSlot(int slotId, int button, ClickType clickType) {
        if (slotId >= 0 && slotId < menu.slots.size()) slotClicked(menu.slots.get(slotId), slotId, button, clickType);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {}
}
