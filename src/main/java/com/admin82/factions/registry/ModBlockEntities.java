package com.admin82.factions.registry;

import com.admin82.factions.blockentity.BarracksBlockEntity;
import com.admin82.factions.blockentity.CarePackageBlockEntity;
import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.blockentity.MonumentControllerBlockEntity;
import com.admin82.factions.blockentity.MonumentCrateBlockEntity;
import com.admin82.factions.blockentity.OutpostManagerBlockEntity;
import com.admin82.factions.blockentity.OutpostStorageBlockEntity;
import com.admin82.factions.meter.MeterBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.admin82.factions.AdminsFactions.MODID;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CarePackageBlockEntity>> CARE_PACKAGE =
            BLOCK_ENTITIES.register("carepackage", () ->
                    BlockEntityType.Builder
                            .of(CarePackageBlockEntity::new, ModBlocks.CARE_PACKAGE.get())
                            .build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BarracksBlockEntity>> BARRACKS =
            BLOCK_ENTITIES.register("barracks", () ->
                    BlockEntityType.Builder
                            .of(BarracksBlockEntity::new, ModBlocks.BARRACKS.get())
                            .build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FactionTableBlockEntity>> FACTION_TABLE =
            BLOCK_ENTITIES.register("faction_table", () ->
                    BlockEntityType.Builder
                            .of(FactionTableBlockEntity::new, ModBlocks.FACTION_TABLE.get())
                            .build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OutpostManagerBlockEntity>> OUTPOST_MANAGER =
            BLOCK_ENTITIES.register("outpost_manager", () ->
                    BlockEntityType.Builder
                            .of(OutpostManagerBlockEntity::new, ModBlocks.OUTPOST_MANAGER.get())
                            .build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MonumentControllerBlockEntity>> MONUMENT_CONTROLLER =
            BLOCK_ENTITIES.register("monument_controller", () -> BlockEntityType.Builder
                    .of(MonumentControllerBlockEntity::new, ModBlocks.MONUMENT_CONTROLLER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OutpostStorageBlockEntity>> OUTPOST_STORAGE =
            BLOCK_ENTITIES.register("outpost_storage", () -> BlockEntityType.Builder
                    .of(OutpostStorageBlockEntity::new, ModBlocks.OUTPOST_STORAGE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MonumentCrateBlockEntity>> MONUMENT_CRATE =
            BLOCK_ENTITIES.register("monument_crate", () -> BlockEntityType.Builder
                    .of(MonumentCrateBlockEntity::new, ModBlocks.MONUMENT_CRATE.get()).build(null));

    /** Bottom half of both the power meter and the fluid meter. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MeterBlockEntity>> METER =
            BLOCK_ENTITIES.register("meter", () -> BlockEntityType.Builder
                    .of(MeterBlockEntity::new, ModBlocks.POWER_METER.get(), ModBlocks.FLUID_METER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.admin82.factions.seam.SeamExtractorBlockEntity>> SEAM_EXTRACTOR =
            BLOCK_ENTITIES.register("seam_extractor", () -> BlockEntityType.Builder
                    .of(com.admin82.factions.seam.SeamExtractorBlockEntity::new, ModBlocks.SEAM_EXTRACTOR.get()).build(null));
}
