package com.admin82.factions.screen;

import com.admin82.factions.economy.MarketListing;
import com.admin82.factions.economy.MarketDelivery;
import com.admin82.factions.economy.SoldListing;
import com.admin82.factions.menu.MarketMenu;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * Thin wrapper screen for the Market block — all rendering is handled by LDLib2.
 */
public class MarketScreen extends AbstractContainerScreen<MarketMenu> {

    public MarketScreen(MarketMenu menu, Inventory playerInventory, Component title) {
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

    /** Called by {@link com.admin82.factions.network.packet.SyncMarketPacket} when data arrives. */
    public void updateListings(List<MarketListing> listings, long wallet, int myCount, int maxSlots) {
        menu.updateListings(listings, wallet, myCount, maxSlots);
    }

    /** Called by {@link com.admin82.factions.network.packet.SyncSoldListingsPacket} when data arrives. */
    public void updateSoldListings(List<SoldListing> sold) {
        menu.updateSoldListings(sold);
    }

    public void updateDeliveries(List<MarketDelivery> deliveries) {
        menu.updateDeliveries(deliveries);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (menu.handleMouseScroll(scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {}
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {}
}
