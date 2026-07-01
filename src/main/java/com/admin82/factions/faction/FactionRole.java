package com.admin82.factions.faction;

public enum FactionRole {
    OWNER("owner", 3),
    ADMIN("admin", 2),
    OFFICER("officer", 1),
    MEMBER("member", 0);

    private final String id;
    private final int level;

    FactionRole(String id, int level) {
        this.id = id;
        this.level = level;
    }

    public String getId() { return id; }
    public int getLevel() { return level; }

    public static FactionRole fromId(String id) {
        for (FactionRole role : values()) {
            if (role.id.equals(id)) return role;
        }
        return MEMBER;
    }
}
