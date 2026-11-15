package com.admin82.factions.economy;

import com.admin82.factions.Config;
import com.admin82.factions.registry.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;
import java.util.stream.Stream;

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
    /** Newest-first money history per player wallet and per faction vault. */
    private final Map<UUID, Deque<Transaction>> walletHistory = new HashMap<>();
    private final Map<UUID, Deque<Transaction>> vaultHistory  = new HashMap<>();
    /** Repeating charges (upkeep) are merged into one entry for this long. */
    private static final long MERGE_WINDOW_MS = 3_600_000L;
    /** Factions whose vault ran dry, claims still exist but have NO protection. */
    private final Set<UUID>       insolventFactions    = new HashSet<>();

    // Singleton access

    public static EconomyManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new Factory<>(EconomyManager::new, EconomyManager::load),
            DATA_NAME
        );
    }

    // Player wallets

    public long getWallet(UUID player) {
        return playerWallets.getOrDefault(player, 0L);
    }

    /** Adds without wrapping around: a huge balance caps out instead of going negative and resetting to 0. */
    private static long saturatedAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) return a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        return r;
    }

    public void setWallet(UUID player, long copper) {
        playerWallets.put(player, Math.max(0, copper));
        setDirty();
    }

    public boolean deductWallet(UUID player, long cost) {
        return deductWallet(player, cost, "Other");
    }

    public void addWallet(UUID player, long copper) {
        addWallet(player, copper, "Other");
    }

    public boolean deductWallet(UUID player, long cost, String reason) {
        long bal = getWallet(player);
        if (cost < 0 || bal < cost) return false;
        setWallet(player, bal - cost);
        logWallet(player, -cost, reason);
        return true;
    }

    public void addWallet(UUID player, long copper, String reason) {
        setWallet(player, saturatedAdd(getWallet(player), copper));
        logWallet(player, copper, reason);
    }

    public void logWallet(UUID player, long delta, String reason) {
        if (delta == 0) return;
        Transaction.push(walletHistory.computeIfAbsent(player, ignored -> new ArrayDeque<>()),
                new Transaction(System.currentTimeMillis(), delta, getWallet(player), reason, ItemStack.EMPTY));
        setDirty();
    }

    public List<Transaction> getWalletHistory(UUID player) {
        return List.copyOf(walletHistory.getOrDefault(player, new ArrayDeque<>()));
    }

    // Faction vaults

    public long getVault(UUID faction) {
        return factionVaults.getOrDefault(faction, 0L);
    }

    public void setVault(UUID faction, long copper) {
        factionVaults.put(faction, Math.max(0, copper));
        setDirty();
    }

    public boolean deductVault(UUID faction, long cost) {
        return deductVault(faction, cost, "Other");
    }

    public void addVault(UUID faction, long copper) {
        addVault(faction, copper, "Other");
    }

    public boolean deductVault(UUID faction, long cost, String reason) {
        long bal = getVault(faction);
        if (cost < 0 || bal < cost) return false;
        setVault(faction, bal - cost);
        logVault(faction, -cost, reason, false);
        return true;
    }

    public void addVault(UUID faction, long copper, String reason) {
        setVault(faction, saturatedAdd(getVault(faction), copper));
        logVault(faction, copper, reason, false);
    }

    /**
     * Records a vault change. With {@code merge}, a change with the same reason within the last hour is
     * added to the previous entry instead (so small repeating upkeep charges stay one readable line).
     */
    public void logVault(UUID faction, long delta, String reason, boolean merge) {
        if (delta == 0) return;
        Deque<Transaction> history = vaultHistory.computeIfAbsent(faction, ignored -> new ArrayDeque<>());
        Transaction last = history.peekFirst();
        long now = System.currentTimeMillis();
        if (merge && last != null && last.text().equals(reason) && now - last.time() < MERGE_WINDOW_MS) {
            history.removeFirst();
            history.addFirst(last.withAmount(last.amount() + delta, getVault(faction)));
        } else {
            Transaction.push(history, new Transaction(now, delta, getVault(faction), reason, ItemStack.EMPTY));
        }
        setDirty();
    }

    public List<Transaction> getVaultHistory(UUID faction) {
        return List.copyOf(vaultHistory.getOrDefault(faction, new ArrayDeque<>()));
    }

    public void removeFactionData(UUID faction) {
        factionVaults.remove(faction);
        vaultHistory.remove(faction);
        upkeepPeriodStart.remove(faction);
        upkeepChargedSoFar.remove(faction);
        insolventFactions.remove(faction);
        setDirty();
    }

    // Claim and outpost prices. These live in the config file; see Config.

    public double getClaimRateMultiplier() { return Config.CLAIM_RATE.get(); }

    public void setClaimRateMultiplier(double rate) {
        Config.set(Config.CLAIM_RATE, Math.max(1.0, Math.min(10.0, rate)));
    }

    public static long computeClaimCost(long baseCost, int ownedChunks, double claimRateMultiplier) {
        return (long) (baseCost * Math.pow(Math.max(1.0, claimRateMultiplier), ownedChunks));
    }

    /** Extra outpost upkeep per 5 chunks from the faction table (0.1 = +10%). */
    public double getOutpostDistanceRamp() { return Config.OUTPOST_DISTANCE_RAMP.get(); }

    public void setOutpostDistanceRamp(double ramp) {
        Config.set(Config.OUTPOST_DISTANCE_RAMP, Math.max(0.0, Math.min(5.0, ramp)));
    }

    public long getTpCostToOutpost() { return Config.OUTPOST_TELEPORT_COST_COPPER.get(); }

    public void setTpCostToOutpost(long copper) {
        Config.set(Config.OUTPOST_TELEPORT_COST_COPPER, (int) Math.max(0, Math.min(1_000_000, copper)));
    }

    // Upkeep: gradual drain + solvent/insolvent state

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
     * Returns {@code false} if the vault ran dry (insolvent), land is still claimed
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

    // Physical coin helpers (server-side)

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

    /**
     * Gives physical coin items to the player's inventory (largest denominations first). Coins that don't
     * fit go into the player's wallet instead of being dropped, so a big withdrawal can't flood the ground.
     */
    public static void giveCopperToInventory(net.minecraft.world.entity.player.Player player, long copper) {
        long remaining = copper;
        long plat = remaining / Currency.COPPER_PER_PLATINUM; remaining %= Currency.COPPER_PER_PLATINUM;
        long gold = remaining / Currency.COPPER_PER_GOLD;     remaining %= Currency.COPPER_PER_GOLD;
        long silv = remaining / Currency.COPPER_PER_SILVER;   remaining %= Currency.COPPER_PER_SILVER;
        long cop  = remaining;
        long leftover = giveCoins(player, ModItems.PLATINUM_COIN.get(), plat) * Currency.COPPER_PER_PLATINUM
                + giveCoins(player, ModItems.GOLD_COIN.get(), gold) * Currency.COPPER_PER_GOLD
                + giveCoins(player, ModItems.SILVER_COIN.get(), silv) * Currency.COPPER_PER_SILVER
                + giveCoins(player, ModItems.COPPER_COIN.get(), cop);
        if (leftover <= 0) return;
        if (player instanceof ServerPlayer sp) {
            get(sp.server).addWallet(sp.getUUID(), leftover, "Coins that didn't fit in your inventory");
            sp.displayClientMessage(Component.literal("§eYour inventory was full: §a"
                    + Currency.format(leftover) + " §ewent to your wallet instead."), false);
        } else {
            player.drop(new ItemStack(ModItems.COPPER_COIN.get(), (int) Math.min(leftover, 64)), false);
        }
    }

    /**
     * Gives {@code count} coins of one type ("Copper", "Silver", "Gold" or "Platinum", see {@link Currency#COINS}).
     * Returns how many didn't fit in the inventory.
     */
    public static long giveCoinsOfType(net.minecraft.world.entity.player.Player player, String coin, long count) {
        Item item = switch (coin) {
            case "Platinum" -> ModItems.PLATINUM_COIN.get();
            case "Gold" -> ModItems.GOLD_COIN.get();
            case "Silver" -> ModItems.SILVER_COIN.get();
            default -> ModItems.COPPER_COIN.get();
        };
        return giveCoins(player, item, count);
    }

    /** Adds coins until the inventory is full. Returns how many coins didn't fit. */
    private static long giveCoins(net.minecraft.world.entity.player.Player player, Item coinItem, long count) {
        while (count > 0) {
            int stackSize = (int) Math.min(count, coinItem.getDefaultMaxStackSize());
            ItemStack coins = new ItemStack(coinItem, stackSize);
            player.getInventory().add(coins);
            count -= stackSize - coins.getCount();
            if (!coins.isEmpty()) return count;
        }
        return 0;
    }

    public static boolean isCoin(Item item) {
        return item == ModItems.COPPER_COIN.get()
            || item == ModItems.SILVER_COIN.get()
            || item == ModItems.GOLD_COIN.get()
            || item == ModItems.PLATINUM_COIN.get();
    }

    // Leaderboard

    /** Vault balance for a faction (used for leaderboard). */
    public long getFactionVaultBalance(UUID faction) {
        return getVault(faction);
    }

    // Serialization

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
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

        tag.put("walletHistory", saveHistory(walletHistory, registries));
        tag.put("vaultHistory", saveHistory(vaultHistory, registries));

        return tag;
    }

    private static EconomyManager load(CompoundTag tag, HolderLookup.Provider reg) {
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
        // Settings used to be saved here; move them to the config.
        if (tag.getDouble("claimRateMultiplier") > 0)
            Config.migrate(Config.CLAIM_RATE, Math.max(1.0, Math.min(10.0, tag.getDouble("claimRateMultiplier"))));
        if (tag.getDouble("outpostDistanceRamp") > 0)
            Config.migrate(Config.OUTPOST_DISTANCE_RAMP, Math.min(5.0, tag.getDouble("outpostDistanceRamp")));
        if (tag.contains("tpCostToOutpost"))
            Config.migrate(Config.OUTPOST_TELEPORT_COST_COPPER, (int) Math.max(0, Math.min(1_000_000, tag.getLong("tpCostToOutpost"))));
        loadHistory(tag.getList("walletHistory", Tag.TAG_COMPOUND), manager.walletHistory, reg);
        loadHistory(tag.getList("vaultHistory", Tag.TAG_COMPOUND), manager.vaultHistory, reg);
        // Old settings were copied to the config above; save once more so they leave the world file.
        if (Stream.of("claimRateMultiplier", "outpostDistanceRamp", "tpCostToOutpost").anyMatch(tag::contains)) manager.setDirty();
        return manager;
    }

    static ListTag saveHistory(Map<UUID, Deque<Transaction>> histories, HolderLookup.Provider registries) {
        var list = new ListTag();
        histories.forEach((owner, entries) -> {
            if (entries.isEmpty()) return;
            var ownerTag = new CompoundTag();
            ownerTag.putUUID("owner", owner);
            var entryList = new ListTag();
            entries.forEach(entry -> entryList.add(entry.save(registries)));
            ownerTag.put("entries", entryList);
            list.add(ownerTag);
        });
        return list;
    }

    static void loadHistory(ListTag list, Map<UUID, Deque<Transaction>> into, HolderLookup.Provider registries) {
        for (int i = 0; i < list.size(); i++) {
            var ownerTag = list.getCompound(i);
            if (!ownerTag.hasUUID("owner")) continue;
            Deque<Transaction> entries = new ArrayDeque<>();
            var entryList = ownerTag.getList("entries", Tag.TAG_COMPOUND);
            for (int e = 0; e < entryList.size() && e < Transaction.MAX_ENTRIES; e++) {
                entries.addLast(Transaction.load(entryList.getCompound(e), registries));
            }
            into.put(ownerTag.getUUID("owner"), entries);
        }
    }
}
