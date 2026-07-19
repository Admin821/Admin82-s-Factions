package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.barracks.KitData;
import com.admin82.factions.screen.KitSelectionScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: opens the kit selection screen with previews of all available kits.
 * Sent on respawn (if kits are available) and when the player clicks Quick Take.
 */
public record OpenKitSelectionPacket(List<KitEntry> kits) implements CustomPacketPayload {

    /**
     * Lightweight kit entry for the selection screen.
     * Contains the kit name and a compact array of all 40 item slots.
     */
    /** A unique kit shown on the selection screen. {@code count} = how many copies remain to take. */
    public record KitEntry(String name, ItemStack[] items, int count) {}

    public static final Type<OpenKitSelectionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "open_kit_selection"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenKitSelectionPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.kits().size());
                        for (KitEntry entry : pkt.kits()) {
                            buf.writeUtf(entry.name(), 64);
                            buf.writeVarInt(entry.count());
                            // Write non-empty slots only (sparse)
                            int nonEmpty = 0;
                            for (int i = 0; i < entry.items().length; i++)
                                if (!entry.items()[i].isEmpty()) nonEmpty++;
                            buf.writeVarInt(nonEmpty);
                            for (int i = 0; i < entry.items().length; i++) {
                                ItemStack s = entry.items()[i];
                                if (!s.isEmpty()) {
                                    buf.writeVarInt(i);
                                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
                                }
                            }
                        }
                    },
                    buf -> {
                        int kitCount = buf.readVarInt();
                        List<KitEntry> kits = new ArrayList<>();
                        for (int k = 0; k < kitCount; k++) {
                            String name = buf.readUtf(64);
                            int count = buf.readVarInt();
                            ItemStack[] items = new ItemStack[KitData.SLOT_COUNT];
                            for (int i = 0; i < KitData.SLOT_COUNT; i++) items[i] = ItemStack.EMPTY;
                            int nonEmpty = buf.readVarInt();
                            for (int n = 0; n < nonEmpty; n++) {
                                int idx = buf.readVarInt();
                                ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                                if (idx >= 0 && idx < KitData.SLOT_COUNT) items[idx] = stack;
                            }
                            kits.add(new KitEntry(name, items, count));
                        }
                        return new OpenKitSelectionPacket(kits);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * Builds the packet from a list of KitData objects.
     * Kits with the same name are deduplicated — one card is shown per unique name
     * with a count badge showing how many copies remain to take.
     */
    public static OpenKitSelectionPacket fromKits(List<KitData> kits) {
        // Preserve insertion order; track first-seen items and running counts
        java.util.LinkedHashMap<String, KitEntry> seen = new java.util.LinkedHashMap<>();
        for (KitData kit : kits) {
            String name = kit.getName();
            KitEntry existing = seen.get(name);
            if (existing == null) {
                seen.put(name, new KitEntry(name, kit.getSlotsCopy(), 1));
            } else {
                seen.put(name, new KitEntry(name, existing.items(), existing.count() + 1));
            }
        }
        return new OpenKitSelectionPacket(new ArrayList<>(seen.values()));
    }

    public static void handle(OpenKitSelectionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(pkt);
        });
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(OpenKitSelectionPacket pkt) {
        net.minecraft.client.Minecraft.getInstance()
                .setScreen(new KitSelectionScreen(pkt.kits()));
    }
}
