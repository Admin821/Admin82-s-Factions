package com.admin82.factions.registry;

import com.admin82.factions.network.packet.*;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModPackets {

    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("adminsfactions");

        // Client → Server
        registrar.playToServer(CreateFactionPacket.TYPE, CreateFactionPacket.STREAM_CODEC, CreateFactionPacket::handle);
        registrar.playToServer(MemberActionPacket.TYPE, MemberActionPacket.STREAM_CODEC, MemberActionPacket::handle);
        registrar.playToServer(ClaimChunkPacket.TYPE, ClaimChunkPacket.STREAM_CODEC, ClaimChunkPacket::handle);
        registrar.playToServer(UnclaimChunkPacket.TYPE, UnclaimChunkPacket.STREAM_CODEC, UnclaimChunkPacket::handle);
        registrar.playToServer(DeclareWarPacket.TYPE, DeclareWarPacket.STREAM_CODEC, DeclareWarPacket::handle);
        registrar.playToServer(UpdatePermissionPacket.TYPE, UpdatePermissionPacket.STREAM_CODEC, UpdatePermissionPacket::handle);
        registrar.playToServer(UpdateRolePermissionPacket.TYPE, UpdateRolePermissionPacket.STREAM_CODEC, UpdateRolePermissionPacket::handle);
        registrar.playToServer(RequestMoveTablePacket.TYPE, RequestMoveTablePacket.STREAM_CODEC, RequestMoveTablePacket::handle);
        registrar.playToServer(DisbandFactionPacket.TYPE, DisbandFactionPacket.STREAM_CODEC, DisbandFactionPacket::handle);

        // Economy: Client → Server
        registrar.playToServer(MarketActionPacket.TYPE, MarketActionPacket.STREAM_CODEC, MarketActionPacket::handle);
        registrar.playToServer(ExchangeActionPacket.TYPE, ExchangeActionPacket.STREAM_CODEC, ExchangeActionPacket::handle);
        registrar.playToServer(VaultActionPacket.TYPE, VaultActionPacket.STREAM_CODEC, VaultActionPacket::handle);

        // War: Client → Server
        registrar.playToServer(WageWarPacket.TYPE, WageWarPacket.STREAM_CODEC, WageWarPacket::handle);
        registrar.playToServer(ConquestActionPacket.TYPE, ConquestActionPacket.STREAM_CODEC, ConquestActionPacket::handle);

        // War: Server → Client
        registrar.playToClient(SyncWarStatePacket.TYPE, SyncWarStatePacket.STREAM_CODEC, SyncWarStatePacket::handle);
        registrar.playToClient(OpenConquestGuiPacket.TYPE, OpenConquestGuiPacket.STREAM_CODEC, OpenConquestGuiPacket::handle);

        // Server → Client
        registrar.playToClient(SyncFactionDataPacket.TYPE, SyncFactionDataPacket.STREAM_CODEC, SyncFactionDataPacket::handle);
        registrar.playToClient(SyncAllFactionsPacket.TYPE, SyncAllFactionsPacket.STREAM_CODEC, SyncAllFactionsPacket::handle);
        registrar.playToClient(SyncEconomyPacket.TYPE, SyncEconomyPacket.STREAM_CODEC, SyncEconomyPacket::handle);
        registrar.playToClient(SyncMarketPacket.TYPE, SyncMarketPacket.STREAM_CODEC, SyncMarketPacket::handle);
    }
}
