package com.admin82.factions;

/**
 * Whether this game is showing the Medieval skin (set on the client whenever resources load; always false on a
 * dedicated server). A few multi-block parts use it to match their hitbox to the model actually shown: the Medieval
 * models are smaller than the Modern ones they replace.
 */
public final class SkinState {

    public static volatile boolean medieval;

    private SkinState() {}
}
