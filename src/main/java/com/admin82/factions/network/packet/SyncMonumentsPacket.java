package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.menu.MonumentMenu;
import com.admin82.factions.monument.MonumentView;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SyncMonumentsPacket(List<MonumentView> monuments, @Nullable UUID selectedId)
        implements CustomPacketPayload {
    public static final Type<SyncMonumentsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_monuments"));

    public static final StreamCodec<FriendlyByteBuf, SyncMonumentsPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.monuments.size());
                packet.monuments.forEach(view -> view.write(buf));
                buf.writeBoolean(packet.selectedId != null);
                if (packet.selectedId != null) buf.writeUUID(packet.selectedId);
            },
            buf -> {
                int count = buf.readVarInt();
                List<MonumentView> views = new ArrayList<>();
                for (int i = 0; i < count; i++) views.add(MonumentView.read(buf));
                UUID selected = buf.readBoolean() ? buf.readUUID() : null;
                return new SyncMonumentsPacket(views, selected);
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncMonumentsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.containerMenu instanceof MonumentMenu menu) {
                menu.updateViews(packet.monuments, packet.selectedId);
                if (Minecraft.getInstance().screen instanceof com.admin82.factions.screen.MonumentScreen screen) {
                    screen.refreshContent();
                }
            }
        });
    }
}