package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.*;
import net.minecraft.core.SectionPos;
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
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

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

    // Server handler

    public static void handle(RespondWarDemandPacket pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer           player = (ServerPlayer) context.player();
            MinecraftServer        server = player.server;
            FactionManager         fmgr   = FactionManager.get(server);
            WarManager             wmgr   = WarManager.get(server);
            EconomyManager         eco    = EconomyManager.get(server);
            WarNegotiationsManager negMgr = WarNegotiationsManager.get(server);

            WarDemand demand = negMgr.getDemand(pkt.demandId());
            if (demand == null || demand.status != WarDemand.Status.PENDING) {
                player.displayClientMessage(Component.literal("§cThis demand is no longer valid."), false);
                return;
            }
            if (demand.isExpired()) {
                negMgr.resolveDemand(pkt.demandId(), WarDemand.Status.EXPIRED);
                player.displayClientMessage(Component.literal("§cThis demand has expired."), false);
                broadcastDemands(server, demand, negMgr, fmgr);
                return;
            }

            Faction mine = fmgr.getFactionForPlayer(player.getUUID());
            FactionMember member = mine != null ? mine.getMember(player.getUUID()) : null;
            if (member == null || member.getRole().getLevel() < FactionRole.OFFICER.getLevel()) {
                player.displayClientMessage(Component.literal("§cOfficer rank or higher required."), false);
                return;
            }

            // Withdraw (the sending faction takes its own demand back)
            if (mine.getId().equals(demand.senderFactionId)) {
                if (pkt.accepted()) {
                    player.displayClientMessage(Component.literal("§cYou cannot accept your own demand."), false);
                    return;
                }
                negMgr.resolveDemand(pkt.demandId(), WarDemand.Status.WITHDRAWN);
                SendWarDemandPacket.notifyFaction(server, mine, Component.literal(
                        "§e[Negotiations] §7" + player.getName().getString() + " withdrew your demand to §c"
                                + demand.receiverFactionName + "§7."));
                Faction receiver = fmgr.getFaction(demand.receiverFactionId);
                if (receiver != null) SendWarDemandPacket.notifyFaction(server, receiver, Component.literal(
                        "§e[Negotiations] §c" + mine.getName() + " §7withdrew their demand."));
                broadcastDemands(server, demand, negMgr, fmgr);
                return;
            }

            if (!mine.getId().equals(demand.receiverFactionId)) {
                player.displayClientMessage(Component.literal("§cYou cannot respond to this demand."), false);
                return;
            }
            Faction receiver = mine;

            // Rejection
            if (!pkt.accepted()) {
                negMgr.resolveDemand(pkt.demandId(), WarDemand.Status.REJECTED);
                Faction sender = fmgr.getFaction(demand.senderFactionId);
                if (sender != null)
                    SendWarDemandPacket.notifyFaction(server, sender,
                            Component.literal("§e[Negotiations] §c" + receiver.getName()
                                    + " §7rejected your " + (demand.isWhitePeace() ? "peace offer." : "demand.")));
                SendWarDemandPacket.notifyFaction(server, receiver,
                        Component.literal("§e[Negotiations] §7" + player.getName().getString() + " rejected §c"
                                + demand.senderFactionName + "§7's " + (demand.isWhitePeace() ? "peace offer." : "demand.")));
                broadcastDemands(server, demand, negMgr, fmgr);
                return;
            }

            // Acceptance, check every term first, then apply them all
            Faction sender = fmgr.getFaction(demand.senderFactionId);
            if (sender == null) {
                player.displayClientMessage(Component.literal("§cSender faction no longer exists."), false);
                negMgr.resolveDemand(pkt.demandId(), WarDemand.Status.EXPIRED);
                broadcastDemands(server, demand, negMgr, fmgr);
                return;
            }
            ActiveWar war = wmgr.getWarBetween(sender.getId(), receiver.getId());
            boolean staleWar = war != null && demand.warId != null && !war.warId.equals(demand.warId);
            if (staleWar || war == null && !receiver.isAtWarWith(sender.getId())) {
                player.displayClientMessage(Component.literal("§cYou are no longer at war with " + sender.getName() + "."), false);
                negMgr.clearWarDemands(demand.warId);
                broadcastDemands(server, demand, negMgr, fmgr);
                return;
            }

            if (demand.moneyAmount > 0 && eco.getVault(receiver.getId()) < demand.moneyAmount) {
                player.displayClientMessage(Component.literal("§cCannot accept: your vault needs §e"
                        + Currency.format(demand.moneyAmount) + "§c but holds §e"
                        + Currency.format(eco.getVault(receiver.getId())) + "§c."), false);
                return;
            }

            Item reqItem = null;
            if (!demand.itemId.isEmpty() && demand.itemCount > 0) {
                ResourceLocation id = ResourceLocation.tryParse(demand.itemId);
                reqItem = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (reqItem == null || reqItem == Items.AIR) {
                    player.displayClientMessage(Component.literal(
                            "§cUnknown item §e" + demand.itemId + "§c. The demand has been cancelled."), false);
                    negMgr.resolveDemand(pkt.demandId(), WarDemand.Status.REJECTED);
                    broadcastDemands(server, demand, negMgr, fmgr);
                    return;
                }
                int available = countOnline(server, receiver, reqItem);
                if (available < demand.itemCount) {
                    player.displayClientMessage(Component.literal("§cCannot accept: online members of your faction have §e"
                            + available + "/" + demand.itemCount + " §f" + reqItem.getDescription().getString()
                            + "§c in their inventories."), false);
                    return;
                }
                if (firstOnline(server, sender) == null) {
                    player.displayClientMessage(Component.literal("§cCannot accept yet: nobody from §e"
                            + sender.getName() + " §cis online to receive the items."), false);
                    return;
                }
            }

            List<LandClaim> releasable = new ArrayList<>();
            if (demand.landChunks > 0) {
                FactionManager.TableLocation table = fmgr.getFactionTable(receiver.getId());
                for (LandClaim c : receiver.getLandClaims()) {
                    boolean core = table != null
                            && c.dimension().toString().equals(table.dimension())
                            && c.chunkX() == SectionPos.blockToSectionCoord(table.pos().getX())
                            && c.chunkZ() == SectionPos.blockToSectionCoord(table.pos().getZ());
                    if (!core) releasable.add(c);
                }
                if (releasable.size() < demand.landChunks) {
                    player.displayClientMessage(Component.literal("§cCannot accept: you only have §e" + releasable.size()
                            + "§c chunks besides your core chunk, but §e" + demand.landChunks + "§c are demanded."), false);
                    return;
                }
            }

            // Everything checks out: apply.
            if (demand.moneyAmount > 0) {
                eco.deductVault(receiver.getId(), demand.moneyAmount, "War demand paid to " + sender.getName());
                eco.addVault(sender.getId(), demand.moneyAmount, "War demand paid by " + receiver.getName());
            }
            if (reqItem != null) {
                giveItems(server, sender, takeItems(server, receiver, reqItem, demand.itemCount));
            }
            if (demand.landChunks > 0) {
                // Release the most recently claimed chunks first.
                for (int i = releasable.size() - 1, released = 0; i >= 0 && released < demand.landChunks; i--, released++) {
                    LandClaim c = releasable.get(i);
                    fmgr.unclaimChunk(receiver.getId(), c.chunkX(), c.chunkZ(), c.dimension().toString());
                }
                ChunkLoadManager.sync(server);
            }
            if (demand.vassalTerm) {
                VassalManager.get(server).makeVassal(receiver.getId(), sender.getId());
                SendWarDemandPacket.notifyFaction(server, sender, Component.literal(
                        "§e[Negotiations] §5" + receiver.getName() + " §eis now your vassal state!"));
                SendWarDemandPacket.notifyFaction(server, receiver, Component.literal(
                        "§e[Negotiations] §5Your faction is now a vassal of §f" + sender.getName() + "§5."));
            }

            // Accepting any demand ends the war.
            negMgr.clearWarDemands(demand.warId);
            FactionWarEvents.endWarByAgreement(server, war, sender.getId(), receiver.getId());

            Component resultMsg = Component.literal("§a[Negotiations] §7Peace agreed between §e" + sender.getName()
                    + "§7 and §e" + receiver.getName() + "§7. The war is over. Terms: " + demand.termsSummary());
            SendWarDemandPacket.notifyFaction(server, sender, resultMsg);
            SendWarDemandPacket.notifyFaction(server, receiver, resultMsg);
            broadcastDemands(server, demand, negMgr, fmgr);
        });
    }

    // Helpers

    @Nullable
    private static ServerPlayer firstOnline(MinecraftServer server, Faction faction) {
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) return sp;
        }
        return null;
    }

    /** Total of {@code item} in the main inventories of every online member (worn armour doesn't count). */
    private static int countOnline(MinecraftServer server, Faction faction, Item item) {
        int found = 0;
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp == null) continue;
            for (ItemStack s : sp.getInventory().items) {
                if (s.getItem() == item) found += s.getCount();
            }
        }
        return found;
    }

    /**
     * Takes {@code required} items from the main inventories of online members and returns the
     * actual stacks taken, so damage, enchantments and contents go along with them.
     */
    private static List<ItemStack> takeItems(MinecraftServer server, Faction faction, Item item, int required) {
        List<ItemStack> taken = new ArrayList<>();
        int rem = required;
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp == null) continue;
            for (ItemStack s : sp.getInventory().items) {
                if (rem <= 0) break;
                if (s.getItem() == item) {
                    int take = Math.min(rem, s.getCount());
                    taken.add(s.split(take));
                    rem -= take;
                }
            }
            sp.getInventory().setChanged();
            if (rem <= 0) break;
        }
        return taken;
    }

    /** Gives the stacks to the first online member; anything that doesn't fit drops at their feet. */
    private static void giveItems(MinecraftServer server, Faction faction, List<ItemStack> stacks) {
        ServerPlayer sp = firstOnline(server, faction);
        if (sp == null) return;
        for (ItemStack stack : stacks) {
            if (!sp.getInventory().add(stack) && !stack.isEmpty()) sp.drop(stack, false);
        }
    }

    private static void broadcastDemands(MinecraftServer server, WarDemand demand,
                                          WarNegotiationsManager negMgr, FactionManager fmgr) {
        SendWarDemandPacket.broadcastDemandsToFaction(
                server, demand.senderFactionId,   demand.warId, negMgr, fmgr);
        SendWarDemandPacket.broadcastDemandsToFaction(
                server, demand.receiverFactionId, demand.warId, negMgr, fmgr);
    }
}
