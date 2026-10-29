package com.admin82.factions.entity.monument;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.registry.ModEntities;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@EventBusSubscriber(modid = AdminsFactions.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class RoguePmcEvents {
    private RoguePmcEvents() {}

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        register(event, ModEntities.ROGUE_SNIPER.get(), RoguePmcRole.SNIPER);
        register(event, ModEntities.ROGUE_ASSAULT.get(), RoguePmcRole.ASSAULT);
        register(event, ModEntities.ROGUE_HEAVY_GUNNER.get(), RoguePmcRole.HEAVY_GUNNER);
        register(event, ModEntities.ROGUE_LIGHT_GUNNER.get(), RoguePmcRole.LIGHT_GUNNER);
    }

    private static void register(EntityAttributeCreationEvent event,
                                 net.minecraft.world.entity.EntityType<RoguePmcEntity> type,
                                 RoguePmcRole role) {
        event.put(type, Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, role.health())
                .add(Attributes.MOVEMENT_SPEED, role.speed())
                .add(Attributes.FOLLOW_RANGE, role.range())
                .add(Attributes.ARMOR, role == RoguePmcRole.HEAVY_GUNNER ? 12.0 : 5.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, role == RoguePmcRole.HEAVY_GUNNER ? 0.45 : 0.15)
                .build());
    }
}