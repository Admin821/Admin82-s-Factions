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
    /** System-time (ms) when each faction's current upkeep period started. */
    private final Map<UUID, Long> upkeepPeriodStart   = new HashMap<>();
    /** Copper already charged in the current 24-hour upkeep period. */
    private final Map<UUID, Long> upkeepChargedSoFar  = new HashMap<>();
    /** Factions whose vault ran dry — claims still exist but have NO protection. */
    private final Set<UUID>       insolventFactions    = new HashSet<>();
    /** Multiplier for the land-claim cost exponent (default 1.4). -1 = use default. */
    private double claimRateMultiplier     = -1;
    /** Ramp added per 5-chunk distance band for outpost upkeep. -1 = use default (0.1). */
    private double outpostDistanceRamp     = -1;

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
        upkeepPeriodStart.remove(faction);
        upkeepChargedSoFar.remove(faction);
        insolventFactions.remove(faction);
        setDirty();
    }

    // ── Claim rate multiplier ─────────────────────────────────────────────────

    /**
     * Returns the active claim-rate exponent multiplier.
     * Values above 1.0 make deeds increasingly expensive.
     * Default (when unset) is 1.4.
     */
    public double getClaimRateMultiplier() {
        return claimRateMultiplier > 0 ? claimRateMultiplier : 1.4;
    }

    public void setClaimRateMultiplier(double rate) {
        this.claimRateMultiplier = Math.max(1.0, rate);
        setDirty();
    }

    /**
     * Returns the upkeep ramp added per 5-chunk Chebyshev distance band for outposts.
     * Default 0.1 (each 5-chunk band adds +10% to base outpost upkeep).
     */
    public double getOutpostDistanceRamp() {
        return outpostDistanceRamp > 0 ? outpostDistanceRamp : 0.1;
    }

    public void setOutpostDistanceRamp(double ramp) {
        this.outpostDistanceRamp = Math.max(0.0, ramp);
        setDirty();
    }

    // ── Outpost teleport cost ─────────────────────────────────────────────────

    /** Copper cost to teleport to a faction outpost from the Faction Table. Default 10 silver. */
    private long tpCostToOutpostCopper = 1_000L;

    public long getTpCostToOutpost() { return tpCostToOutpostCopper; }
    public void setTpCostToOutpost(long copper) {
        this.tpCostToOutpostCopper = Math.max(0, copper); setDirty();
    }

    // ── Upkeep: gradual drain + solvent/insolvent state ──────────────────────

    public long getUpkeepPeriodStart(UUID faction) {
        return upkeepPeriodStart.getOrDefault(faction, 0L);
    }
    public void setUpkeepPeriodStart(UUID faction, long ms) {
        upkeepPeriodStart.put(faction, ms); setDirty();
    }

    public long getUpkeepChargedSoFar(UUID faction) {
        return upkeepChargedSoFar.getOrDefault(faction, 0L);
    }
    public void setUpkeepChargedSoFar(UUID faction, long copper) {
        upkeepChargedSoFar.put(faction, Math.max(0, copper)); setDirty();
    }

    /**
     * Returns {@code true} if the faction's claims are currently protected by upkeep.
     * Returns {@code false} if the vault ran dry (insolvent) — land is still claimed
     * but offers no protection to the owning faction.
     */
    public boolean isProtected(UUID factionId) {
        return !insolventFactions.contains(factionId);
    }

    /** Marks the faction as insolvent: claims exist but are unprotected. */
    public void setInsolvent(UUID factionId) {
        insolventFactions.add(factionId); setDirty();
    }

    /** Marks the faction as solvent: claims are protected again. */
    public void setSolvent(UUID factionId) {
        insolventFactions.remove(factionId); setDirty();
    }

    /**
     * Legacy method kept for compatibility.
     * Use {@link #isProtected(UUID)} for protection checks.
     */
    @Deprecated
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

        var periodStartList = new ListTag();
        for (var e : upkeepPeriodStart.entrySet()) {
            var entry = new CompoundTag();
            entry.putUUID("id", e.getKey());
            entry.putLong("start", e.getValue());
            periodStartList.add(entry);
        }
        tag.put("upkeepPeriodStart", periodStartList);

        var chargedList = new ListTag();
        for (var e : upkeepChargedSoFar.entrySet()) {
            var entry = new CompoundTag();
            entry.putUUID("id", e.getKey());
            entry.putLong("charged", e.getValue());
            chargedList.add(entry);
        }
        tag.put("upkeepChargedSoFar", chargedList);

        var insolventList = new ListTag();
        for (UUID fid : insolventFactions) {
            var entry = new CompoundTag();
            entry.putUUID("id", fid);
            insolventList.add(entry);
        }
        tag.put("insolventFactions", insolventList);

        if (claimRateMultiplier > 0)   tag.putDouble("claimRateMultiplier", claimRateMultiplier);
        if (outpostDistanceRamp >= 0)   tag.putDouble("outpostDistanceRamp", outpostDistanceRamp);
        tag.putLong("tpCostToOutpost", tpCostToOutpostCopper);

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
        var periodStartList = tag.getList("upkeepPeriodStart", Tag.TAG_COMPOUND);
        for (int i = 0; i < periodStartList.size(); i++) {
            var entry = periodStartList.getCompound(i);
            manager.upkeepPeriodStart.put(entry.getUUID("id"), entry.getLong("start"));
        }
        var chargedList = tag.getList("upkeepChargedSoFar", Tag.TAG_COMPOUND);
        for (int i = 0; i < chargedList.size(); i++) {
            var entry = chargedList.getCompound(i);
            manager.upkeepChargedSoFar.put(entry.getUUID("id"), entry.getLong("charged"));
        }
        var insolventList = tag.getList("insolventFactions", Tag.TAG_COMPOUND);
        for (int i = 0; i < insolventList.size(); i++) {
            manager.insolventFactions.add(insolventList.getCompound(i).getUUID("id"));
        }
        if (tag.contains("claimRateMultiplier"))
            manager.claimRateMultiplier = tag.getDouble("claimRateMultiplier");
        if (tag.contains("outpostDistanceRamp"))
            manager.outpostDistanceRamp = tag.getDouble("outpostDistanceRamp");
        if (tag.contains("tpCostToOutpost"))
            manager.tpCostToOutpostCopper = tag.getLong("tpCostToOutpost");
        return manager;
    }
}
