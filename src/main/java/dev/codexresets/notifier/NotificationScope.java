package dev.codexresets.notifier;

import java.util.Properties;

enum NotificationScope {
    RESET_ONLY, RESET_AND_SCHEDULED, ALL;

    boolean includesScheduled() { return this != RESET_ONLY; }
    boolean includesWatch() { return this == ALL; }

    static NotificationScope from(Properties properties) {
        String value = properties.getProperty("notify.scope");
        if (value == null) {
            // Preserve behavior for existing installations that still have the old setting.
            String legacy = properties.getProperty("notify.scheduled", "true").strip();
            if (legacy.equalsIgnoreCase("true")) return RESET_AND_SCHEDULED;
            if (legacy.equalsIgnoreCase("false")) return RESET_ONLY;
            throw new IllegalArgumentException("notify.scheduled는 true 또는 false여야 합니다.");
        }
        return switch (value.strip()) {
            case "reset-only" -> RESET_ONLY;
            case "reset-and-scheduled" -> RESET_AND_SCHEDULED;
            case "all" -> ALL;
            default -> throw new IllegalArgumentException(
                    "notify.scope는 reset-only, reset-and-scheduled, all 중 하나여야 합니다.");
        };
    }
}
