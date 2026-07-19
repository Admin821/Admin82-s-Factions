package com.admin82.factions.blockentity;

import com.admin82.factions.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

public class OutpostManagerBlockEntity extends BlockEntity {

    @Nullable private UUID linkedFactionId;

    public OutpostManagerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OUTPOST_MANAGER.get(), pos, state);
    }

    @Nullable public UUID getLinkedFactionId() { return linkedFactionId; }

    public void setLinkedFactionId(@Nullable UUID id) {
        this.linkedFactionId = id;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (linkedFactionId != null) tag.putUUID("linkedFactionId", linkedFactionId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        linkedFactionId = tag.hasUUID("linkedFactionId") ? tag.getUUID("linkedFactionId") : null;
    }
}
