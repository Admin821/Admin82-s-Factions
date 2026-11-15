package com.admin82.factions.economy;

import com.admin82.factions.Config;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Persistent item → copper exchange rates, set by server ops. */
public class ExchangeManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_exchange";

    /** Maps item registry name (e.g. "minecraft:iron_ingot") to copper value per 1 item. */
    private final Map<String, Long> rates = new HashMap<>();

    // Singleton

    public static ExchangeManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new Factory<>(ExchangeManager::new, ExchangeManager::load),
            DATA_NAME
        );
    }

    // API

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

    /**
     * Whether a stack may be exchanged. Enchanted, renamed or damaged items are skipped so
     * nobody sells a gear piece they cared about by accident.
     */
    public static boolean isSellable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.isDamaged() || stack.isEnchanted()) return false;
        return !stack.has(DataComponents.CUSTOM_NAME);
    }

    /** Whether players can buy items from the exchange too. Lives in the config file. */
    public boolean isBothWaysExchange() {
        return Config.EXCHANGE_SELLS_ITEMS.get();
    }

    public void setBothWaysExchange(boolean enabled) {
        Config.set(Config.EXCHANGE_SELLS_ITEMS, enabled);
    }

    // Serialization

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        var ratesTag = new CompoundTag();
        for (var e : rates.entrySet()) {
            ratesTag.putLong(e.getKey(), e.getValue());
        }
        tag.put("rates", ratesTag);
        return tag;
    }

    public static ExchangeManager load(CompoundTag tag, HolderLookup.Provider reg) {
        var manager = new ExchangeManager();
        if (tag.contains("rates")) {
            var ratesTag = tag.getCompound("rates");
            for (String key : ratesTag.getAllKeys()) {
                manager.rates.put(key, ratesTag.getLong(key));
            }
        }
        // Used to be saved here; move it to the config.
        if (tag.contains("bothWaysExchange"))
            Config.migrate(Config.EXCHANGE_SELLS_ITEMS, tag.getBoolean("bothWaysExchange"));
        // Old settings were copied to the config above; save once more so they leave the world file.
        if (Stream.of("bothWaysExchange").anyMatch(tag::contains)) manager.setDirty();
        return manager;
    }
}
