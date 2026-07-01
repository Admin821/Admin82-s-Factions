package com.admin82.factions;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * All server-side tunable values for Admin's Factions.
 * Edit adminsfactions-common.toml in the config folder to change these.
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ── Economy ───────────────────────────────────────────────────────────────

    public static final ModConfigSpec.IntValue CLAIM_COST_COPPER;
    public static final ModConfigSpec.IntValue UPKEEP_PER_CLAIM_PER_DAY;
    public static final ModConfigSpec.IntValue UPKEEP_INTERVAL_HOURS;
    public static final ModConfigSpec.IntValue MAX_CLAIMS_PER_FACTION;

    // ── Market ────────────────────────────────────────────────────────────────

    public static final ModConfigSpec.IntValue MAX_LISTINGS_PER_PLAYER;
    public static final ModConfigSpec.IntValue MARKET_TAX_BIN_PERCENT;
    public static final ModConfigSpec.IntValue MARKET_TAX_AUCTION_4H;
    public static final ModConfigSpec.IntValue MARKET_TAX_AUCTION_8H;
    public static final ModConfigSpec.IntValue MARKET_TAX_AUCTION_12H;
    public static final ModConfigSpec.IntValue MARKET_TAX_AUCTION_16H;
    public static final ModConfigSpec.IntValue MARKET_TAX_AUCTION_24H;

    // ── Wars ──────────────────────────────────────────────────────────────────

    public static final ModConfigSpec.IntValue WAR_GRACE_PERIOD_SECONDS;
    public static final ModConfigSpec.IntValue WAR_ATTACKER_LIVES;
    public static final ModConfigSpec.IntValue WAR_DEFENDER_LIVES;
    public static final ModConfigSpec.IntValue WAR_DEFENDER_LIVES_NO_UPKEEP;
    public static final ModConfigSpec.IntValue WAR_CAPTURE_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue WAR_CAPTURE_TIME_SECONDS;
    public static final ModConfigSpec.IntValue WAR_BOUNDARY_RADIUS_BLOCKS;
    public static final ModConfigSpec.BooleanValue WAR_BOUNDARY_ENABLED;

    // ── Vassal ────────────────────────────────────────────────────────────────

    public static final ModConfigSpec.IntValue VASSAL_TAX_PERCENT;
    public static final ModConfigSpec.IntValue VASSAL_BUYOUT_COPPER;

    static {
        BUILDER.push("economy");
        CLAIM_COST_COPPER        = BUILDER.comment("Cost to claim one chunk, in copper coins (100 copper = 1 silver).")
                                          .defineInRange("claimCostCopper", 100, 1, Integer.MAX_VALUE);
        UPKEEP_PER_CLAIM_PER_DAY = BUILDER.comment("Daily upkeep cost per claimed chunk, in copper coins.")
                                          .defineInRange("upkeepPerClaimPerDay", 20, 0, Integer.MAX_VALUE);
        UPKEEP_INTERVAL_HOURS    = BUILDER.comment("Hours between upkeep charges (default 24 = once per real day).")
                                          .defineInRange("upkeepIntervalHours", 24, 1, 720);
        MAX_CLAIMS_PER_FACTION   = BUILDER.comment("Maximum chunks a faction may claim (0 = unlimited).")
                                          .defineInRange("maxClaimsPerFaction", 50, 0, 10000);
        BUILDER.pop();

        BUILDER.push("market");
        MAX_LISTINGS_PER_PLAYER  = BUILDER.comment("Maximum active listings a player may have at once.")
                                          .defineInRange("maxListingsPerPlayer", 5, 1, 100);
        MARKET_TAX_BIN_PERCENT   = BUILDER.comment("Tax % deducted from Buy-It-Now sales.")
                                          .defineInRange("marketTaxBinPercent", 10, 0, 100);
        MARKET_TAX_AUCTION_4H    = BUILDER.comment("Tax % for 4-hour auctions.")
                                          .defineInRange("marketTaxAuction4h", 10, 0, 100);
        MARKET_TAX_AUCTION_8H    = BUILDER.comment("Tax % for 8-hour auctions.")
                                          .defineInRange("marketTaxAuction8h", 12, 0, 100);
        MARKET_TAX_AUCTION_12H   = BUILDER.comment("Tax % for 12-hour auctions.")
                                          .defineInRange("marketTaxAuction12h", 16, 0, 100);
        MARKET_TAX_AUCTION_16H   = BUILDER.comment("Tax % for 16-hour auctions.")
                                          .defineInRange("marketTaxAuction16h", 20, 0, 100);
        MARKET_TAX_AUCTION_24H   = BUILDER.comment("Tax % for 24-hour auctions.")
                                          .defineInRange("marketTaxAuction24h", 24, 0, 100);
        BUILDER.pop();

        BUILDER.push("war");
        WAR_GRACE_PERIOD_SECONDS     = BUILDER.comment("Duration of the grace period after a war is declared, in seconds (default 600 = 10 minutes). Also changeable in-game via /faction war graceperiod set <seconds>.")
                                              .defineInRange("gracePeriodSeconds", 600, 0, 86400);
        WAR_ATTACKER_LIVES           = BUILDER.comment("Lives each committed attacker gets per war.")
                                              .defineInRange("attackerLives", 3, 1, 20);
        WAR_DEFENDER_LIVES           = BUILDER.comment("Lives each defender gets when their faction has active upkeep.")
                                              .defineInRange("defenderLives", 3, 1, 20);
        WAR_DEFENDER_LIVES_NO_UPKEEP = BUILDER.comment("Lives each defender gets when their faction vault is empty (no upkeep).")
                                              .defineInRange("defenderLivesNoUpkeep", 1, 1, 20);
        WAR_CAPTURE_RADIUS_BLOCKS    = BUILDER.comment("Block radius around the defending faction table that counts as the capture point.")
                                              .defineInRange("captureRadiusBlocks", 25, 5, 200);
        WAR_CAPTURE_TIME_SECONDS     = BUILDER.comment("Seconds of uncontested attacker presence needed to capture the point and win.")
                                              .defineInRange("captureTimeSeconds", 300, 30, 3600);
        WAR_BOUNDARY_RADIUS_BLOCKS   = BUILDER.comment("Block radius from the defending faction table that defines the war boundary. Attackers who leave this area lose all their lives.")
                                              .defineInRange("boundaryRadiusBlocks", 300, 50, 10000);
        WAR_BOUNDARY_ENABLED         = BUILDER.comment("Whether the war boundary is enforced for attackers.")
                                              .define("boundaryEnabled", true);
        BUILDER.pop();

        BUILDER.push("vassal");
        VASSAL_TAX_PERCENT   = BUILDER.comment("Percentage of a vassal faction's market sale proceeds and claim costs redirected as tax to the suzerain.")
                                      .defineInRange("taxPercent", 15, 0, 100);
        VASSAL_BUYOUT_COPPER = BUILDER.comment("Total copper a vassal faction must pay from their vault to purchase independence (default 500 gold = 5,000,000 copper).")
                                      .defineInRange("buyoutCopperValue", 5_000_000, 1000, Integer.MAX_VALUE);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();
}
