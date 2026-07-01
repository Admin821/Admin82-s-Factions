package com.admin82.factions.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

public class FactionMember {
    private final UUID uuid;
    private String playerName;
    private FactionRole role;

    public FactionMember(UUID uuid, String playerName, FactionRole role) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.role = role;
    }

    public UUID getUuid() { return uuid; }
    public String getPlayerName() { return playerName; }
    public void setPlayerName(String name) { this.playerName = name; }
    public FactionRole getRole() { return role; }
    public void setRole(FactionRole role) { this.role = role; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);
        tag.putString("name", playerName);
        tag.putString("role", role.getId());
        return tag;
    }

    public static FactionMember load(CompoundTag tag) {
        return new FactionMember(
                tag.getUUID("uuid"),
                tag.getString("name"),
                FactionRole.fromId(tag.getString("role"))
        );
    }

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeUUID(uuid);
        buf.writeUtf(playerName);
        buf.writeUtf(role.getId());
    }

    public static FactionMember fromNetwork(FriendlyByteBuf buf) {
        return new FactionMember(buf.readUUID(), buf.readUtf(50), FactionRole.fromId(buf.readUtf(20)));
    }
}
