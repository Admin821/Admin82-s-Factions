package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.Treasury;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionCombatEvents;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionRole;
import com.admin82.factions.faction.UpgradeService;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.outpost.OutpostService;
import com.admin82.factions.registry.ModItems;
import com.admin82.factions.war.ActiveWar;
import com.admin82.factions.war.WarManager;
import com.admin82.factions.war.WarPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Client → Server: actions on an Outpost Manager block.
 */
public record OutpostActionPacket(Action action, UUID outpostId, long amount, String text) implements CustomPacketPayload {

    public enum Action { SET_WAR_SPAWN, MOVE, DELETE, TELEPORT, OPEN_COINS, OPEN_MANAGER, UPGRADE, RENAME }

    public OutpostActionPacket(Action action, UUID outpostId) {
        this(action, outpostId, 0, "");
    }

    public OutpostActionPacket(Action action, UUID outpostId, long amount) {
        this(action, outpostId, amount, "");
    }

    public static final Type<OutpostActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "outpost_action"));

    public static final StreamCodec<FriendlyByteBuf, OutpostActionPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> { buf.writeVarInt(pkt.action().ordinal()); buf.writeUUID(pkt.outpostId()); buf.writeVarLong(pkt.amount()); buf.writeUtf(pkt.text(), 64); },
                    buf -> {
                        int a = buf.readVarInt();
                        return new OutpostActionPacket(Action.values()[Math.max(0, Math.min(Action.values().length - 1, a))],
                                buf.readUUID(), buf.readVarLong(), buf.readUtf(64));
                    });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OutpostActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            switch (pkt.action()) {
                case SET_WAR_SPAWN -> handleSetWarSpawn(sp, pkt.outpostId());
                case MOVE          -> handleRemove(sp, pkt.outpostId(), true);
                case DELETE        -> handleRemove(sp, pkt.outpostId(), false);
                case TELEPORT      -> handleTeleport(sp, pkt.outpostId());
                case OPEN_COINS, OPEN_MANAGER, UPGRADE, RENAME -> {
                    OutpostEntry entry = OutpostData.get(sp.server).getOutpost(pkt.outpostId());
                    if (entry == null) return;
                    if (sp.distanceToSqr(entry.managerPos.getCenter()) > 100) {
                        sp.displayClientMessage(Component.literal("§cStand next to the outpost flag."), true);
                        return;
                    }
                    switch (pkt.action()) {
                        case OPEN_COINS -> { OutpostService.openCoins(sp, entry); return; }
                        case OPEN_MANAGER -> { }
                        case RENAME -> OutpostService.rename(sp, entry, pkt.text());
                        default -> UpgradeService.upgrade(sp,
                                UpgradeService.Track.OUTPOST, entry.id);
                    }
                    com.admin82.factions.network.InventorySync.push(sp);
                    if (OutpostData.get(sp.server).getOutpost(entry.id) != null) OutpostService.openManager(sp, entry);
                }
            }
        });
    }

    // SET_WAR_SPAWN

    private static void handleSetWarSpawn(ServerPlayer sp, UUID outpostId) {
        WarManager warmgr = WarManager.get(sp.server);
        ActiveWar  war    = warmgr.getWarForPlayer(sp.getUUID());
        if (war == null || war.phase != WarPhase.ACTIVE) {
            sp.displayClientMessage(
                    Component.literal("§cYou must be in an active war to set a war spawn."), true);
            return;
        }

        OutpostData  outposts = OutpostData.get(sp.server);
        OutpostEntry entry    = outposts.getOutpost(outpostId);
        if (entry == null) {
            sp.displayClientMessage(Component.literal("§cOutpost not found."), true);
            return;
        }

        FactionManager fmgr    = FactionManager.get(sp.server);
        Faction        faction = fmgr.getFactionForPlayer(sp.getUUID());
        if (faction == null || !faction.getId().equals(entry.ownerFactionId)) {
            sp.displayClientMessage(
                    Component.literal("§cYou can only use your own faction's outpost."), true);
            return;
        }

        BlockPos spawnPos = entry.managerPos.above();
        outposts.setWarSpawn(sp.getUUID(), spawnPos, entry.dimension);
        sp.displayClientMessage(Component.literal("§a[Outpost] War spawn set to this outpost!"), false);
    }

    // MOVE / DELETE

    // TELEPORT

    private static void handleTeleport(ServerPlayer sp, UUID outpostId) {
        OutpostData  outposts = OutpostData.get(sp.server);
        OutpostEntry entry    = outposts.getOutpost(outpostId);
        if (entry == null) { sp.displayClientMessage(Component.literal("§cOutpost not found."), true); return; }

        FactionManager fmgr    = FactionManager.get(sp.server);
        Faction        faction = fmgr.getFactionForPlayer(sp.getUUID());
        if (faction == null || !faction.getId().equals(entry.ownerFactionId)) {
            sp.displayClientMessage(Component.literal("§cYou can only teleport to your own faction's outpost."), true); return;
        }
        if (entry.disintegrating) {
            sp.displayClientMessage(Component.literal("§c[Outpost] Cannot teleport — outpost is disintegrating."), true); return;
        }
        // Same combat lock as /factionreturn, so the outpost can't be used to escape a fight.
        long combatLeft = FactionCombatEvents.getReturnCombatRemainingSeconds(sp);
        if (combatLeft > 0) {
            sp.displayClientMessage(Component.literal("§c[Outpost] You were in a fight. Wait §e" + combatLeft + "s§c before teleporting."), true);
            return;
        }

        // Payment: try physical coins first; if not enough, charge the full cost from wallet.
        EconomyManager eco = EconomyManager.get(sp.server);
        long cost = eco.getTpCostToOutpost();
        if (cost > 0) {
            long inInv = EconomyManager.countCoinsInInventory(sp);
            if (inInv >= cost) {
                EconomyManager.removeCoinsFromInventory(sp, cost);
                Treasury.record(sp.server, Treasury.Source.OUTPOST_TELEPORTS,
                        cost, sp.getGameProfile().getName(), "Outpost teleport");
            } else if (eco.getWallet(sp.getUUID()) >= cost) {
                eco.deductWallet(sp.getUUID(), cost, "Outpost teleport");
                Treasury.record(sp.server, Treasury.Source.OUTPOST_TELEPORTS,
                        cost, sp.getGameProfile().getName(), "Outpost teleport");
            } else {
                long have = inInv + eco.getWallet(sp.getUUID());
                sp.displayClientMessage(Component.literal(
                        "§c[Outpost] Not enough funds! Need §e"
                        + Currency.format(cost)
                        + "§c, have §e" + Currency.format(have) + "§c."), false);
                return;
            }
        }

        ServerLevel level = null;
        for (ServerLevel lvl : sp.server.getAllLevels()) {
            if (lvl.dimension().location().toString().equals(entry.dimension)) { level = lvl; break; }
        }
        if (level == null) { sp.displayClientMessage(Component.literal("§cOutpost dimension not found."), true); return; }

        BlockPos tp = entry.managerPos.above();
        final ServerLevel fl = level;
        sp.teleportTo(fl, tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5, sp.getYRot(), sp.getXRot());
        String costMsg = cost > 0 ? " §8(§e" + Currency.format(cost) + " §8deducted)" : "";
        sp.displayClientMessage(Component.literal("§a[Outpost] Teleported!" + costMsg), false);
    }

    // MOVE / DELETE

    private static String ownerName(ServerPlayer sp, Faction faction) {
        var profile = sp.server.getProfileCache() == null ? null : sp.server.getProfileCache().get(faction.getOwnerId()).orElse(null);
        return profile != null ? profile.getName() : "the faction owner";
    }

    private static void handleRemove(ServerPlayer sp, UUID outpostId, boolean refundItem) {
        OutpostData  outposts = OutpostData.get(sp.server);
        OutpostEntry entry    = outposts.getOutpost(outpostId);
        if (entry == null) {
            sp.displayClientMessage(Component.literal("§cOutpost not found."), true);
            return;
        }

        FactionManager fmgr    = FactionManager.get(sp.server);
        Faction        faction = fmgr.getFactionForPlayer(sp.getUUID());
        if (faction == null || !faction.getId().equals(entry.ownerFactionId)) {
            sp.displayClientMessage(
                    Component.literal("§cOnly members of the owning faction can remove this outpost."), true);
            return;
        }
        var member = faction.getMember(sp.getUUID());
        if (member == null || member.getRole().getLevel() < FactionRole.OFFICER.getLevel()) {
            sp.displayClientMessage(Component.literal("§cOnly officers can move or remove the outpost."), true);
            return;
        }

        // No pulling an outpost out from under an attack.
        if (WarManager.get(sp.server).isFactionInActiveWar(entry.ownerFactionId)) {
            sp.displayClientMessage(Component.literal("§c[Outpost] You can't move or remove an outpost during a war."), true);
            return;
        }
        if (entry.hp < entry.maxHp()) {
            sp.displayClientMessage(Component.literal("§c[Outpost] The flag is damaged. Wait until it has repaired before moving it."), true);
            return;
        }

        // Moving needs the storage box emptied of items first (coins travel with it)
        if (refundItem && !entry.storage.isEmpty()) {
            int kinds = entry.storage.entries().size();
            sp.displayClientMessage(Component.literal("§c[Outpost] Empty the storage box before moving the outpost: it still holds "
                    + kinds + " kind" + (kinds == 1 ? "" : "s") + " of item. (Coins can stay: they move with it.)"), false);
            return;
        }
        // The outpost's screen goes first, so the item handed back below lands in a normal inventory on the client.
        sp.closeContainer();
        ServerLevel level = sp.serverLevel();
        if (refundItem) {
            // Moving packs the outpost (name, level, storage, coins) into the item, to be placed again as it was
            ItemStack refund = com.admin82.factions.outpost.OutpostPack.pack(entry, sp.server.registryAccess());
            OutpostService.dissolve(sp.server, entry, "moved");
            if (!sp.getInventory().add(refund)) {
                level.addFreshEntity(new ItemEntity(
                        level, sp.getX(), sp.getY(), sp.getZ(), refund));
            }
            sp.displayClientMessage(Component.literal("§a[Outpost] §f" + entry.name + " §apacked up. Its level and coins "
                    + "are inside the Outpost item: place it to set it up again. §7Don't lose the item!"), false);
        } else {
            // Deleting: items to the faction item vault (overflow to the owner's My Market), coins to the faction vault
            var went = OutpostService.dissolve(sp.server, entry, "removed");
            Faction owner = FactionManager.get(sp.server).getFaction(entry.ownerFactionId);
            sp.displayClientMessage(Component.literal("§c[Outpost] Outpost deleted. " + went.describe(owner != null
                    ? net.minecraft.Util.NIL_UUID.equals(owner.getOwnerId()) ? "the owner" : ownerName(sp, owner) : "the owner")), false);
        }
    }
}
