package com.admin82.factions.faction;

public enum FactionPermission {
    MEMBER_BUILD("member_build", true),
    MEMBER_INTERACT("member_interact", true),
    MEMBER_USE_STORAGE("member_use_storage", false),
    OFFICER_INVITE("officer_invite", true),
    OFFICER_CLAIM("officer_claim", true),
    OFFICER_KICK("officer_kick", false),
    OFFICER_DECLARE_WAR("officer_declare_war", false),
    VAULT_WITHDRAW("vault_withdraw", false);

    private final String key;
    private final boolean defaultValue;

    FactionPermission(String key, boolean defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public String getKey() { return key; }
    public boolean getDefaultValue() { return defaultValue; }

    public static FactionPermission fromKey(String key) {
        for (FactionPermission perm : values()) {
            if (perm.key.equals(key)) return perm;
        }
        return null;
    }
}
