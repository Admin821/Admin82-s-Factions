package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.storage.ItemVaultService.Action;
import com.admin82.factions.storage.ItemVaultService.Kind;
import com.admin82.factions.storage.ItemVaultService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Client → server: open an item vault, or deposit, withdraw or upgrade in the open one.
 * {@code index} is an inventory slot for deposits and a vault line for withdrawals.
 */
public record ItemVaultActionPacket(int kind, UUID id, int action, int index, int amount) implements CustomPacketPayload {

    /** Sent with action = -1 to open the vault. */
    public static final int OPEN = -1;
    /** Sent with action = -2 to go from the item vault back to the faction table. */
    public static final int BACK_TO_TABLE = -2;

    public static final Type<ItemVaultActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "item_vault_action"));

    public static final StreamCodec<FriendlyByteBuf, ItemVaultActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.kind);
                buf.writeUUID(p.id);
                buf.writeInt(p.action);
                buf.writeVarInt(p.index);
                buf.writeVarInt(p.amount);
            },
            buf -> new ItemVaultActionPacket(buf.readVarInt(), buf.readUUID(), buf.readInt(), buf.readVarInt(), buf.readVarInt()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ItemVaultActionPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (p.kind < 0 || p.kind >= Kind.values().length) return;
            Kind kind = Kind.values()[p.kind];
            if (p.action == BACK_TO_TABLE) {
                com.admin82.factions.storage.FactionVaultService.backToTable(sp);
                return;
            }
            if (p.action == OPEN) {
                ItemVaultService.open(sp, kind, p.id);
                return;
            }
            if (p.action < 0 || p.action >= Action.values().length) return;
            ItemVaultService.handle(sp, kind, p.id, Action.values()[p.action], p.index, p.amount);
        });
    }
}
