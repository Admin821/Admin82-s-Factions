package com.admin82.factions.blockentity;

import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.registry.ModBlockEntities;
import com.admin82.factions.storage.ItemVault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The outpost storage box's computer. Every second the server checks the box's contents and, when they've changed,
 * sends players a short summary for the screen: how many kinds of item are stored out of the max, and the biggest
 * stacks. The items themselves stay in the outpost's {@link ItemVault}.
 */
public class OutpostStorageBlockEntity extends BlockEntity {

    /** Item kinds shown on the screen. */
    public static final int SHOWN = 5;

    private boolean linked;
    private int kinds, maxKinds;
    private long total;
    private final List<ItemStack> top = new ArrayList<>();
    private final List<Long> counts = new ArrayList<>();

    /** Server side: what was last sent, so unchanged boxes send nothing. */
    private int sentSignature;
    private boolean sent;

    public OutpostStorageBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OUTPOST_STORAGE.get(), pos, state);
    }

    public boolean linked() { return linked; }
    public int kinds() { return kinds; }
    public int maxKinds() { return maxKinds; }
    public long total() { return total; }
    public List<ItemStack> top() { return top; }
    public List<Long> counts() { return counts; }

    public static void serverTick(Level level, BlockPos pos, BlockState state, OutpostStorageBlockEntity box) {
        // Once a second, spread out so boxes don't all check on the same tick
        if (!(level instanceof ServerLevel server) || Math.floorMod(level.getGameTime() + pos.hashCode(), 20) != 0) return;
        box.refresh(server);
    }

    private void refresh(ServerLevel level) {
        OutpostEntry entry = OutpostData.get(level.getServer())
                .getOutpostByStorage(worldPosition, level.dimension().location().toString());
        top.clear();
        counts.clear();
        linked = entry != null;
        kinds = 0;
        maxKinds = 0;
        total = 0;
        if (entry != null) {
            List<ItemVault.Entry> entries = new ArrayList<>(entry.storage.entries());
            kinds = entries.size();
            maxKinds = entry.storageKinds();
            for (ItemVault.Entry e : entries) total += e.count;
            entries.sort(Comparator.comparingLong((ItemVault.Entry e) -> e.count).reversed());
            for (int i = 0; i < Math.min(SHOWN, entries.size()); i++) {
                top.add(entries.get(i).item.copyWithCount(1));
                counts.add(entries.get(i).count);
            }
        }
        int signature = signature();
        if (sent && signature == sentSignature) return;
        sent = true;
        sentSignature = signature;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    private int signature() {
        int h = Boolean.hashCode(linked);
        h = 31 * h + kinds;
        h = 31 * h + maxKinds;
        h = 31 * h + Long.hashCode(total);
        for (int i = 0; i < top.size(); i++) {
            h = 31 * h + ItemStack.hashItemAndComponents(top.get(i));
            h = 31 * h + Long.hashCode(counts.get(i));
        }
        return h;
    }

    // Client sync: only the screen summary goes to players; nothing is saved (it's rebuilt every second)

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        CompoundTag screen = new CompoundTag();
        screen.putBoolean("linked", linked);
        screen.putInt("kinds", kinds);
        screen.putInt("max", maxKinds);
        screen.putLong("total", total);
        ListTag list = new ListTag();
        for (int i = 0; i < top.size(); i++) {
            CompoundTag line = new CompoundTag();
            line.put("item", top.get(i).save(registries));
            line.putLong("count", counts.get(i));
            list.add(line);
        }
        screen.put("top", list);
        tag.put("screen", screen);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (!tag.contains("screen")) return;
        CompoundTag screen = tag.getCompound("screen");
        linked = screen.getBoolean("linked");
        kinds = screen.getInt("kinds");
        maxKinds = screen.getInt("max");
        total = screen.getLong("total");
        top.clear();
        counts.clear();
        for (Tag raw : screen.getList("top", Tag.TAG_COMPOUND)) {
            CompoundTag line = (CompoundTag) raw;
            ItemStack stack = ItemStack.parseOptional(registries, line.getCompound("item"));
            if (stack.isEmpty()) continue;
            top.add(stack);
            counts.add(line.getLong("count"));
        }
    }
}
