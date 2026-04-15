package com.example.beautiful_barometer.notifications;

public enum PressureNotificationType {
    SIMPLE_DROP,
    SIMPLE_RISE,
    FAST_WORSENING,
    SUSTAINED_WORSENING,
    STABILIZING_AFTER_DROP,
    IMPROVING_AFTER_DROP;

    public String prefValue() {
        return name();
    }

    public static PressureNotificationType fromPrefValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return PressureNotificationType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
