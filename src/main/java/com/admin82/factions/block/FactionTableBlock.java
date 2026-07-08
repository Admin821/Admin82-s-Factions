package com.admin82.factions.block;

import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.registry.ModItems;
import com.admin82.factions.faction.FactionSummary;
import com.admin82.factions.menu.FactionTableMenu;
import com.admin82.factions.network.packet.SyncAllFactionsPacket;
import com.admin82.factions.network.packet.SyncFactionDataPacket;
import com.admin82.factions.war.VassalManager;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.UUID;

public class FactionTableBlock extends BaseEntityBlock {

    public static final MapCodec<FactionTableBlock> CODEC = simpleCodec(FactionTableBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public FactionTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof FactionTableBlockEntity factionBe) {
            ServerLevel serverLevel = (ServerLevel) level;
            FactionManager manager = FactionManager.get(serverLevel);
            UUID linkedId = factionBe.getLinkedFactionId();

            // If the table is linked, only members of that faction may open it
            if (linkedId != null) {
                Faction linkedFaction = manager.getFaction(linkedId);
                if (linkedFaction == null || !linkedFaction.hasMember(player.getUUID())) {
                    String name = linkedFaction != null ? linkedFaction.getName() : "a disbanded faction";
                    player.displayClientMessage(
                            Component.literal("§cThis Faction Table belongs to: §e" + name), true);
                    return InteractionResult.FAIL;
                }
            }

            // Open the menu — send faction data if player has one
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            VassalManager vmgr = VassalManager.get(serverLevel.getServer());
            ((ServerPlayer) player).openMenu(factionBe, buf -> {
                buf.writeBlockPos(pos);
                if (faction != null) {
                    buf.writeBoolean(true);
                    faction.toNetwork(buf);
                } else {
                    buf.writeBoolean(false);
                }
                // Vassal data for the Vassal tab
                boolean isVassal   = faction != null && vmgr.isVassal(faction.getId());
                boolean isSuzerain = faction != null && vmgr.isSuzerain(faction.getId());
                buf.writeBoolean(isVassal);
                buf.writeBoolean(isSuzerain);
                // Suzerain name (empty if not a vassal)
                if (isVassal && faction != null) {
                    UUID suzerainId = vmgr.getSuzerain(faction.getId());
                    Faction suzerain = suzerainId != null ? manager.getFaction(suzerainId) : null;
                    buf.writeUtf(suzerain != null ? suzerain.getName() : "");
                } else {
                    buf.writeUtf("");
                }
                // Pending tax for this vassal
                long pendingTax = faction != null && isVassal ? vmgr.getPendingTax(faction.getId()) : 0L;
                buf.writeLong(pendingTax);
                // Vassal subjects if suzerain
                if (isSuzerain && faction != null) {
                    var subjects = vmgr.getVassals(faction.getId());
                    buf.writeVarInt(subjects.size());
                    for (UUID vid : subjects) {
                        Faction vf = manager.getFaction(vid);
                        buf.writeUUID(vid);
                        buf.writeUtf(vf != null ? vf.getName() : "Unknown");
                        buf.writeLong(vmgr.getPendingTax(vid));
                    }
                } else {
                    buf.writeVarInt(0);
                }
                buf.writeVarInt(0); // LDLib2 UISyncManager initial pack: 0 sync values
            });
            // Send all other factions to the client for the Wars tab
            final UUID ownFactionId = faction != null ? faction.getId() : null;
            var ecoMgr = com.admin82.factions.economy.EconomyManager.get(serverLevel.getServer());
            List<FactionSummary> summaries = manager.getAllFactions().values().stream()
                    .filter(f -> !f.getId().equals(ownFactionId))
                    .map(f -> {
                        int online = (int) ((ServerPlayer) player).server.getPlayerList().getPlayers().stream()
                                .filter(p -> f.getId().equals(manager.getPlayerFactionId(p.getUUID())))
                                .count();
                        long wealth = ecoMgr.getFactionVaultBalance(f.getId());
                        return new FactionSummary(f.getId(), f.getName(), f.getMembers().size(), f.getPower(), online, wealth);
                    })
                    .sorted(Comparator.comparing(FactionSummary::name))
                    .collect(Collectors.toList());
            List<String> otherClaims = manager.getAllFactions().values().stream()
                    .filter(f -> !f.getId().equals(ownFactionId))
                    .flatMap(f -> f.getLandClaims().stream())
                    .map(c -> c.chunkX() + "," + c.chunkZ() + "," + c.dimension().toString())
                    .collect(Collectors.toList());
            List<String> availablePlayers = ((ServerPlayer) player).server.getPlayerList().getPlayers().stream()
                    .filter(p -> manager.getFactionForPlayer(p.getUUID()) == null)
                    .map(p -> p.getGameProfile().getName())
                    .collect(Collectors.toList());
            PacketDistributor.sendToPlayer((ServerPlayer) player, new SyncAllFactionsPacket(summaries, otherClaims, availablePlayers));

            // Send economy balances
            long walletBal = ecoMgr.getWallet(player.getUUID());
            long vaultBal  = faction != null ? ecoMgr.getVault(faction.getId()) : 0L;
            PacketDistributor.sendToPlayer((ServerPlayer) player,
                    new com.admin82.factions.network.packet.SyncEconomyPacket(walletBal, vaultBal));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Called after the block is placed in the world.
     * Handles: move-mode completion and duplicate-table prevention.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!(placer instanceof ServerPlayer player)) return;
        if (!(serverLevel.getBlockEntity(pos) instanceof FactionTableBlockEntity be)) return;

        FactionManager manager = FactionManager.get(serverLevel);

        // ── Case 1: player is completing a move ───────────────────────────────
        FactionManager.PendingMove pending = manager.getPendingMove(player.getUUID());
        if (pending != null) {
            // Gate: new position must be inside a chunk claimed by the faction
            int chunkX = SectionPos.blockToSectionCoord(pos.getX());
            int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
            String dim  = serverLevel.dimension().location().toString();
            Faction pendingFaction = manager.getFaction(pending.factionId());
            if (pendingFaction == null || !pendingFaction.hasClaim(chunkX, chunkZ, dim)) {
                serverLevel.removeBlock(pos, false);
                player.getInventory().add(new ItemStack(ModItems.FACTION_TABLE.get()));
                player.displayClientMessage(
                        Component.literal("§cYou can only place the Faction Table inside a chunk claimed by your faction!"), true);
                return;
            }

            be.setLinkedFactionId(pending.factionId());

            // Remove the OLD table block
            ResourceKey<Level> oldDimKey = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(pending.dimension()));
            ServerLevel oldLevel = serverLevel.getServer().getLevel(oldDimKey);
            if (oldLevel != null && !pending.originalPos().equals(pos)) {
                oldLevel.removeBlock(pending.originalPos(), false);
            }

            manager.setFactionTable(pending.factionId(), pos, dim);
            manager.clearPendingMove(player.getUUID());
            player.displayClientMessage(Component.literal("§aFaction Table moved successfully!"), false);
            return;
        }

        // ── Case 2: player's faction already has a registered table ───────────
        Faction faction = manager.getFactionForPlayer(player.getUUID());
        if (faction != null && manager.getFactionTable(faction.getId()) != null) {
            serverLevel.removeBlock(pos, false);
            player.getInventory().add(new ItemStack(ModItems.FACTION_TABLE.get()));
            player.displayClientMessage(
                    Component.literal("§cYour faction already has a Faction Table! Use the menu to move it."), true);
            return;
        }
        // Case 3: faction exists but no table yet — link this one directly
        //   (e.g. placed via /setblock, or FactionTableItem fallback for faction-already member)
        if (faction != null) {
            be.setLinkedFactionId(faction.getId());
            manager.setFactionTable(faction.getId(), pos, serverLevel.dimension().location().toString());
        }
        // Case 4: no faction at all — handled by FactionTableItem which opens Create Faction UI
        //   without placing the block. If somehow we reach here (e.g. /setblock), leave unlinked.
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FactionTableBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}

