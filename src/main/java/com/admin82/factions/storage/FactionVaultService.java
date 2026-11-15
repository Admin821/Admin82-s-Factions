package com.admin82.factions.storage;

import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionMember;
import com.admin82.factions.faction.FactionPermission;
import com.admin82.factions.faction.FactionRole;
import com.admin82.factions.menu.FactionVaultMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.util.UUID;

/** Opens the faction item vault. Changes show for everyone at once because they share one container. */
public final class FactionVaultService {

    private FactionVaultService() {}

    public static void open(ServerPlayer sp) {
        MinecraftServer server = sp.server;
        Faction faction = FactionManager.get(server).getFactionForPlayer(sp.getUUID());
        FactionMember member = faction == null ? null : faction.getMember(sp.getUUID());
        if (member == null) {
            sp.displayClientMessage(Component.literal("§cJoin a faction to use an item vault."), true);
            return;
        }
        UUID id = faction.getId();
        FactionItemVaults all = FactionItemVaults.get(server);
        FactionItemVaults.Vault vault = all.vault(id);
        int level = vault.level;
        int maxLevel = FactionItemVaults.maxLevel();
        int cost = level >= maxLevel ? 0 : FactionItemVaults.upgradeCost(level);
        // Putting items in and taking them out both need the "use storage" permission.
        boolean canTake = faction.getRolePermission(member.getRole(), FactionPermission.MEMBER_USE_STORAGE);
        if (!canTake) {
            sp.displayClientMessage(Component.literal("§cYour rank needs the \"Use storage\" permission to use the item vault."), true);
            return;
        }
        boolean canUpgrade = member.getRole().getLevel() >= FactionRole.OFFICER.getLevel();
        sp.openMenu(new SimpleMenuProvider((containerId, inv, p) -> new FactionVaultMenu(containerId, inv, id,
                        vault.container, vault.secure(), level, maxLevel, cost, canTake, canUpgrade),
                Component.literal(faction.getName() + " · Item Vault")), buf -> {
            FactionVaultMenu.writeOpenData(buf, id, vault.container.getContainerSize(), vault.secure(), level, maxLevel,
                    cost, canTake, canUpgrade);
            buf.writeVarInt(0); // LDLib2 UISyncManager initial pack
        });
    }

    /** The vault's Back button: reopens the faction table, if the player is still next to it. */
    public static void backToTable(ServerPlayer sp) {
        Faction faction = FactionManager.get(sp.server).getFactionForPlayer(sp.getUUID());
        var table = faction == null ? null : FactionManager.get(sp.server).getFactionTable(faction.getId());
        var level = table == null ? null : com.admin82.factions.outpost.OutpostService.level(sp.server, table.dimension());
        if (level == null || level != sp.level() || sp.distanceToSqr(table.pos().getCenter()) > 64) {
            sp.closeContainer();
            sp.displayClientMessage(Component.literal("§7Walk back to your faction table to open it."), true);
            return;
        }
        var state = level.getBlockState(table.pos());
        state.useWithoutItem(level, sp, new net.minecraft.world.phys.BlockHitResult(
                table.pos().getCenter(), net.minecraft.core.Direction.UP, table.pos(), false));
    }

    /** After an upgrade the vault is a different size: reopen it for everyone who has it open. */
    public static void reopenAll(MinecraftServer server, UUID faction) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof FactionVaultMenu m && m.factionId.equals(faction)) open(p);
        }
    }
}
