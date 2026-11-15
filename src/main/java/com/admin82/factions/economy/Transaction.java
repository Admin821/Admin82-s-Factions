package com.admin82.factions.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One line of money history (wallet, faction vault or market activity).
 *
 * @param time    when it happened (epoch ms)
 * @param amount  signed change in copper; 0 for informational entries (e.g. "listed an item")
 * @param balance balance after the change, or -1 when not applicable (market activity)
 * @param text    human-readable description
 * @param icon    item involved, or {@link ItemStack#EMPTY}
 */
public record Transaction(long time, long amount, long balance, String text, ItemStack icon) {
    /** Entries kept per wallet / vault / player. */
    public static final int MAX_ENTRIES = 100;

    public Transaction withAmount(long newAmount, long newBalance) {
        return new Transaction(System.currentTimeMillis(), newAmount, newBalance, text, icon);
    }

    /** Adds to the front (newest first) and trims to {@link #MAX_ENTRIES}. */
    public static void push(Deque<Transaction> history, Transaction entry) {
        history.addFirst(entry);
        while (history.size() > MAX_ENTRIES) history.removeLast();
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("time", time);
        tag.putLong("amount", amount);
        tag.putLong("balance", balance);
        tag.putString("text", text);
        if (!icon.isEmpty()) tag.put("icon", icon.save(registries));
        return tag;
    }

    public static Transaction load(CompoundTag tag, HolderLookup.Provider registries) {
        ItemStack icon = tag.contains("icon") ? ItemStack.parseOptional(registries, tag.getCompound("icon")) : ItemStack.EMPTY;
        return new Transaction(tag.getLong("time"), tag.getLong("amount"), tag.getLong("balance"),
                tag.getString("text"), icon);
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeLong(time);
        buf.writeLong(amount);
        buf.writeLong(balance);
        buf.writeUtf(text, 256);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, icon);
    }

    public static Transaction read(RegistryFriendlyByteBuf buf) {
        return new Transaction(buf.readLong(), buf.readLong(), buf.readLong(), buf.readUtf(256),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
    }

    public static void writeList(RegistryFriendlyByteBuf buf, List<Transaction> entries) {
        buf.writeVarInt(entries.size());
        entries.forEach(entry -> entry.write(buf));
    }

    public static List<Transaction> readList(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        var list = new ArrayList<Transaction>(size);
        for (int i = 0; i < size; i++) list.add(read(buf));
        return list;
    }
}
