package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.Config;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Server → client: the server's config values. The config file is per machine, so without this
 * a client's screens would show its own file's numbers (claim cost, lives, ...) instead of the server's.
 * Sent on login and whenever a setting changes.
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public record SyncServerConfigPacket(Map<String, Double> values) implements CustomPacketPayload {

    public static final Type<SyncServerConfigPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_server_config"));

    public static final StreamCodec<FriendlyByteBuf, SyncServerConfigPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.values.size());
                p.values.forEach((k, v) -> { buf.writeUtf(k, 128); buf.writeDouble(v); });
            },
            buf -> {
                int n = buf.readVarInt();
                Map<String, Double> values = new HashMap<>(n);
                for (int i = 0; i < n; i++) values.put(buf.readUtf(128), buf.readDouble());
                return new SyncServerConfigPacket(values);
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** The server's values, on the client. Empty until the first sync (and in singleplayer it's the same file anyway). */
    private static volatile Map<String, Double> received = Map.of();

    public static void handle(SyncServerConfigPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> received = Map.copyOf(p.values));
    }

    // Reading on the client: the server's value when we have it, otherwise the local file.

    public static int get(ModConfigSpec.IntValue value) {
        Double v = received.get(key(value));
        return v != null ? v.intValue() : value.get();
    }

    public static double get(ModConfigSpec.DoubleValue value) {
        Double v = received.get(key(value));
        return v != null ? v : value.get();
    }

    public static boolean get(ModConfigSpec.BooleanValue value) {
        Double v = received.get(key(value));
        return v != null ? v >= 0.5 : value.get();
    }

    private static String key(ModConfigSpec.ConfigValue<?> value) {
        return String.join(".", value.getPath());
    }

    // Sending

    private static SyncServerConfigPacket current() {
        Map<String, Double> values = new HashMap<>();
        for (var field : Config.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            try {
                Object o = field.get(null);
                if (o instanceof ModConfigSpec.BooleanValue b) values.put(key(b), b.get() ? 1.0 : 0.0);
                else if (o instanceof ModConfigSpec.ConfigValue<?> c && c.get() instanceof Number n) values.put(key(c), n.doubleValue());
            } catch (IllegalAccessException ignored) { }
        }
        return new SyncServerConfigPacket(values);
    }

    /** Sends the current values to everyone online. Safe to call from anywhere on the server. */
    public static void broadcast() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getPlayerList() == null || server.getPlayerList().getPlayerCount() == 0) return;
        PacketDistributor.sendToAllPlayers(current());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, current());
    }
}
