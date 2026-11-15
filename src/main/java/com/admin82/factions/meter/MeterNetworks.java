package com.admin82.factions.meter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * The power and fluid market. Sellers make listings ("networks" in the code): a price and the supplier meters
 * that hold the stock. Buyer meters set a secret maximum price and are connected to a listing at or under it
 * automatically (see {@link MeterBlockEntity#buy}). The average price paid each hour is kept for the public graph.
 */
public class MeterNetworks extends SavedData {

    private static final String DATA_NAME = "adminsfactions_meter_networks";

    /** Who can buy from a listing. PASSWORD is from before automatic matching and now works like FACTION. */
    public enum Access { PUBLIC, PASSWORD, FACTION }

    /** Prices are compared and paid in millionths of a copper per FE or mB, so cheap prices aren't rounded away. */
    public static final long MICRO = 1_000_000L;
    /** Highest price per unit (10,000 copper per FE or mB), so a tick's bill can't overflow. */
    public static final long MAX_MICRO = 10_000_000_000L;
    /** Hours of price history kept and graphed. */
    public static final int HISTORY_HOURS = 24;

    /** "{@code priceCopper} per {@code perUnits}" as millionths of a copper per unit. */
    public static long micro(long priceCopper, long perUnits) {
        if (priceCopper <= 0) return 0;
        if (perUnits <= 0) return MAX_MICRO;
        return java.math.BigInteger.valueOf(priceCopper).multiply(java.math.BigInteger.valueOf(MICRO))
                .divide(java.math.BigInteger.valueOf(perUnits)).min(java.math.BigInteger.valueOf(MAX_MICRO)).longValue();
    }

    /** A price per 1000 units as coin text, like "2s 50c", "0.25c" or "free". */
    public static String per1000(long micro) {
        if (micro <= 0) return "free";
        double copper = micro / 1000.0;
        if (copper < 1) return String.format(java.util.Locale.ROOT, "%.2fc", copper);
        return com.admin82.factions.economy.Currency.format(Math.round(copper)).replaceAll("§.", "");
    }

    /** Whether {@code player} may buy from a listing: public ones, their own, or their faction's faction-only ones. */
    public static boolean allowed(MinecraftServer server, Network n, @Nullable UUID player) {
        if (n.access == Access.PUBLIC) return true;
        if (player == null) return false;
        if (n.owner.equals(player)) return true;
        var mine = com.admin82.factions.faction.FactionManager.get(server).getFactionForPlayer(player);
        return mine != null && mine.getId().equals(n.faction);
    }

    public static final class Network {
        public final UUID id;
        public final MeterKind kind;
        public String name;
        public final UUID owner;
        public String ownerName;
        @Nullable public UUID faction;
        public Access access = Access.PUBLIC;
        public String passwordHash = "";
        /** Price: {@code priceCopper} for every {@code perUnits} mB or FE. */
        public long priceCopper = 1;
        public long perUnits = 1000;
        public final List<GlobalPos> suppliers = new ArrayList<>();
        public long unitsSold;
        public long earned;

        public Network(UUID id, MeterKind kind, String name, UUID owner, String ownerName) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.owner = owner;
            this.ownerName = ownerName;
        }

        public boolean checkPassword(String password) {
            return access != Access.PASSWORD || hash(id, password).equals(passwordHash);
        }

        public void setPassword(String password) {
            passwordHash = hash(id, password);
        }
    }

    private final Map<UUID, Network> networks = new LinkedHashMap<>();
    /** Per market ("POWER", or "FLUID:" + fluid id): hour number → {units sold, sum of units × price}. */
    private final Map<String, java.util.TreeMap<Long, double[]>> history = new LinkedHashMap<>();

    private static String historyKey(MeterKind kind, String fluid) {
        return kind == MeterKind.POWER ? "POWER" : "FLUID:" + fluid;
    }

    private static long hourNow() {
        return System.currentTimeMillis() / 3_600_000L;
    }

    /** Adds a sale to this hour's average price. */
    public void recordSale(MeterKind kind, String fluid, long units, long micro) {
        if (units <= 0) return;
        long hour = hourNow();
        var map = history.computeIfAbsent(historyKey(kind, fluid), k -> new java.util.TreeMap<>());
        double[] h = map.computeIfAbsent(hour, k -> new double[2]);
        h[0] += units;
        h[1] += (double) units * micro;
        map.headMap(hour - HISTORY_HOURS * 2L).clear();
        setDirty();
    }

    public static MeterNetworks get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MeterNetworks::new, MeterNetworks::load, null), DATA_NAME);
    }

    public Collection<Network> all() { return networks.values(); }
    @Nullable public Network get(@Nullable UUID id) { return id == null ? null : networks.get(id); }
    public void add(Network n) { networks.put(n.id, n); setDirty(); }
    public void remove(UUID id) { if (networks.remove(id) != null) setDirty(); }
    public void changed() { setDirty(); }

    public static String hash(UUID salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString((salt + ":" + password).hashCode());
        }
    }

    /**
     * The last {@link #HISTORY_HOURS} hours of every market of this kind that sold anything in them, keyed by fluid id
     * ("" for power). Each is [0] average price per hour (micro, -1 if nothing sold), [1] units sold, oldest first.
     */
    public Map<String, long[][]> histories(MeterKind kind) {
        Map<String, long[][]> out = new LinkedHashMap<>();
        String prefix = historyKey(kind, "");
        long now = hourNow();
        history.forEach((key, map) -> {
            if (!(kind == MeterKind.POWER ? key.equals(prefix) : key.startsWith(prefix))) return;
            long[][] h = new long[2][HISTORY_HOURS];
            java.util.Arrays.fill(h[0], -1);
            boolean any = false;
            for (int i = 0; i < HISTORY_HOURS; i++) {
                double[] d = map.get(now - (HISTORY_HOURS - 1 - i));
                if (d == null || d[0] <= 0) continue;
                h[0][i] = Math.round(d[1] / d[0]);
                h[1][i] = (long) d[0];
                any = true;
            }
            if (any) out.put(kind == MeterKind.POWER ? "" : key.substring(prefix.length()), h);
        });
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Network n : networks.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", n.id);
            t.putString("kind", n.kind.name());
            t.putString("name", n.name);
            t.putUUID("owner", n.owner);
            t.putString("ownerName", n.ownerName);
            if (n.faction != null) t.putUUID("faction", n.faction);
            t.putString("access", n.access.name());
            t.putString("pass", n.passwordHash);
            t.putLong("price", n.priceCopper);
            t.putLong("per", n.perUnits);
            t.putLong("sold", n.unitsSold);
            t.putLong("earned", n.earned);
            ListTag sup = new ListTag();
            for (GlobalPos g : n.suppliers) {
                CompoundTag s = new CompoundTag();
                s.putString("dim", g.dimension().location().toString());
                s.putLong("pos", g.pos().asLong());
                sup.add(s);
            }
            t.put("suppliers", sup);
            list.add(t);
        }
        tag.put("networks", list);
        ListTag hist = new ListTag();
        history.forEach((key, map) -> map.forEach((hour, h) -> {
            CompoundTag t = new CompoundTag();
            t.putString("key", key);
            t.putLong("hour", hour);
            t.putDouble("units", h[0]);
            t.putDouble("sum", h[1]);
            hist.add(t);
        }));
        tag.put("history", hist);
        return tag;
    }

    public static MeterNetworks load(CompoundTag tag, HolderLookup.Provider registries) {
        MeterNetworks data = new MeterNetworks();
        for (Tag raw : tag.getList("networks", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            MeterKind kind;
            try { kind = MeterKind.valueOf(t.getString("kind")); } catch (IllegalArgumentException e) { continue; }
            Network n = new Network(t.getUUID("id"), kind, t.getString("name"), t.getUUID("owner"), t.getString("ownerName"));
            if (t.hasUUID("faction")) n.faction = t.getUUID("faction");
            try { n.access = Access.valueOf(t.getString("access")); } catch (IllegalArgumentException ignored) { }
            n.passwordHash = t.getString("pass");
            n.priceCopper = t.getLong("price");
            n.perUnits = Math.max(1, t.getLong("per"));
            n.unitsSold = t.getLong("sold");
            n.earned = t.getLong("earned");
            for (Tag s : t.getList("suppliers", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) s;
                ResourceLocation dim = ResourceLocation.tryParse(c.getString("dim"));
                if (dim != null) n.suppliers.add(GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(c.getLong("pos"))));
            }
            data.networks.put(n.id, n);
        }
        for (Tag raw : tag.getList("history", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            String key = t.getString("key");
            if (key.isEmpty()) key = "POWER".equals(t.getString("kind")) ? "POWER" : "FLUID:"; // saved before per-fluid history
            data.history.computeIfAbsent(key, k -> new java.util.TreeMap<>())
                    .put(t.getLong("hour"), new double[]{t.getDouble("units"), t.getDouble("sum")});
        }
        return data;
    }
}
