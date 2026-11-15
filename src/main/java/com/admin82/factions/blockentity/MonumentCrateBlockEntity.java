package com.admin82.factions.blockentity;

import com.admin82.factions.block.MonumentCrateBlock;
import com.admin82.factions.monument.MonumentCrateType;
import com.admin82.factions.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nullable;

public class MonumentCrateBlockEntity extends RandomizableContainerBlockEntity {
    private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
    @Nullable private UUID monumentId;
    private String poolName = "Supply";

    /** Tracks who has the crate open, so the lid goes up while anyone is looking inside (same as a barrel). */
    private final ContainerOpenersCounter openers = new ContainerOpenersCounter() {
        @Override protected void onOpen(Level level, BlockPos pos, BlockState state) {
            sound(SoundEvents.BARREL_OPEN);
            setOpen(state, true);
        }
        @Override protected void onClose(Level level, BlockPos pos, BlockState state) {
            sound(SoundEvents.BARREL_CLOSE);
            setOpen(state, false);
        }
        @Override protected void openerCountChanged(Level level, BlockPos pos, BlockState state, int before, int after) {}
        @Override protected boolean isOwnContainer(Player player) {
            return player.containerMenu instanceof ChestMenu menu && menu.getContainer() == MonumentCrateBlockEntity.this;
        }
    };

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

    @Override
    public void startOpen(Player player) {
        if (!remove && !player.isSpectator() && level != null) openers.incrementOpeners(player, level, getBlockPos(), getBlockState());
    }

    @Override
    public void stopOpen(Player player) {
        if (!remove && !player.isSpectator() && level != null) openers.decrementOpeners(player, level, getBlockPos(), getBlockState());
    }

    public void recheckOpen() {
        if (!remove && level != null) openers.recheckOpeners(level, getBlockPos(), getBlockState());
    }

    private void setOpen(BlockState state, boolean open) {
        if (level != null && state.hasProperty(MonumentCrateBlock.OPEN)) {
            level.setBlock(getBlockPos(), state.setValue(MonumentCrateBlock.OPEN, open), 3);
        }
    }

    private void sound(SoundEvent sound) {
        if (level == null) return;
        BlockPos p = getBlockPos();
        level.playSound(null, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, sound, SoundSource.BLOCKS,
                0.5f, level.random.nextFloat() * 0.1f + 0.9f);
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