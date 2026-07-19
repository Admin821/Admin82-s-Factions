package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Client → Server: accept or reject a wartime demand.
 */
public record RespondWarDemandPacket(UUID demandId, boolean accepted) implements CustomPacketPayload {

    public static final Type<RespondWarDemandPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "respond_war_demand")
    );

    public static final StreamCodec<FriendlyByteBuf, RespondWarDemandPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> { buf.writeUUID(pkt.demandId()); buf.writeBoolean(pkt.accepted()); },
            buf -> new RespondWarDemandPacket(buf.readUUID(), buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ── Server handler ────────────────────────────────────────────────────────

    public static void handle(RespondWarDemandPacket pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer           player = (ServerPlayer) context.player();
            FactionManager         fmgr   = FactionManager.get(player.server);
            WarManager             wmgr   = WarManager.get(player.server);
            EconomyManager         eco    = EconomyManager.get(player.server);
            WarNegotiationsManager negMgr = WarNegotiationsManager.get(player.server);

            WarDemand demand = negMgr.getDemand(pkt.demandId());
            if (demand == null || demand.status != WarDemand.Status.PENDING) {
                player.displayClientMessage(Component.literal("§cThis demand is no longer valid."), false);
                return;
            }
            if (demand.isExpired()) {
                negMgr.resolveDemand(pkt.demandId(), WarDemand.Status.EXPIRED);
                player.displayClientMessage(Component.literal("§cThis demand has expired."), false);
                return;
            }

            Faction receiver = fmgr.getFactionForPlayer(player.getUUID());
            if (receiver == null || !receiver.getId().equals(demand.receiverFactionId)) {
                player.displayClientMessage(Component.literal("§cYou cannot respond to this demand."), false);
                return;
            }

            FactionMember member = receiver.getMember(player.getUUID());
            if (member == null || member.getRole().getLevel() < FactionRole.OFFICER.getLevel()) {
                player.displayClientMessage(Component.literal("§cOfficer rank or higher required."), false);
                return;
            }

            // ── Rejection ────────────────────────────────────────────────────────
            if (!pkt.accepted()) {
                negMgr.resolveDemand(pkt.demandId(), WarDemand.Status.REJECTED);
                Faction sender = fmgr.getFaction(demand.senderFactionId);
                if (sender != null)
                    SendWarDemandPacket.notifyFaction(player.server, sender,
                            Component.literal("§e[Negotiations] §c" + receiver.getName()
                                    + " §7rejected your demand."));
                SendWarDemandPacket.notifyFaction(player.server, receiver,
                        Component.literal("§e[Negotiations] §7You rejected §c"
                                + demand.senderFactionName + "§7's demand."));
                broadcastDemands(player.server, demand, negMgr, fmgr);
                return;
            }

            // ── Acceptance — resolve each term ────────────────────────────────────
            Faction sender = fmgr.getFaction(demand.senderFactionId);
            if (sender == null) {
                player.displayClientMessage(Component.literal("§cSender faction no longer exists."), false);
                negMgr.resolveDemand(pkt.demandId(), WarDemand.Status.EXPIRED);
                return;
            }

            // Money
            if (demand.moneyAmount > 0) {
                if (!eco.deductVault(receiver.getId(), demand.moneyAmount)) {
                    player.displayClientMessage(Component.literal(
                            "§cCannot accept: your vault lacks §e"
                            + Currency.format(demand.moneyAmount) + "§c."), false);
                    return;
                }
                eco.addVault(sender.getId(), demand.moneyAmount);
            }

            // Items
            if (!demand.itemId.isEmpty() && demand.itemCount > 0) {
                Item reqItem = BuiltInRegistries.ITEM
                        .get(ResourceLocation.tryParse(demand.itemId));
                if (reqItem == null || reqItem == Items.AIR) {
                    player.displayClientMessage(Component.literal(
                            "§cUnknown item §e" + demand.itemId + "§c — demand cancelled."), false);
                    negMgr.resolveDemand(pkt.demandId(), WarDemand.Status.REJECTED);
                    return;
                }
                // Attempt to collect from any online receiver-faction member
                boolean collected = collectItems(player.server, receiver, reqItem, demand.itemCount);
                if (!collected) {
                    // Undo money if already taken
                    if (demand.moneyAmount > 0) {
                        eco.addVault(receiver.getId(), demand.moneyAmount);
                        eco.deductVault(sender.getId(), demand.moneyAmount);
                    }
                    player.displayClientMessage(Component.literal(
                            "§cNo online faction member has §e" + demand.itemCount
                            + "x " + demand.itemId + "§c."), false);
                    return;
                }
                // Deliver items to first online sender member
                giveItems(player.server, sender, reqItem, demand.itemCount);
            }

            // Land — release the most-recently-added chunks
            if (demand.landChunks > 0) {
                var claims = new ArrayList<>(receiver.getLandClaims());
                int released = 0;
                for (int i = claims.size() - 1; i >= 0 && released < demand.landChunks; i--) {
                    var c = claims.get(i);
                    fmgr.unclaimChunk(receiver.getId(), c.chunkX(), c.chunkZ(), c.dimension().toString());
                    released++;
                }
            }

            // Vassal — make receiver a vassal of sender
            if (demand.vassalTerm) {
                VassalManager vassalMgr = VassalManager.get(player.server);
                vassalMgr.makeVassal(receiver.getId(), sender.getId());
                // End the war since terms have been agreed
                ActiveWar war = wmgr.getWarBetween(sender.getId(), receiver.getId());
                if (war != null) wmgr.endWar(war.warId);
                fmgr.endWar(sender.getId(), receiver.getId());
                SendWarDemandPacket.notifyFaction(player.server, sender,
                        Component.literal("§e[Negotiations] §5" + receiver.getName()
                                + " §eis now your vassal state!"));
                SendWarDemandPacket.notifyFaction(player.server, receiver,
                        Component.literal("§e[Negotiations] §5Your faction is now a vassal of §f"
                                + sender.getName() + "§5. The war has ended."));
            }

            negMgr.resolveDemand(pkt.demandId(), WarDemand.Status.ACCEPTED);
            negMgr.clearWarDemands(demand.warId);

            Component resultMsg = buildResultMessage(demand, sender.getName(), receiver.getName());
            SendWarDemandPacket.notifyFaction(player.server, sender, resultMsg);
            SendWarDemandPacket.notifyFaction(player.server, receiver, resultMsg);
            broadcastDemands(player.server, demand, negMgr, fmgr);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean collectItems(MinecraftServer server, Faction faction,
                                        Item item, int required) {
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp == null) continue;
            int found = 0;
            for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
                ItemStack s = sp.getInventory().getItem(i);
                if (s.getItem() == item) found += s.getCount();
            }
            if (found >= required) {
                int rem = required;
                for (int i = 0; i < sp.getInventory().getContainerSize() && rem > 0; i++) {
                    ItemStack s = sp.getInventory().getItem(i);
                    if (s.getItem() == item) {
                        int take = Math.min(rem, s.getCount());
                        s.shrink(take);
                        rem -= take;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static void giveItems(MinecraftServer server, Faction faction,
                                  Item item, int count) {
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) {
                int remaining = count;
                while (remaining > 0) {
                    int stack = Math.min(remaining, item.getDefaultMaxStackSize());
                    sp.getInventory().add(new ItemStack(item, stack));
                    remaining -= stack;
                }
                return; // give to first online member only
            }
        }
    }

    private static Component buildResultMessage(WarDemand d, String senderName, String receiverName) {
        var sb = new StringBuilder("§a[Negotiations] §7Peace accepted between §e")
                .append(senderName).append("§7 and §e").append(receiverName)
                .append("§7. Terms: ").append(d.termsSummary());
        return Component.literal(sb.toString());
    }

    private static void broadcastDemands(MinecraftServer server, WarDemand demand,
                                          WarNegotiationsManager negMgr, FactionManager fmgr) {
        SendWarDemandPacket.broadcastDemandsToFaction(
                server, demand.senderFactionId,   demand.warId, negMgr, fmgr);
        SendWarDemandPacket.broadcastDemandsToFaction(
                server, demand.receiverFactionId, demand.warId, negMgr, fmgr);
    }
}
