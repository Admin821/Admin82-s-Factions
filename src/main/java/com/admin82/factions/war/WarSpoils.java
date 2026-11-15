package com.admin82.factions.war;

import com.admin82.factions.storage.ItemVault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * What a faction left behind in its old faction table when its core was taken: coins and items,
 * for the winning faction to loot. The table crumbles once it's empty.
 */
public class WarSpoils extends SavedData {

    private static final String DATA_NAME = "adminsfactions_war_spoils";

    public static final class Spoils {
        public final UUID id;
        public final UUID winnerFactionId;
        public final String loserName;
        public final BlockPos pos;
        public final String dimension;
        public long coins;
        public final ItemVault items = new ItemVault();

        public Spoils(UUID id, UUID winnerFactionId, String loserName, BlockPos pos, String dimension) {
            this.id = id;
            this.winnerFactionId = winnerFactionId;
            this.loserName = loserName;
            this.pos = pos;
            this.dimension = dimension;
        }

        public boolean isEmpty() { return coins <= 0 && items.isEmpty(); }
    }

    private final Map<UUID, Spoils> spoils = new HashMap<>();

    public static WarSpoils get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WarSpoils::new, WarSpoils::load, null), DATA_NAME);
    }

    public void add(Spoils s) { spoils.put(s.id, s); setDirty(); }
    public void remove(UUID id) { if (spoils.remove(id) != null) setDirty(); }
    @Nullable public Spoils get(UUID id) { return spoils.get(id); }
    public void changed() { setDirty(); }

    @Nullable
    public Spoils at(BlockPos pos, String dimension) {
        for (Spoils s : spoils.values()) if (s.pos.equals(pos) && s.dimension.equals(dimension)) return s;
        return null;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Spoils s : spoils.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", s.id);
            t.putUUID("winner", s.winnerFactionId);
            t.putString("loser", s.loserName);
            t.putLong("pos", s.pos.asLong());
            t.putString("dim", s.dimension);
            t.putLong("coins", s.coins);
            t.put("items", s.items.save(registries));
            list.add(t);
        }
        tag.put("spoils", list);
        return tag;
    }

    public static WarSpoils load(CompoundTag tag, HolderLookup.Provider registries) {
        WarSpoils data = new WarSpoils();
        for (Tag raw : tag.getList("spoils", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            Spoils s = new Spoils(t.getUUID("id"), t.getUUID("winner"), t.getString("loser"),
                    BlockPos.of(t.getLong("pos")), t.getString("dim"));
            s.coins = t.getLong("coins");
            for (var stack : ItemVault.load(t.getCompound("items"), registries).takeAll()) s.items.putBack(stack);
            data.spoils.put(s.id, s);
        }
        return data;
    }
}
