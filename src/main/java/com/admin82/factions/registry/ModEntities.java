package com.admin82.factions.registry;

import com.admin82.factions.entity.SupplyDropVisualEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.admin82.factions.AdminsFactions.MODID;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<SupplyDropVisualEntity>> SUPPLY_DROP_VISUAL =
            ENTITIES.register("supply_drop_visual", () -> EntityType.Builder
                    .of(SupplyDropVisualEntity::new, MobCategory.MISC)
                    .sized(2.5F, 5.0F)
                    .clientTrackingRange(12)
                    .updateInterval(1)
                    .build("supply_drop_visual"));

    private ModEntities() {}
}