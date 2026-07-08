package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.Config;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.VassalManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Client → Server: post-war conquest decisions and vassal management actions.
 */
public record ConquestActionPacket(Action action, UUID targetFactionId) implements CustomPacketPayload {

    public enum Action {
        MAKE_VASSAL,    // conqueror chooses to make defeated faction a vassal
        TAKE_ALL,       // conqueror seizes vault + releases all claims
        COLLECT_TAX,    // suzerain collects accumulated tax from a vassal
        FREE_VASSAL,    // suzerain grants independence
        BUYOUT          // vassal pays lump sum for independence
    }

    public static final Type<ConquestActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "conquest_action")
    );

    public static final StreamCodec<FriendlyByteBuf, ConquestActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> { buf.writeVarInt(pkt.action().ordinal()); buf.writeUUID(pkt.targetFactionId()); },
            buf -> new ConquestActionPacket(Action.values()[buf.readVarInt()], buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ── Server handler ────────────────────────────────────────────────────────

    public static void handle(ConquestActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer   sp    = (ServerPlayer) ctx.player();
            FactionManager fmgr  = FactionManager.get(sp.server);
            EconomyManager eco   = EconomyManager.get(sp.server);
            VassalManager  vmgr  = VassalManager.get(sp.server);

            Faction myFaction = fmgr.getFactionForPlayer(sp.getUUID());
            if (myFaction == null) return;
            FactionMember member = myFaction.getMember(sp.getUUID());
            if (member == null) return;

            switch (pkt.action()) {
                // ── Conquest decisions (require pending conquest + admin+ rank) ──
                case MAKE_VASSAL -> {
                    UUID conquered = vmgr.getConqueredFaction(myFaction.getId());
                    if (!pkt.targetFactionId().equals(conquered)) return;
                    if (member.getRole().getLevel() < FactionRole.ADMIN.getLevel()) return;
                    Faction defeated = fmgr.getAllFactions().get(pkt.targetFactionId());
                    if (defeated == null) return;

                    vmgr.makeVassal(pkt.targetFactionId(), myFaction.getId());
                    vmgr.clearPendingConquest(myFaction.getId());

                    notifyFaction(sp.server, myFaction,
                            Component.literal("§6[Conquest] §f" + defeated.getName()
                                    + " §6is now your vassal state!"));
                    notifyFaction(sp.server, defeated,
                            Component.literal("§c[Conquest] §eYour faction has become a vassal of §f"
                                    + myFaction.getName()
                                    + "§e. Earn independence by paying your taxes, or buy it outright."));
                    sp.closeContainer();
                }

                case TAKE_ALL -> {
                    UUID conquered = vmgr.getConqueredFaction(myFaction.getId());
                    if (!pkt.targetFactionId().equals(conquered)) return;
                    if (member.getRole().getLevel() < FactionRole.ADMIN.getLevel()) return;
                    Faction defeated = fmgr.getAllFactions().get(pkt.targetFactionId());
                    if (defeated == null) return;

                    // Transfer vault
                    long loot = eco.getVault(defeated.getId());
                    eco.setVault(defeated.getId(), 0);
                    eco.addVault(myFaction.getId(), loot);

                    // Transfer all land claims to the winner
                    var claims = new ArrayList<>(defeated.getLandClaims());
                    for (var claim : claims) {
                        // Unclaim from defeated, then add directly to winner
                        defeated.removeClaim(claim.chunkX(), claim.chunkZ(), claim.dimension().toString());
                        myFaction.addClaim(claim);
                    }
                    fmgr.setDirty();

                    String defeatedName = defeated.getName();
                    vmgr.clearPendingConquest(myFaction.getId());

                    notifyFaction(sp.server, myFaction,
                            Component.literal("§6[Conquest] §a§lVICTORY! Seized §e" + Currency.format(loot)
                                    + " §aand §e" + claims.size() + " claim"
                                    + (claims.size() == 1 ? "" : "s") + " §afrom §f" + defeatedName + "§a!"));

                    // Disband the defeated faction (removes their table, notifies members)
                    com.admin82.factions.FactionCommands.performDisband(sp.server, defeated.getId(),
                            Component.literal("§c[Conquest] Your faction §e" + defeatedName
                                    + " §chas been conquered by §e" + myFaction.getName()
                                    + "§c! All claims and vault have been seized."));

                    sp.closeContainer();
                }

                // ── Suzerain management ────────────────────────────────────────
                case COLLECT_TAX -> {
                    UUID suzerainId = vmgr.getSuzerain(pkt.targetFactionId());
                    if (!myFaction.getId().equals(suzerainId)) return;
                    long tax = vmgr.collectTax(pkt.targetFactionId());
                    if (tax > 0) {
                        eco.addVault(myFaction.getId(), tax);
                        sp.displayClientMessage(Component.literal("§a[Vassal] Collected §e"
                                + Currency.format(tax) + " §ain taxes from your vassal."), false);
                    } else {
                        sp.displayClientMessage(Component.literal("§7[Vassal] No taxes have accumulated yet."), false);
                    }
                }

                case FREE_VASSAL -> {
                    UUID suzerainId = vmgr.getSuzerain(pkt.targetFactionId());
                    if (!myFaction.getId().equals(suzerainId)) return;
                    if (member.getRole().getLevel() < FactionRole.ADMIN.getLevel()) return;
                    Faction vassal = fmgr.getAllFactions().get(pkt.targetFactionId());
                    vmgr.freeVassal(pkt.targetFactionId());
                    String vassalName = vassal != null ? vassal.getName() : "?";
                    notifyFaction(sp.server, myFaction,
                            Component.literal("§a[Vassal] You granted independence to §e" + vassalName + "§a."));
                    if (vassal != null) notifyFaction(sp.server, vassal,
                            Component.literal("§a[Vassal] §f" + myFaction.getName()
                                    + " §ahas granted your faction full independence!"));
                }

                // ── Vassal buyout ──────────────────────────────────────────────
                case BUYOUT -> {
                    UUID suzerainId = vmgr.getSuzerain(myFaction.getId());
                    if (suzerainId == null) return;
                    long cost = Config.VASSAL_BUYOUT_COPPER.get();
                    long vault = eco.getVault(myFaction.getId());
                    if (vault < cost) {
                        sp.displayClientMessage(Component.literal(
                                "§cInsufficient vault. Need §e" + Currency.format(cost)
                                        + "§c, have §e" + Currency.format(vault) + "§c."), false);
                        return;
                    }
                    eco.deductVault(myFaction.getId(), cost);
                    eco.addVault(suzerainId, cost);
                    Faction suzerain = fmgr.getAllFactions().get(suzerainId);
                    vmgr.freeVassal(myFaction.getId());
                    sp.displayClientMessage(Component.literal("§a[Vassal] Independence purchased for §e"
                            + Currency.format(cost) + "§a. Your faction is free!"), false);
                    if (suzerain != null) notifyFaction(sp.server, suzerain,
                            Component.literal("§a[Vassal] §f" + myFaction.getName()
                                    + " §apurchased independence for §e" + Currency.format(cost)));
                }
            }
        });
    }

    private static void notifyFaction(net.minecraft.server.MinecraftServer server,
                                      Faction faction, Component msg) {
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) sp.displayClientMessage(msg, false);
        }
    }
}
