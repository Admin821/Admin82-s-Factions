package com.admin82.factions.blockentity;

import com.admin82.factions.monument.MonumentCrateType;
import com.admin82.factions.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.NonNullList;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

public class MonumentCrateBlockEntity extends RandomizableContainerBlockEntity {
    private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
    @Nullable private UUID monumentId;
    private String poolName = "Supply";

    public MonumentCrateBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MONUMENT_CRATE.get(), pos, state);
    }

    @Nullable public UUID getMonumentId() { return monumentId; }
    public String getPoolName() { return poolName; }
    public MonumentCrateType getCrateType() {
        try {
            return MonumentCrateType.parse(poolName.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MonumentCrateType.SUPPLY;
        }
    }

    public void link(UUID monumentId, String poolName) {
        this.monumentId = monumentId;
        this.poolName = poolName;
        setChanged();
    }

    @Override public int getContainerSize() { return 27; }
    @Override protected Component getDefaultName() { return Component.literal(poolName + " Monument Crate"); }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) { return ChestMenu.threeRows(id, inventory, this); }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> items) { this.items = items; }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (monumentId != null) tag.putUUID("monumentId", monumentId);
        tag.putString("poolName", poolName);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        monumentId = tag.hasUUID("monumentId") ? tag.getUUID("monumentId") : null;
        poolName = tag.contains("poolName") ? tag.getString("poolName") : legacyPoolName(tag.getString("crateType"));
    }

    private static String legacyPoolName(String type) {
        if (type == null || type.isBlank()) return "Supply";
        String lower = type.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}