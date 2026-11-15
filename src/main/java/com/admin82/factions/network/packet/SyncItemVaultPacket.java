package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.menu.ItemVaultMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server → client: what's in an item vault, and what the viewer may do with it. */
public record SyncItemVaultPacket(int kind, UUID id, String title, int level, int maxLevel, int maxKinds, long maxPerKind,
                                  List<Line> lines, boolean canWithdraw, boolean canUpgrade, long upgradeCost,
                                  String upgradeNote, long coins) implements CustomPacketPayload {

    /** One kind of item and how many are stored. */
    public record Line(ItemStack item, long count) {}

    public static final Type<SyncItemVaultPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_item_vault"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncItemVaultPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.kind);
                buf.writeUUID(p.id);
                buf.writeUtf(p.title, 128);
                buf.writeVarInt(p.level);
                buf.writeVarInt(p.maxLevel);
                buf.writeVarInt(p.maxKinds);
                buf.writeVarLong(p.maxPerKind);
                buf.writeVarInt(p.lines.size());
                for (Line l : p.lines) {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, l.item);
                    buf.writeVarLong(l.count);
                }
                buf.writeBoolean(p.canWithdraw);
                buf.writeBoolean(p.canUpgrade);
                buf.writeVarLong(p.upgradeCost);
                buf.writeUtf(p.upgradeNote, 128);
                buf.writeLong(p.coins);
            },
            buf -> {
                int kind = buf.readVarInt();
                UUID id = buf.readUUID();
                String title = buf.readUtf(128);
                int level = buf.readVarInt();
                int maxLevel = buf.readVarInt();
                int maxKinds = buf.readVarInt();
                long maxPer = buf.readVarLong();
                int n = buf.readVarInt();
                List<Line> lines = new ArrayList<>(n);
                for (int i = 0; i < n; i++) lines.add(new Line(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readVarLong()));
                return new SyncItemVaultPacket(kind, id, title, level, maxLevel, maxKinds, maxPer, lines,
                        buf.readBoolean(), buf.readBoolean(), buf.readVarLong(), buf.readUtf(128), buf.readLong());
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncItemVaultPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.containerMenu instanceof ItemVaultMenu menu) {
                menu.updateData(p);
            }
        });
    }
}
