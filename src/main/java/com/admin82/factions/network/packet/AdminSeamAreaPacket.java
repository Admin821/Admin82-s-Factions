package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.screen.AdminPanelScreen;
import com.admin82.factions.seam.SeamCell;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server → client: every seam (live, chunks with no seam left out) in a square around a chunk, for the admin panel's
 * seam map. Ores are sent once as a palette and chunks as offsets from the centre, so a zoomed-out map stays small.
 */
public record AdminSeamAreaPacket(int centerX, int centerZ, int radius, List<SeamCell> cells) implements CustomPacketPayload {

    /** Furthest the server loads from the map's centre (chunks); further out shows as not loaded. */
    public static final int MAX_RADIUS = 384;
    /** Most seams sent at once (keeps the packet well under the size limit). */
    public static final int MAX_SEAMS = 40_000;

    public static final Type<AdminSeamAreaPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "admin_seam_area"));

    public static final StreamCodec<FriendlyByteBuf, AdminSeamAreaPacket> STREAM_CODEC = StreamCodec.of(
            AdminSeamAreaPacket::write, AdminSeamAreaPacket::read);

    private static void write(FriendlyByteBuf buf, AdminSeamAreaPacket p) {
        buf.writeVarInt(p.centerX);
        buf.writeVarInt(p.centerZ);
        buf.writeVarInt(p.radius);
        Map<String, Integer> palette = new HashMap<>();
        List<String> ores = new ArrayList<>();
        for (SeamCell c : p.cells) {
            if (palette.putIfAbsent(c.ore(), ores.size()) == null) ores.add(c.ore());
        }
        buf.writeVarInt(ores.size());
        for (String ore : ores) buf.writeUtf(ore, 256);
        buf.writeVarInt(p.cells.size());
        for (SeamCell c : p.cells) {
            buf.writeVarInt(zig(c.chunkX() - p.centerX));
            buf.writeVarInt(zig(c.chunkZ() - p.centerZ));
            buf.writeVarInt(palette.get(c.ore()));
            buf.writeByte(c.tier() | (c.endless() ? 0x80 : 0));
            buf.writeVarLong(Math.max(0, c.remaining()));
            buf.writeVarLong(Math.max(0, c.amount()));
        }
    }

    private static AdminSeamAreaPacket read(FriendlyByteBuf buf) {
        int cx = buf.readVarInt(), cz = buf.readVarInt(), r = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), 4096);
        List<String> ores = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ores.add(buf.readUtf(256));
        int count = Math.min(buf.readVarInt(), MAX_SEAMS);
        List<SeamCell> cells = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int x = cx + unzig(buf.readVarInt()), z = cz + unzig(buf.readVarInt());
            int ore = buf.readVarInt();
            int flags = buf.readByte() & 0xFF;
            long remaining = buf.readVarLong(), amount = buf.readVarLong();
            cells.add(new SeamCell(x, z, ore >= 0 && ore < ores.size() ? ores.get(ore) : "", flags & 0x7F,
                    (flags & 0x80) != 0, remaining, amount, 0));
        }
        return new AdminSeamAreaPacket(cx, cz, r, cells);
    }

    private static int zig(int v) { return (v << 1) ^ (v >> 31); }
    private static int unzig(int v) { return (v >>> 1) ^ -(v & 1); }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(AdminSeamAreaPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof AdminPanelScreen screen) {
                screen.getMenu().addSeamArea(p.centerX, p.centerZ, p.radius, p.cells);
            }
        });
    }
}
