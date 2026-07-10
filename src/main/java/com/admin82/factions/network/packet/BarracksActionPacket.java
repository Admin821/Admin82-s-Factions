package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.barracks.BarracksData;
import com.admin82.factions.barracks.KitData;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.menu.BarracksMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Client → Server: all barracks-related actions.
 *
 * {@code data} usage by action:
 *   CREATE_KIT   — data = kitName
 *   DELETE_KIT   — data = kitName
 *   LOAD_KIT     — data = kitName     (server loads kit items into staging handler)
 *   QUICK_TAKE   — (no data)          (server sends OpenKitSelectionPacket back)
 */
public record BarracksActionPacket(Action action, @Nullable String data, int lifeSlot,
                                   @Nullable UUID unused) implements CustomPacketPayload {

    public enum Action {
        CREATE_KIT, DELETE_KIT, LOAD_KIT, QUICK_TAKE
    }

    public static final Type<BarracksActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "barracks_action"));

    public static final StreamCodec<FriendlyByteBuf, BarracksActionPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.action().ordinal());
                        buf.writeBoolean(pkt.data() != null);
                        if (pkt.data() != null) buf.writeUtf(pkt.data(), 64);
                        buf.writeVarInt(pkt.lifeSlot());
                    },
                    buf -> {
                        Action a = Action.values()[buf.readVarInt()];
                        String data = buf.readBoolean() ? buf.readUtf(64) : null;
                        int lifeSlot = buf.readVarInt();
                        return new BarracksActionPacket(a, data, lifeSlot, null);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(BarracksActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            var server = sp.getServer();
            if (server == null) return;

            // Verify player has an open BarracksMenu
            if (!(sp.containerMenu instanceof BarracksMenu bMenu)) return;
            UUID factionId = bMenu.getLinkedFactionId();
            if (factionId == null) return;
            UUID ownerId = sp.getUUID();

            // Verify player is in that faction
            FactionManager fmgr = FactionManager.get(server);
            Faction faction = fmgr.getFaction(factionId);
            if (faction == null || !faction.hasMember(sp.getUUID())) return;

            BarracksData bData = BarracksData.get(server);

            switch (pkt.action()) {
                case CREATE_KIT -> {
                    String name = pkt.data();
                    if (name == null || name.isBlank() || name.length() > 32) return;
                    if (bData.createKit(ownerId, name)) {
                        syncToPlayer(sp, ownerId, bData);
                    } else {
                        sp.displayClientMessage(
                                Component.literal("§cKit name already exists or limit reached."), true);
                    }
                }
                case DELETE_KIT -> {
                    String name = pkt.data();
                    if (name == null) return;
                    bData.deleteKit(ownerId, name);
                    if (name.equals(bMenu.getCurrentEditingKitName())) {
                        bMenu.serverClearStaging();
                    }
                    syncToPlayer(sp, ownerId, bData);
                }
                case LOAD_KIT -> {
                    String name = pkt.data();
                    if (name == null) return;
                    bMenu.serverLoadKit(name);
                    // No SyncBarracksPacket needed — staging items sync via vanilla slot sync
                }
                case QUICK_TAKE -> handleQuickTake(sp, ownerId, bData);
            }
        });
    }

    private static void handleQuickTake(ServerPlayer sp, UUID ownerId, BarracksData bData) {
        List<KitData> kits = new java.util.ArrayList<>(bData.getKits(ownerId));
        if (kits.isEmpty()) {
            sp.displayClientMessage(
                    Component.literal("§cNo kits available in the Barracks."), true);
            return;
        }
        PacketDistributor.sendToPlayer(sp, OpenKitSelectionPacket.fromKits(kits));
    }

    /** Sends updated kit names to the player. */
    static void syncToPlayer(ServerPlayer sp, UUID ownerId, BarracksData bData) {
        List<String> names = bData.getKits(ownerId).stream()
                .map(KitData::getName).toList();
        PacketDistributor.sendToPlayer(sp, new SyncBarracksPacket(names));
    }
}
