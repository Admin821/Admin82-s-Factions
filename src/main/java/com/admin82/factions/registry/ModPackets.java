package com.admin82.factions.registry;

import com.admin82.factions.network.packet.*;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModPackets {

    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("adminsfactions");

        // Barracks: Client → Server
        registrar.playToServer(BarracksActionPacket.TYPE, BarracksActionPacket.STREAM_CODEC, BarracksActionPacket::handle);
        registrar.playToServer(SelectKitPacket.TYPE, SelectKitPacket.STREAM_CODEC, SelectKitPacket::handle);

        // Barracks: Server → Client
        registrar.playToClient(SyncBarracksPacket.TYPE, SyncBarracksPacket.STREAM_CODEC, SyncBarracksPacket::handle);
        registrar.playToClient(OpenKitSelectionPacket.TYPE, OpenKitSelectionPacket.STREAM_CODEC, OpenKitSelectionPacket::handle);

        // Client → Server
        registrar.playToServer(CreateFactionPacket.TYPE, CreateFactionPacket.STREAM_CODEC, CreateFactionPacket::handle);
        registrar.playToServer(MemberActionPacket.TYPE, MemberActionPacket.STREAM_CODEC, MemberActionPacket::handle);
        registrar.playToServer(ClaimChunkPacket.TYPE, ClaimChunkPacket.STREAM_CODEC, ClaimChunkPacket::handle);
        registrar.playToServer(UnclaimChunkPacket.TYPE, UnclaimChunkPacket.STREAM_CODEC, UnclaimChunkPacket::handle);
        registrar.playToServer(DeclareWarPacket.TYPE, DeclareWarPacket.STREAM_CODEC, DeclareWarPacket::handle);
        registrar.playToServer(UpdatePermissionPacket.TYPE, UpdatePermissionPacket.STREAM_CODEC, UpdatePermissionPacket::handle);
        registrar.playToServer(UpdateRolePermissionPacket.TYPE, UpdateRolePermissionPacket.STREAM_CODEC, UpdateRolePermissionPacket::handle);
        registrar.playToServer(RequestMoveTablePacket.TYPE, RequestMoveTablePacket.STREAM_CODEC, RequestMoveTablePacket::handle);
        registrar.playToServer(RequestMoveBarracksPacket.TYPE, RequestMoveBarracksPacket.STREAM_CODEC, RequestMoveBarracksPacket::handle);
        registrar.playToServer(DisbandFactionPacket.TYPE, DisbandFactionPacket.STREAM_CODEC, DisbandFactionPacket::handle);

        // Economy: Client → Server
        registrar.playToServer(MarketActionPacket.TYPE, MarketActionPacket.STREAM_CODEC, MarketActionPacket::handle);
        registrar.playToServer(ExchangeActionPacket.TYPE, ExchangeActionPacket.STREAM_CODEC, ExchangeActionPacket::handle);
        registrar.playToServer(VaultActionPacket.TYPE, VaultActionPacket.STREAM_CODEC, VaultActionPacket::handle);
        registrar.playToServer(SupplyDropActionPacket.TYPE, SupplyDropActionPacket.STREAM_CODEC, SupplyDropActionPacket::handle);

        // War: Client → Server
        registrar.playToServer(WageWarPacket.TYPE, WageWarPacket.STREAM_CODEC, WageWarPacket::handle);
        registrar.playToServer(ConquestActionPacket.TYPE, ConquestActionPacket.STREAM_CODEC, ConquestActionPacket::handle);
        registrar.playToServer(SendWarDemandPacket.TYPE, SendWarDemandPacket.STREAM_CODEC, SendWarDemandPacket::handle);
        registrar.playToServer(RespondWarDemandPacket.TYPE, RespondWarDemandPacket.STREAM_CODEC, RespondWarDemandPacket::handle);
        registrar.playToServer(OpenNegotiationsPacket.TYPE, OpenNegotiationsPacket.STREAM_CODEC, OpenNegotiationsPacket::handle);
        registrar.playToServer(TerritoryClaimActionPacket.TYPE, TerritoryClaimActionPacket.STREAM_CODEC, TerritoryClaimActionPacket::handle);
        registrar.playToServer(RequestEnemyClaimsPacket.TYPE, RequestEnemyClaimsPacket.STREAM_CODEC, RequestEnemyClaimsPacket::handle);

        // War: Server → Client
        registrar.playToClient(SyncWarStatePacket.TYPE, SyncWarStatePacket.STREAM_CODEC, SyncWarStatePacket::handle);
        registrar.playToClient(OpenConquestGuiPacket.TYPE, OpenConquestGuiPacket.STREAM_CODEC, OpenConquestGuiPacket::handle);
        registrar.playToClient(SyncWarDemandsPacket.TYPE, SyncWarDemandsPacket.STREAM_CODEC, SyncWarDemandsPacket::handle);
        registrar.playToClient(OpenTerritoryClaimPacket.TYPE, OpenTerritoryClaimPacket.STREAM_CODEC, OpenTerritoryClaimPacket::handle);
        registrar.playToClient(SyncContainerHighlightsPacket.TYPE, SyncContainerHighlightsPacket.STREAM_CODEC, SyncContainerHighlightsPacket::handle);
        registrar.playToClient(SyncEnemyClaimsPacket.TYPE, SyncEnemyClaimsPacket.STREAM_CODEC, SyncEnemyClaimsPacket::handle);

        // Outpost: Client → Server
        registrar.playToServer(PlaceOutpostPacket.TYPE, PlaceOutpostPacket.STREAM_CODEC, PlaceOutpostPacket::handle);
        registrar.playToServer(OutpostActionPacket.TYPE, OutpostActionPacket.STREAM_CODEC, OutpostActionPacket::handle);
        // Outpost: Server → Client
        registrar.playToClient(OpenOutpostPlacementPacket.TYPE, OpenOutpostPlacementPacket.STREAM_CODEC, OpenOutpostPlacementPacket::handle);
        registrar.playToClient(OpenOutpostManagerPacket.TYPE, OpenOutpostManagerPacket.STREAM_CODEC, OpenOutpostManagerPacket::handle);
        registrar.playToClient(SyncOutpostsPacket.TYPE, SyncOutpostsPacket.STREAM_CODEC, SyncOutpostsPacket::handle);

        // Server → Client
        registrar.playToClient(SyncFactionDataPacket.TYPE, SyncFactionDataPacket.STREAM_CODEC, SyncFactionDataPacket::handle);
        registrar.playToClient(SyncAllFactionsPacket.TYPE, SyncAllFactionsPacket.STREAM_CODEC, SyncAllFactionsPacket::handle);
        registrar.playToClient(SyncEconomyPacket.TYPE, SyncEconomyPacket.STREAM_CODEC, SyncEconomyPacket::handle);
        registrar.playToClient(SyncMarketPacket.TYPE, SyncMarketPacket.STREAM_CODEC, SyncMarketPacket::handle);
        registrar.playToClient(SyncSoldListingsPacket.TYPE, SyncSoldListingsPacket.STREAM_CODEC, SyncSoldListingsPacket::handle);
        registrar.playToClient(SyncResourceWarAccessPacket.TYPE, SyncResourceWarAccessPacket.STREAM_CODEC, SyncResourceWarAccessPacket::handle);
        registrar.playToClient(SyncSupplyDropPacket.TYPE, SyncSupplyDropPacket.STREAM_CODEC, SyncSupplyDropPacket::handle);
        registrar.playToClient(SyncSupplyDropSettingsPacket.TYPE, SyncSupplyDropSettingsPacket.STREAM_CODEC, SyncSupplyDropSettingsPacket::handle);
    }
}
