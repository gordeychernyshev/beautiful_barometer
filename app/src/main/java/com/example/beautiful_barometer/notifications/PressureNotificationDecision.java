package com.example.beautiful_barometer.notifications;

public final class PressureNotificationDecision {

    public static final PressureNotificationDecision NONE = new PressureNotificationDecision(
            false,
            null,
            null,
            null,
            null
    );

    public final boolean shouldNotify;
    public final PressureNotificationType type;
    public final String title;
    public final String body;
    public final String debugReason;

    public PressureNotificationDecision(
            boolean shouldNotify,
            PressureNotificationType type,
            String title,
            String body,
            String debugReason
    ) {
        this.shouldNotify = shouldNotify;
        this.type = type;
        this.title = title;
        this.body = body;
        this.debugReason = debugReason;
    }
}
