package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.Config;
import com.admin82.factions.economy.BountyEvents;
import com.admin82.factions.economy.BountyManager;
import com.admin82.factions.faction.AllianceRules;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.screen.MarketScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → client: every bounty on the board, with whether the viewer may claim it, plus the
 * online players the viewer may target. Who placed a bounty is never sent: only the name they
 * chose to show (or "Anonymous") and whether it is the viewer's own.
 */
public record SyncBountiesPacket(List<View> bounties, List<String> onlinePlayers, int taxPercent) implements CustomPacketPayload {

    public record View(UUID id, UUID targetId, String targetName, String targetFaction, boolean targetOnline,
                       boolean mine, String placerName, long reward, long createdAt, long expiresAt,
                       BountyEvents.ClaimStatus status) {}

    public static final Type<SyncBountiesPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_bounties"));

    public static final StreamCodec<FriendlyByteBuf, SyncBountiesPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeVarInt(pkt.bounties.size());
                for (View v : pkt.bounties) {
                    buf.writeUUID(v.id);
                    buf.writeUUID(v.targetId);
                    buf.writeUtf(v.targetName, 64);
                    buf.writeUtf(v.targetFaction, 64);
                    buf.writeBoolean(v.targetOnline);
                    buf.writeBoolean(v.mine);
                    buf.writeUtf(v.placerName, 64);
                    buf.writeLong(v.reward);
                    buf.writeLong(v.createdAt);
                    buf.writeLong(v.expiresAt);
                    buf.writeVarInt(v.status.ordinal());
                }
                buf.writeVarInt(pkt.onlinePlayers.size());
                pkt.onlinePlayers.forEach(n -> buf.writeUtf(n, 64));
                buf.writeVarInt(pkt.taxPercent);
            },
            buf -> {
                int size = buf.readVarInt();
                List<View> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    UUID id = buf.readUUID(), target = buf.readUUID();
                    String targetName = buf.readUtf(64), faction = buf.readUtf(64);
                    boolean online = buf.readBoolean();
                    boolean mine = buf.readBoolean();
                    String placerName = buf.readUtf(64);
                    long reward = buf.readLong(), created = buf.readLong(), expires = buf.readLong();
                    int ordinal = buf.readVarInt();
                    var statuses = BountyEvents.ClaimStatus.values();
                    list.add(new View(id, target, targetName, faction, online, mine, placerName, reward, created, expires,
                            ordinal >= 0 && ordinal < statuses.length ? statuses[ordinal] : BountyEvents.ClaimStatus.CLAIMABLE));
                }
                int players = buf.readVarInt();
                List<String> names = new ArrayList<>(players);
                for (int i = 0; i < players; i++) names.add(buf.readUtf(64));
                return new SyncBountiesPacket(list, names, buf.readVarInt());
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static SyncBountiesPacket forViewer(MinecraftServer server, ServerPlayer viewer) {
        FactionManager fmgr = FactionManager.get(server);
        List<View> views = new ArrayList<>();
        for (BountyManager.Bounty b : BountyManager.get(server).getAll()) {
            Faction tf = fmgr.getFactionForPlayer(b.targetId);
            views.add(new View(b.id, b.targetId, b.targetName, tf != null ? tf.getName() : "",
                    server.getPlayerList().getPlayer(b.targetId) != null,
                    b.placerId.equals(viewer.getUUID()), b.placerName, b.reward, b.createdAt, b.expiresAt,
                    BountyEvents.claimStatus(server, viewer.getUUID(), b)));
        }
        // Suggest only players the viewer may put a bounty on (not teammates or allies).
        UUID viewerFaction = fmgr.getPlayerFactionId(viewer.getUUID());
        List<String> online = server.getPlayerList().getPlayers().stream()
                .filter(p -> p != viewer)
                .filter(p -> viewerFaction == null || !viewerFaction.equals(fmgr.getPlayerFactionId(p.getUUID())))
                .filter(p -> !AllianceRules.playersProtected(server, viewer.getUUID(), p.getUUID()))
                .map(p -> p.getGameProfile().getName())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return new SyncBountiesPacket(views, online, Config.BOUNTY_TAX_PERCENT.get());
    }

    public static void handle(SyncBountiesPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof MarketScreen screen) {
                screen.getMenu().updateBounties(pkt.bounties, pkt.onlinePlayers, pkt.taxPercent);
            }
        });
    }
}
