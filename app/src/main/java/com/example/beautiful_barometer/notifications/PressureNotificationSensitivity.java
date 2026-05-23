package com.example.beautiful_barometer.notifications;

import java.util.Locale;

public enum PressureNotificationSensitivity {
    LOW,
    MEDIUM,
    HIGH;

    public String prefValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PressureNotificationSensitivity fromPrefValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return MEDIUM;
        }
        switch (value.toLowerCase(Locale.ROOT)) {
            case "low":
                return LOW;
            case "high":
                return HIGH;
            case "medium":
            default:
                return MEDIUM;
        }
    }
}
