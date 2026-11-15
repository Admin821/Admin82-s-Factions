package com.admin82.factions;

import com.admin82.factions.network.packet.SyncServerConfigPacket;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server settings, saved in config/adminsfactions-common.toml.
 *
 * Everything here can also be changed in game with /faction admin. Changes made there
 * are written straight back to the file. Money is in copper: 100 copper = 1 silver,
 * 100 silver = 1 gold.
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Land
    public static final ModConfigSpec.IntValue CLAIM_COST_COPPER;
    public static final ModConfigSpec.DoubleValue CLAIM_RATE;
    public static final ModConfigSpec.IntValue UPKEEP_PER_CLAIM_PER_DAY;
    public static final ModConfigSpec.IntValue UPKEEP_INTERVAL_HOURS;
    public static final ModConfigSpec.IntValue MAX_CLAIMS_PER_FACTION;

    // Market
    public static final ModConfigSpec.IntValue MAX_LISTINGS_PER_PLAYER;
    public static final ModConfigSpec.IntValue MARKET_TAX_BIN_PERCENT;
    public static final ModConfigSpec.IntValue MARKET_TAX_AUCTION_4H;
    public static final ModConfigSpec.IntValue MARKET_TAX_AUCTION_8H;
    public static final ModConfigSpec.IntValue MARKET_TAX_AUCTION_12H;
    public static final ModConfigSpec.IntValue MARKET_TAX_AUCTION_16H;
    public static final ModConfigSpec.IntValue MARKET_TAX_AUCTION_24H;
    public static final ModConfigSpec.BooleanValue EXCHANGE_SELLS_ITEMS;

    // Wars
    public static final ModConfigSpec.IntValue WAR_GRACE_PERIOD_SECONDS;
    public static final ModConfigSpec.IntValue WAR_COOLDOWN_SECONDS;
    public static final ModConfigSpec.IntValue WAR_MIN_DEFENDERS_ONLINE_PERCENT;
    public static final ModConfigSpec.IntValue WAR_CAPTURE_TIME_SECONDS;
    public static final ModConfigSpec.IntValue WAR_OUTPOST_CAPTURE_TIME_SECONDS;
    public static final ModConfigSpec.IntValue WAR_CAPTURE_RADIUS_BLOCKS;
    public static final ModConfigSpec.BooleanValue WAR_TELEPORT_ATTACKERS;
    public static final ModConfigSpec.IntValue WAR_TELEPORT_DISTANCE_CHUNKS;
    public static final ModConfigSpec.BooleanValue WAR_BOUNDARY_ENABLED;
    public static final ModConfigSpec.IntValue WAR_BOUNDARY_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue WAR_ATTACKER_LIVES;
    public static final ModConfigSpec.IntValue WAR_DEFENDER_LIVES;
    public static final ModConfigSpec.IntValue WAR_DEFENDER_LIVES_NO_UPKEEP;
    public static final ModConfigSpec.IntValue WAR_RESOURCE_BLOCK_LIMIT;

    // Outposts and /factionreturn
    public static final ModConfigSpec.DoubleValue OUTPOST_DISTANCE_RAMP;
    public static final ModConfigSpec.IntValue OUTPOST_TELEPORT_COST_COPPER;
    public static final ModConfigSpec.IntValue OUTPOST_UPKEEP_COPPER;
    public static final ModConfigSpec.IntValue OUTPOST_CAPTURE_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue OUTPOST_UNSUPPLIED_GRACE_HOURS;
    public static final ModConfigSpec.IntValue OUTPOST_FLAG_HP;
    public static final ModConfigSpec.IntValue OUTPOST_FLAG_REGEN_MINUTES;
    public static final ModConfigSpec.IntValue OUTPOST_STORAGE_KINDS;
    public static final ModConfigSpec.IntValue OUTPOST_STORAGE_PER_KIND;
    public static final ModConfigSpec.IntValue OUTPOST_UPGRADE_WARBONDS;

    // Faction item vault
    public static final ModConfigSpec.IntValue ITEM_VAULT_SLOTS;
    public static final ModConfigSpec.IntValue ITEM_VAULT_SLOTS_PER_LEVEL;
    public static final ModConfigSpec.IntValue ITEM_VAULT_MAX_LEVEL;
    public static final ModConfigSpec.IntValue ITEM_VAULT_UPGRADE_WARBONDS;

    // Warbonds and faction upgrades
    public static final ModConfigSpec.IntValue WARBOND_WAR_REWARD;
    public static final ModConfigSpec.IntValue LAND_UPGRADE_WARBONDS;
    public static final ModConfigSpec.IntValue LAND_BASE_RADIUS;
    public static final ModConfigSpec.DoubleValue LAND_UPGRADE_RAMP;
    public static final ModConfigSpec.IntValue OUTPOST_SLOT_WARBONDS;
    public static final ModConfigSpec.IntValue CORE_TRANSFER_PERCENT;

    // Power and fluid meters
    public static final ModConfigSpec.IntValue METER_POWER_CAPACITY;
    public static final ModConfigSpec.IntValue METER_FLUID_CAPACITY;
    public static final ModConfigSpec.IntValue METER_POWER_RATE;
    public static final ModConfigSpec.IntValue METER_FLUID_RATE;
    public static final ModConfigSpec.IntValue METER_EFFICIENCY_WARBONDS;
    public static final ModConfigSpec.IntValue METER_EFFICIENCY_MAX;
    public static final ModConfigSpec.IntValue METER_EFFICIENCY_PERCENT;
    public static final ModConfigSpec.IntValue METER_COIN_CAP;

    // Seams (fake ore veins mined by Seam Extractors)
    /** Chance (out of 100) of each kind of seam: overflowing, rich, middling, poor, depleted, endless. */
    public static final ModConfigSpec.IntValue[] SEAM_CHANCE = new ModConfigSpec.IntValue[6];
    /** Endless seams: chance of each speed (overflowing, rich, middling, poor). */
    public static final ModConfigSpec.IntValue[] SEAM_ENDLESS_CHANCE = new ModConfigSpec.IntValue[4];
    /** Seconds per ore at each richness (overflowing, rich, middling, poor). */
    public static final ModConfigSpec.IntValue[] SEAM_SECONDS = new ModConfigSpec.IntValue[4];
    /** Ore in a seam of each richness before it drops to the next (overflowing, rich, middling, poor). */
    public static final ModConfigSpec.IntValue[] SEAM_AMOUNT = new ModConfigSpec.IntValue[4];
    public static final ModConfigSpec.IntValue SEAM_MIN_GAP;
    public static final ModConfigSpec.IntValue SEAM_MAX_GAP;
    public static final ModConfigSpec.IntValue SEAM_SPAWN_CHANCE;
    public static final ModConfigSpec.IntValue SEAM_POWER;
    public static final ModConfigSpec.IntValue SEAM_MINERS_WARBONDS;
    public static final ModConfigSpec.IntValue SEAM_PATH_WARBONDS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> SEAM_ORE_BLACKLIST;
    public static final ModConfigSpec.IntValue RETURN_COOLDOWN_SECONDS;
    public static final ModConfigSpec.IntValue RETURN_COMBAT_LOCK_SECONDS;

    // Alliances, vassals, bounties
    public static final ModConfigSpec.IntValue ALLIANCE_BREAK_COOLDOWN_HOURS;
    public static final ModConfigSpec.IntValue LEAVE_COOLDOWN_HOURS;
    public static final ModConfigSpec.IntValue VASSAL_TAX_PERCENT;
    public static final ModConfigSpec.IntValue VASSAL_BUYOUT_COPPER;
    public static final ModConfigSpec.IntValue BOUNTY_MIN_COPPER;
    public static final ModConfigSpec.IntValue BOUNTY_MAX_DAYS;
    public static final ModConfigSpec.IntValue BOUNTY_MAX_ACTIVE_PER_PLAYER;
    public static final ModConfigSpec.IntValue BOUNTY_TAX_PERCENT;

    // Monument guards
    public static final ModConfigSpec.BooleanValue MONUMENT_PMCS_ENABLED;
    public static final ModConfigSpec.DoubleValue MONUMENT_PMC_CROUCH_EXPOSURE;
    public static final ModConfigSpec.DoubleValue MONUMENT_PMC_PRONE_EXPOSURE;

    static {
        BUILDER.push("economy");
        CLAIM_COST_COPPER = BUILDER
                .comment("Price of a faction's first chunk, in copper.")
                .defineInRange("claimCostCopper", 100, 1, Integer.MAX_VALUE);

        CLAIM_RATE = BUILDER
                .comment("Each chunk after the first costs the base price times this number to the power of",
                         "chunks already owned. 1.0 means every chunk costs the same.")
                .defineInRange("claimRateMultiplier", 1.4, 1.0, 10.0);

        UPKEEP_PER_CLAIM_PER_DAY = BUILDER
                .comment("Upkeep for each claimed chunk, per day, in copper.")
                .defineInRange("upkeepPerClaimPerDay", 20, 0, Integer.MAX_VALUE);

        UPKEEP_INTERVAL_HOURS = BUILDER
                .comment("How many hours one upkeep period lasts.")
                .defineInRange("upkeepIntervalHours", 24, 1, 720);

        MAX_CLAIMS_PER_FACTION = BUILDER
                .comment("Not used any more: land is limited by a radius around the faction table (warbonds.landBaseRadius).")
                .defineInRange("maxClaimsPerFaction", 50, 0, 10000);

        BUILDER.pop();

        BUILDER.push("market");
        MAX_LISTINGS_PER_PLAYER = BUILDER
                .comment("Most listings one player can have up at once.")
                .defineInRange("maxListingsPerPlayer", 5, 1, 100);

        MARKET_TAX_BIN_PERCENT = BUILDER
                .comment("Tax taken from Buy Now sales and from selling into a player's buy order, in percent.")
                .defineInRange("marketTaxBinPercent", 10, 0, 100);

        MARKET_TAX_AUCTION_4H = BUILDER
                .comment("Auction tax in percent, by auction length. Longer auctions pay more.")
                .defineInRange("marketTaxAuction4h", 10, 0, 100);

        MARKET_TAX_AUCTION_8H = BUILDER.defineInRange("marketTaxAuction8h", 12, 0, 100);

        MARKET_TAX_AUCTION_12H = BUILDER.defineInRange("marketTaxAuction12h", 16, 0, 100);

        MARKET_TAX_AUCTION_16H = BUILDER.defineInRange("marketTaxAuction16h", 20, 0, 100);

        MARKET_TAX_AUCTION_24H = BUILDER.defineInRange("marketTaxAuction24h", 24, 0, 100);

        EXCHANGE_SELLS_ITEMS = BUILDER
                .comment("Let players buy items from the Currency Exchange, not just sell to it.")
                .define("exchangeSellsItems", false);

        BUILDER.pop();

        BUILDER.push("war");
        WAR_GRACE_PERIOD_SECONDS = BUILDER
                .comment("Seconds between declaring war and the fighting starting.")
                .defineInRange("gracePeriodSeconds", 600, 0, 86400);

        WAR_COOLDOWN_SECONDS = BUILDER
                .comment("Seconds before an attacker can declare war on the same faction again.")
                .defineInRange("cooldownSeconds", 86400, 0, 604800);

        WAR_MIN_DEFENDERS_ONLINE_PERCENT = BUILDER
                .comment("Percent of a faction's members that must be online before anyone can declare war on it.")
                .defineInRange("minDefendersOnlinePercent", 50, 0, 100);

        WAR_CAPTURE_TIME_SECONDS = BUILDER
                .comment("Seconds attackers must hold the faction table to win.")
                .defineInRange("captureTimeSeconds", 300, 1, 86400);

        WAR_OUTPOST_CAPTURE_TIME_SECONDS = BUILDER
                .comment("Seconds attackers must hold an outpost to destroy it.")
                .defineInRange("outpostCaptureTimeSeconds", 60, 1, 86400);

        WAR_CAPTURE_RADIUS_BLOCKS = BUILDER
                .comment("Size of the capture area around the faction table, in blocks.")
                .defineInRange("captureRadiusBlocks", 25, 5, 200);

        WAR_TELEPORT_ATTACKERS = BUILDER
                .comment("Teleport attackers near the defenders' land when the grace period ends.")
                .define("teleportAttackers", false);

        WAR_TELEPORT_DISTANCE_CHUNKS = BUILDER
                .comment("How far from the defenders' land attackers are placed, in chunks.")
                .defineInRange("teleportDistanceChunks", 5, 1, 50);

        WAR_BOUNDARY_ENABLED = BUILDER
                .comment("Attackers who leave the war area lose all their lives.")
                .define("boundaryEnabled", false);

        WAR_BOUNDARY_RADIUS_BLOCKS = BUILDER
                .comment("Size of the war area around the defenders' faction table, in blocks.")
                .defineInRange("boundaryRadiusBlocks", 300, 50, 10000);

        WAR_ATTACKER_LIVES = BUILDER
                .comment("Lives each attacker gets per war.")
                .defineInRange("attackerLives", 3, 1, 20);

        WAR_DEFENDER_LIVES = BUILDER
                .comment("Lives each defender gets while their faction's upkeep is paid.")
                .defineInRange("defenderLives", 3, 1, 20);

        WAR_DEFENDER_LIVES_NO_UPKEEP = BUILDER
                .comment("Lives each defender gets when their faction's vault is empty.")
                .defineInRange("defenderLivesNoUpkeep", 1, 1, 20);

        WAR_RESOURCE_BLOCK_LIMIT = BUILDER
                .comment("Blocks the winner of a resource war may break in the loser's land.")
                .defineInRange("resourceWarBlockLimit", 50, 1, 1000);

        BUILDER.pop();

        BUILDER.push("outposts");
        OUTPOST_DISTANCE_RAMP = BUILDER
                .comment("Extra outpost upkeep for every 5 chunks between the outpost and the faction table.",
                         "0.1 means +10% per 5 chunks.")
                .defineInRange("upkeepDistanceRamp", 0.1, 0.0, 5.0);

        OUTPOST_TELEPORT_COST_COPPER = BUILDER
                .comment("Price of teleporting to an outpost, in copper.")
                .defineInRange("teleportCostCopper", 1000, 0, 1_000_000);

        OUTPOST_UPKEEP_COPPER = BUILDER
                .comment("Daily outpost upkeep in copper, before the distance ramp.")
                .defineInRange("upkeepCopper", 500, 0, 10_000_000);

        OUTPOST_CAPTURE_RADIUS_BLOCKS = BUILDER
                .comment("Size of the capture area around an outpost, in blocks.")
                .defineInRange("captureRadiusBlocks", 20, 3, 100);

        OUTPOST_UNSUPPLIED_GRACE_HOURS = BUILDER
                .comment("When upkeep can't be paid, the outpost is unsupplied for this many hours before it falls apart.",
                         "Payment is retried every hour. (Outposts can be captured at any time by any faction except the",
                         "owner's allies, whether supplied or not.)")
                .defineInRange("unsuppliedGraceHours", 24, 0, 24 * 14);

        OUTPOST_FLAG_HP = BUILDER
                .comment("Hits the flag of a level 1 outpost takes before it's captured. Each level adds half again.")
                .defineInRange("flagHp", 50, 1, 10000);

        OUTPOST_FLAG_REGEN_MINUTES = BUILDER
                .comment("A damaged flag goes back to full health after this many minutes without being hit.")
                .defineInRange("flagRegenMinutes", 5, 1, 1440);

        OUTPOST_STORAGE_KINDS = BUILDER
                .comment("Different items a level 1 outpost storage box holds. Each level adds 5.")
                .defineInRange("storageKinds", 15, 1, 100);

        OUTPOST_STORAGE_PER_KIND = BUILDER
                .comment("How many of each item a level 1 storage box holds. Doubles every level.")
                .defineInRange("storagePerKind", 150, 1, 1_000_000);

        OUTPOST_UPGRADE_WARBONDS = BUILDER
                .comment("Warbonds to upgrade an outpost. Level n to n+1 costs this × n.")
                .defineInRange("upgradeWarbonds", 3, 0, 10000);

        BUILDER.pop();

        BUILDER.push("itemVault");
        ITEM_VAULT_SLOTS = BUILDER
                .comment("Slots in a faction's item vault at level 1. One of them is a secure slot.")
                .defineInRange("slots", 27, 9, 270);

        ITEM_VAULT_SLOTS_PER_LEVEL = BUILDER
                .comment("Slots each upgrade adds. Every upgrade also adds one secure slot.")
                .defineInRange("slotsPerLevel", 10, 1, 90);

        ITEM_VAULT_MAX_LEVEL = BUILDER
                .comment("Highest item vault level.")
                .defineInRange("maxLevel", 6, 1, 20);

        ITEM_VAULT_UPGRADE_WARBONDS = BUILDER
                .comment("Warbonds to upgrade the item vault. Level n to n+1 costs this × n.")
                .defineInRange("upgradeWarbonds", 2, 0, 10000);

        BUILDER.pop();

        BUILDER.push("warbonds");
        WARBOND_WAR_REWARD = BUILDER
                .comment("Warbonds the winning faction of a war gets, put in its item vault.",
                         "Warbonds pay for every faction upgrade (Faction Upgrades tab on the faction table).")
                .defineInRange("warWinReward", 5, 0, 10000);

        LAND_BASE_RADIUS = BUILDER
                .comment("How far from the faction table (in chunks, in every direction) a faction can claim land.",
                         "Each land upgrade adds 1. There is no limit to how many land upgrades a faction can buy.")
                .defineInRange("landBaseRadius", 3, 1, 1000);

        LAND_UPGRADE_WARBONDS = BUILDER
                .comment("Warbonds for the first land upgrade.")
                .defineInRange("landUpgradeWarbonds", 3, 0, 10000);

        LAND_UPGRADE_RAMP = BUILDER
                .comment("Each land upgrade costs the previous one times this (1.5 = 3, 5, 7, 11, 16 ... warbonds).")
                .defineInRange("landUpgradeRamp", 1.5, 1.0, 10.0);

        OUTPOST_SLOT_WARBONDS = BUILDER
                .comment("Warbonds for each extra outpost slot. Slot n costs this × n.")
                .defineInRange("outpostSlotWarbonds", 5, 0, 10000);

        CORE_TRANSFER_PERCENT = BUILDER
                .comment("When a faction's core is taken and it has an outpost, each slot of its item vault has this percent",
                         "chance for its stack to move to the outpost's storage box (secure slots always move).",
                         "The rest is left for the winners to loot.")
                .defineInRange("coreTransferPercent", 25, 0, 100);

        BUILDER.pop();


        BUILDER.push("meters");
        METER_POWER_CAPACITY = BUILDER
                .comment("FE a power meter's battery holds at Meter Efficiency level 1.")
                .defineInRange("powerCapacityBase", 10_000, 1000, Integer.MAX_VALUE);

        METER_FLUID_CAPACITY = BUILDER
                .comment("mB a fluid meter's tank holds at Meter Efficiency level 1 (1000 mB = 1 bucket).")
                .defineInRange("fluidCapacityBase", 10_000, 1000, Integer.MAX_VALUE);

        METER_POWER_RATE = BUILDER
                .comment("FE per tick a meter can buy from a network, before Meter Efficiency upgrades.")
                .defineInRange("powerPerTick", 256, 1, 1_000_000);

        METER_FLUID_RATE = BUILDER
                .comment("mB per second a meter can buy from a network, before Meter Efficiency upgrades.")
                .defineInRange("fluidPerSecond", 1000, 20, 1_000_000);

        METER_EFFICIENCY_WARBONDS = BUILDER
                .comment("Warbonds for a Meter Efficiency upgrade. Level n to n+1 costs this × n.")
                .defineInRange("efficiencyWarbonds", 3, 0, 10000);

        METER_EFFICIENCY_MAX = BUILDER
                .comment("Highest Meter Efficiency level. Level 1 is where every faction starts.")
                .defineInRange("efficiencyMaxLevel", 10, 1, 50);

        METER_EFFICIENCY_PERCENT = BUILDER
                .comment("Percent each Meter Efficiency level above 1 adds to a meter's buy rate and battery/tank size.",
                         "At 50, level 2 is +50%, level 3 is +100% (double) and level 10 is +450%.")
                .defineInRange("efficiencyPercentPerLevel", 50, 0, 1000);

        METER_COIN_CAP = BUILDER
                .comment("Most coins (in copper) a meter's coin storage holds at Meter Efficiency level 1; each level adds",
                         "efficiencyPercentPerLevel on top. Supplier meters stop selling when theirs is full, until it's emptied.",
                         "50000 = 5 gold.")
                .defineInRange("coinCapBase", 50_000, 100, Integer.MAX_VALUE);

        BUILDER.pop();

        BUILDER.push("seams");
        SEAM_MIN_GAP = BUILDER
                .comment("Fewest empty chunks between a seam and its neighbours (north, south, east, west).")
                .defineInRange("minEmptyChunks", 5, 0, 64);
        SEAM_MAX_GAP = BUILDER
                .comment("Most empty chunks between a seam and its neighbours, when spawnChance is 100 (lower chances leave",
                         "some seams out, so gaps can be bigger). Raised to minEmptyChunks if it's below it. Seams sit on a grid,",
                         "so when max - min is odd the real max is one less (5 and 8 behave like 5 and 7).")
                .defineInRange("maxEmptyChunks", 7, 0, 128);

        SEAM_SPAWN_CHANCE = BUILDER
                .comment("Percent chance that each seam spot actually has a seam. Below 100 some are left out, so gaps",
                         "between seams can be bigger than maxEmptyChunks.")
                .defineInRange("spawnChance", 100, 0, 100);
        String[] kinds = {"overflowing", "rich", "middling", "poor", "depleted", "endless"};
        int[] chances = {10, 20, 30, 20, 10, 10};
        for (int i = 0; i < 6; i++) {
            SEAM_CHANCE[i] = BUILDER
                    .comment("Chance (out of all six chances added up, normally 100) that a seam is " + kinds[i] + ".")
                    .defineInRange("chance_" + kinds[i], chances[i], 0, 1000);
        }
        int[] endless = {10, 20, 30, 40};
        for (int i = 0; i < 4; i++) {

            SEAM_ENDLESS_CHANCE[i] = BUILDER
                    .comment("Endless seams never run out. Chance that one mines at " + new String[]{"exceptional", "accelerated", "middling", "poor"}[i]
                            + " speed (as fast as a " + kinds[i] + " seam).")
                    .defineInRange("endlessChance_" + kinds[i], endless[i], 0, 1000);
        }
        int[] seconds = {15, 30, 45, 60};
        int[] amounts = {2000, 3000, 4000, 5000};
        for (int i = 0; i < 4; i++) {
            SEAM_SECONDS[i] = BUILDER
                    .comment("Seconds a Seam Extractor takes per ore on a " + kinds[i] + " seam, before upgrades.")
                    .defineInRange("seconds_" + kinds[i], seconds[i], 1, 86400);
            SEAM_AMOUNT[i] = BUILDER
                    .comment("Ore a " + kinds[i] + " seam gives before it drops to the next richness.")
                    .defineInRange("amount_" + kinds[i], amounts[i], 1, Integer.MAX_VALUE);
        }

        SEAM_POWER = BUILDER
                .comment("FE per tick a Seam Extractor uses while mining, before the faction's seam upgrade doubles it (50 = 1,000 FE a second).")
                .defineInRange("extractorPowerPerTick", 50, 0, Integer.MAX_VALUE);

        SEAM_MINERS_WARBONDS = BUILDER
                .comment("Warbonds for Seam Extractor Quantity: going from n to n+1 extractors per seam costs this × n.")
                .defineInRange("minersUpgradeWarbonds", 5, 0, 10000);

        SEAM_PATH_WARBONDS = BUILDER
                .comment("Warbonds for a level of the Speed, Quantity or Efficiency seam upgrade: level n costs this × n.")
                .defineInRange("pathUpgradeWarbonds", 4, 0, 10000);

        SEAM_ORE_BLACKLIST = BUILDER
                .comment("Ores that never appear in seams: item ids like \"minecraft:coal_ore\", or \"modid:*\" for a whole mod.")
                .defineListAllowEmpty("oreBlacklist", java.util.List.of(), () -> "", o -> o instanceof String);

        BUILDER.pop();

        BUILDER.push("factionReturn");

        RETURN_COOLDOWN_SECONDS = BUILDER
                .comment("Seconds between uses of /factionreturn.")
                .defineInRange("cooldownSeconds", 30, 0, 86400);

        RETURN_COMBAT_LOCK_SECONDS = BUILDER
                .comment("Seconds a player has to wait after a fight before using /factionreturn.")
                .defineInRange("combatLockSeconds", 15, 0, 86400);

        BUILDER.pop();

        BUILDER.push("alliances");
        ALLIANCE_BREAK_COOLDOWN_HOURS = BUILDER
                .comment("Hours an alliance keeps protecting both sides after it is broken.")
                .defineInRange("breakCooldownHours", 48, 0, 24 * 30);

        LEAVE_COOLDOWN_HOURS = BUILDER
                .comment("Hours after leaving a faction during which a player still can't place or claim bounties on,",
                         "or go to war against, their old faction or the factions it was allied with. 0 turns this off.")
                .defineInRange("leaveCooldownHours", 24, 0, 24 * 30);

        BUILDER.pop();

        BUILDER.push("vassal");
        VASSAL_TAX_PERCENT = BUILDER
                .comment("Percent of a vassal's market sales and claim costs that goes to its overlord.")
                .defineInRange("taxPercent", 15, 0, 100);

        VASSAL_BUYOUT_COPPER = BUILDER
                .comment("What a vassal pays from its vault to become independent, in copper. 5,000,000 = 500 gold.")
                .defineInRange("buyoutCopperValue", 5_000_000, 1000, Integer.MAX_VALUE);

        BUILDER.pop();

        BUILDER.push("bounties");
        BOUNTY_MIN_COPPER = BUILDER
                .comment("Smallest bounty a player can place, in copper.")
                .defineInRange("minimumCopper", 100, 1, Integer.MAX_VALUE);

        BOUNTY_MAX_DAYS = BUILDER
                .comment("Days before a bounty expires and is refunded.")
                .defineInRange("maxDays", 7, 1, 60);

        BOUNTY_MAX_ACTIVE_PER_PLAYER = BUILDER
                .comment("Most bounties one player can have up at once.")
                .defineInRange("maxActivePerPlayer", 5, 1, 100);

        BOUNTY_TAX_PERCENT = BUILDER
                .comment("Percent the server keeps when a bounty is placed. It isn't refunded if the bounty",
                         "is cancelled or expires.")
                .defineInRange("taxPercent", 15, 0, 100);

        BUILDER.pop();

        BUILDER.push("monumentPmcAi");
        MONUMENT_PMCS_ENABLED = BUILDER
                .comment("Spawn Rogue PMC guards when a monument refills.")
                .define("enabled", true);

        MONUMENT_PMC_CROUCH_EXPOSURE = BUILDER
                .comment("How well guards aim at crouching players. 1.0 means no penalty.")
                .defineInRange("crouchExposureMultiplier", 0.65, 0.05, 1.0);

        MONUMENT_PMC_PRONE_EXPOSURE = BUILDER
                .comment("How well guards aim at prone or crawling players. 1.0 means no penalty.")
                .defineInRange("proneExposureMultiplier", 0.35, 0.01, 1.0);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Sets a value and writes the file. */
    public static void set(ModConfigSpec.IntValue value, int v) {
        value.set(v);
        saved();
    }

    public static void set(ModConfigSpec.DoubleValue value, double v) {
        value.set(v);
        saved();
    }

    public static void set(ModConfigSpec.BooleanValue value, boolean v) {
        value.set(v);
        saved();
    }

    public static void set(ModConfigSpec.ConfigValue<java.util.List<? extends String>> value, java.util.List<String> v) {
        value.set(v);
        saved();
    }

    /** Writes the file and tells connected players about the new values. */
    private static void saved() {
        SPEC.save();
        SyncServerConfigPacket.broadcast();
    }

    /**
     * Older versions kept some settings in the world save. When one of those is found,
     * it's copied into the config once; the world stops saving it after that.
     */
    public static void migrate(ModConfigSpec.IntValue value, int v) {
        if (value.get() != v) set(value, v);
    }

    public static void migrate(ModConfigSpec.DoubleValue value, double v) {
        if (value.get() != v) set(value, v);
    }

    public static void migrate(ModConfigSpec.BooleanValue value, boolean v) {
        if (value.get() != v) set(value, v);
    }
}
