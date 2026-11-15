package com.admin82.factions.map;

import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class MapMenu extends AbstractContainerMenu implements IModularUIHolderMenu {
    protected MapMenu(@Nullable MenuType<?> menuType, int containerId) {
        super(menuType, containerId);
    }

    @Override public @Nullable ModularUI ldlib2$getModularUI() {
        return null;
    }

    @Override public @Nullable ItemSlot ldlib2$getItemSlot(Slot slot) {
        return null;
    }

    @Override public void ldlib2$addSlot(ItemSlot itemSlot) {

    }

    @Override public void ldlib2$setModularUI(ModularUI modularUI) {

    }

    @Override public ItemStack quickMoveStack(Player player, int i) {
        return null;
    }

    @Override public boolean stillValid(Player player) {
        return false;
    }
}
