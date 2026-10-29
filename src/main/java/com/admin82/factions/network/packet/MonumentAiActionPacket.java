package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.menu.MonumentMenu;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record MonumentAiActionPacket(Action action, UUID monumentId, int role,
                                     int firstValue, int secondValue, int slot, int rarity)
        implements CustomPacketPayload {
    public enum Action { SELECT_ROLE, SELECT_DROPS, SAVE_ROLE_RANGE, SAVE_DROP_SLOT_SETTINGS, SAVE_ROLE_DIFFICULTY }

    public static final Type<MonumentAiActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "monument_ai_action"));

    public static final StreamCodec<FriendlyByteBuf, MonumentAiActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.action.ordinal());
                buf.writeUUID(packet.monumentId);
                buf.writeVarInt(packet.role);
                buf.writeVarInt(packet.firstValue);
                buf.writeVarInt(packet.secondValue);
                buf.writeVarInt(packet.slot);
                buf.writeVarInt(packet.rarity);
            },
            buf -> new MonumentAiActionPacket(Action.values()[buf.readVarInt()], buf.readUUID(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MonumentAiActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)
                    || !(player.containerMenu instanceof MonumentMenu menu)
                    || !packet.monumentId.equals(menu.getSelectedId())) return;
            MonumentData data = MonumentData.get(player.server);
            MonumentEntry monument = data.get(packet.monumentId);
            if (monument == null) return;
            RoguePmcRole role = RoguePmcRole.values()[Math.clamp(packet.role, 0, RoguePmcRole.values().length - 1)];

            switch (packet.action) {
                case SELECT_ROLE -> menu.serverLoadAiRole(monument.id, role);
                case SELECT_DROPS -> menu.serverLoadAiDrops(monument.id);
                case SAVE_ROLE_RANGE -> {
                    monument.setAiCountRange(role, packet.firstValue, packet.secondValue);
                    data.changed();
                    menu.serverLoadAiRole(monument.id, role);
                }
                case SAVE_DROP_SLOT_SETTINGS -> {
                    monument.getAiDropPool().setGenerationSettings(
                            packet.slot, packet.firstValue, packet.secondValue, packet.rarity);
                    data.changed();
                    menu.syncAiEditor(monument);
                }
                case SAVE_ROLE_DIFFICULTY -> {
                    monument.setAiDifficultyLevel(role, packet.firstValue);
                    data.changed();
                    menu.serverLoadAiRole(monument.id, role);
                }
            }
        });
    }
}