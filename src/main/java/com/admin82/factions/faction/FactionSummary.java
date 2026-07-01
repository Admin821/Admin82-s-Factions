package com.admin82.factions.faction;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Lightweight snapshot of a faction sent to clients for the Wars tab.
 */
public record FactionSummary(UUID id, String name, int memberCount, int power, int onlineCount, long totalWealth) {

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name);
        buf.writeVarInt(memberCount);
        buf.writeVarInt(power);
        buf.writeVarInt(onlineCount);
        buf.writeLong(totalWealth);
    }

    public static FactionSummary fromNetwork(FriendlyByteBuf buf) {
        return new FactionSummary(buf.readUUID(), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readLong());
    }
}
