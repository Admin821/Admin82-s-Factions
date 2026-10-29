package com.admin82.factions.registry;

import com.admin82.factions.entity.SupplyDropVisualEntity;
import com.admin82.factions.entity.monument.RoguePmcEntity;
import com.admin82.factions.entity.monument.RoguePmcRole;
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

    public static final DeferredHolder<EntityType<?>, EntityType<RoguePmcEntity>> ROGUE_SNIPER =
            registerPmc("rogue_sniper", RoguePmcRole.SNIPER);
    public static final DeferredHolder<EntityType<?>, EntityType<RoguePmcEntity>> ROGUE_ASSAULT =
            registerPmc("rogue_assault", RoguePmcRole.ASSAULT);
    public static final DeferredHolder<EntityType<?>, EntityType<RoguePmcEntity>> ROGUE_HEAVY_GUNNER =
            registerPmc("rogue_heavy_gunner", RoguePmcRole.HEAVY_GUNNER);
    public static final DeferredHolder<EntityType<?>, EntityType<RoguePmcEntity>> ROGUE_LIGHT_GUNNER =
            registerPmc("rogue_light_gunner", RoguePmcRole.LIGHT_GUNNER);

    private static DeferredHolder<EntityType<?>, EntityType<RoguePmcEntity>> registerPmc(
            String id, RoguePmcRole role) {
        return ENTITIES.register(id, () -> EntityType.Builder
                .<RoguePmcEntity>of((type, level) -> new RoguePmcEntity(type, level, role), MobCategory.MONSTER)
                .sized(0.6F, 1.95F)
                .clientTrackingRange(12)
                .updateInterval(2)
                .build(id));
    }

        public static EntityType<RoguePmcEntity> pmcType(RoguePmcRole role) {
                return switch (role) {
                        case SNIPER -> ROGUE_SNIPER.get();
                        case ASSAULT -> ROGUE_ASSAULT.get();
                        case HEAVY_GUNNER -> ROGUE_HEAVY_GUNNER.get();
                        case LIGHT_GUNNER -> ROGUE_LIGHT_GUNNER.get();
                };
        }

    private ModEntities() {}
}