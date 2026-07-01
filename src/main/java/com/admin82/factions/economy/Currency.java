package com.admin82.factions.economy;

/**
 * Utility constants and helpers for the faction currency system.
 *
 * Base unit: copper coin.
 *   100 copper  = 1 silver
 *   100 silver  = 1 gold      (10,000 copper)
 *   100 gold    = 1 platinum  (1,000,000 copper)
 */
public final class Currency {

    public static final long COPPER_PER_SILVER   = 100L;
    public static final long COPPER_PER_GOLD     = 10_000L;
    public static final long COPPER_PER_PLATINUM = 1_000_000L;

    // ── Economy tuning constants ──────────────────────────────────────────────
    /** Cost to claim one chunk, in copper. */
    public static final long CLAIM_COST_COPPER            = 100L;   // 1 silver
    /** Upkeep cost per claim per 24 h, in copper (≈ 20 % of claim cost). */
    public static final long UPKEEP_PER_CLAIM_PER_DAY     = 20L;    // 20 copper
    /** Upkeep interval in milliseconds (24 hours). */
    public static final long UPKEEP_INTERVAL_MS           = 86_400_000L;

    private Currency() {}

    // ── Conversion helpers ────────────────────────────────────────────────────

    public static long of(long platinum, long gold, long silver, long copper) {
        return platinum * COPPER_PER_PLATINUM
             + gold     * COPPER_PER_GOLD
             + silver   * COPPER_PER_SILVER
             + copper;
    }

    public static long platinum(long copper)       { return copper / COPPER_PER_PLATINUM; }
    public static long goldPart(long copper)       { return (copper % COPPER_PER_PLATINUM) / COPPER_PER_GOLD; }
    public static long silverPart(long copper)     { return (copper % COPPER_PER_GOLD) / COPPER_PER_SILVER; }
    public static long copperRemainder(long copper){ return copper % COPPER_PER_SILVER; }

    // ── Formatting ────────────────────────────────────────────────────────────

    /**
     * Short coloured format, e.g. §d3p §r§62g §r§71s §r§c5c
     * Zero components are omitted. Returns "0c" for 0.
     */
    public static String format(long copper) {
        if (copper <= 0) return "0§cc";
        long p = platinum(copper);
        long g = goldPart(copper);
        long s = silverPart(copper);
        long c = copperRemainder(copper);
        var sb = new StringBuilder();
        if (p > 0) sb.append("§d").append(p).append("p §r");
        if (g > 0) sb.append("§6").append(g).append("g §r");
        if (s > 0) sb.append("§7").append(s).append("s §r");
        if (c > 0) sb.append("§c").append(c).append("c");
        return sb.toString().stripTrailing();
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * Parses strings like "1p2g3s4c", "1g50c", "150" (treated as copper).
     * Returns -1 on parse failure.
     */
    public static long parse(String input) {
        if (input == null || input.isBlank()) return 0;
        String s = input.trim().toLowerCase();
        // plain integer → copper
        try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        long total = 0;
        long cur   = 0;
        for (char ch : s.toCharArray()) {
            if (ch >= '0' && ch <= '9') {
                cur = cur * 10 + (ch - '0');
            } else {
                total += switch (ch) {
                    case 'p' -> cur * COPPER_PER_PLATINUM;
                    case 'g' -> cur * COPPER_PER_GOLD;
                    case 's' -> cur * COPPER_PER_SILVER;
                    case 'c' -> cur;
                    default  -> 0;
                };
                cur = 0;
            }
        }
        return total + cur; // trailing number = copper
    }
}
