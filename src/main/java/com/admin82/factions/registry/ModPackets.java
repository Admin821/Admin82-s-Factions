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
        registrar.playToClient(SyncServerConfigPacket.TYPE, SyncServerConfigPacket.STREAM_CODEC, SyncServerConfigPacket::handle);
        registrar.playToClient(SyncBarracksPacket.TYPE, SyncBarracksPacket.STREAM_CODEC, SyncBarracksPacket::handle);
        registrar.playToClient(OpenKitSelectionPacket.TYPE, OpenKitSelectionPacket.STREAM_CODEC, OpenKitSelectionPacket::handle);
        registrar.playToClient(com.admin82.factions.network.packet.SkinPacket.TYPE, com.admin82.factions.network.packet.SkinPacket.STREAM_CODEC,
                com.admin82.factions.network.packet.SkinPacket::handle);

        // Client → Server
        registrar.playToServer(CreateFactionPacket.TYPE, CreateFactionPacket.STREAM_CODEC, CreateFactionPacket::handle);
        registrar.playToServer(MemberActionPacket.TYPE, MemberActionPacket.STREAM_CODEC, MemberActionPacket::handle);
        registrar.playToServer(ClaimChunkPacket.TYPE, ClaimChunkPacket.STREAM_CODEC, ClaimChunkPacket::handle);
        registrar.playToServer(ChunkloadActionPacket.TYPE,
                ChunkloadActionPacket.STREAM_CODEC,
                ChunkloadActionPacket::handle);
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
        registrar.playToServer(AllianceActionPacket.TYPE, AllianceActionPacket.STREAM_CODEC, AllianceActionPacket::handle);
        registrar.playToClient(SyncAlliancesPacket.TYPE, SyncAlliancesPacket.STREAM_CODEC, SyncAlliancesPacket::handle);
        registrar.playToServer(AdminPanelPacket.TYPE, AdminPanelPacket.STREAM_CODEC, AdminPanelPacket::handle);
        registrar.playToClient(AdminPanelPacket.Sync.TYPE, AdminPanelPacket.Sync.STREAM_CODEC, AdminPanelPacket.Sync::handle);
        registrar.playToServer(TreasuryPacket.TYPE, TreasuryPacket.STREAM_CODEC, TreasuryPacket::handle);
        registrar.playToClient(TreasuryPacket.Sync.TYPE, TreasuryPacket.Sync.STREAM_CODEC, TreasuryPacket.Sync::handle);
        registrar.playToServer(BountyActionPacket.TYPE, BountyActionPacket.STREAM_CODEC, BountyActionPacket::handle);
        registrar.playToClient(SyncBountiesPacket.TYPE, SyncBountiesPacket.STREAM_CODEC, SyncBountiesPacket::handle);
        registrar.playToClient(SyncExchangePacket.TYPE, SyncExchangePacket.STREAM_CODEC, SyncExchangePacket::handle);
        registrar.playToServer(VaultActionPacket.TYPE, VaultActionPacket.STREAM_CODEC, VaultActionPacket::handle);
        registrar.playToServer(SupplyDropActionPacket.TYPE, SupplyDropActionPacket.STREAM_CODEC, SupplyDropActionPacket::handle);
        registrar.playToServer(MeterActionPacket.TYPE, MeterActionPacket.STREAM_CODEC, MeterActionPacket::handle);
        registrar.playToClient(SyncMeterPacket.TYPE, SyncMeterPacket.STREAM_CODEC, SyncMeterPacket::handle);
        registrar.playBidirectional(com.admin82.factions.network.packet.SeamMemoryPacket.TYPE,
                com.admin82.factions.network.packet.SeamMemoryPacket.STREAM_CODEC,
                new net.neoforged.neoforge.network.handling.DirectionalPayloadHandler<>(
                        com.admin82.factions.network.packet.SeamMemoryPacket::handleClient,
                        com.admin82.factions.network.packet.SeamMemoryPacket::handleServer));
        registrar.playToClient(com.admin82.factions.network.packet.AdminSeamAreaPacket.TYPE,
                com.admin82.factions.network.packet.AdminSeamAreaPacket.STREAM_CODEC,
                com.admin82.factions.network.packet.AdminSeamAreaPacket::handle);
        registrar.playToClient(com.admin82.factions.network.packet.OpenSeamViewerPacket.TYPE,
                com.admin82.factions.network.packet.OpenSeamViewerPacket.STREAM_CODEC,
                com.admin82.factions.network.packet.OpenSeamViewerPacket::handle);
        registrar.playToClient(com.admin82.factions.network.packet.SyncSeamExtractorPacket.TYPE,
                com.admin82.factions.network.packet.SyncSeamExtractorPacket.STREAM_CODEC,
                com.admin82.factions.network.packet.SyncSeamExtractorPacket::handle);
        registrar.playToServer(MonumentActionPacket.TYPE, MonumentActionPacket.STREAM_CODEC, MonumentActionPacket::handle);
        registrar.playToServer(MonumentLootActionPacket.TYPE, MonumentLootActionPacket.STREAM_CODEC, MonumentLootActionPacket::handle);
        registrar.playToServer(MonumentAiActionPacket.TYPE, MonumentAiActionPacket.STREAM_CODEC, MonumentAiActionPacket::handle);
        registrar.playToServer(MonumentAiRangesPacket.TYPE, MonumentAiRangesPacket.STREAM_CODEC, MonumentAiRangesPacket::handle);
        registrar.playToServer(MonumentAiLoadoutItemPacket.TYPE, MonumentAiLoadoutItemPacket.STREAM_CODEC, MonumentAiLoadoutItemPacket::handle);
        registrar.playToServer(AiCombatProfilePacket.TYPE, AiCombatProfilePacket.STREAM_CODEC, AiCombatProfilePacket::handle);
        registrar.playToClient(SyncMonumentsPacket.TYPE, SyncMonumentsPacket.STREAM_CODEC, SyncMonumentsPacket::handle);
        registrar.playToClient(SyncMonumentLootPacket.TYPE, SyncMonumentLootPacket.STREAM_CODEC, SyncMonumentLootPacket::handle);
        registrar.playToClient(SyncMonumentAiPacket.TYPE, SyncMonumentAiPacket.STREAM_CODEC, SyncMonumentAiPacket::handle);
        registrar.playToClient(SyncAiCombatProfilePacket.TYPE, SyncAiCombatProfilePacket.STREAM_CODEC, SyncAiCombatProfilePacket::handle);

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
        registrar.playToServer(HistoryRequestPacket.TYPE, HistoryRequestPacket.STREAM_CODEC, HistoryRequestPacket::handle);
        registrar.playToClient(SyncHistoryPacket.TYPE, SyncHistoryPacket.STREAM_CODEC, SyncHistoryPacket::handle);
        registrar.playToClient(SyncPriceStatsPacket.TYPE, SyncPriceStatsPacket.STREAM_CODEC, SyncPriceStatsPacket::handle);
        registrar.playToClient(SyncSoldListingsPacket.TYPE, SyncSoldListingsPacket.STREAM_CODEC, SyncSoldListingsPacket::handle);
        registrar.playToServer(FactionUpgradePacket.TYPE, FactionUpgradePacket.STREAM_CODEC, FactionUpgradePacket::handle);
        registrar.playToClient(SyncFactionUpgradesPacket.TYPE, SyncFactionUpgradesPacket.STREAM_CODEC, SyncFactionUpgradesPacket::handle);
        registrar.playToClient(com.admin82.factions.network.packet.SyncStocksPacket.TYPE, com.admin82.factions.network.packet.SyncStocksPacket.STREAM_CODEC, com.admin82.factions.network.packet.SyncStocksPacket::handle);
        registrar.playToServer(ItemVaultActionPacket.TYPE, ItemVaultActionPacket.STREAM_CODEC, ItemVaultActionPacket::handle);
        registrar.playToClient(SyncItemVaultPacket.TYPE, SyncItemVaultPacket.STREAM_CODEC, SyncItemVaultPacket::handle);
        registrar.playToClient(SyncMarketDeliveriesPacket.TYPE, SyncMarketDeliveriesPacket.STREAM_CODEC, SyncMarketDeliveriesPacket::handle);
        registrar.playToClient(SyncResourceWarAccessPacket.TYPE, SyncResourceWarAccessPacket.STREAM_CODEC, SyncResourceWarAccessPacket::handle);
        registrar.playToClient(SyncSupplyDropPacket.TYPE, SyncSupplyDropPacket.STREAM_CODEC, SyncSupplyDropPacket::handle);
        registrar.playToClient(SyncSupplyDropSettingsPacket.TYPE, SyncSupplyDropSettingsPacket.STREAM_CODEC, SyncSupplyDropSettingsPacket::handle);
    }
}
