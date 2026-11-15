package com.admin82.factions.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The server treasury: every copper the server takes in (taxes, land and deed purchases,
 * upkeep, fees) and every copper it pays out of thin air (exchange sales, server buy orders).
 * Operators view it on the market's Treasury page.
 */
public class Treasury extends SavedData {

    private static final String DATA_NAME = "adminsfactions_treasury";
    public static final int DAYS_KEPT = 30;
    public static final int RECENT_KEPT = 100;
    public static final int CONTRIBUTORS_KEPT = 200;

    /** Where money comes from (income) or goes to (issued). */
    public enum Source {
        MARKET_TAX_BUY_NOW("Market tax · Buy Now", true),
        MARKET_TAX_AUCTION("Market tax · Auctions", true),
        MARKET_TAX_BUY_ORDER("Market tax · Buy orders", true),
        BOUNTY_TAX("Bounty tax", true),
        LAND_CLAIMS("Land claims", true),
        CHUNKLOAD_DEEDS("Chunkloading deeds", true),
        LAND_UPKEEP("Land upkeep", true),
        OUTPOST_UPKEEP("Outpost upkeep", true),
        OUTPOST_TELEPORTS("Outpost teleports", true),
        EXCHANGE_PURCHASES("Exchange item purchases", true),
        SERVER_SHOP("Server shop sales", true),
        EXCHANGE_PAYOUTS("Exchange payouts", false),
        SERVER_BUY_ORDERS("Server buy orders", false);

        public final String label;
        /** True for money the server takes in; false for money it creates by paying out. */
        public final boolean income;

        Source(String label, boolean income) {
            this.label = label;
            this.income = income;
        }
    }

    public record Entry(long time, Source source, long amount, String payer, String detail) {}

    private final EnumMap<Source, long[]> totals = new EnumMap<>(Source.class); // [copper, count]
    /** epoch day → source → copper */
    private final TreeMap<Long, EnumMap<Source, Long>> daily = new TreeMap<>();
    private final Deque<Entry> recent = new ArrayDeque<>();
    private final Map<String, Long> contributors = new HashMap<>();
    private long since;

    public static Treasury get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(Treasury::new, Treasury::load, null), DATA_NAME);
    }

    /** Payer label for money paid by a faction, so it never merges with a player who has the same name. */
    public static String factionPayer(String factionName) {
        return "⚑ " + factionName;
    }

    /** Convenience: record without needing the instance. Ignores zero or negative amounts. */
    public static void record(MinecraftServer server, Source source, long amount, String payer, String detail) {
        if (server == null || amount <= 0) return;
        get(server).add(source, amount, payer, detail);
    }

    public void add(Source source, long amount, String payer, String detail) {
        // Same limits as the network codec; longer text (custom item names) would kick whoever opens the page.
        if (payer.length() > 64) payer = payer.substring(0, 64);
        if (detail.length() > 128) detail = detail.substring(0, 125) + "...";
        long now = System.currentTimeMillis();
        if (since == 0) since = now;
        long[] t = totals.computeIfAbsent(source, ignored -> new long[2]);
        t[0] += amount;
        t[1]++;
        long day = LocalDate.now(ZoneId.systemDefault()).toEpochDay();
        daily.computeIfAbsent(day, ignored -> new EnumMap<>(Source.class)).merge(source, amount, Long::sum);
        while (daily.size() > DAYS_KEPT) daily.pollFirstEntry();
        // Upkeep is charged every few seconds: merge it into the newest line within the hour.
        Entry last = recent.peekFirst();
        boolean merge = last != null && last.source() == source && last.payer().equals(payer)
                && (source == Source.LAND_UPKEEP || source == Source.OUTPOST_UPKEEP)
                && now - last.time() < 3_600_000L;
        if (merge) {
            recent.pollFirst();
            recent.addFirst(new Entry(last.time(), source, last.amount() + amount, payer, last.detail()));
        } else {
            recent.addFirst(new Entry(now, source, amount, payer, detail));
            while (recent.size() > RECENT_KEPT) recent.pollLast();
        }
        if (source.income && !payer.isBlank()) {
            contributors.merge(payer, amount, Long::sum);
            if (contributors.size() > CONTRIBUTORS_KEPT * 2) trimContributors();
        }
        setDirty();
    }

    private void trimContributors() {
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(contributors.entrySet());
        sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        contributors.clear();
        sorted.stream().limit(CONTRIBUTORS_KEPT).forEach(e -> contributors.put(e.getKey(), e.getValue()));
    }

    /** Wipes all statistics (operator command). */
    public void reset() {
        totals.clear();
        daily.clear();
        recent.clear();
        contributors.clear();
        since = System.currentTimeMillis();
        setDirty();
    }

    // Network snapshot

    /** Everything the Treasury page shows. */
    public record Snapshot(long since, Map<Source, long[]> totals, Map<Source, Long> last24h, Map<Source, Long> last7d,
                           List<long[]> dailyIncome, List<Entry> recent, List<Map.Entry<String, Long>> topContributors) {

        public void write(FriendlyByteBuf buf) {
            buf.writeLong(since);
            buf.writeVarInt(totals.size());
            totals.forEach((s, t) -> { buf.writeVarInt(s.ordinal()); buf.writeLong(t[0]); buf.writeLong(t[1]); });
            writeMap(buf, last24h);
            writeMap(buf, last7d);
            buf.writeVarInt(dailyIncome.size());
            dailyIncome.forEach(d -> { buf.writeLong(d[0]); buf.writeLong(d[1]); buf.writeLong(d[2]); });
            buf.writeVarInt(recent.size());
            for (Entry e : recent) {
                buf.writeLong(e.time());
                buf.writeVarInt(e.source().ordinal());
                buf.writeLong(e.amount());
                buf.writeUtf(e.payer(), 64);
                buf.writeUtf(e.detail(), 128);
            }
            buf.writeVarInt(topContributors.size());
            topContributors.forEach(e -> { buf.writeUtf(e.getKey(), 64); buf.writeLong(e.getValue()); });
        }

        public static Snapshot read(FriendlyByteBuf buf) {
            long since = buf.readLong();
            Map<Source, long[]> totals = new EnumMap<>(Source.class);
            int n = buf.readVarInt();
            for (int i = 0; i < n; i++) {
                Source s = source(buf.readVarInt());
                long copper = buf.readLong(), count = buf.readLong();
                if (s != null) totals.put(s, new long[]{copper, count});
            }
            Map<Source, Long> d1 = readMap(buf), d7 = readMap(buf);
            int days = buf.readVarInt();
            List<long[]> daily = new ArrayList<>(days);
            for (int i = 0; i < days; i++) daily.add(new long[]{buf.readLong(), buf.readLong(), buf.readLong()});
            int r = buf.readVarInt();
            List<Entry> recent = new ArrayList<>(r);
            for (int i = 0; i < r; i++) {
                long time = buf.readLong();
                Source s = source(buf.readVarInt());
                long amount = buf.readLong();
                String payer = buf.readUtf(64), detail = buf.readUtf(128);
                if (s != null) recent.add(new Entry(time, s, amount, payer, detail));
            }
            int c = buf.readVarInt();
            List<Map.Entry<String, Long>> top = new ArrayList<>(c);
            for (int i = 0; i < c; i++) top.add(Map.entry(buf.readUtf(64), buf.readLong()));
            return new Snapshot(since, totals, d1, d7, daily, recent, top);
        }

        private static void writeMap(FriendlyByteBuf buf, Map<Source, Long> map) {
            buf.writeVarInt(map.size());
            map.forEach((s, v) -> { buf.writeVarInt(s.ordinal()); buf.writeLong(v); });
        }

        private static Map<Source, Long> readMap(FriendlyByteBuf buf) {
            Map<Source, Long> map = new EnumMap<>(Source.class);
            int n = buf.readVarInt();
            for (int i = 0; i < n; i++) {
                Source s = source(buf.readVarInt());
                long v = buf.readLong();
                if (s != null) map.put(s, v);
            }
            return map;
        }

        private static Source source(int ordinal) {
            Source[] values = Source.values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
        }
    }

    public Snapshot snapshot() {
        long today = LocalDate.now(ZoneId.systemDefault()).toEpochDay();
        Map<Source, Long> d1 = sumDays(today, today), d7 = sumDays(today - 6, today);
        // Daily income/issued for the last 14 days: [epochDay, income, issued]
        List<long[]> chart = new ArrayList<>();
        for (long day = today - 13; day <= today; day++) {
            long income = 0, issued = 0;
            var map = daily.get(day);
            if (map != null) for (var e : map.entrySet()) {
                if (e.getKey().income) income += e.getValue(); else issued += e.getValue();
            }
            chart.add(new long[]{day, income, issued});
        }
        List<Map.Entry<String, Long>> top = contributors.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10)
                .map(e -> Map.entry(e.getKey(), e.getValue())).toList();
        Map<Source, long[]> totalsCopy = new EnumMap<>(Source.class);
        totals.forEach((s, t) -> totalsCopy.put(s, t.clone()));
        return new Snapshot(since, totalsCopy, d1, d7, chart, new ArrayList<>(recent), top);
    }

    private Map<Source, Long> sumDays(long from, long to) {
        Map<Source, Long> result = new EnumMap<>(Source.class);
        daily.subMap(from, true, to, true).values().forEach(map -> map.forEach((s, v) -> result.merge(s, v, Long::sum)));
        return result;
    }

    // Persistence

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("since", since);
        CompoundTag t = new CompoundTag();
        totals.forEach((s, v) -> { CompoundTag e = new CompoundTag(); e.putLong("c", v[0]); e.putLong("n", v[1]); t.put(s.name(), e); });
        tag.put("totals", t);
        ListTag days = new ListTag();
        daily.forEach((day, map) -> {
            CompoundTag d = new CompoundTag();
            d.putLong("day", day);
            map.forEach((s, v) -> d.putLong(s.name(), v));
            days.add(d);
        });
        tag.put("daily", days);
        ListTag rec = new ListTag();
        for (Entry e : recent) {
            CompoundTag r = new CompoundTag();
            r.putLong("t", e.time());
            r.putString("s", e.source().name());
            r.putLong("a", e.amount());
            r.putString("p", e.payer());
            r.putString("d", e.detail());
            rec.add(r);
        }
        tag.put("recent", rec);
        CompoundTag con = new CompoundTag();
        contributors.forEach(con::putLong);
        tag.put("contributors", con);
        return tag;
    }

    public static Treasury load(CompoundTag tag, HolderLookup.Provider registries) {
        Treasury t = new Treasury();
        t.since = tag.getLong("since");
        CompoundTag totals = tag.getCompound("totals");
        for (String key : totals.getAllKeys()) {
            Source s = byName(key);
            if (s != null) t.totals.put(s, new long[]{totals.getCompound(key).getLong("c"), totals.getCompound(key).getLong("n")});
        }
        for (Tag raw : tag.getList("daily", Tag.TAG_COMPOUND)) {
            CompoundTag d = (CompoundTag) raw;
            EnumMap<Source, Long> map = new EnumMap<>(Source.class);
            for (String key : d.getAllKeys()) {
                Source s = byName(key);
                if (s != null) map.put(s, d.getLong(key));
            }
            t.daily.put(d.getLong("day"), map);
        }
        for (Tag raw : tag.getList("recent", Tag.TAG_COMPOUND)) {
            CompoundTag r = (CompoundTag) raw;
            Source s = byName(r.getString("s"));
            if (s != null) t.recent.addLast(new Entry(r.getLong("t"), s, r.getLong("a"), r.getString("p"), r.getString("d")));
        }
        CompoundTag con = tag.getCompound("contributors");
        for (String key : con.getAllKeys()) t.contributors.put(key, con.getLong(key));
        return t;
    }

    private static Source byName(String name) {
        try { return Source.valueOf(name); } catch (IllegalArgumentException e) { return null; }
    }
}
