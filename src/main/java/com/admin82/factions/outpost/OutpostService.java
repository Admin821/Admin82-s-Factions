package com.admin82.factions.outpost;

import com.admin82.factions.blockentity.OutpostManagerBlockEntity;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.faction.AllianceManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionMember;
import com.admin82.factions.faction.FactionRole;
import com.admin82.factions.network.packet.OpenOutpostManagerPacket;
import com.admin82.factions.registry.ModBlocks;
import com.admin82.factions.storage.FactionItemVaults;
import com.admin82.factions.storage.ItemVault;
import com.admin82.factions.war.ActiveWar;
import com.admin82.factions.war.WarManager;
import com.admin82.factions.war.WarPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * What can happen to an outpost: claiming its land, its flag being hit and captured, upgrades,
 * coins going in and out, and being torn down. Shared by the blocks, packets and the outpost tick.
 */
public final class OutpostService {

    private OutpostService() {}

    @Nullable
    public static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    // Land

    /** Claims the chunks for the outpost's level that nobody else owns. */
    public static void claimLand(MinecraftServer server, OutpostEntry entry) {
        FactionManager fmgr = FactionManager.get(server);
        int cx = SectionPos.blockToSectionCoord(entry.managerPos.getX());
        int cz = SectionPos.blockToSectionCoord(entry.managerPos.getZ());
        for (long[] off : OutpostEntry.chunkOffsets(entry.level)) {
            int x = cx + (int) off[0], z = cz + (int) off[1];
            boolean taken = fmgr.getAllFactions().values().stream().anyMatch(f -> f.hasClaim(x, z, entry.dimension));
            if (!taken) fmgr.claimChunk(entry.ownerFactionId, x, z, entry.dimension);
        }
    }

    /** Releases the outpost's chunks held by {@code faction}. */
    public static void releaseLand(MinecraftServer server, OutpostEntry entry, UUID faction) {
        FactionManager fmgr = FactionManager.get(server);
        int cx = SectionPos.blockToSectionCoord(entry.managerPos.getX());
        int cz = SectionPos.blockToSectionCoord(entry.managerPos.getZ());
        for (long[] off : OutpostEntry.chunkOffsets(entry.level)) {
            fmgr.unclaimChunk(faction, cx + (int) off[0], cz + (int) off[1], entry.dimension);
        }
    }

    // Capture

    /**
     * Whether players of {@code faction} can damage this outpost's flag right now: any faction can, at any time,
     * except the owner and its allies (and a faction at war with the owner always can).
     */
    public static boolean canAttack(MinecraftServer server, OutpostEntry entry, @Nullable UUID faction) {
        if (faction == null || faction.equals(entry.ownerFactionId) || entry.disintegrating) return false;
        ActiveWar war = WarManager.get(server).getWarBetween(faction, entry.ownerFactionId);
        if (war != null && war.phase == WarPhase.ACTIVE) return true;
        return !AllianceManager.get(server).isProtected(faction, entry.ownerFactionId);
    }

    /**
     * Whether it's in particular danger (for the flames above the flag and the status shown): being attacked, out of
     * coins, or its owner at war. Any outpost can be attacked at any time; this marks the ones to worry about.
     */
    public static boolean underThreat(MinecraftServer server, OutpostEntry entry) {
        if (entry.disintegrating) return false;
        if (entry.hp < entry.maxHp() || entry.isUnsupplied()) return true;
        for (ActiveWar w : WarManager.get(server).getActiveWars()) {
            if (w.phase == WarPhase.ACTIVE
                    && (entry.ownerFactionId.equals(w.attackerFactionId) || entry.ownerFactionId.equals(w.defenderFactionId))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A player broke the flag. Returns true if it counted as a hit (the break itself is always cancelled).
     */
    public static boolean hitFlag(ServerPlayer player, OutpostEntry entry) {
        MinecraftServer server = player.server;
        FactionManager fmgr = FactionManager.get(server);
        Faction attacker = fmgr.getFactionForPlayer(player.getUUID());
        if (attacker != null && attacker.getId().equals(entry.ownerFactionId)) {
            player.displayClientMessage(Component.literal("§7This is your outpost. Use the flag's menu to move or remove it."), true);
            return false;
        }
        if (!canAttack(server, entry, attacker == null ? null : attacker.getId())) {
            player.displayClientMessage(Component.literal(attacker == null
                    ? "§cJoin a faction to capture outposts."
                    : "§cThis outpost belongs to an ally of yours: you can't attack it."), true);
            return false;
        }
        long now = System.currentTimeMillis();
        boolean firstHit = entry.hp >= entry.maxHp();
        entry.hp = Math.max(0, entry.hp - 1);
        entry.lastHitAt = now;
        entry.lastHitBy = attacker.getId();
        OutpostData.get(server).setDirty();

        Faction owner = fmgr.getFaction(entry.ownerFactionId);
        if (entry.hp > 0) {
            player.displayClientMessage(Component.literal("§eOutpost flag: §c" + entry.hp + "§7/" + entry.maxHp() + " HP"), true);
            if (firstHit && owner != null) {
                notifyFaction(server, owner, "§c[Outpost] §e" + attacker.getName() + " §cis attacking your outpost flag!"
                        + " Defend it, or it's theirs after " + entry.maxHp() + " hits.");
            }
            return true;
        }
        capture(server, entry, attacker);
        return true;
    }

    /** The flag ran out of health: the attacking faction takes the outpost with everything in it. */
    public static void capture(MinecraftServer server, OutpostEntry entry, Faction newOwner) {
        FactionManager fmgr = FactionManager.get(server);
        UUID prevOwner = entry.ownerFactionId;
        Faction prev = fmgr.getFaction(prevOwner);

        releaseLand(server, entry, prevOwner);
        entry.ownerFactionId = newOwner.getId();
        entry.hp = entry.maxHp();
        entry.lastHitAt = 0;
        entry.lastHitBy = null;
        entry.unsuppliedSince = 0;
        entry.upkeepNextDue = System.currentTimeMillis() + OutpostEntry.UPKEEP_INTERVAL_MS;
        claimLand(server, entry);
        ServerLevel level = level(server, entry.dimension);
        if (level != null && level.getBlockEntity(entry.managerPos) instanceof OutpostManagerBlockEntity be) {
            be.setLinkedFactionId(newOwner.getId());
        }
        OutpostData.get(server).setDirty();

        String prevName = prev != null ? prev.getName() : "Unknown";
        if (prev != null) notifyFaction(server, prev, "§c[Outpost] Your outpost §f" + entry.name + " §cwas captured by §e" + newOwner.getName()
                + "§c, along with its storage and coins.");
        notifyFaction(server, newOwner, "§a[Outpost] You captured §f" + prevName + "§a's outpost §f" + entry.name + "§a! Its storage box and "
                + Currency.format(entry.coinBalance()) + " §ain coins are yours.");
    }

    // Upgrades and coins

    public static boolean isOfficer(MinecraftServer server, UUID player, OutpostEntry entry) {
        Faction f = FactionManager.get(server).getFaction(entry.ownerFactionId);
        FactionMember m = f == null ? null : f.getMember(player);
        return m != null && m.getRole().getLevel() >= FactionRole.OFFICER.getLevel();
    }

    public static boolean isMember(MinecraftServer server, UUID player, OutpostEntry entry) {
        Faction f = FactionManager.get(server).getFaction(entry.ownerFactionId);
        return f != null && f.getMember(player) != null;
    }

    /** Raises the outpost one level (paid for by {@link com.admin82.factions.faction.UpgradeService}). */
    public static void levelUp(MinecraftServer server, OutpostEntry entry) {
        entry.level++;
        entry.hp = entry.maxHp();
        claimLand(server, entry);
        OutpostData.get(server).setDirty();
    }

    /** Officers can name their outpost (shown in lists, messages and on its screen). */
    public static void rename(ServerPlayer player, OutpostEntry entry, String requested) {
        if (!isOfficer(player.server, player.getUUID(), entry)) { fail(player, "Only officers can rename the outpost."); return; }
        String name = requested.replace("§", "").trim().replaceAll("\\s+", " ");
        if (!name.matches("[A-Za-z0-9 _'-]{1,24}")) {
            fail(player, "Outpost names are 1 to 24 letters, numbers, spaces, _ ' or -.");
            return;
        }
        entry.name = name;
        OutpostData.get(player.server).setDirty();
        player.displayClientMessage(Component.literal("§a[Outpost] Renamed to " + name + "."), true);
    }

    /** Opens the outpost's coin storage: members put coins in, officers can take them out. */
    public static void openCoins(ServerPlayer player, OutpostEntry entry) {
        if (entry.disintegrating) { fail(player, "This outpost is falling apart."); return; }
        if (!isMember(player.server, player.getUUID(), entry)) { fail(player, "Only the owning faction can supply this outpost."); return; }
        MinecraftServer server = player.server;
        UUID id = entry.id;
        com.admin82.factions.menu.CoinStorageMenu.open(player, com.admin82.factions.menu.CoinStorageMenu.Source.OUTPOST,
                entry.managerPos, id, entry.name + " coins", entry.coinSlots, () -> -1, () -> entry.coins,
                isOfficer(server, player.getUUID(), entry),
                p -> {
                    OutpostEntry now = OutpostData.get(server).getOutpost(id);
                    boolean ok = now == entry && !now.disintegrating && isMember(server, p.getUUID(), now)
                            && p.distanceToSqr(now.managerPos.getCenter()) <= 100;
                    if (ok) OutpostData.get(server).setDirty();
                    return ok;
                });
    }

    // Removal

    /**
     * Removes the outpost from the world: flag, storage box and land. Its items go to the owning faction's
     * item vault, and whatever doesn't fit is delivered to the faction owner through the market.
     * Its coins go to the faction's coin vault.
     */
    public static HandOver dissolve(MinecraftServer server, OutpostEntry entry, String reason) {
        ServerLevel level = level(server, entry.dimension);
        if (level != null) {
            for (BlockPos bp : new ArrayList<>(entry.structureBlocks)) level.removeBlock(bp, false);
            if (level.getBlockState(entry.managerPos.above()).is(ModBlocks.OUTPOST_MANAGER_FILLER.get())) {
                level.setBlock(entry.managerPos.above(), Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(entry.managerPos, Blocks.AIR.defaultBlockState(), 3);
            if (entry.storageBoxPos != null && level.getBlockState(entry.storageBoxPos.above()).is(ModBlocks.OUTPOST_STORAGE_TOP.get())) {
                level.setBlock(entry.storageBoxPos.above(), Blocks.AIR.defaultBlockState(), 3);
            }
            if (entry.storageBoxPos != null && level.getBlockState(entry.storageBoxPos).is(ModBlocks.OUTPOST_STORAGE.get())) {
                level.setBlock(entry.storageBoxPos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        releaseLand(server, entry, entry.ownerFactionId);
        HandOver items = handOver(server, entry.ownerFactionId, entry.storage, reason);
        long coins = entry.takeAllCoins();
        if (coins > 0) EconomyManager.get(server).addVault(entry.ownerFactionId, coins, "Coins from an outpost (" + reason + ")");
        OutpostData.get(server).removeOutpost(entry.id);
        return new HandOver(items.toVault(), items.toMarket(), coins);
    }

    /** Where a removed outpost's things went: item counts into the faction item vault and to My Market, coins (copper) to the faction vault. */
    public record HandOver(int toVault, int toMarket, long coins) {
        public boolean isEmpty() { return toVault == 0 && toMarket == 0 && coins == 0; }

        /** e.g. "§f120 items §7went to the faction item vault (Faction Table → Vault), §f64 §7didn't fit ..." */
        public String describe(String ownerName) {
            if (isEmpty()) return "§7It was empty.";
            StringBuilder sb = new StringBuilder();
            if (toVault > 0) sb.append("§f").append(toVault).append(" item").append(toVault == 1 ? "" : "s")
                    .append(" §7went to the faction item vault (Faction Table → Vault). ");
            if (toMarket > 0) sb.append("§f").append(toMarket).append(" §7didn't fit and were sent to §f").append(ownerName)
                    .append("§7's My Market deliveries. ");
            if (coins > 0) sb.append("§7Its coins (").append(Currency.format(coins)).append("§7) went to the faction coin vault.");
            return sb.toString().trim();
        }
    }

    /**
     * Moves everything in {@code from} into the faction's item vault, and delivers what doesn't fit to the
     * faction owner through the market (claimable in My Market).
     */
    public static HandOver handOver(MinecraftServer server, UUID faction, ItemVault from, String reason) {
        if (from.isEmpty()) return new HandOver(0, 0, 0);
        Faction f = FactionManager.get(server).getFaction(faction);
        FactionItemVaults vaults = FactionItemVaults.get(server);
        int toVault = 0, toMarket = 0;
        for (ItemStack stack : from.takeAll()) {
            int count = stack.getCount();
            ItemStack rest = f != null ? vaults.insert(faction, stack) : stack;
            toVault += count - rest.getCount();
            if (!rest.isEmpty() && f != null) {
                toMarket += rest.getCount();
                MarketManager.get(server).queueDelivery(f.getOwnerId(), rest, "Outpost storage (" + reason + ")");
            }
        }
        return new HandOver(toVault, toMarket, 0);
    }

    // Screen

    /** Opens (or refreshes) the outpost flag's screen for a player. */
    public static void openManager(ServerPlayer sp, OutpostEntry entry) {
        MinecraftServer server = sp.server;
        FactionManager fmgr = FactionManager.get(server);
        Faction myFaction = fmgr.getFactionForPlayer(sp.getUUID());
        ActiveWar war = WarManager.get(server).getWarForPlayer(sp.getUUID());
        boolean inWar = war != null && war.phase == WarPhase.ACTIVE;
        Faction ownerFaction = fmgr.getFaction(entry.ownerFactionId);
        String ownerName = ownerFaction != null ? ownerFaction.getName() : "Unknown";
        boolean isOwner = myFaction != null && myFaction.getId().equals(entry.ownerFactionId);

        Faction hitter = entry.lastHitBy != null && entry.hp < entry.maxHp() ? fmgr.getFaction(entry.lastHitBy) : null;

        int radius = 5, size = radius * 2 + 1;
        int centerCX = SectionPos.blockToSectionCoord(entry.managerPos.getX());
        int centerCZ = SectionPos.blockToSectionCoord(entry.managerPos.getZ());
        byte[] tiles = new byte[size * size];
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int idx = (dz + radius) * size + (dx + radius);
                for (Faction f : fmgr.getAllFactions().values()) {
                    if (f.hasClaim(centerCX + dx, centerCZ + dz, entry.dimension)) {
                        tiles[idx] = f.getId().equals(entry.ownerFactionId) ? (byte) 1 : (byte) 2;
                        break;
                    }
                }
            }
        }
        if (sp.containerMenu != sp.inventoryMenu) sp.doCloseContainer(); // e.g. coin storage, when going back
        PacketDistributor.sendToPlayer(sp, new OpenOutpostManagerPacket(
                entry.id, ownerName, entry.disintegrating, inWar && isOwner, isOwner,
                entry.managerPos, entry.dimension, entry.maxHp() - entry.hp, hitter != null ? hitter.getName() : "",
                tiles, centerCX, centerCZ,
                entry.isUnsupplied() ? entry.unsuppliedUntil() : 0L,
                OutpostEvents.computeOutpostUpkeep(server, entry, fmgr),
                underThreat(server, entry),
                entry.level, entry.hp, entry.maxHp(), isOwner ? entry.coinBalance() : -1,
                entry.level >= OutpostEntry.MAX_LEVEL ? 0 : entry.upgradeCost(),
                isOfficer(server, sp.getUUID(), entry), entry.name));
    }

    // Helpers

    static void notifyFaction(MinecraftServer server, Faction faction, String message) {
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer p = server.getPlayerList().getPlayer(m.getUuid());
            if (p != null) p.displayClientMessage(Component.literal(message), false);
        }
    }

    private static void fail(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal("§c" + message), true);
    }

    private static String plain(long copper) {
        return Currency.format(copper).replaceAll("§.", "");
    }
}
