package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.block.OutpostManagerBlock;
import com.admin82.factions.block.OutpostStorageBlock;
import com.admin82.factions.block.OutpostManagerFillerBlock;
import com.admin82.factions.blockentity.OutpostManagerBlockEntity;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionUpgrades;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.outpost.OutpostEvents;
import com.admin82.factions.outpost.OutpostService;
import com.admin82.factions.registry.ModBlocks;
import com.admin82.factions.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Client → Server: player confirmed they want to place an Outpost here.
 */
public record PlaceOutpostPacket(boolean confirmed) implements CustomPacketPayload {

    public static final Type<PlaceOutpostPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "place_outpost"));

    public static final StreamCodec<FriendlyByteBuf, PlaceOutpostPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeBoolean(pkt.confirmed()),
                    buf -> new PlaceOutpostPacket(buf.readBoolean()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Where the parts of an outpost would go, or why it can't be placed ({@code problem} is "" when it can). */
    public record Check(String problem, Direction facing, BlockPos boxPos, int slotsUsed, int slots, long upkeep) {}

    /** Everything that decides whether {@code player}'s faction can place an outpost at {@code pos}. */
    public static Check check(ServerPlayer sp, BlockPos pos, String dim) {
        FactionManager fmgr = FactionManager.get(sp.server);
        OutpostData outposts = OutpostData.get(sp.server);
        Faction faction = fmgr.getFactionForPlayer(sp.getUUID());
        Direction facing = sp.getDirection().getOpposite();
        if (faction == null) return new Check("You must be in a faction to place an outpost.", facing, pos, 0, 0, 0);

        int slots = FactionUpgrades.get(sp.server).outpostSlots(faction.getId());
        int used = outposts.getOutpostsForFaction(faction.getId()).size();
        long upkeep = OutpostEvents.computeOutpostUpkeep(sp.server, new OutpostEntry(UUID.randomUUID(), faction.getId(), pos, dim, List.of()), fmgr);
        BlockPos boxPos = pos.relative(facing.getClockWise());
        java.util.function.Function<String, Check> no = msg -> new Check(msg, facing, pos, used, slots, upkeep);

        if (!dim.equals(Level.OVERWORLD.location().toString())) return no.apply("Outposts can only be placed in the Overworld.");
        if (used >= slots) return no.apply("Your faction has used all " + slots + " outpost slot" + (slots == 1 ? "" : "s")
                + ". Buy another in Faction Upgrades, or remove one first.");
        long cooldownExpiry = outposts.getOutpostCooldown(faction.getId());
        long now = System.currentTimeMillis();
        if (now < cooldownExpiry) {
            long minsLeft = Math.max(1, (cooldownExpiry - now) / 60_000L);
            return no.apply("An outpost of yours was destroyed in war: you can place another in " + minsLeft + " minute" + (minsLeft == 1 ? "" : "s") + ".");
        }
        int ocx = SectionPos.blockToSectionCoord(pos.getX());
        int ocz = SectionPos.blockToSectionCoord(pos.getZ());
        for (var other : fmgr.getAllFactions().values()) {
            if (!other.getId().equals(faction.getId()) && other.hasClaim(ocx, ocz, dim)) return no.apply("That land belongs to " + other.getName() + ".");
        }
        boolean hasItem = false;
        for (int i = 0; i < sp.getInventory().getContainerSize() && !hasItem; i++) hasItem = sp.getInventory().getItem(i).is(ModItems.OUTPOST.get());
        if (!hasItem) return no.apply("You need an outpost item to place one.");

        ServerLevel level = sp.server.getLevel(sp.level().dimension());
        if (level == null || !level.dimension().location().toString().equals(dim)) return no.apply("Stand in the same world as the spot.");
        if (!level.getBlockState(pos).canBeReplaced()) return no.apply("That spot is blocked.");
        if (!level.getBlockState(pos.above()).canBeReplaced()) return no.apply("Not enough space: the flag needs 2 free blocks.");
        // The storage box's computer pokes up into the block above it, so it needs 2 free blocks
        if (!boxFits(level, boxPos)) boxPos = pos.relative(facing.getCounterClockWise());
        if (!boxFits(level, boxPos)) return no.apply("Leave 2 free blocks (one on top of the other) beside the flag for the storage box.");
        return new Check("", facing, boxPos, used, slots, upkeep);
    }

    private static boolean boxFits(ServerLevel level, BlockPos boxPos) {
        return level.getBlockState(boxPos).canBeReplaced() && level.getBlockState(boxPos.above()).canBeReplaced();
    }

    public static void handle(PlaceOutpostPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            OutpostData outposts = OutpostData.get(sp.server);

            BlockPos pending = outposts.getPendingPos(sp.getUUID());
            String   dim     = outposts.getPendingDim(sp.getUUID());
            outposts.clearPending(sp.getUUID());

            if (!pkt.confirmed() || pending == null || dim == null) return;

            Check check = check(sp, pending, dim);
            if (!check.problem().isEmpty()) {
                sp.displayClientMessage(Component.literal("§c[Outpost] " + check.problem()), true);
                return;
            }
            FactionManager fmgr = FactionManager.get(sp.server);
            Faction faction = fmgr.getFactionForPlayer(sp.getUUID());
            ServerLevel targetLevel = (ServerLevel) sp.level();
            // The outpost item in hand first (it may be a packed, moved outpost), else any in the inventory
            int outpostSlot = -1;
            if (sp.getInventory().getSelected().is(ModItems.OUTPOST.get())) outpostSlot = sp.getInventory().selected;
            else if (sp.getOffhandItem().is(ModItems.OUTPOST.get())) outpostSlot = net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND;
            for (int i = 0; i < sp.getInventory().getContainerSize() && outpostSlot < 0; i++) {
                if (sp.getInventory().getItem(i).is(ModItems.OUTPOST.get())) outpostSlot = i;
            }
            if (outpostSlot < 0) return;
            net.minecraft.nbt.CompoundTag packed = com.admin82.factions.outpost.OutpostPack.packed(sp.getInventory().getItem(outpostSlot));
            Direction facing = check.facing();
            BlockPos fillerPos = pending.above();
            BlockPos boxPos = check.boxPos();
            List<BlockPos> structureBlocks = new ArrayList<>();
            targetLevel.setBlockAndUpdate(boxPos, ModBlocks.OUTPOST_STORAGE.get().defaultBlockState()
                    .setValue(OutpostStorageBlock.FACING, facing));
            targetLevel.setBlockAndUpdate(boxPos.above(), ModBlocks.OUTPOST_STORAGE_TOP.get().defaultBlockState()
                    .setValue(OutpostStorageBlock.FACING, facing));

            // Place the outpost manager block
                targetLevel.setBlockAndUpdate(pending, ModBlocks.OUTPOST_MANAGER.get().defaultBlockState()
                    .setValue(OutpostManagerBlock.FACING, facing));
                targetLevel.setBlockAndUpdate(fillerPos, ModBlocks.OUTPOST_MANAGER_FILLER.get().defaultBlockState()
                    .setValue(OutpostManagerFillerBlock.FACING, facing));
            if (targetLevel.getBlockEntity(pending) instanceof OutpostManagerBlockEntity be) {
                be.setLinkedFactionId(faction.getId());
            }

            // Register outpost entry
            UUID entryId = UUID.randomUUID();
            OutpostEntry entry = new OutpostEntry(entryId, faction.getId(), pending, dim, structureBlocks);
            entry.storageBoxPos = boxPos;
            // Default name: the next free "Outpost N"
            int n = 1;
            Set<String> used = new HashSet<>();
            for (OutpostEntry o : outposts.getOutpostsForFaction(faction.getId())) used.add(o.name.toLowerCase(Locale.ROOT));
            while (used.contains("outpost " + n)) n++;
            entry.name = "Outpost " + n;
            // A moved outpost comes back as it was: name, level, storage and coins
            if (packed != null) {
                String oldName = packed.getString("name");
                if (!oldName.isBlank() && !used.contains(oldName.toLowerCase(Locale.ROOT))) entry.name = oldName;
                entry.level = com.admin82.factions.outpost.OutpostPack.level(packed);
                com.admin82.factions.outpost.OutpostPack.unpack(packed, entry, sp.server.registryAccess());
            }
            outposts.addOutpost(entry);

            // Claim the outpost's land (one chunk at level 1)
            OutpostService.claimLand(sp.server, entry);

            // Use up the outpost item we found above.
            sp.getInventory().getItem(outpostSlot).shrink(1);

            if (packed != null) {
                sp.displayClientMessage(Component.literal("§a[Outpost] §f" + entry.name + " §ais set up again (level "
                        + entry.level + "), with its storage and coins."), false);
            }
            sp.displayClientMessage(Component.literal(
                    "§a[Outpost] Outpost placed! §7Upkeep: §e" + Currency.format(
                            OutpostEvents.computeOutpostUpkeep(sp.server, entry, fmgr))
                    + "§7/day, paid from coins you put into the outpost (open the flag). If it runs out, it falls apart. Other factions can capture it at any time by breaking the flag: defend it!"), false);
        });
    }
}
