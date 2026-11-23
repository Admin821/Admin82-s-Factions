package com.admin82.factions.menu;

import com.admin82.factions.network.packet.OpenSeamViewerPacket;
import com.admin82.factions.network.packet.SeamMemoryPacket;
import com.admin82.factions.seam.SeamCell;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.vfyjxf.taffy.style.AlignItems;


import javax.annotation.Nullable;
import java.util.List;

/**
 * The Seam Viewer's map: everything this player has scanned, dragged around like a map mod. The chunks just scanned
 * are up to date; the rest show what they were when last scanned. Opened on the client from {@link OpenSeamViewerPacket}.
 */
public class SeamViewerMenu extends AbstractContainerMenu implements IModularUIHolderMenu {

    private static final int VISIBLE = 11;
    private static final int CELL = 22;

    @Nullable private ModularUI modularUI;
    private final OpenSeamViewerPacket data;
    @Nullable private SeamMapView view;

    public SeamViewerMenu(int id, Inventory inv, OpenSeamViewerPacket data) {
        super(null, id);
        this.data = data;
        if (FMLEnvironment.dist == Dist.CLIENT) setModularUI(createModularUI(inv.player));
    }

    @Override @Nullable public ModularUI getModularUI() { return modularUI; }
    @Override public void setModularUI(ModularUI ui) { this.modularUI = ui; }
    @Override public ModularUI ldlib2$getModularUI() { return modularUI; }
    @Override public void ldlib2$setModularUI(ModularUI ui) { this.modularUI = ui; }
    @Override @Nullable public ItemSlot ldlib2$getItemSlot(Slot slot) { return null; }
    @Override public void ldlib2$addSlot(ItemSlot slot) { }
    @Override public boolean stillValid(Player p) { return true; }
    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }

    /** More remembered chunks from the server (after dragging). */
    public void addCells(List<SeamCell> cells) {
        if (view != null) view.addCells(cells);
    }

    @OnlyIn(Dist.CLIENT)
    private ModularUI createModularUI(Player player) {
        // Centre on where the player really is (the chunk they're standing in), with them outlined in white.
        var here = player.chunkPosition();
        view = new SeamMapView(VISIBLE, CELL, here.x, here.z,
                (x, z, r) -> PacketDistributor.sendToServer(new SeamMemoryPacket(x, z, List.of())),
                SeamMemoryPacket.WINDOW / 2, false, false, "Not scanned yet", null);
        view.playerChunk = here.x + "," + here.z;
        view.addCells(data.cells());
        if (here.x != data.centerX() || here.z != data.centerZ()) view.moveTo(here.x, here.z);

        int mapPx = view.pixels();
        int width = mapPx + 36;
        var frame = new UIElement();
        frame.layout(l -> l.width(width).height(mapPx + 92).paddingAll(2));
        frame.addClass("preview_bg");
        var root = new UIElement();
        root.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).paddingAll(8).gapAll(5).alignItems(AlignItems.CENTER));
        root.addClass("panel_bg");

        root.addChildren(SeamMap.fit("§6§l⛏ Seam Viewer"), SeamMap.legend(3), view.element(),
                SeamMap.fit("§8Drag to look around · white outline: you"),
                SeamMap.fit("§8Far chunks show your last scan of them"));
        frame.addChildren(root);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }
}
