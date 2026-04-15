package com.example.beautiful_barometer.util;

public final class AdaptiveRecordingPolicy {

    public enum Mode {
        CALM("calm"),
        NORMAL("normal"),
        RAPID("rapid"),
        FIXED("fixed"),
        STOPPED("stopped");

        private final String prefValue;

        Mode(String prefValue) {
            this.prefValue = prefValue;
        }

        public String getPrefValue() {
            return prefValue;
        }

        public static Mode fromPref(String value) {
            for (Mode mode : values()) {
                if (mode.prefValue.equals(value)) {
                    return mode;
                }
            }
            return STOPPED;
        }
    }

    private static final long WINDOW_MS = 2L * 60_000L;

    private static final double NORMAL_ENTER_DELTA_HPA = 0.12;
    private static final double CALM_EXIT_DELTA_HPA = 0.08;
    private static final double RAPID_ENTER_DELTA_HPA = 0.45;
    private static final double RAPID_EXIT_DELTA_HPA = 0.25;

    private AdaptiveRecordingPolicy() {
    }

    public static long getWindowMs() {
        return WINDOW_MS;
    }

    public static Mode resolveMode(Mode currentMode, double windowDeltaHpa) {
        if (currentMode == null || currentMode == Mode.FIXED || currentMode == Mode.STOPPED) {
            currentMode = Mode.NORMAL;
        }

        switch (currentMode) {
            case CALM:
                if (windowDeltaHpa >= RAPID_ENTER_DELTA_HPA) {
                    return Mode.RAPID;
                }
                if (windowDeltaHpa >= NORMAL_ENTER_DELTA_HPA) {
                    return Mode.NORMAL;
                }
                return Mode.CALM;
            case RAPID:
                if (windowDeltaHpa < RAPID_EXIT_DELTA_HPA) {
                    if (windowDeltaHpa < CALM_EXIT_DELTA_HPA) {
                        return Mode.CALM;
                    }
                    return Mode.NORMAL;
                }
                return Mode.RAPID;
            case NORMAL:
            default:
                if (windowDeltaHpa >= RAPID_ENTER_DELTA_HPA) {
                    return Mode.RAPID;
                }
                if (windowDeltaHpa < CALM_EXIT_DELTA_HPA) {
                    return Mode.CALM;
                }
                return Mode.NORMAL;
        }
    }

    public static long getMinSaveIntervalMs(Mode mode) {
        switch (mode) {
            case CALM:
                return 3L * 60_000L;
            case RAPID:
                return 15_000L;
            case NORMAL:
            case FIXED:
            case STOPPED:
            default:
                return 60_000L;
        }
    }

    public static double getMinDeltaHpa(Mode mode) {
        switch (mode) {
            case CALM:
                return 0.18;
            case RAPID:
                return 0.04;
            case NORMAL:
            case FIXED:
            case STOPPED:
            default:
                return 0.10;
        }
    }
}
