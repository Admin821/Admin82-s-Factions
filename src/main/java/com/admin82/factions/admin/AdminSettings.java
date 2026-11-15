package com.admin82.factions.admin;

import com.admin82.factions.Config;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * The settings shown in the admin panel. Each one is a value in the config file.
 * Values are sent as doubles: on/off is 0 or 1, money is in copper.
 */
public final class AdminSettings {

    public enum Kind { INT, DOUBLE, BOOL, MONEY }

    public record Setting(String key, String group, String label, String help, Kind kind, double min, double max,
                          Function<MinecraftServer, Double> getter, BiConsumer<MinecraftServer, Double> setter,
                          @Nullable Function<MinecraftServer, Double> defaultValue) {

        public double get(MinecraftServer server) { return getter.apply(server); }

        /** Clamps to the allowed range, rounds whole-number settings, saves, and returns the saved value. */
        public double set(MinecraftServer server, double value) {
            double v = Math.max(min, Math.min(max, value));
            if (kind != Kind.DOUBLE) v = Math.round(v);
            setter.accept(server, v);
            return get(server);
        }
    }

    private static final List<Setting> SETTINGS = new ArrayList<>();

    public static List<Setting> all() { return Collections.unmodifiableList(SETTINGS); }

    @Nullable
    public static Setting byKey(String key) {
        for (Setting s : SETTINGS) if (s.key.equals(key)) return s;
        return null;
    }

    private AdminSettings() {}

    private static void add(String key, String group, String label, String help, Kind kind, double min, double max,
                            Function<MinecraftServer, Double> getter, BiConsumer<MinecraftServer, Double> setter,
                            Function<MinecraftServer, Double> def) {
        SETTINGS.add(new Setting(key, group, label, help, kind, min, max, getter, setter, def));
    }

    private static void number(String key, String group, String label, String help, Kind kind, ModConfigSpec.IntValue value) {
        add(key, group, label, help, kind, min(value), max(value),
                s -> (double) value.get(),
                (s, v) -> Config.set(value, (int) Math.round(v)),
                s -> (double) value.getDefault());
    }

    private static void decimal(String key, String group, String label, String help, ModConfigSpec.DoubleValue value) {
        add(key, group, label, help, Kind.DOUBLE, min(value), max(value),
                s -> value.get(),
                (s, v) -> Config.set(value, v),
                s -> value.getDefault());
    }

    private static void toggle(String key, String group, String label, String help, ModConfigSpec.BooleanValue value) {
        add(key, group, label, help, Kind.BOOL, 0, 1,
                s -> value.get() ? 1.0 : 0.0,
                (s, v) -> Config.set(value, v >= 0.5),
                s -> value.getDefault() ? 1.0 : 0.0);
    }

    /** The allowed range, read from the config spec. */
    @Nullable
    private static ModConfigSpec.Range<?> range(ModConfigSpec.ConfigValue<?> value) {
        try {
            return Config.SPEC.getSpec().<ModConfigSpec.ValueSpec>get(value.getPath()).getRange();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double min(ModConfigSpec.ConfigValue<?> value) {
        var r = range(value);
        return r != null && r.getMin() instanceof Number n ? n.doubleValue() : 0;
    }

    private static double max(ModConfigSpec.ConfigValue<?> value) {
        var r = range(value);
        return r != null && r.getMax() instanceof Number n ? n.doubleValue() : Integer.MAX_VALUE;
    }

    static {
        String land = "Land & Upkeep";
        number("claimCost", land, "Base claim cost", "Price of a faction's first chunk. Later chunks cost more, see claim rate.", Kind.MONEY, Config.CLAIM_COST_COPPER);
        // Goes through the command so existing claims are repriced and factions get refunded.
        add("claimRate", land, "Claim rate", "Each extra chunk costs base × rate^chunks owned. Changing it reprices every claim and refunds the difference.",
                Kind.DOUBLE, min(Config.CLAIM_RATE), max(Config.CLAIM_RATE),
                s -> Config.CLAIM_RATE.get(),
                (s, v) -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
                        "faction economy claimrates " + v),
                s -> Config.CLAIM_RATE.getDefault());
        number("upkeepPerClaim", land, "Upkeep per chunk per day", "Charged every day for each claimed chunk.", Kind.MONEY, Config.UPKEEP_PER_CLAIM_PER_DAY);
        number("upkeepInterval", land, "Upkeep period (hours)", "How long one upkeep period lasts.", Kind.INT, Config.UPKEEP_INTERVAL_HOURS);

        String market = "Market";
        number("maxListings", market, "Listings per player", "How many listings one player can have up at once.", Kind.INT, Config.MAX_LISTINGS_PER_PLAYER);
        number("taxBin", market, "Buy Now tax %", "Taken from the seller when a Buy Now listing sells.", Kind.INT, Config.MARKET_TAX_BIN_PERCENT);
        number("tax4h", market, "Auction tax % (4h)", "Taken from the seller of a 4 hour auction.", Kind.INT, Config.MARKET_TAX_AUCTION_4H);
        number("tax8h", market, "Auction tax % (8h)", "Taken from the seller of an 8 hour auction.", Kind.INT, Config.MARKET_TAX_AUCTION_8H);
        number("tax12h", market, "Auction tax % (12h)", "Taken from the seller of a 12 hour auction.", Kind.INT, Config.MARKET_TAX_AUCTION_12H);
        number("tax16h", market, "Auction tax % (16h)", "Taken from the seller of a 16 hour auction.", Kind.INT, Config.MARKET_TAX_AUCTION_16H);
        number("tax24h", market, "Auction tax % (24h)", "Taken from the seller of a 24 hour auction.", Kind.INT, Config.MARKET_TAX_AUCTION_24H);
        toggle("exchangeTwoWay", market, "Exchange sells items too", "Players can buy items from the Currency Exchange at its rates.", Config.EXCHANGE_SELLS_ITEMS);

        String wars = "Wars";
        number("gracePeriod", wars, "Grace period (seconds)", "Time between declaring war and the fighting starting.", Kind.INT, Config.WAR_GRACE_PERIOD_SECONDS);
        number("afterWarCooldown", wars, "Cooldown after a war (seconds)", "How long before the same attacker can declare on the same faction again.", Kind.INT, Config.WAR_COOLDOWN_SECONDS);
        number("minOnlinePercent", wars, "Defenders online needed %", "Share of a faction that has to be online before anyone can declare war on it.", Kind.INT, Config.WAR_MIN_DEFENDERS_ONLINE_PERCENT);
        number("tableKoth", wars, "Base capture time (seconds)", "How long attackers have to hold the faction table to win.", Kind.INT, Config.WAR_CAPTURE_TIME_SECONDS);
        number("outpostKoth", wars, "Outpost capture time (seconds)", "How long attackers have to hold an outpost to destroy it.", Kind.INT, Config.WAR_OUTPOST_CAPTURE_TIME_SECONDS);
        number("captureRadius", wars, "Capture radius (blocks)", "Size of the capture area around the faction table.", Kind.INT, Config.WAR_CAPTURE_RADIUS_BLOCKS);
        toggle("warTp", wars, "Teleport attackers", "Move attackers near the defenders' land when the grace period ends.", Config.WAR_TELEPORT_ATTACKERS);
        number("warTpDistance", wars, "Teleport distance (chunks)", "How far from the defenders' land attackers are placed.", Kind.INT, Config.WAR_TELEPORT_DISTANCE_CHUNKS);
        toggle("warBoundary", wars, "War boundary", "Attackers who leave the war area lose all their lives.", Config.WAR_BOUNDARY_ENABLED);
        number("boundaryRadius", wars, "Boundary radius (blocks)", "Size of the war area around the defenders' table.", Kind.INT, Config.WAR_BOUNDARY_RADIUS_BLOCKS);
        number("attackerLives", wars, "Attacker lives", "Lives each attacker gets per war.", Kind.INT, Config.WAR_ATTACKER_LIVES);
        number("defenderLives", wars, "Defender lives", "Lives each defender gets while their upkeep is paid.", Kind.INT, Config.WAR_DEFENDER_LIVES);
        number("defenderLivesNoUpkeep", wars, "Defender lives (unpaid upkeep)", "Lives each defender gets when their vault is empty.", Kind.INT, Config.WAR_DEFENDER_LIVES_NO_UPKEEP);
        number("blockBreakLimit", wars, "Resource war block limit", "Blocks the winner of a resource war may break.", Kind.INT, Config.WAR_RESOURCE_BLOCK_LIMIT);

        String outposts = "Outposts & Return";
        decimal("outpostRamp", outposts, "Outpost upkeep distance ramp", "Extra outpost upkeep for every 5 chunks from the faction table. 0.1 is +10%.", Config.OUTPOST_DISTANCE_RAMP);
        number("outpostTpCost", outposts, "Outpost teleport price", "Charged each time someone teleports to an outpost.", Kind.MONEY, Config.OUTPOST_TELEPORT_COST_COPPER);
        number("outpostUpkeep", outposts, "Outpost upkeep per day", "Before the distance ramp.", Kind.MONEY, Config.OUTPOST_UPKEEP_COPPER);
        number("outpostCaptureRadius", outposts, "Outpost capture radius (blocks)", "Size of the capture area around an outpost.", Kind.INT, Config.OUTPOST_CAPTURE_RADIUS_BLOCKS);
        number("outpostGrace", outposts, "Unsupplied time (hours)", "How long an outpost can go with unpaid upkeep before it falls apart.", Kind.INT, Config.OUTPOST_UNSUPPLIED_GRACE_HOURS);
        number("outpostFlagHp", outposts, "Flag health (level 1)", "Hits it takes to capture a level 1 outpost. Each level adds half again.", Kind.INT, Config.OUTPOST_FLAG_HP);
        number("outpostFlagRegen", outposts, "Flag repair time (minutes)", "A flag goes back to full health after this long without being hit.", Kind.INT, Config.OUTPOST_FLAG_REGEN_MINUTES);
        number("outpostStorageKinds", outposts, "Storage box kinds (level 1)", "Different items a level 1 storage box holds. +5 per level.", Kind.INT, Config.OUTPOST_STORAGE_KINDS);
        number("outpostStoragePer", outposts, "Storage box per item (level 1)", "How many of each item a level 1 box holds. Doubles every level.", Kind.INT, Config.OUTPOST_STORAGE_PER_KIND);
        number("outpostUpgrade", outposts, "Outpost upgrade (warbonds)", "Level n to n+1 costs this × n warbonds.", Kind.INT, Config.OUTPOST_UPGRADE_WARBONDS);
        String itemVault = "Faction Item Vault";
        number("itemVaultSlots", itemVault, "Slots (level 1)", "Item vault slots at level 1, one of them secure.", Kind.INT, Config.ITEM_VAULT_SLOTS);
        number("itemVaultSlotsPer", itemVault, "Slots per upgrade", "Each upgrade also adds one secure slot.", Kind.INT, Config.ITEM_VAULT_SLOTS_PER_LEVEL);
        number("itemVaultMax", itemVault, "Highest level", "How many times the item vault can be upgraded, plus one.", Kind.INT, Config.ITEM_VAULT_MAX_LEVEL);
        number("itemVaultUpgrade", itemVault, "Upgrade (warbonds)", "Level n to n+1 costs this × n warbonds.", Kind.INT, Config.ITEM_VAULT_UPGRADE_WARBONDS);

        String meters = "Power & Fluid Meters";
        number("meterPowerCap", meters, "Battery size (FE)", "FE a power meter holds before upgrades.", Kind.INT, Config.METER_POWER_CAPACITY);
        number("meterFluidCap", meters, "Tank size (mB)", "mB a fluid meter holds before upgrades.", Kind.INT, Config.METER_FLUID_CAPACITY);
        number("meterPowerRate", meters, "Power buy rate (FE/tick)", "Before Meter Efficiency upgrades.", Kind.INT, Config.METER_POWER_RATE);
        number("meterFluidRate", meters, "Fluid buy rate (mB/s)", "Before Meter Efficiency upgrades.", Kind.INT, Config.METER_FLUID_RATE);
        number("meterEffCost", meters, "Meter Efficiency (warbonds)", "Level n to n+1 costs this × n.", Kind.INT, Config.METER_EFFICIENCY_WARBONDS);
        number("meterEffMax", meters, "Meter Efficiency max level", "Highest level a faction can reach.", Kind.INT, Config.METER_EFFICIENCY_MAX);
        number("meterCoinCap", meters, "Meter coin storage (copper)", "Most coins a meter holds at level 1 (50000 = 5 gold).", Kind.INT, Config.METER_COIN_CAP);
        number("meterEffPct", meters, "Meter Efficiency bonus (%)", "Percent each level adds to meter rate and size.", Kind.INT, Config.METER_EFFICIENCY_PERCENT);

        String seams = "Seam settings";
        number("seamMinGap", seams, "Min empty chunks between seams", "Fewest empty chunks between a seam and its neighbours. Changes seams nobody has mined or edited yet.", Kind.INT, Config.SEAM_MIN_GAP);
        number("seamMaxGap", seams, "Max empty chunks between seams", "Most empty chunks between a seam and its neighbours (at a 100% spawn chance).", Kind.INT, Config.SEAM_MAX_GAP);
        number("seamSpawnChance", seams, "Seam spawn chance (%)", "Chance each seam spot really has a seam. Below 100, gaps can be bigger than the max.", Kind.INT, Config.SEAM_SPAWN_CHANCE);
        String[] kinds = {"Overflowing", "Rich", "Middling", "Poor", "Depleted", "Endless"};
        for (int i = 0; i < 6; i++) {
            number("seamChance" + i, seams, kinds[i] + " chance", "Chance a chunk's seam is " + kinds[i].toLowerCase()
                    + ", out of all six chances added up (normally 100). Changes seams nobody has mined or edited yet.", Kind.INT, Config.SEAM_CHANCE[i]);
        }
        for (int i = 0; i < 4; i++) {
            String speed = com.admin82.factions.seam.SeamTier.values()[i].speedLabel;
            number("seamEndless" + i, seams, "Endless: " + speed + " speed", "Chance an endless seam mines at " + speed.toLowerCase()
                    + " speed, out of the four endless chances added up.", Kind.INT, Config.SEAM_ENDLESS_CHANCE[i]);
        }
        for (int i = 0; i < 4; i++) {
            number("seamSeconds" + i, seams, kinds[i] + ": seconds per ore", "How long an extractor takes per ore on a "
                    + kinds[i].toLowerCase() + " seam, before upgrades.", Kind.INT, Config.SEAM_SECONDS[i]);
            number("seamAmount" + i, seams, kinds[i] + ": ore in seam", "Ore a " + kinds[i].toLowerCase()
                    + " seam gives before dropping to the next richness.", Kind.INT, Config.SEAM_AMOUNT[i]);
        }
        number("seamPower", seams, "Extractor power (FE/tick)", "Power a Seam Extractor uses while mining. Each level of the faction's seam upgrade doubles it.", Kind.INT, Config.SEAM_POWER);
        number("seamMinersCost", seams, "Extractor Quantity (warbonds)", "Going from n to n+1 extractors per seam costs this × n.", Kind.INT, Config.SEAM_MINERS_WARBONDS);
        number("seamPathCost", seams, "Speed/Quantity/Efficiency (warbonds)", "Level n of a seam upgrade path costs this × n.", Kind.INT, Config.SEAM_PATH_WARBONDS);

        String warbonds = "Warbonds & Upgrades";
        number("warbondReward", warbonds, "Warbonds for winning a war", "Put in the winning faction's item vault.", Kind.INT, Config.WARBOND_WAR_REWARD);
        number("landRadius", warbonds, "Land radius (chunks)", "How far from the faction table land can be claimed. +1 per land upgrade.", Kind.INT, Config.LAND_BASE_RADIUS);
        number("landUpgrade", warbonds, "First land upgrade (warbonds)", "Each one after costs more, see the ramp.", Kind.INT, Config.LAND_UPGRADE_WARBONDS);
        decimal("landRamp", warbonds, "Land upgrade price ramp", "Each land upgrade costs the previous one times this.", Config.LAND_UPGRADE_RAMP);
        number("outpostSlot", warbonds, "Outpost slot (warbonds)", "Extra outpost slot n costs this × n warbonds.", Kind.INT, Config.OUTPOST_SLOT_WARBONDS);
        number("coreTransfer", warbonds, "Core loss: item transfer chance %", "Chance per item vault slot to reach the outpost when the core is taken (secure slots always do).", Kind.INT, Config.CORE_TRANSFER_PERCENT);
        number("returnCooldown", outposts, "/factionreturn cooldown (seconds)", "Time between uses of /factionreturn.", Kind.INT, Config.RETURN_COOLDOWN_SECONDS);
        number("returnCombat", outposts, "/factionreturn combat lock (seconds)", "How long after a fight before someone can use /factionreturn.", Kind.INT, Config.RETURN_COMBAT_LOCK_SECONDS);

        String diplomacy = "Alliances & Vassals";
        number("allianceCooldown", diplomacy, "Broken alliance cooldown (hours)", "How long an alliance keeps protecting both sides after it's broken.", Kind.INT, Config.ALLIANCE_BREAK_COOLDOWN_HOURS);
        number("leaveCooldown", diplomacy, "Leave cooldown (hours)", "After leaving a faction, how long a player still can't use bounties or wars against their old faction and its allies.", Kind.INT, Config.LEAVE_COOLDOWN_HOURS);
        number("vassalTax", diplomacy, "Vassal tax %", "Share of a vassal's market sales and claim costs paid to its overlord.", Kind.INT, Config.VASSAL_TAX_PERCENT);
        number("vassalBuyout", diplomacy, "Independence price", "What a vassal pays from its vault to become independent.", Kind.MONEY, Config.VASSAL_BUYOUT_COPPER);

        String bounties = "Bounties";
        number("bountyMin", bounties, "Smallest bounty", "Lowest amount a player can put on someone.", Kind.MONEY, Config.BOUNTY_MIN_COPPER);
        number("bountyDays", bounties, "Longest bounty (days)", "After this the bounty expires and is refunded, minus tax.", Kind.INT, Config.BOUNTY_MAX_DAYS);
        number("bountyActive", bounties, "Bounties per player", "How many bounties one player can have up at once.", Kind.INT, Config.BOUNTY_MAX_ACTIVE_PER_PLAYER);
        number("bountyTax", bounties, "Bounty tax %", "Kept by the server when a bounty is placed.", Kind.INT, Config.BOUNTY_TAX_PERCENT);

        String monuments = "Monuments & AI";
        toggle("pmcsEnabled", monuments, "Spawn monument guards", "Spawn Rogue PMCs when a monument refills.", Config.MONUMENT_PMCS_ENABLED);
        decimal("pmcCrouch", monuments, "Guard aim vs. crouching", "How well guards aim at crouching players. 1 means no penalty.", Config.MONUMENT_PMC_CROUCH_EXPOSURE);
        decimal("pmcProne", monuments, "Guard aim vs. prone", "How well guards aim at prone or crawling players. 1 means no penalty.", Config.MONUMENT_PMC_PRONE_EXPOSURE);
    }
}
