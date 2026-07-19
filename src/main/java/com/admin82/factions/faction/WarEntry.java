package com.admin82.factions.faction;

import com.admin82.factions.war.WarType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

public record WarEntry(UUID targetFactionId, String targetFactionName, long startTime, WarType warType) {

    /** Convenience constructor for wars declared without an explicit type (defaults to FIGHT). */
    public WarEntry(UUID targetFactionId, String targetFactionName, long startTime) {
        this(targetFactionId, targetFactionName, startTime, WarType.FIGHT);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("target", targetFactionId);
        tag.putString("targetName", targetFactionName);
        tag.putLong("startTime", startTime);
        tag.putInt("warType", warType.ordinal());
        return tag;
    }

    public static WarEntry load(CompoundTag tag) {
        return new WarEntry(
                tag.getUUID("target"),
                tag.getString("targetName"),
                tag.getLong("startTime"),
                WarType.fromOrdinal(tag.getInt("warType")));
    }

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeUUID(targetFactionId);
        buf.writeUtf(targetFactionName);
        buf.writeLong(startTime);
        buf.writeVarInt(warType.ordinal());
    }

    public static WarEntry fromNetwork(FriendlyByteBuf buf) {
        return new WarEntry(buf.readUUID(), buf.readUtf(64), buf.readLong(),
                WarType.fromOrdinal(buf.readVarInt()));
    }
}
