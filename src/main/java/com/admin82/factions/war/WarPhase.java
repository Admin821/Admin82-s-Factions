package com.admin82.factions.war;

/** Lifecycle phases of an active war. */
public enum WarPhase {
    GRACE,   // grace period — no combat advantage, countdown running
    ACTIVE,  // full war — capture progress ticking, lives count
    ENDED    // war resolved — cleanup pending
}
