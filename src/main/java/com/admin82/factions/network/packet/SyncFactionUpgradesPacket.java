package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.menu.factiontable.FactionTableMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Server → client: everything the Faction Upgrades tab shows. */
public record SyncFactionUpgradesPacket(int warbondsInVault, int warbondsCarried, boolean canUpgrade, List<Row> rows)
        implements CustomPacketPayload {

    /** One upgrade track. {@code cost} is 0 when it's fully upgraded; {@code outpostId} is set for outpost rows. */
    public record Row(int track, String outpostId, String name, int level, int maxLevel, int cost, String now, String next) {}

    public static final Type<SyncFactionUpgradesPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_faction_upgrades"));

    public static final StreamCodec<FriendlyByteBuf, SyncFactionUpgradesPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.warbondsInVault);
                buf.writeVarInt(p.warbondsCarried);
                buf.writeBoolean(p.canUpgrade);
                buf.writeVarInt(p.rows.size());
                for (Row r : p.rows) {
                    buf.writeVarInt(r.track); buf.writeUtf(r.outpostId, 64); buf.writeUtf(r.name, 96);
                    buf.writeVarInt(r.level); buf.writeVarInt(r.maxLevel); buf.writeVarInt(r.cost);
                    buf.writeUtf(r.now, 128); buf.writeUtf(r.next, 128);
                }
            },
            buf -> {
                int vault = buf.readVarInt(), carried = buf.readVarInt();
                boolean can = buf.readBoolean();
                int n = buf.readVarInt();
                List<Row> rows = new ArrayList<>(n);
                for (int i = 0; i < n; i++) rows.add(new Row(buf.readVarInt(), buf.readUtf(64), buf.readUtf(96),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(128), buf.readUtf(128)));
                return new SyncFactionUpgradesPacket(vault, carried, can, rows);
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncFactionUpgradesPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.containerMenu instanceof FactionTableMenu menu) {
                menu.updateUpgrades(p);
            }
        });
    }
}
