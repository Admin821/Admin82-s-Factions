package com.admin82.factions.blockentity;

import com.admin82.factions.block.CarePackageBlock;
import com.admin82.factions.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.NonNullList;

public class CarePackageBlockEntity extends RandomizableContainerBlockEntity {
    private NonNullList<ItemStack> items = NonNullList.withSize(54, ItemStack.EMPTY);
    private boolean supplyDrop;

    public CarePackageBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CARE_PACKAGE.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            CarePackageBlock.placeFiller(level, worldPosition, getBlockState());
        }
    }

    @Override
    public int getContainerSize() {
        return 54;
    }

    public boolean isSupplyDrop() {
        return supplyDrop;
    }

    public void setSupplyDrop(boolean supplyDrop) {
        this.supplyDrop = supplyDrop;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("SupplyDrop", supplyDrop);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        supplyDrop = tag.getBoolean("SupplyDrop");
    }

    @Override
    protected Component getDefaultName() {
        return Component.literal("Care Package");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return ChestMenu.sixRows(containerId, inventory, this);
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }
}
