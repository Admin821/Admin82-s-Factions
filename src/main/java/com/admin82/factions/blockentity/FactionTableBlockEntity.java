package com.admin82.factions.blockentity;

import com.admin82.factions.menu.FactionTableMenu;
import com.admin82.factions.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

public class FactionTableBlockEntity extends BlockEntity implements MenuProvider {

    @Nullable
    private UUID linkedFactionId;

    public FactionTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FACTION_TABLE.get(), pos, state);
    }

    @Nullable
    public UUID getLinkedFactionId() { return linkedFactionId; }

    public void setLinkedFactionId(@Nullable UUID id) {
        this.linkedFactionId = id;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (linkedFactionId != null) {
            tag.putUUID("linkedFactionId", linkedFactionId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        linkedFactionId = tag.hasUUID("linkedFactionId") ? tag.getUUID("linkedFactionId") : null;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("gui.adminsfactions.faction_table");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new FactionTableMenu(containerId, playerInventory, this.worldPosition, null);
    }
}
