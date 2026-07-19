package com.admin82.factions.economy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Persistent item → copper exchange rates, set by server ops. */
public class ExchangeManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_exchange";

    /** Maps item registry name (e.g. "minecraft:iron_ingot") to copper value per 1 item. */
    private final Map<String, Long> rates = new HashMap<>();

    /** If true, configured exchange rates can also be used to buy items back with coins. */
    private boolean bothWaysExchange = false;

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static ExchangeManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new Factory<>(ExchangeManager::new, ExchangeManager::load),
            DATA_NAME
        );
    }

    // ── API ───────────────────────────────────────────────────────────────────

    public void setRate(String itemId, long copperPer) {
        rates.put(itemId, copperPer);
        setDirty();
    }

    public void removeRate(String itemId) {
        if (rates.remove(itemId) != null) setDirty();
    }

    public long getRate(String itemId) {
        return rates.getOrDefault(itemId, 0L);
    }

    public Map<String, Long> getRates() {
        return Collections.unmodifiableMap(rates);
    }

    public boolean hasRate(String itemId) {
        return rates.containsKey(itemId);
    }

    public boolean isBothWaysExchange() {
        return bothWaysExchange;
    }

    public void setBothWaysExchange(boolean enabled) {
        bothWaysExchange = enabled;
        setDirty();
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider reg) {
        var ratesTag = new CompoundTag();
        for (var e : rates.entrySet()) {
            ratesTag.putLong(e.getKey(), e.getValue());
        }
        tag.put("rates", ratesTag);
        tag.putBoolean("bothWaysExchange", bothWaysExchange);
        return tag;
    }

    private static ExchangeManager load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider reg) {
        var manager = new ExchangeManager();
        if (tag.contains("rates")) {
            var ratesTag = tag.getCompound("rates");
            for (String key : ratesTag.getAllKeys()) {
                manager.rates.put(key, ratesTag.getLong(key));
            }
        }
        manager.bothWaysExchange = tag.getBoolean("bothWaysExchange");
        return manager;
    }
}
