package com.admin82.factions.blockentity;

import com.admin82.factions.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.NonNullList;

public class MonumentControllerBlockEntity extends RandomizableContainerBlockEntity {
    private NonNullList<ItemStack> items = NonNullList.withSize(54, ItemStack.EMPTY);

    public MonumentControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MONUMENT_CONTROLLER.get(), pos, state);
    }

    @Override public int getContainerSize() { return 54; }
    @Override protected Component getDefaultName() { return Component.literal("Monument Loot Pools"); }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) { return ChestMenu.sixRows(id, inventory, this); }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> items) { this.items = items; }
}