package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.faction.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record DisbandFactionPacket() implements CustomPacketPayload {

    public static final Type<DisbandFactionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "disband_faction")
    );

    public static final StreamCodec<FriendlyByteBuf, DisbandFactionPacket> STREAM_CODEC =
            StreamCodec.unit(new DisbandFactionPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(DisbandFactionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            FactionManager manager = FactionManager.get(player.server);
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            if (faction == null || !faction.getOwnerId().equals(player.getUUID())) return;

            UUID factionId = faction.getId();
            String factionName = faction.getName();

            // Unlink the block entity so the table becomes freely usable again
            FactionManager.TableLocation table = manager.getFactionTable(factionId);
            if (table != null) {
                ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(table.dimension()));
                ServerLevel tableLevel = player.server.getLevel(dimKey);
                if (tableLevel != null && tableLevel.getBlockEntity(table.pos()) instanceof FactionTableBlockEntity be) {
                    be.setLinkedFactionId(null);
                }
            }

            // Notify all online members
            for (FactionMember member : faction.getMembers()) {
                ServerPlayer memberPlayer = player.server.getPlayerList().getPlayer(member.getUuid());
                if (memberPlayer != null) {
                    PacketDistributor.sendToPlayer(memberPlayer, new SyncFactionDataPacket(null));
                    memberPlayer.displayClientMessage(
                            Component.literal("§cFaction '§e" + factionName + "§c' has been disbanded by its owner."), false);
                }
            }

            manager.disbandFaction(factionId);
        });
    }
}
