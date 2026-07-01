package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.menu.FactionTableMenu;
import com.admin82.factions.registry.ModBlocks;
import com.admin82.factions.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
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

            String name = packet.name().trim();
            if (name.isEmpty() || name.length() > 32) return;
            if (manager.isNameTaken(name)) return;

            Faction faction = manager.createFaction(name, player, packet.description().trim());

            BlockPos tablePos = null;
            ServerLevel tableLevel = null;
            String tableDim = null;

            // ── Path A: player used FactionTableItem (no block placed yet) ──────
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
            // ── Path B: player right-clicked an existing unlinked table ──────────
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
                manager.claimChunk(faction.getId(), chunkX, chunkZ, tableDim);
            }

            PacketDistributor.sendToPlayer(player, new SyncFactionDataPacket(faction));
        });
    }
}
