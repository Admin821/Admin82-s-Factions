package com.admin82.factions.storage;

import com.admin82.factions.Config;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Every faction's item vault: a normal inventory (27 slots to start) shared by the whole faction.
 * The first slots are secure: their items are never lost or looted, not even when the faction's core
 * is taken. Each upgrade adds slots and one more secure slot.
 */
public class FactionItemVaults extends SavedData {

    private static final String DATA_NAME = "adminsfactions_item_vaults";

    /** One faction's vault. Slots 0 .. secure-1 are the secure ones. */
    public static final class Vault {
        public int level = 1;
        public SimpleContainer container;

        Vault(int level, Runnable onChange) {
            this.level = level;
            this.container = newContainer(slots(level), onChange);
        }

        public int secure() { return secureSlots(level); }
        public boolean isSecure(int slot) { return slot < secure(); }
    }

    private final Map<UUID, Vault> vaults = new HashMap<>();
    /** Items from old-style vaults that didn't fit after converting; delivered to the owner once the server is up. */
    private final Map<UUID, List<ItemStack>> overflow = new HashMap<>();

    public static FactionItemVaults get(MinecraftServer server) {
        FactionItemVaults data = server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FactionItemVaults::new, FactionItemVaults::load, null), DATA_NAME);
        if (!data.overflow.isEmpty()) data.deliverOverflow(server);
        return data;
    }

    // Size per level

    public static int maxLevel() { return Config.ITEM_VAULT_MAX_LEVEL.get(); }

    public static int slots(int level) {
        return Config.ITEM_VAULT_SLOTS.get() + Config.ITEM_VAULT_SLOTS_PER_LEVEL.get() * (level - 1);
    }

    public static int secureSlots(int level) {
        return level; // 1 to start, +1 per upgrade
    }

    /** Warbonds to go from {@code level} to the next one. */
    public static int upgradeCost(int level) {
        return Config.ITEM_VAULT_UPGRADE_WARBONDS.get() * level;
    }

    private static SimpleContainer newContainer(int size, Runnable onChange) {
        SimpleContainer c = new SimpleContainer(size);
        c.addListener(ignored -> onChange.run());
        return c;
    }

    // Access

    public Vault vault(UUID faction) {
        return vaults.computeIfAbsent(faction, id -> new Vault(1, this::setDirty));
    }

    public SimpleContainer container(UUID faction) { return vault(faction).container; }

    /** Raises the vault one level: a bigger container with the same items in the same slots. */
    public void upgrade(UUID faction) {
        Vault v = vault(faction);
        SimpleContainer old = v.container;
        v.level++;
        v.container = newContainer(slots(v.level), this::setDirty);
        // Secure slots grow by one: keep secure items in the secure range and the rest after it.
        int oldSecure = secureSlots(v.level - 1);
        for (int i = 0; i < old.getContainerSize(); i++) {
            int target = i < oldSecure ? i : i + 1;
            v.container.setItem(target, old.getItem(i));
        }
        setDirty();
    }

    /** Puts a stack in (regular slots first, then secure ones). Returns what didn't fit or isn't allowed (see {@link StorageRules}). */
    public ItemStack insert(UUID faction, ItemStack stack) {
        if (!StorageRules.allowed(stack)) return stack.copy(); // kept out: the caller delivers it some other way
        Vault v = vault(faction);
        ItemStack rest = stack.copy();
        int size = v.container.getContainerSize();
        // Merge into matching stacks, then empty slots; regular slots before secure ones.
        for (int pass = 0; pass < 2 && !rest.isEmpty(); pass++) {
            for (int n = 0; n < size && !rest.isEmpty(); n++) {
                int i = (n + v.secure()) % size; // start after the secure slots, wrap round to them
                ItemStack in = v.container.getItem(i);
                if (pass == 0 && !in.isEmpty() && ItemStack.isSameItemSameComponents(in, rest)) {
                    int move = Math.min(rest.getCount(), in.getMaxStackSize() - in.getCount());
                    if (move > 0) { in.grow(move); rest.shrink(move); }
                } else if (pass == 1 && in.isEmpty()) {
                    v.container.setItem(i, rest.copy());
                    rest = ItemStack.EMPTY;
                }
            }
        }
        v.container.setChanged();
        return rest;
    }

    public int count(UUID faction, Predicate<ItemStack> match) {
        SimpleContainer c = container(faction);
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (match.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** Removes up to {@code amount} matching items; returns how many were removed. */
    public int remove(UUID faction, Predicate<ItemStack> match, int amount) {
        SimpleContainer c = container(faction);
        int left = amount;
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !match.test(s)) continue;
            int take = Math.min(left, s.getCount());
            s.shrink(take);
            left -= take;
        }
        c.setChanged();
        return amount - left;
    }

    /** Empties the regular (non-secure) slots and returns their items. */
    public List<ItemStack> takeRegular(UUID faction) {
        Vault v = vault(faction);
        List<ItemStack> out = new ArrayList<>();
        for (int i = v.secure(); i < v.container.getContainerSize(); i++) {
            ItemStack s = v.container.removeItemNoUpdate(i);
            if (!s.isEmpty()) out.add(s);
        }
        v.container.setChanged();
        return out;
    }

    /** Removes a faction's vault entirely and returns everything in it. */
    public List<ItemStack> remove(UUID faction) {
        Vault v = vaults.remove(faction);
        setDirty();
        List<ItemStack> out = new ArrayList<>();
        if (v == null) return out;
        for (int i = 0; i < v.container.getContainerSize(); i++) {
            ItemStack s = v.container.removeItemNoUpdate(i);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public void changed() { setDirty(); }

    private void deliverOverflow(MinecraftServer server) {
        FactionManager fmgr = FactionManager.get(server);
        MarketManager market = MarketManager.get(server);
        overflow.forEach((faction, stacks) -> {
            Faction f = fmgr.getFaction(faction);
            if (f == null) return;
            for (ItemStack s : stacks) market.queueDelivery(f.getOwnerId(), s, "Item vault (didn't fit after the vault changed)");
        });
        overflow.clear();
    }

    // Saving

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", 2);
        CompoundTag all = new CompoundTag();
        vaults.forEach((id, v) -> {
            CompoundTag t = new CompoundTag();
            t.putInt("level", v.level);
            ListTag items = new ListTag();
            for (int i = 0; i < v.container.getContainerSize(); i++) {
                ItemStack s = v.container.getItem(i);
                if (s.isEmpty()) continue;
                CompoundTag it = new CompoundTag();
                it.putInt("slot", i);
                it.put("item", s.save(registries));
                items.add(it);
            }
            t.put("items", items);
            all.put(id.toString(), t);
        });
        tag.put("vaults", all);
        return tag;
    }

    public static FactionItemVaults load(CompoundTag tag, HolderLookup.Provider registries) {
        FactionItemVaults data = new FactionItemVaults();
        CompoundTag all = tag.getCompound("vaults");
        boolean oldFormat = tag.getInt("format") < 2;
        for (String key : all.getAllKeys()) {
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException e) { continue; }
            CompoundTag t = all.getCompound(key);
            if (oldFormat) {
                // Old drawer-style vault: put its items into a level 1 inventory; the rest is delivered to the owner.
                Vault v = data.vault(id);
                for (ItemStack s : ItemVault.load(t, registries).takeAll()) {
                    ItemStack rest = data.insert(id, s);
                    if (!rest.isEmpty()) data.overflow.computeIfAbsent(id, k -> new ArrayList<>()).add(rest);
                }
                continue;
            }
            int level = Math.max(1, t.getInt("level"));
            Vault v = new Vault(level, data::setDirty);
            for (Tag raw : t.getList("items", Tag.TAG_COMPOUND)) {
                CompoundTag it = (CompoundTag) raw;
                int slot = it.getInt("slot");
                ItemStack s = ItemStack.parseOptional(registries, it.getCompound("item"));
                if (slot >= 0 && slot < v.container.getContainerSize()) v.container.setItem(slot, s);
                else if (!s.isEmpty()) data.overflow.computeIfAbsent(id, k -> new ArrayList<>()).add(s);
            }
            data.vaults.put(id, v);
        }
        return data;
    }
}
