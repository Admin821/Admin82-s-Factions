package com.admin82.factions.faction;

import com.admin82.factions.Config;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Players who recently left a faction. For a while after leaving (config alliances.leaveCooldownHours)
 * they still can't act against their old faction or the factions it was allied with: no bounties on
 * them and no wars against them. Otherwise anyone could step out of an alliance, attack, and step back in.
 *
 * The protected factions are worked out when the player leaves, so it still holds if the old faction
 * disbands or its alliances change afterwards.
 */
public class FactionDepartures extends SavedData {

    private static final String DATA_NAME = "adminsfactions_departures";

    /** One player's departure: who they left, when the cooldown ends, and who they can't act against. */
    public record Departure(String formerFaction, long until, Set<UUID> protectedFactions) {}

    private final Map<UUID, Departure> departures = new HashMap<>();

    public static FactionDepartures get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FactionDepartures::new, FactionDepartures::load, null), DATA_NAME);
    }

    /** Call before the player is removed from {@code faction} (leaving, kicked, moved, or the faction disbanding). */
    public static void record(MinecraftServer server, UUID player, Faction faction) {
        long hours = Config.LEAVE_COOLDOWN_HOURS.get();
        if (hours <= 0) return;
        Set<UUID> covered = new HashSet<>();
        covered.add(faction.getId());
        AllianceManager alliances = AllianceManager.get(server);
        covered.addAll(alliances.getAllies(faction.getId()));
        covered.addAll(alliances.getCooldowns(faction.getId()).keySet());
        FactionDepartures data = get(server);
        // Leaving twice in a row keeps the older protections too.
        Departure previous = data.active(player);
        if (previous != null) covered.addAll(previous.protectedFactions());
        data.departures.put(player, new Departure(faction.getName(),
                System.currentTimeMillis() + hours * 3_600_000L, covered));
        data.setDirty();
    }

    /** The player's current departure, or null if they haven't left a faction recently. */
    @Nullable
    public Departure active(UUID player) {
        Departure d = departures.get(player);
        if (d == null) return null;
        if (d.until() <= System.currentTimeMillis()) {
            departures.remove(player);
            setDirty();
            return null;
        }
        return d;
    }

    /** If {@code player} recently left a faction that covers {@code faction}, their departure; otherwise null. */
    @Nullable
    public Departure covering(UUID player, @Nullable UUID faction) {
        if (faction == null) return null;
        Departure d = active(player);
        return d != null && d.protectedFactions().contains(faction) ? d : null;
    }

    // Saving

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        long now = System.currentTimeMillis();
        departures.forEach((player, d) -> {
            if (d.until() <= now) return;
            CompoundTag e = new CompoundTag();
            e.putUUID("player", player);
            e.putString("former", d.formerFaction());
            e.putLong("until", d.until());
            ListTag factions = new ListTag();
            d.protectedFactions().forEach(f -> factions.add(NbtUtils.createUUID(f)));
            e.put("factions", factions);
            list.add(e);
        });
        tag.put("departures", list);
        return tag;
    }

    public static FactionDepartures load(CompoundTag tag, HolderLookup.Provider registries) {
        FactionDepartures data = new FactionDepartures();
        for (Tag raw : tag.getList("departures", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) raw;
            Set<UUID> factions = new HashSet<>();
            for (Tag f : e.getList("factions", Tag.TAG_INT_ARRAY)) factions.add(NbtUtils.loadUUID(f));
            data.departures.put(e.getUUID("player"), new Departure(e.getString("former"), e.getLong("until"), factions));
        }
        return data;
    }
}
