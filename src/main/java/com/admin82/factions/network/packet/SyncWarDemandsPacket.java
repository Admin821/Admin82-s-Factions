package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.war.WarDemand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → Client: pushes the full demand list for a given war to a player.
 */
public record SyncWarDemandsPacket(UUID warId, List<WarDemand> demands) implements CustomPacketPayload {

    public static final Type<SyncWarDemandsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_war_demands")
    );

    public static final StreamCodec<FriendlyByteBuf, SyncWarDemandsPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.warId());
                buf.writeVarInt(pkt.demands().size());
                pkt.demands().forEach(d -> d.toNetwork(buf));
            },
            buf -> {
                UUID warId = buf.readUUID();
                int count = buf.readVarInt();
                List<WarDemand> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) list.add(WarDemand.fromNetwork(buf));
                return new SyncWarDemandsPacket(warId, List.copyOf(list));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncWarDemandsPacket pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(pkt);
        });
    }

    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncWarDemandsPacket pkt) {
        var screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (screen instanceof com.admin82.factions.screen.FactionTableScreen fts) {
            fts.updateWarDemands(pkt.warId(), pkt.demands());
        }
    }
}
