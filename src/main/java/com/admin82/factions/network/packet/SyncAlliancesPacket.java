package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.AllianceManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionMember;
import com.admin82.factions.screen.FactionTableScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Server → client: the viewer faction's alliances, open proposals and broken-alliance cooldowns. */
public record SyncAlliancesPacket(List<Entry> entries) implements CustomPacketPayload {

    public enum State { ALLY, INCOMING, OUTGOING, COOLDOWN }

    /**
     * @param time ALLY: when formed; INCOMING/OUTGOING: when proposed; COOLDOWN: milliseconds of protection left
     */
    public record Entry(UUID factionId, String name, State state, long time, int members) {}

    public static final Type<SyncAlliancesPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_alliances"));

    public static final StreamCodec<FriendlyByteBuf, SyncAlliancesPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeVarInt(pkt.entries.size());
                for (Entry e : pkt.entries) {
                    buf.writeUUID(e.factionId);
                    buf.writeUtf(e.name, 64);
                    buf.writeVarInt(e.state.ordinal());
                    buf.writeLong(e.time);
                    buf.writeVarInt(e.members);
                }
            },
            buf -> {
                int size = buf.readVarInt();
                List<Entry> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    UUID id = buf.readUUID();
                    String name = buf.readUtf(64);
                    int ordinal = buf.readVarInt();
                    State state = ordinal >= 0 && ordinal < State.values().length ? State.values()[ordinal] : State.ALLY;
                    list.add(new Entry(id, name, state, buf.readLong(), buf.readVarInt()));
                }
                return new SyncAlliancesPacket(list);
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Everything the given faction should see about its alliances. */
    public static SyncAlliancesPacket forFaction(MinecraftServer server, UUID factionId) {
        FactionManager fmgr = FactionManager.get(server);
        AllianceManager am = AllianceManager.get(server);
        List<Entry> list = new ArrayList<>();
        for (UUID ally : am.getAllies(factionId)) add(list, fmgr, ally, State.ALLY, am.allianceFormedAt(factionId, ally));
        am.getIncoming(factionId).forEach((id, at) -> add(list, fmgr, id, State.INCOMING, at));
        am.getOutgoing(factionId).forEach((id, at) -> add(list, fmgr, id, State.OUTGOING, at));
        am.getCooldowns(factionId).forEach((id, left) -> add(list, fmgr, id, State.COOLDOWN, left));
        list.sort(Comparator.comparing((Entry e) -> e.state).thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        return new SyncAlliancesPacket(list);
    }

    private static void add(List<Entry> list, FactionManager fmgr, UUID id, State state, long time) {
        Faction f = fmgr.getFaction(id);
        if (f != null) list.add(new Entry(id, f.getName(), state, time, f.getMembers().size()));
    }

    /** Sends fresh alliance data to every online member of a faction. */
    public static void sendToFaction(MinecraftServer server, UUID factionId) {
        Faction f = FactionManager.get(server).getFaction(factionId);
        if (f == null) return;
        SyncAlliancesPacket pkt = forFaction(server, factionId);
        for (FactionMember m : f.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) PacketDistributor.sendToPlayer(sp, pkt);
        }
    }

    public static void handle(SyncAlliancesPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof FactionTableScreen screen) {
                screen.getMenu().updateAlliances(pkt.entries);
            }
        });
    }
}
