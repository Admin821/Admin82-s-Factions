package com.admin82.factions.economy;

import com.admin82.factions.registry.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Persistent economy data: player wallets, faction vaults, upkeep timers.
 * Also provides helpers for physical coin ↔ balance conversions.
 */
public class EconomyManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_economy";

    /** Player wallet balances in copper. */
    private final Map<UUID, Long> playerWallets = new HashMap<>();
    /** Faction vault balances in copper. */
    private final Map<UUID, Long> factionVaults = new HashMap<>();
    /** System-time (ms) when each faction's upkeep is next due. */
    private final Map<UUID, Long> upkeepNextDue = new HashMap<>();

    // ── Singleton access ──────────────────────────────────────────────────────

    public static EconomyManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new Factory<>(EconomyManager::new, EconomyManager::load),
            DATA_NAME
        );
    }

    // ── Player wallets ────────────────────────────────────────────────────────

    public long getWallet(UUID player) {
        return playerWallets.getOrDefault(player, 0L);
    }

    public void setWallet(UUID player, long copper) {
        playerWallets.put(player, Math.max(0, copper));
        setDirty();
    }

    public boolean deductWallet(UUID player, long cost) {
        long bal = getWallet(player);
        if (bal < cost) return false;
        setWallet(player, bal - cost);
        return true;
    }

    public void addWallet(UUID player, long copper) {
        setWallet(player, getWallet(player) + copper);
    }

    // ── Faction vaults ────────────────────────────────────────────────────────

    public long getVault(UUID faction) {
        return factionVaults.getOrDefault(faction, 0L);
    }

    public void setVault(UUID faction, long copper) {
        factionVaults.put(faction, Math.max(0, copper));
        setDirty();
    }

    public boolean deductVault(UUID faction, long cost) {
        long bal = getVault(faction);
        if (bal < cost) return false;
        setVault(faction, bal - cost);
        return true;
    }

    public void addVault(UUID faction, long copper) {
        setVault(faction, getVault(faction) + copper);
    }

    public void removeFactionData(UUID faction) {
        factionVaults.remove(faction);
        upkeepNextDue.remove(faction);
        setDirty();
    }

    // ── Upkeep timers ─────────────────────────────────────────────────────────

    public long getUpkeepNextDue(UUID faction) {
        return upkeepNextDue.getOrDefault(faction, 0L);
    }

    public void setUpkeepNextDue(UUID faction, long timeMs) {
        upkeepNextDue.put(faction, timeMs);
        setDirty();
    }

    /**
     * Returns {@code true} if the faction's vault has a positive balance,
     * meaning they can still cover upcoming upkeep.
     * A zero-vault faction is considered "no upkeep" for war and protection purposes.
     */
    public boolean hasUpkeep(UUID faction) {
        return getVault(faction) > 0;
    }

    // ── Physical coin helpers (server-side) ───────────────────────────────────

    /** Converts all coins in the player's inventory to copper. */
    public static long countCoinsInInventory(net.minecraft.world.entity.player.Player player) {
        long total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            total += stackCopper(player.getInventory().getItem(i));
        }
        return total;
    }

    /** Copper value of a single ItemStack (0 if not a coin). */
    public static long stackCopper(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        long perCoin = 0;
        if (item == ModItems.COPPER_COIN.get())    perCoin = 1;
        else if (item == ModItems.SILVER_COIN.get())   perCoin = Currency.COPPER_PER_SILVER;
        else if (item == ModItems.GOLD_COIN.get())     perCoin = Currency.COPPER_PER_GOLD;
        else if (item == ModItems.PLATINUM_COIN.get()) perCoin = Currency.COPPER_PER_PLATINUM;
        return perCoin * stack.getCount();
    }

    /**
     * Removes exactly {@code copper} worth of coins from the player's inventory,
     * giving change as smaller coins if needed.
     * @return false if the player doesn't have enough coins.
     */
    public static boolean removeCoinsFromInventory(net.minecraft.world.entity.player.Player player, long copper) {
        long total = countCoinsInInventory(player);
        if (total < copper) return false;
        // Remove all coins
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isCoin(player.getInventory().getItem(i).getItem())) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
        // Give back change
        long change = total - copper;
        if (change > 0) giveCopperToInventory(player, change);
        return true;
    }

    /** Gives physical coin items to the player's inventory (largest denominations first). */
    public static void giveCopperToInventory(net.minecraft.world.entity.player.Player player, long copper) {
        long remaining = copper;
        long plat = remaining / Currency.COPPER_PER_PLATINUM; remaining %= Currency.COPPER_PER_PLATINUM;
        long gold = remaining / Currency.COPPER_PER_GOLD;     remaining %= Currency.COPPER_PER_GOLD;
        long silv = remaining / Currency.COPPER_PER_SILVER;   remaining %= Currency.COPPER_PER_SILVER;
        long cop  = remaining;
        giveCoins(player, ModItems.PLATINUM_COIN.get(), plat);
        giveCoins(player, ModItems.GOLD_COIN.get(),     gold);
        giveCoins(player, ModItems.SILVER_COIN.get(),   silv);
        giveCoins(player, ModItems.COPPER_COIN.get(),   cop);
    }

    private static void giveCoins(net.minecraft.world.entity.player.Player player, Item coinItem, long count) {
        while (count > 0) {
            int stackSize = (int) Math.min(count, 64);
            player.getInventory().add(new ItemStack(coinItem, stackSize));
            count -= stackSize;
        }
    }

    public static boolean isCoin(Item item) {
        return item == ModItems.COPPER_COIN.get()
            || item == ModItems.SILVER_COIN.get()
            || item == ModItems.GOLD_COIN.get()
            || item == ModItems.PLATINUM_COIN.get();
    }

    // ── Leaderboard ───────────────────────────────────────────────────────────

    /** Vault balance for a faction (used for leaderboard). */
    public long getFactionVaultBalance(UUID faction) {
        return getVault(faction);
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        var walletList = new ListTag();
        for (var e : playerWallets.entrySet()) {
            var entry = new CompoundTag();
            entry.putUUID("id", e.getKey());
            entry.putLong("copper", e.getValue());
            walletList.add(entry);
        }
        tag.put("playerWallets", walletList);

        var vaultList = new ListTag();
        for (var e : factionVaults.entrySet()) {
            var entry = new CompoundTag();
            entry.putUUID("id", e.getKey());
            entry.putLong("copper", e.getValue());
            vaultList.add(entry);
        }
        tag.put("factionVaults", vaultList);

        var upkeepList = new ListTag();
        for (var e : upkeepNextDue.entrySet()) {
            var entry = new CompoundTag();
            entry.putUUID("id", e.getKey());
            entry.putLong("nextDue", e.getValue());
            upkeepList.add(entry);
        }
        tag.put("upkeepNextDue", upkeepList);

        return tag;
    }

    private static EconomyManager load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider reg) {
        var manager = new EconomyManager();
        var walletList = tag.getList("playerWallets", Tag.TAG_COMPOUND);
        for (int i = 0; i < walletList.size(); i++) {
            var entry = walletList.getCompound(i);
            manager.playerWallets.put(entry.getUUID("id"), entry.getLong("copper"));
        }
        var vaultList = tag.getList("factionVaults", Tag.TAG_COMPOUND);
        for (int i = 0; i < vaultList.size(); i++) {
            var entry = vaultList.getCompound(i);
            manager.factionVaults.put(entry.getUUID("id"), entry.getLong("copper"));
        }
        var upkeepList = tag.getList("upkeepNextDue", Tag.TAG_COMPOUND);
        for (int i = 0; i < upkeepList.size(); i++) {
            var entry = upkeepList.getCompound(i);
            manager.upkeepNextDue.put(entry.getUUID("id"), entry.getLong("nextDue"));
        }
        return manager;
    }
}
