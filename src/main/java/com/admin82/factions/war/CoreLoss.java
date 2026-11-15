package com.admin82.factions.war;

import com.admin82.factions.Config;
import com.admin82.factions.blockentity.BarracksBlockEntity;
import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionMember;
import com.admin82.factions.faction.LandClaim;
import com.admin82.factions.network.packet.SyncFactionDataPacket;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.outpost.OutpostService;
import com.admin82.factions.registry.ModItems;
import com.admin82.factions.storage.FactionItemVaults;
import com.admin82.factions.storage.ItemVaultService;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * A faction lost its core (its faction table chunk) but still has an outpost: instead of being disbanded,
 * it falls back to that outpost.
 * <ul>
 *   <li>Each slot of its item vault has a chance (config) to make it to the outpost's storage box; secure slots always do.</li>
 *   <li>Everything else, and all the coins in its vault, stays in the old faction table for the winners to loot.</li>
 *   <li>The winners take all its land except the outposts'.</li>
 *   <li>The owner gets a new faction table to place on the outpost's land, which becomes the new core.</li>
 * </ul>
 */
public final class CoreLoss {

    private static final Random RANDOM = new Random();

    private CoreLoss() {}

    /** Falls the loser back to an outpost. Returns false (and does nothing) if it has no outpost to fall back to. */
    public static boolean fallBack(MinecraftServer server, Faction winner, Faction loser) {
        FactionManager fmgr = FactionManager.get(server);
        OutpostData outposts = OutpostData.get(server);
        OutpostEntry target = outposts.getOutpostsForFaction(loser.getId()).stream()
                .filter(o -> !o.disintegrating)
                .max(Comparator.comparingInt((OutpostEntry o) -> o.level).thenComparingLong(o -> o.coins))
                .orElse(null);
        if (target == null) return false;

        UUID loserId = loser.getId();
        FactionManager.TableLocation table = fmgr.getFactionTable(loserId);

        // 1. Leave the spoils in the old table: all the coins, and the items that don't make it.
        WarSpoils.Spoils spoils = null;
        if (table != null) {
            spoils = new WarSpoils.Spoils(UUID.randomUUID(), winner.getId(), loser.getName(), table.pos(), table.dimension());
            EconomyManager eco = EconomyManager.get(server);
            spoils.coins = eco.getVault(loserId);
            eco.setVault(loserId, 0);
            eco.logVault(loserId, -spoils.coins, "Left behind when the core was taken by " + winner.getName(), false);
        }
        List<String> moved = new ArrayList<>();
        int stacksLeft = 0;
        int chance = Config.CORE_TRANSFER_PERCENT.get();
        FactionItemVaults vaults = FactionItemVaults.get(server);
        FactionItemVaults.Vault vault = vaults.vault(loserId);

        // Secure slots always make it: into the outpost's box as far as it fits; anything else simply stays
        // in the secure slots, which can't be looted.
        for (int i = 0; i < vault.secure(); i++) {
            ItemStack s = vault.container.getItem(i);
            if (s.isEmpty()) continue;
            String name = s.getHoverName().getString();
            int before = s.getCount();
            target.storage.deposit(s, target.storageKinds(), target.storagePerKind());
            if (before - s.getCount() > 0) moved.add((before - s.getCount()) + "× " + name + " §6(secure)§f");
        }
        vault.container.setChanged();

        // Regular slots: every slot rolls on its own. A win moves that whole stack (as far as the box has room).
        Map<String, Long> arrivedByName = new LinkedHashMap<>();
        for (ItemStack stack : vaults.takeRegular(loserId)) {
            if (RANDOM.nextInt(100) < chance) {
                int before = stack.getCount();
                target.storage.deposit(stack, target.storageKinds(), target.storagePerKind());
                if (before - stack.getCount() > 0) arrivedByName.merge(stack.getHoverName().getString(), (long) (before - stack.getCount()), Long::sum);
            }
            if (!stack.isEmpty()) {
                stacksLeft++;
                if (spoils != null) spoils.items.putBack(stack);
                else MarketManager.get(server).queueDelivery(winner.getOwnerId(), stack, "Spoils from " + loser.getName());
            }
        }
        arrivedByName.forEach((name, n) -> moved.add(n + "× " + name));
        vaults.changed();
        outposts.setDirty();
        if (spoils != null) WarSpoils.get(server).add(spoils);

        // 2. The old table stops being theirs: it's now loot for the winners.
        ServerLevel tableLevel = table == null ? null : OutpostService.level(server, table.dimension());
        if (tableLevel != null && tableLevel.getBlockEntity(table.pos()) instanceof FactionTableBlockEntity be) {
            be.setLinkedFactionId(null);
        }
        fmgr.removeFactionTable(loserId);

        // 3. The winners take all their land except the outposts'.
        Set<String> keep = new HashSet<>();
        for (OutpostEntry o : outposts.getOutpostsForFaction(loserId)) {
            int cx = SectionPos.blockToSectionCoord(o.managerPos.getX());
            int cz = SectionPos.blockToSectionCoord(o.managerPos.getZ());
            for (long[] off : OutpostEntry.chunkOffsets(o.level)) keep.add((cx + off[0]) + "," + (cz + off[1]) + "," + o.dimension);
        }
        int seized = 0;
        for (LandClaim c : new ArrayList<>(loser.getLandClaims())) {
            String dim = c.dimension().toString();
            if (keep.contains(c.chunkX() + "," + c.chunkZ() + "," + dim)) continue;
            fmgr.unclaimChunk(loserId, c.chunkX(), c.chunkZ(), dim);
            fmgr.claimChunk(winner.getId(), c.chunkX(), c.chunkZ(), dim);
            seized++;
        }
        // A barracks on land they lost is no longer theirs either.
        FactionManager.TableLocation barracks = fmgr.getFactionBarracks(loserId);
        if (barracks != null && !keep.contains(SectionPos.blockToSectionCoord(barracks.pos().getX()) + ","
                + SectionPos.blockToSectionCoord(barracks.pos().getZ()) + "," + barracks.dimension())) {
            ServerLevel barrLevel = OutpostService.level(server, barracks.dimension());
            if (barrLevel != null && barrLevel.getBlockEntity(barracks.pos()) instanceof BarracksBlockEntity be) be.setLinkedFactionId(null);
            fmgr.removeFactionBarracks(loserId);
        }

        // 4. A new faction table for the owner, to place on the outpost's land.
        ServerPlayer owner = server.getPlayerList().getPlayer(loser.getOwnerId());
        ItemStack newTable = new ItemStack(ModItems.FACTION_TABLE.get());
        if (owner != null && owner.getInventory().add(newTable)) com.admin82.factions.network.InventorySync.push(owner);
        else MarketManager.get(server).queueDelivery(loser.getOwnerId(), newTable, "New faction table (core lost)");

        // 5. Tell everyone what happened.
        String where = target.name + " §7(" + target.managerPos.getX() + ", " + target.managerPos.getY() + ", " + target.managerPos.getZ() + ")";
        String coinsLeft = spoils != null ? Currency.format(spoils.coins) : "nothing";
        List<String> lines = new ArrayList<>();
        lines.add("§c[Core lost] §e" + winner.getName() + " §ctook your faction's core and " + seized + " chunk" + (seized == 1 ? "" : "s") + ".");
        lines.add("§e[Core lost] §fYour faction fell back to the outpost §e" + where + "§f. It's your new home:");
        lines.add("§7  the owner has a new Faction Table to place on the outpost's land.");
        lines.add(moved.isEmpty()
                ? "§7  Nothing from the item vault made it to the outpost (" + chance + "% chance per slot)."
                : "§a  Made it to the outpost's storage box: §f" + String.join(", ", moved));
        lines.add("§7  Left behind for " + winner.getName() + ": §e" + coinsLeft + "§7 and " + stacksLeft + " stack"
                + (stacksLeft == 1 ? "" : "s") + " of items.");
        for (FactionMember m : loser.getMembers()) {
            ServerPlayer p = server.getPlayerList().getPlayer(m.getUuid());
            if (p == null) continue;
            lines.forEach(l -> p.displayClientMessage(Component.literal(l), false));
            PacketDistributor.sendToPlayer(p, new SyncFactionDataPacket(fmgr.getFaction(loserId)));
        }
        if (table != null) {
            String loot = "§6[Spoils] §e" + loser.getName() + "§6's old faction table at §f" + table.pos().getX() + ", "
                    + table.pos().getY() + ", " + table.pos().getZ() + " §6holds what they left behind: §e" + coinsLeft
                    + "§6 and " + stacksLeft + " stack" + (stacksLeft == 1 ? "" : "s") + " of items. Open it to loot.";
            for (FactionMember m : winner.getMembers()) {
                ServerPlayer p = server.getPlayerList().getPlayer(m.getUuid());
                if (p != null) p.displayClientMessage(Component.literal(loot), false);
            }
        }
        ItemVaultService.pushFaction(server, loserId);
        return true;
    }
}
