package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.registry.ModItems;
import com.admin82.factions.war.ActiveWar;
import com.admin82.factions.war.WarManager;
import com.admin82.factions.war.WarPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Client → Server: actions on an Outpost Manager block.
 */
public record OutpostActionPacket(Action action, UUID outpostId) implements CustomPacketPayload {

    public enum Action { SET_WAR_SPAWN, MOVE, DELETE, TELEPORT }

    public static final Type<OutpostActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "outpost_action"));

    public static final StreamCodec<FriendlyByteBuf, OutpostActionPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> { buf.writeVarInt(pkt.action().ordinal()); buf.writeUUID(pkt.outpostId()); },
                    buf -> new OutpostActionPacket(Action.values()[buf.readVarInt()], buf.readUUID()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OutpostActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            switch (pkt.action()) {
                case SET_WAR_SPAWN -> handleSetWarSpawn(sp, pkt.outpostId());
                case MOVE          -> handleRemove(sp, pkt.outpostId(), true);
                case DELETE        -> handleRemove(sp, pkt.outpostId(), false);
                case TELEPORT      -> handleTeleport(sp, pkt.outpostId());
            }
        });
    }

    // ── SET_WAR_SPAWN ─────────────────────────────────────────────────────────

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

    // ── MOVE / DELETE ─────────────────────────────────────────────────────────

    // ── TELEPORT ──────────────────────────────────────────────────────────────────

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

        // Payment: try physical coins first; if not enough, charge the full cost from wallet.
        com.admin82.factions.economy.EconomyManager eco = com.admin82.factions.economy.EconomyManager.get(sp.server);
        long cost = eco.getTpCostToOutpost();
        if (cost > 0) {
            long inInv = com.admin82.factions.economy.EconomyManager.countCoinsInInventory(sp);
            if (inInv >= cost) {
                com.admin82.factions.economy.EconomyManager.removeCoinsFromInventory(sp, cost);
            } else if (eco.getWallet(sp.getUUID()) >= cost) {
                eco.deductWallet(sp.getUUID(), cost);
            } else {
                long have = inInv + eco.getWallet(sp.getUUID());
                sp.displayClientMessage(Component.literal(
                        "§c[Outpost] Not enough funds! Need §e"
                        + com.admin82.factions.economy.Currency.format(cost)
                        + "§c, have §e" + com.admin82.factions.economy.Currency.format(have) + "§c."), false);
                return;
            }
        }

        ServerLevel level = null;
        for (ServerLevel lvl : sp.server.getAllLevels()) {
            if (lvl.dimension().location().toString().equals(entry.dimension)) { level = lvl; break; }
        }
        if (level == null) { sp.displayClientMessage(Component.literal("§cOutpost dimension not found."), true); return; }

        net.minecraft.core.BlockPos tp = entry.managerPos.above();
        final ServerLevel fl = level;
        sp.teleportTo(fl, tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5, sp.getYRot(), sp.getXRot());
        String costMsg = cost > 0 ? " §8(§e" + com.admin82.factions.economy.Currency.format(cost) + " §8deducted)" : "";
        sp.displayClientMessage(Component.literal("§a[Outpost] Teleported!" + costMsg), false);
    }

    // ── MOVE / DELETE ─────────────────────────────────────────────────────────

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

        // Block removal if the outpost is the active war-phase target
        WarManager warmgr = WarManager.get(sp.server);
        for (ActiveWar w : warmgr.getActiveWars()) {
            if (w.outpostPhase && entry.id.equals(w.outpostId)) {
                sp.displayClientMessage(
                        Component.literal("§c[Outpost] Cannot remove — it is currently contested in a war!"), true);
                return;
            }
        }

        // Find the level
        ServerLevel level = null;
        for (ServerLevel lvl : sp.server.getAllLevels()) {
            if (lvl.dimension().location().toString().equals(entry.dimension)) { level = lvl; break; }
        }
        if (level != null) {
            for (BlockPos bp : new ArrayList<>(entry.structureBlocks)) level.removeBlock(bp, false);
            level.removeBlock(entry.managerPos, false);
        }

        // Unclaim the chunk the outpost occupied
        int chunkX = SectionPos.blockToSectionCoord(entry.managerPos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(entry.managerPos.getZ());
        fmgr.unclaimChunk(entry.ownerFactionId, chunkX, chunkZ, entry.dimension);

        outposts.removeOutpost(outpostId);

        if (refundItem) {
            ItemStack refund = new ItemStack(ModItems.OUTPOST.get(), 1);
            if (!sp.getInventory().add(refund)) {
                assert level != null;
                level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                        level, sp.getX(), sp.getY(), sp.getZ(), refund));
            }
            sp.displayClientMessage(
                    Component.literal("§a[Outpost] Outpost retrieved — place it somewhere new."), false);
        } else {
            sp.displayClientMessage(Component.literal("§c[Outpost] Outpost deleted."), false);
        }
    }
}
