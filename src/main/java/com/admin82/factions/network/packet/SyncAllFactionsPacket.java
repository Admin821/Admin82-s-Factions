package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.FactionSummary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record SyncAllFactionsPacket(
        List<FactionSummary> factions,
        List<String> otherClaimedChunks,
        List<String> availablePlayers
) implements CustomPacketPayload {

    public static final Type<SyncAllFactionsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_all_factions")
    );

    public static final StreamCodec<FriendlyByteBuf, SyncAllFactionsPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeVarInt(pkt.factions().size());
                pkt.factions().forEach(s -> s.toNetwork(buf));
                buf.writeVarInt(pkt.otherClaimedChunks().size());
                pkt.otherClaimedChunks().forEach(s -> buf.writeUtf(s));
                buf.writeVarInt(pkt.availablePlayers().size());
                pkt.availablePlayers().forEach(s -> buf.writeUtf(s));
            },
            buf -> {
                int fc = buf.readVarInt();
                List<FactionSummary> factions = new ArrayList<>(fc);
                for (int i = 0; i < fc; i++) factions.add(FactionSummary.fromNetwork(buf));
                int oc = buf.readVarInt();
                List<String> other = new ArrayList<>(oc);
                for (int i = 0; i < oc; i++) other.add(buf.readUtf(128));
                int pc = buf.readVarInt();
                List<String> players = new ArrayList<>(pc);
                for (int i = 0; i < pc; i++) players.add(buf.readUtf(50));
                return new SyncAllFactionsPacket(factions, other, players);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncAllFactionsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(packet);
        });
    }

    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncAllFactionsPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof com.admin82.factions.screen.FactionTableScreen screen) {
            screen.getMenu().updateAllFactions(packet.factions(), packet.otherClaimedChunks(), packet.availablePlayers());
        }
    }
}