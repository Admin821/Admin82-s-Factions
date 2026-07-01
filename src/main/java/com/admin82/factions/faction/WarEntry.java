package com.admin82.factions.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

public record WarEntry(UUID targetFactionId, String targetFactionName, long startTime) {

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("target", targetFactionId);
        tag.putString("targetName", targetFactionName);
        tag.putLong("startTime", startTime);
        return tag;
    }

    public static WarEntry load(CompoundTag tag) {
        return new WarEntry(
                tag.getUUID("target"),
                tag.getString("targetName"),
                tag.getLong("startTime")
        );
    }

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeUUID(targetFactionId);
        buf.writeUtf(targetFactionName);
        buf.writeLong(startTime);
    }

    public static WarEntry fromNetwork(FriendlyByteBuf buf) {
        return new WarEntry(buf.readUUID(), buf.readUtf(64), buf.readLong());
    }
}
