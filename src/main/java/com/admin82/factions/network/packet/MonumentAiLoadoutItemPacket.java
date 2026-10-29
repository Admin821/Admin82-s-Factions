package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.entity.monument.RoguePmcEntity;
import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.menu.MonumentMenu;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record MonumentAiLoadoutItemPacket(UUID monumentId, int role, int slot, String itemId)
        implements CustomPacketPayload {
    public static final Type<MonumentAiLoadoutItemPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "monument_ai_loadout_item"));

    public static final StreamCodec<FriendlyByteBuf, MonumentAiLoadoutItemPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeUUID(packet.monumentId);
                buf.writeVarInt(packet.role);
                buf.writeVarInt(packet.slot);
                buf.writeUtf(packet.itemId, 256);
            },
            buf -> new MonumentAiLoadoutItemPacket(
                    buf.readUUID(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(256)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MonumentAiLoadoutItemPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)
                    || !(player.containerMenu instanceof MonumentMenu menu)
                    || !packet.monumentId.equals(menu.getSelectedId()) || packet.slot < 0 || packet.slot >= 5) return;
            MonumentData data = MonumentData.get(player.server);
            MonumentEntry monument = data.get(packet.monumentId);
            if (monument == null) return;
            RoguePmcRole role = RoguePmcRole.values()[Math.clamp(packet.role, 0, RoguePmcRole.values().length - 1)];
            ItemStack stack = ItemStack.EMPTY;
            if (!packet.itemId.isBlank()) {
                ResourceLocation id = ResourceLocation.tryParse(packet.itemId);
                if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty()) return;
                stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
            }
            monument.setAiLoadoutSlot(role, packet.slot, stack);
            data.changed();
            for (var level : player.server.getAllLevels()) {
                for (var entity : level.getAllEntities()) {
                    if (entity instanceof RoguePmcEntity pmc && pmc.isAlive()
                            && monument.id.equals(pmc.getMonumentId()) && pmc.getRole() == role) {
                        pmc.refreshConfiguredLoadout(monument.getAiLoadout(role));
                    }
                }
            }
            menu.serverLoadAiRole(monument.id, role);
        });
    }
}