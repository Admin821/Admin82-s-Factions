package com.admin82.factions.screen;

import com.admin82.factions.menu.SupplyDropMenu;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;

import java.util.List;

public class SupplyDropScreen extends AbstractContainerScreen<SupplyDropMenu> {
    public SupplyDropScreen(SupplyDropMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    public void init() {
        if (menu instanceof IModularUIHolderMenu holder) {
            ModularUI mui = holder.getModularUI();
            if (mui != null) {
                this.imageWidth = (int) mui.getWidth();
                this.imageHeight = (int) mui.getHeight();
            }
        }
        super.init();
    }

    public void updateSupplyDropData(List<String> poolNames, String scheduledPoolName, int intervalHours,
                                     int radius, int fallSeconds, long nextDropAt) {
        menu.updateSupplyDropData(poolNames, scheduledPoolName, intervalHours, radius, fallSeconds, nextDropAt);
    }

    public void updatePoolSettings(String poolName, int[] minCounts, int[] maxCounts, int[] rarityLevels) {
        menu.updatePoolSettings(poolName, minCounts, maxCounts, rarityLevels);
    }

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