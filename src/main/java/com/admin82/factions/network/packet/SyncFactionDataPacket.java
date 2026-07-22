package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.Faction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;

public record SyncFactionDataPacket(@Nullable Faction faction, double claimRateMultiplier) implements CustomPacketPayload {

    public SyncFactionDataPacket(@Nullable Faction faction) {
        this(faction, -1.0);
    }

    public static final Type<SyncFactionDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_faction_data")
    );

    public static final StreamCodec<FriendlyByteBuf, SyncFactionDataPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                if (pkt.faction() != null) {
                    buf.writeBoolean(true);
                    pkt.faction().toNetwork(buf);
                } else {
                    buf.writeBoolean(false);
                }
                buf.writeDouble(pkt.claimRateMultiplier());
            },
            buf -> new SyncFactionDataPacket(buf.readBoolean() ? Faction.fromNetwork(buf) : null, buf.readDouble())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncFactionDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                handleClient(packet);
            }
        });
    }

    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncFactionDataPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof com.admin82.factions.screen.FactionTableScreen screen) {
            if (packet.claimRateMultiplier() > 0) screen.updateClaimRate(packet.claimRateMultiplier());
            screen.updateFactionData(packet.faction());
        }
    }
}
