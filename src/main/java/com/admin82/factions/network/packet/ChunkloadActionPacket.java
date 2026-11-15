package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.Config;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.Treasury;
import com.admin82.factions.faction.ChunkLoadManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionMember;
import com.admin82.factions.faction.FactionRole;
import com.admin82.factions.faction.LandClaim;
import com.admin82.factions.war.VassalManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: buy a chunkloading deed for an owned chunk, or switch its chunkloading on/off. */
public record ChunkloadActionPacket(Action action, int chunkX, int chunkZ, String dimension, boolean enabled)
        implements CustomPacketPayload {
    public enum Action { PURCHASE, SET_ENABLED }

    public static final Type<ChunkloadActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "chunkload_action"));

    public static final StreamCodec<FriendlyByteBuf, ChunkloadActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.action.ordinal());
                buf.writeInt(packet.chunkX);
                buf.writeInt(packet.chunkZ);
                buf.writeUtf(packet.dimension);
                buf.writeBoolean(packet.enabled);
            },
            buf -> new ChunkloadActionPacket(Action.values()[buf.readVarInt()], buf.readInt(), buf.readInt(),
                    buf.readUtf(256), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ChunkloadActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            FactionManager manager = FactionManager.get(player.server);
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            if (faction == null) return;
            FactionMember member = faction.getMember(player.getUUID());
            if (member == null || member.getRole().getLevel() < FactionRole.OFFICER.getLevel()) {
                player.displayClientMessage(Component.literal("§cOnly officers can manage chunkloading."), true);
                return;
            }
            LandClaim claim = faction.getClaim(packet.chunkX, packet.chunkZ, packet.dimension);
            if (claim == null) {
                player.displayClientMessage(Component.literal("§cYour faction does not own that chunk."), true);
                return;
            }

            EconomyManager eco = EconomyManager.get(player.server);
            switch (packet.action) {
                case PURCHASE -> {
                    if (claim.chunkloadDeed()) {
                        player.displayClientMessage(Component.literal("§eThis chunk already has a chunkloading deed."), true);
                        return;
                    }
                    long cost = claim.chunkloadDeedCost();
                    if (!eco.deductVault(faction.getId(), cost, "Chunkload deed " + packet.chunkX + ", " + packet.chunkZ
                            + " by " + player.getGameProfile().getName())) {
                        player.displayClientMessage(Component.literal("§cNot enough funds in the faction vault!"
                                + " §7Need §e" + Currency.format(cost)
                                + " §7(vault: §e" + Currency.format(eco.getVault(faction.getId())) + "§7)"), true);
                        return;
                    }
                    manager.updateClaim(faction.getId(), claim.withChunkload(true, true));
                    Treasury.record(player.server, Treasury.Source.CHUNKLOAD_DEEDS, cost, Treasury.factionPayer(faction.getName()),
                            "Chunk " + packet.chunkX + ", " + packet.chunkZ + " by " + player.getGameProfile().getName());
                    VassalManager vassals = VassalManager.get(player.server);
                    if (vassals.isVassal(faction.getId())) {
                        vassals.accumulateTax(faction.getId(), Math.max(1, cost * Config.VASSAL_TAX_PERCENT.get() / 100));
                    }
                    player.displayClientMessage(Component.literal("§aChunkloading deed purchased for §e"
                            + Currency.format(cost) + "§a. This chunk now stays loaded (upkeep doubled)."), true);
                }
                case SET_ENABLED -> {
                    if (!claim.chunkloadDeed()) {
                        player.displayClientMessage(Component.literal("§cBuy a chunkloading deed for this chunk first."), true);
                        return;
                    }
                    if (claim.chunkloadEnabled() == packet.enabled) return;
                    manager.updateClaim(faction.getId(), claim.withChunkload(true, packet.enabled));
                    player.displayClientMessage(Component.literal(packet.enabled
                            ? "§aChunkloading enabled. Upkeep for this chunk is doubled."
                            : "§eChunkloading disabled. Upkeep for this chunk is back to normal."), true);
                }
            }

            ChunkLoadManager.sync(player.server);
            // Write faction + vault data to disk now so a crash cannot lose a purchase or toggle.
            player.server.overworld().getDataStorage().save();

            Faction updated = manager.getFactionForPlayer(player.getUUID());
            for (FactionMember online : updated.getMembers()) {
                ServerPlayer target = player.server.getPlayerList().getPlayer(online.getUuid());
                if (target == null) continue;
                PacketDistributor.sendToPlayer(target, new SyncFactionDataPacket(updated, eco.getClaimRateMultiplier()));
                PacketDistributor.sendToPlayer(target, new SyncEconomyPacket(
                        eco.getWallet(target.getUUID()), eco.getVault(updated.getId())));
            }
        });
    }
}
