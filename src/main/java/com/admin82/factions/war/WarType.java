package com.admin82.factions.war;

/**
 * The agreed-upon terms (objective) of a declared war.
 * Chosen by the attacker before participants are selected.
 * Determines what the victor claims when the war ends.
 */
public enum WarType {

    TERRITORY("Territory War",
              "§bTerritory War",
              "§7Pre-select enemy chunks before war. Win to claim your chosen territory."),

    RESOURCE ("Resource War",
              "§6Resource War",
              "§7Win to open enemy containers and break up to 50 blocks in their land for 10 minutes."),

    VAULT    ("Vault War",
              "§dVault War",
              "§7Win to seize the enemy faction's entire vault."),

    FIGHT    ("Faction Fight",
              "§eFaction Fight",
              "§7Honor-only war — no territory, resources, or consequences at stake."),

    ALL_OUT  ("All Out War",
              "§c§lAll Out War",
              "§7Winner claims ALL enemy territory, vault, and the defeated faction is disbanded.");

    /** Plain id / name used in saves. */
    public final String id;
    /** §-formatted short name shown in UI buttons. */
    public final String displayName;
    /** §-formatted one-line description shown in tooltips. */
    public final String description;

    WarType(String id, String displayName, String description) {
        this.id          = id;
        this.displayName = displayName;
        this.description = description;
    }

    /** Safe deserializer — falls back to FIGHT for unknown ordinals. */
    public static WarType fromOrdinal(int ord) {
        WarType[] vals = values();
        return (ord >= 0 && ord < vals.length) ? vals[ord] : FIGHT;
    }
}
