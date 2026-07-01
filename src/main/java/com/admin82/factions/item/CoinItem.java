package com.admin82.factions.item;

import net.minecraft.world.item.Item;

/**
 * Generic coin item (copper, silver, gold, platinum).
 * No special behaviour — properties are set at registration time.
 */
public class CoinItem extends Item {

    public CoinItem(Properties properties) {
        super(properties);
    }
}
