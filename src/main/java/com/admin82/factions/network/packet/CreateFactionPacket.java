package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.block.FactionTableBlock;
import com.admin82.factions.blockentity.BarracksBlockEntity;
import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.menu.factiontable.FactionTableMenu;
import com.admin82.factions.registry.ModBlocks;
import com.admin82.factions.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record CreateFactionPacket(String name, String description) implements CustomPacketPayload {

    public static final Type<CreateFactionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "create_faction")
    );

    public static final StreamCodec<FriendlyByteBuf, CreateFactionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUtf(pkt.name());
                buf.writeUtf(pkt.description());
            },
            buf -> new CreateFactionPacket(buf.readUtf(64), buf.readUtf(256))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(CreateFactionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            FactionManager manager = FactionManager.get(player.server);

            if (manager.getFactionForPlayer(player.getUUID()) != null) return;

            // Plain names only: colour codes would make look-alike names and names nobody can type.
            String name = packet.name().replace("§", "").trim().replaceAll("\\s+", " ");
            if (!name.matches("[A-Za-z0-9 _-]{3,24}")) {
                fail(player, "Faction names are 3 to 24 letters, numbers, spaces, _ or -.");
                return;
            }
            if (manager.isNameTaken(name)) {
                fail(player, "A faction called " + name + " already exists.");
                return;
            }

            // Require a placed Barracks before faction can be created
            FactionManager.TableLocation pendingBarracks = manager.getPendingBarracks(player.getUUID());
            if (pendingBarracks == null) {
                player.displayClientMessage(
                        Component.literal(
                                "§cYou must place a §eBarracks§c block first!"), true);
                return;
            }

            // The barracks has to still be there.
            ServerLevel barrLevel = player.server.getLevel(ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(pendingBarracks.dimension())));
            if (barrLevel == null || !(barrLevel.getBlockEntity(pendingBarracks.pos()) instanceof BarracksBlockEntity)) {
                manager.clearPendingBarracks(player.getUUID());
                fail(player, "Your Barracks is gone. Place it again, then create the faction.");
                return;
            }

            // If the table is placed now (from the item), check the spot again: things may have changed since.
            FactionManager.TableLocation pendingSpot = manager.getPendingCreation(player.getUUID());
            if (pendingSpot != null) {
                ServerLevel spotLevel = player.server.getLevel(ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(pendingSpot.dimension())));
                String problem = null;
                if (spotLevel == null || !spotLevel.dimension().equals(Level.OVERWORLD)) problem = "Faction Tables can only be placed in the Overworld.";
                else if (!spotLevel.getBlockState(pendingSpot.pos()).canBeReplaced()
                        || !FactionTableBlock.has2x2Space(spotLevel, pendingSpot.pos())) problem = "The spot for the Faction Table isn't clear anymore (it needs 2×2).";
                else {
                    int cx = SectionPos.blockToSectionCoord(pendingSpot.pos().getX());
                    int cz = SectionPos.blockToSectionCoord(pendingSpot.pos().getZ());
                    for (Faction other : manager.getAllFactions().values()) {
                        if (other.hasClaim(cx, cz, pendingSpot.dimension())) { problem = "That land belongs to " + other.getName() + "."; break; }
                    }
                }
                boolean holdsTable = player.getAbilities().instabuild
                        || player.getMainHandItem().is(ModItems.FACTION_TABLE.get())
                        || player.getOffhandItem().is(ModItems.FACTION_TABLE.get());
                if (problem == null && !holdsTable) problem = "Hold the Faction Table to place it.";
                if (problem != null) {
                    manager.clearPendingCreation(player.getUUID());
                    fail(player, problem);
                    return;
                }
            }

            Faction faction = manager.createFaction(name, player, packet.description().trim());

            BlockPos tablePos = null;
            ServerLevel tableLevel = null;
            String tableDim = null;

            // Path A: player used FactionTableItem (no block placed yet)
            FactionManager.TableLocation pendingCreation = manager.getPendingCreation(player.getUUID());
            if (pendingCreation != null) {
                ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(pendingCreation.dimension()));
                tableLevel = player.server.getLevel(dimKey);
                if (tableLevel != null) {
                    tablePos = pendingCreation.pos();
                    tableDim = pendingCreation.dimension();
                    // Place the faction table block at the stored position
                    tableLevel.setBlock(tablePos, ModBlocks.FACTION_TABLE.get().defaultBlockState(), 3);
                    // Link the newly placed block entity
                    if (tableLevel.getBlockEntity(tablePos) instanceof FactionTableBlockEntity be) {
                        be.setLinkedFactionId(faction.getId());
                    }
                    // Place the three invisible filler blocks for the 2×2 footprint
                    if (FactionTableBlock.has2x2Space(tableLevel, tablePos)) {
                        FactionTableBlock.placeFillers(tableLevel, tablePos);
                    }
                    // Consume the Faction Table item from the player's hand
                    if (!player.getAbilities().instabuild) {
                        ItemStack held = player.getMainHandItem();
                        if (held.getItem() == ModItems.FACTION_TABLE.get()) {
                            held.shrink(1);
                        } else {
                            ItemStack offhand = player.getOffhandItem();
                            if (offhand.getItem() == ModItems.FACTION_TABLE.get()) offhand.shrink(1);
                        }
                    }
                }
                manager.clearPendingCreation(player.getUUID());
            }
            // Path B: player right-clicked an existing unlinked table
            else if (player.containerMenu instanceof FactionTableMenu tableMenu) {
                tablePos   = tableMenu.getTablePos();
                tableLevel = player.serverLevel();
                tableDim   = tableLevel.dimension().location().toString();
                if (tableLevel.getBlockEntity(tablePos) instanceof FactionTableBlockEntity be) {
                    be.setLinkedFactionId(faction.getId());
                }
            }

            // Register table location and auto-claim its chunk
            if (tablePos != null && tableLevel != null && tableDim != null) {
                manager.setFactionTable(faction.getId(), tablePos, tableDim);
                int chunkX = SectionPos.blockToSectionCoord(tablePos.getX());
                int chunkZ = SectionPos.blockToSectionCoord(tablePos.getZ());
                manager.claimChunk(faction.getId(), chunkX, chunkZ, tableDim, 100L); // core chunk = 1 silver/day
            }

            // Link the pending barracks to the new faction
            manager.clearPendingBarracks(player.getUUID());
            {
                BlockPos barrPos = pendingBarracks.pos();
                if (barrLevel.getBlockEntity(barrPos) instanceof BarracksBlockEntity barrBe) {
                    barrBe.setLinkedFactionId(faction.getId());
                }
                manager.setFactionBarracks(faction.getId(), barrPos, pendingBarracks.dimension());
            }

            PacketDistributor.sendToPlayer(player, new SyncFactionDataPacket(faction));
        });
    }

    private static void fail(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal("§c" + message), true);
    }
}
