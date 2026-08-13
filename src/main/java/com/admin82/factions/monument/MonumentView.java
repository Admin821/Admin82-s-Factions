package com.admin82.factions.monument;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;
import java.util.List;
import com.admin82.factions.faction.FactionManager;
import net.minecraft.world.level.ChunkPos;

public record MonumentView(UUID id, String name, int tier, int radius, long baseRespawnTicks,
                           double remainingRespawnTicks, int crateCount, int x, int y, int z,
                           String dimension, List<Long> designatedChunks, List<Long> factionClaims,
                           List<String> lootPoolNames) {
    public static MonumentView from(MonumentEntry entry, FactionManager factions, boolean includeClaims) {
        List<Long> claims = includeClaims ? factions.getAllFactions().values().stream()
                .flatMap(faction -> faction.getLandClaims().stream())
                .filter(claim -> claim.dimension().toString().equals(entry.dimension))
                .map(claim -> ChunkPos.asLong(claim.chunkX(), claim.chunkZ()))
                .distinct().toList() : List.of();
        return new MonumentView(entry.id, entry.getName(), entry.getTier(), entry.getRadius(),
                entry.getBaseRespawnTicks(), entry.getRemainingRespawnTicks(), entry.getCrates().size(),
                entry.controllerPos.getX(), entry.controllerPos.getY(), entry.controllerPos.getZ(), entry.dimension,
                List.copyOf(entry.getDesignatedChunks()), claims,
                includeClaims ? entry.getLootPoolNames() : List.of());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name, 64);
        buf.writeVarInt(tier);
        buf.writeVarInt(radius);
        buf.writeVarLong(baseRespawnTicks);
        buf.writeDouble(remainingRespawnTicks);
        buf.writeVarInt(crateCount);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeUtf(dimension, 256);
        buf.writeVarInt(designatedChunks.size());
        designatedChunks.forEach(buf::writeLong);
        buf.writeVarInt(factionClaims.size());
        factionClaims.forEach(buf::writeLong);
        buf.writeVarInt(lootPoolNames.size());
        lootPoolNames.forEach(pool -> buf.writeUtf(pool, 32));
    }

    public static MonumentView read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf(64);
        int tier = buf.readVarInt();
        int radius = buf.readVarInt();
        long baseRespawnTicks = buf.readVarLong();
        double remainingRespawnTicks = buf.readDouble();
        int crateCount = buf.readVarInt();
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        String dimension = buf.readUtf(256);
        int count = buf.readVarInt();
        List<Long> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) chunks.add(buf.readLong());
        int claimCount = buf.readVarInt();
        List<Long> claims = new java.util.ArrayList<>();
        for (int i = 0; i < claimCount; i++) claims.add(buf.readLong());
        int poolCount = buf.readVarInt();
        List<String> poolNames = new java.util.ArrayList<>();
        for (int i = 0; i < poolCount; i++) poolNames.add(buf.readUtf(32));
        return new MonumentView(id, name, tier, radius,
            baseRespawnTicks, remainingRespawnTicks, crateCount, x, y, z, dimension, chunks, claims, poolNames);
    }
}