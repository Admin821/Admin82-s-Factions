package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.WarManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Client → server: propose, accept, decline, cancel or break an alliance (officers and above). */
public record AllianceActionPacket(Action action, UUID targetFactionId) implements CustomPacketPayload {

    public enum Action { PROPOSE, ACCEPT, DECLINE, CANCEL, BREAK, REFRESH }

    public static final Type<AllianceActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "alliance_action"));

    public static final StreamCodec<FriendlyByteBuf, AllianceActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> { buf.writeVarInt(pkt.action.ordinal()); buf.writeUUID(pkt.targetFactionId); },
            buf -> {
                int ordinal = buf.readVarInt();
                Action action = ordinal >= 0 && ordinal < Action.values().length ? Action.values()[ordinal] : Action.REFRESH;
                return new AllianceActionPacket(action, buf.readUUID());
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(AllianceActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            MinecraftServer server = sp.server;
            FactionManager fmgr = FactionManager.get(server);
            AllianceManager am = AllianceManager.get(server);
            Faction mine = fmgr.getFactionForPlayer(sp.getUUID());
            if (mine == null) return;
            if (pkt.action == Action.REFRESH) {
                PacketDistributor.sendToPlayer(sp, SyncAlliancesPacket.forFaction(server, mine.getId()));
                return;
            }
            FactionMember member = mine.getMember(sp.getUUID());
            if (member == null || member.getRole().getLevel() < FactionRole.OFFICER.getLevel()) {
                fail(sp, "Officer rank or higher is needed to manage alliances.");
                return;
            }
            Faction target = fmgr.getFaction(pkt.targetFactionId);
            if (target == null || target.getId().equals(mine.getId())) { fail(sp, "That faction no longer exists."); return; }
            UUID me = mine.getId(), them = target.getId();
            String who = sp.getName().getString();

            switch (pkt.action) {
                case PROPOSE -> {
                    if (am.areAllied(me, them)) { fail(sp, "You are already allied with " + target.getName() + "."); return; }
                    if (mine.isAtWarWith(them) || WarManager.get(server).getWarBetween(me, them) != null) {
                        fail(sp, "You are at war with " + target.getName() + ". End the war before proposing an alliance.");
                        return;
                    }
                    if (am.hasProposal(them, me)) { // they already asked us: accept straight away
                        am.form(me, them);
                        announceFormed(server, mine, target);
                        break;
                    }
                    if (am.hasProposal(me, them)) { fail(sp, "You already proposed an alliance to " + target.getName() + "."); return; }
                    am.propose(me, them);
                    notify(server, mine, "§b[Alliance] §7" + who + " proposed an alliance to §e" + target.getName() + "§7.");
                    notify(server, target, "§b[Alliance] §e" + mine.getName() + " §7proposes an alliance. Answer on the Faction Table → §bAlliances§7.");
                }
                case ACCEPT -> {
                    if (!am.hasProposal(them, me)) { fail(sp, "That proposal is no longer open."); return; }
                    if (mine.isAtWarWith(them) || WarManager.get(server).getWarBetween(me, them) != null) {
                        fail(sp, "You are at war with " + target.getName() + ". End the war first.");
                        return;
                    }
                    am.form(me, them);
                    announceFormed(server, mine, target);
                }
                case DECLINE -> {
                    if (!am.hasProposal(them, me)) { fail(sp, "That proposal is no longer open."); return; }
                    am.removeProposal(them, me);
                    notify(server, mine, "§b[Alliance] §7" + who + " declined §e" + target.getName() + "§7's alliance proposal.");
                    notify(server, target, "§b[Alliance] §e" + mine.getName() + " §7declined your alliance proposal.");
                }
                case CANCEL -> {
                    if (!am.hasProposal(me, them)) { fail(sp, "There's no proposal to withdraw."); return; }
                    am.removeProposal(me, them);
                    notify(server, mine, "§b[Alliance] §7" + who + " withdrew the alliance proposal to §e" + target.getName() + "§7.");
                }
                case BREAK -> {
                    if (!am.areAllied(me, them)) { fail(sp, "You are not allied with " + target.getName() + "."); return; }
                    am.breakAlliance(me, them);
                    long left = am.cooldownRemainingMs(me, them);
                    String tail = left > 0 ? " §7Its protections stay in place for §e" + AllianceManager.formatDuration(left) + "§7." : "";
                    notify(server, mine, "§c[Alliance] §7" + who + " broke the alliance with §e" + target.getName() + "§7." + tail);
                    notify(server, target, "§c[Alliance] §e" + mine.getName() + " §7broke your alliance." + tail);
                }
                default -> { }
            }
            SyncAlliancesPacket.sendToFaction(server, me);
            SyncAlliancesPacket.sendToFaction(server, them);
        });
    }

    private static void announceFormed(MinecraftServer server, Faction a, Faction b) {
        notify(server, a, "§a[Alliance] §7You are now allied with §e" + b.getName() + "§7. Neither side can declare war on or claim bounties against the other.");
        notify(server, b, "§a[Alliance] §7You are now allied with §e" + a.getName() + "§7. Neither side can declare war on or claim bounties against the other.");
    }

    private static void notify(MinecraftServer server, Faction faction, String message) {
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) sp.displayClientMessage(Component.literal(message), false);
        }
    }

    private static void fail(ServerPlayer sp, String message) {
        sp.displayClientMessage(Component.literal("§c" + message), false);
    }
}
