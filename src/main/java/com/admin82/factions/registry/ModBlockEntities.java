package com.admin82.factions.registry;

import com.admin82.factions.blockentity.FactionTableBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.admin82.factions.AdminsFactions.MODID;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FactionTableBlockEntity>> FACTION_TABLE =
            BLOCK_ENTITIES.register("faction_table", () ->
                    BlockEntityType.Builder
                            .of(FactionTableBlockEntity::new, ModBlocks.FACTION_TABLE.get())
                            .build(null)
            );
}
