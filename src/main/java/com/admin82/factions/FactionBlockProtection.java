package com.admin82.factions;

public final class FactionBlockProtection {
    private static final ThreadLocal<Boolean> ALLOW_PROTECTED_REMOVAL = ThreadLocal.withInitial(() -> false);

    private FactionBlockProtection() {}

    public static boolean canRemoveProtectedBlock() {
        return ALLOW_PROTECTED_REMOVAL.get();
    }

    public static void allowProtectedRemoval(Runnable action) {
        boolean previous = ALLOW_PROTECTED_REMOVAL.get();
        ALLOW_PROTECTED_REMOVAL.set(true);
        try {
            action.run();
        } finally {
            ALLOW_PROTECTED_REMOVAL.set(previous);
        }
    }
}
