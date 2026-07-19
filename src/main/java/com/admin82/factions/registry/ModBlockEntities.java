package com.admin82.factions.registry;

import com.admin82.factions.blockentity.BarracksBlockEntity;
import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.blockentity.OutpostManagerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.admin82.factions.AdminsFactions.MODID;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

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
}
