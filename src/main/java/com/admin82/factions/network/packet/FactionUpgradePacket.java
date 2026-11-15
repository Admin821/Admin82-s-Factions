package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.UpgradeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Client → server: ask for the Faction Upgrades data ({@code track} = -1), or buy one level of a track. */
public record FactionUpgradePacket(int track, String outpostId) implements CustomPacketPayload {

    public static final int REFRESH = -1;

    public static final Type<FactionUpgradePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "faction_upgrade"));

    public static final StreamCodec<FriendlyByteBuf, FactionUpgradePacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> { buf.writeInt(p.track); buf.writeUtf(p.outpostId, 64); },
            buf -> new FactionUpgradePacket(buf.readInt(), buf.readUtf(64)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(FactionUpgradePacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (p.track >= 0 && p.track < UpgradeService.Track.values().length) {
                UUID outpost = null;
                try { if (!p.outpostId.isEmpty()) outpost = UUID.fromString(p.outpostId); } catch (IllegalArgumentException ignored) { }
                UpgradeService.upgrade(sp, UpgradeService.Track.values()[p.track], outpost);
                return; // upgrade() sends fresh data
            }
            UpgradeService.sendSync(sp);
        });
    }
}
