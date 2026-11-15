package com.admin82.factions.war;

import com.admin82.factions.command.FactionCommands;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.network.packet.OpenConquestGuiPacket;
import com.admin82.factions.network.packet.SyncContainerHighlightsPacket;
import com.admin82.factions.network.packet.SyncResourceWarAccessPacket;
import com.admin82.factions.war.ActiveWar;
import com.admin82.factions.war.ResourceWarAccess;
import com.admin82.factions.war.VassalManager;
import com.admin82.factions.war.WarManager;
import com.admin82.factions.war.WarType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/** What happens when a war ends: the conquest screen, the outcome, and resource war access. */
final class WarEndings {

    private WarEndings() {}

    // Resource War helpers

    /** Sends the current RWA state to a single online player (for HUD updates). */
    static void sendRwaPacketToPlayer(ServerPlayer player, ResourceWarAccess rwa) {
        PacketDistributor.sendToPlayer(player,
                new SyncResourceWarAccessPacket(
                        rwa.expiresAt, rwa.blockLimit, rwa.blocksBroken));
    }

    /** Sends the current RWA state to all online members of the winner faction. */
    static void broadcastRwaToWinner(MinecraftServer server, ResourceWarAccess rwa, FactionManager fmgr) {
        Faction winner = fmgr.getAllFactions().get(rwa.winnerFactionId);
        if (winner == null) return;
        SyncResourceWarAccessPacket pkt =
                new SyncResourceWarAccessPacket(
                        rwa.expiresAt, rwa.blockLimit, rwa.blocksBroken);
        for (FactionMember m : winner.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) PacketDistributor.sendToPlayer(sp, pkt);
        }
    }

    /** Returns the highest-ranked online member of a faction, or null if nobody is online. */
    private static ServerPlayer getHighestOnlineMember(MinecraftServer server, Faction faction) {
        ServerPlayer target = null;
        int topLevel = -1;
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp == null) continue;
            int lvl = m.getRole().getLevel();
            if (lvl > topLevel) { topLevel = lvl; target = sp; }
        }
        return target;
    }

    /**
     * Sends an {@link OpenConquestGuiPacket} to the highest-ranked online member of the
     * winning faction so they can choose what to do with the defeated faction.
     * Stores a pending conquest so the packet can be re-sent if needed.
     */
    /** On login: if this player's faction still has a conquest choice waiting and they may make it, show it again. */
    static void resendConquest(MinecraftServer server, ServerPlayer player) {
        FactionManager fmgr = FactionManager.get(server);
        Faction mine = fmgr.getFactionForPlayer(player.getUUID());
        if (mine == null) return;
        UUID defeatedId = VassalManager.get(server).getConqueredFaction(mine.getId());
        if (defeatedId == null) return;
        Faction defeated = fmgr.getFaction(defeatedId);
        FactionMember member = mine.getMember(player.getUUID());
        if (defeated == null) {
            VassalManager.get(server).clearPendingConquest(mine.getId());
            return;
        }
        if (member == null || member.getRole().getLevel() < FactionRole.ADMIN.getLevel()) return;
        PacketDistributor.sendToPlayer(player, new OpenConquestGuiPacket(defeatedId, defeated.getName(),
                defeated.getLandClaims().size(), EconomyManager.get(server).getVault(defeatedId)));
    }

    static void openConquestGui(MinecraftServer server, Faction attacker,
                                        Faction defeated, FactionManager fmgr) {
        if (defeated == null) return;
        EconomyManager eco = EconomyManager.get(server);
        VassalManager  vmgr = VassalManager.get(server);

        UUID defeatedId   = defeated.getId();
        String defName    = defeated.getName();
        int    defClaims  = defeated.getLandClaims().size();
        long   defVault   = eco.getVault(defeatedId);

        vmgr.addPendingConquest(attacker.getId(), defeatedId);

        // Find highest-ranked online member of the attacker faction
        ServerPlayer target = null;
        int topLevel = -1;
        for (FactionMember m : attacker.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp == null) continue;
            int lvl = m.getRole().getLevel();
            if (lvl > topLevel) { topLevel = lvl; target = sp; }
        }
        if (target == null) return; // nobody online: offered again when an admin of the winning faction logs in

        PacketDistributor.sendToPlayer(target,
                new OpenConquestGuiPacket(defeatedId, defName, defClaims, defVault));
    }

    /**
     * Resolves the attacker-wins outcome based on the war's {@link WarType}.
     * <ul>
     *   <li>{@code TERRITORY}, opens the full conquest decision GUI (make vassal or seize land).</li>
     *   <li>{@code RESOURCE} , auto-transfers 50 % of the defeated faction's vault to the victor.</li>
     *   <li>{@code VAULT}    , auto-seizes the entire defeated faction's vault.</li>
     *   <li>{@code FIGHT}    , no material consequence; bragging rights only.</li>
     * </ul>
     */
    static void resolveWarOutcome(MinecraftServer server, Faction winner,
                                          Faction loser, ActiveWar war, FactionManager fmgr) {
        if (loser == null || winner == null) return;
        switch (war.warType) {

            // Territory War, auto-transfer the pre-selected chunks
            case TERRITORY -> {
                FactionManager.TableLocation tableLoc = fmgr.getFactionTable(loser.getId());
                int coreX = Integer.MIN_VALUE, coreZ = Integer.MIN_VALUE;
                String coreDim = "";
                if (tableLoc != null) {
                    coreX   = SectionPos.blockToSectionCoord(tableLoc.pos().getX());
                    coreZ   = SectionPos.blockToSectionCoord(tableLoc.pos().getZ());
                    coreDim = tableLoc.dimension();
                }
                int transferred = 0;
                for (String key : war.targetChunkKeys) {
                    String[] parts = key.split(",", 3);
                    if (parts.length < 3) continue;
                    try {
                        int cx = Integer.parseInt(parts[0]);
                        int cz = Integer.parseInt(parts[1]);
                        String dim = parts[2];
                        // Never take the core chunk
                        if (cx == coreX && cz == coreZ && dim.equals(coreDim)) continue;
                        // Verify loser still owns it
                        LandClaim match = null;
                        for (LandClaim c : loser.getLandClaims()) {
                            if (c.chunkX() == cx && c.chunkZ() == cz && c.dimension().toString().equals(dim)) {
                                match = c; break;
                            }
                        }
                        if (match == null) continue;
                        fmgr.unclaimChunk(loser.getId(), cx, cz, dim);
                        fmgr.claimChunk(winner.getId(), cx, cz, dim);
                        transferred++;
                    } catch (NumberFormatException ignored) {}
                }
                final int count = transferred;
                FactionWarEvents.notifyFaction(server, winner, Component.literal(
                        "§6[Territory War] §a§lVICTORY! §e" + count + " chunk"
                        + (count == 1 ? "" : "s") + " transferred from §c" + loser.getName() + "§e."));
                FactionWarEvents.notifyFaction(server, loser, Component.literal(
                        "§6[Territory War] §c§lDEFEAT! §c" + winner.getName()
                        + " §chas seized §e" + count + " chunk" + (count == 1 ? "" : "s")
                        + " §cfrom your territory."));
            }

            // Resource War, grant container-access + block-break access, highlight storage blocks
            case RESOURCE -> {
                WarManager warmgr = WarManager.get(server);
                ResourceWarAccess rwa = new ResourceWarAccess(
                        winner.getId(), loser.getId(), warmgr.getBlockBreakLimit());
                warmgr.addResourceWarAccess(rwa);

                // Send initial HUD state to winner faction members
                broadcastRwaToWinner(server, rwa, fmgr);

                FactionWarEvents.notifyFaction(server, winner, Component.literal(
                        "§6[Resource War] §a§lVICTORY! §eYou may open §c" + loser.getName()
                        + "§e's containers and break up to §c" + rwa.blockLimit
                        + " §eblocks for §a10 minutes§e. §6Storage blocks are highlighted§e!"));
                FactionWarEvents.notifyFaction(server, loser, Component.literal(
                        "§6[Resource War] §c§lDEFEAT! §c" + winner.getName()
                        + " §ccan access your chests and break §c" + rwa.blockLimit
                        + " §cblocks in your territory for the next 10 minutes!"));

                // Scan loser's territory and send container highlights to winning faction
                scanAndSendContainerHighlights(server, winner, loser);
            }

            case VAULT -> {
                EconomyManager eco = EconomyManager.get(server);
                long take = eco.getVault(loser.getId());
                eco.setVault(loser.getId(), 0);
                eco.logVault(loser.getId(), -take, "Vault seized by " + winner.getName() + " (war)", false);
                eco.addVault(winner.getId(), take, "Vault seized from " + loser.getName() + " (war)");
                FactionWarEvents.notifyFaction(server, winner, Component.literal(
                        "§6[War] §a§lVICTORY! §eSeized the entire vault of §c"
                        + loser.getName() + " §e(§6" + Currency.format(take) + "§e)!"));
                FactionWarEvents.notifyFaction(server, loser, Component.literal(
                        "§6[War] §c§lDEFEAT! §c" + winner.getName()
                        + " §cseized your entire faction vault!"));
            }

            case FIGHT -> {
                FactionWarEvents.notifyFaction(server, winner, Component.literal(
                        "§6[War] §a§lVICTORY! §eGlory to §a" + winner.getName() + "§e!"));
                FactionWarEvents.notifyFaction(server, loser, Component.literal(
                        "§6[War] §c§lDEFEAT§c — but fight on with honour."));
            }

            // All Out War, total destruction: all claims, full vault, and disband the loser
            case ALL_OUT -> {
                // With an outpost to fall back to, the loser survives there instead of being disbanded.
                if (CoreLoss.fallBack(server, winner, loser)) {
                    FactionWarEvents.notifyFaction(server, winner, Component.literal("§c[All Out War] §a§lVICTORY! §eYou took §c"
                            + loser.getName() + "§e's core. They fell back to an outpost; loot their old faction table."));
                    return;
                }
                EconomyManager eco = EconomyManager.get(server);
                // Transfer vault FIRST (performDisband will wipe it otherwise)
                long vault = eco.getVault(loser.getId());
                eco.setVault(loser.getId(), 0);
                eco.addVault(winner.getId(), vault, "All-out war spoils from " + loser.getName());
                // Transfer all land claims
                var claims = new ArrayList<>(loser.getLandClaims());
                int transferred = 0;
                for (LandClaim c : claims) {
                    String dim = c.dimension().toString();
                    fmgr.unclaimChunk(loser.getId(), c.chunkX(), c.chunkZ(), dim);
                    fmgr.claimChunk(winner.getId(), c.chunkX(), c.chunkZ(), dim);
                    transferred++;
                }
                final int tCount = transferred;
                final long tVault = vault;
                FactionWarEvents.notifyFaction(server, winner, Component.literal(
                        "§c[All Out War] §a§lTOTAL VICTORY! §eSeized §a" + tCount + " chunk"
                        + (tCount == 1 ? "" : "s") + "§e and §6" + Currency.format(tVault)
                        + " §efrom §c" + loser.getName() + "§e. Their faction has been disbanded!"));
                // Disband the loser (notifies their members)
                FactionCommands.performDisband(server, loser.getId(),
                        Component.literal("§c[All Out War] §f" + winner.getName()
                                + " §chas totally defeated your faction. You have been disbanded!"));
            }
        }
    }

    /** Scans the loser's claimed chunks for container/storage blocks and sends
     *  highlight positions to every online member of the winning faction. */
    private static void scanAndSendContainerHighlights(MinecraftServer server,
                                                        Faction winner, Faction loser) {
        List<BlockPos> positions = new ArrayList<>();
        for (LandClaim claim : loser.getLandClaims()) {
            ServerLevel level = null;
            String dimStr = claim.dimension().toString();
            for (ServerLevel lvl : server.getAllLevels()) {
                if (lvl.dimension().location().toString().equals(dimStr)) { level = lvl; break; }
            }
            if (level == null) continue;
            // Use chunk block-entity list, O(entities in chunk), not O(all blocks)
            // Use getChunk to load the chunk if needed (force-loads to ensure all containers are found)
            var chunk = level.getChunk(claim.chunkX(), claim.chunkZ());
            for (var entry : chunk.getBlockEntities().entrySet()) {
                if (isStorageBlockEntity(entry.getValue())) {
                    positions.add(entry.getKey());
                }
            }
        }

        SyncContainerHighlightsPacket pkt = new SyncContainerHighlightsPacket(positions);
        for (FactionMember m : winner.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) PacketDistributor.sendToPlayer(sp, pkt);
        }
    }

    /** Returns true if the block entity is a storage/container (vanilla or modded). */
    private static boolean isStorageBlockEntity(BlockEntity be) {
        if (be instanceof Container) return true;
        if (be instanceof BaseContainerBlockEntity) return true;
        // Modded storage via NeoForge item-handler capability
        try {
            var cap = be.getLevel().getCapability(
                    Capabilities.ItemHandler.BLOCK,
                    be.getBlockPos(), null);
            if (cap != null) return true;
        } catch (Exception ignored) {}
        return false;
    }
}
