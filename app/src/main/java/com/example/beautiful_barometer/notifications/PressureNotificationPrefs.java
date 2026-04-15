package com.example.beautiful_barometer.notifications;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;

import androidx.preference.PreferenceManager;

import com.example.beautiful_barometer.util.ParseUtils;

import java.util.Calendar;
import java.util.Locale;

public final class PressureNotificationPrefs {

    public static final String PREF_NOTIFICATION_MODE = "pref_notification_mode";
    public static final String PREF_LEGACY_SMART_NOTIFICATIONS_ENABLED = "pref_notifications_enabled";
    public static final String PREF_NOTIFICATION_SENSITIVITY = "pref_notification_sensitivity";
    public static final String PREF_NOTIFICATION_MIN_INTERVAL_MIN = "pref_notification_min_interval_min";
    public static final String PREF_NOTIFICATION_SIMPLE_DROP_ENABLED = "pref_notification_simple_drop_enabled";
    public static final String PREF_NOTIFICATION_SIMPLE_RISE_ENABLED = "pref_notification_simple_rise_enabled";
    public static final String PREF_NOTIFICATION_SIMPLE_THRESHOLD = "pref_notification_simple_threshold";
    public static final String PREF_NOTIFICATION_SIMPLE_WINDOW_HOURS = "pref_notification_simple_window_hours";
    public static final String PREF_SILENT_MODE_KIND = "pref_silent_mode_kind";
    public static final String PREF_SILENT_MODE_ENABLED = "pref_silent_mode_enabled";
    public static final String PREF_SILENT_MODE_START_MIN = "pref_silent_mode_start_min";
    public static final String PREF_SILENT_MODE_END_MIN = "pref_silent_mode_end_min";

    public static final String MODE_OFF = "off";
    public static final String MODE_SIMPLE = "simple";
    public static final String MODE_SMART = "smart";

    private PressureNotificationPrefs() {
    }

    public static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static String getNotificationMode(Context context) {
        SharedPreferences sharedPreferences = prefs(context);
        String mode = sharedPreferences.getString(PREF_NOTIFICATION_MODE, null);
        if (MODE_OFF.equals(mode) || MODE_SIMPLE.equals(mode) || MODE_SMART.equals(mode)) {
            return mode;
        }
        if (sharedPreferences.getBoolean(PREF_LEGACY_SMART_NOTIFICATIONS_ENABLED, false)) {
            return MODE_SMART;
        }
        return MODE_OFF;
    }

    public static boolean areAnyNotificationsEnabled(Context context) {
        return !MODE_OFF.equals(getNotificationMode(context));
    }

    public static boolean isSmartMode(Context context) {
        return MODE_SMART.equals(getNotificationMode(context));
    }

    public static boolean isSimpleMode(Context context) {
        return MODE_SIMPLE.equals(getNotificationMode(context));
    }

    public static PressureNotificationSensitivity getSensitivity(Context context) {
        return PressureNotificationSensitivity.fromPrefValue(
                prefs(context).getString(PREF_NOTIFICATION_SENSITIVITY, "medium")
        );
    }

    public static long getMinIntervalMs(Context context) {
        String value = prefs(context).getString(PREF_NOTIFICATION_MIN_INTERVAL_MIN, "180");
        long minutes;
        try {
            minutes = Long.parseLong(value);
        } catch (Exception ex) {
            minutes = 180L;
        }
        return Math.max(15L, minutes) * 60_000L;
    }

    public static boolean isSimpleDropEnabled(Context context) {
        return prefs(context).getBoolean(PREF_NOTIFICATION_SIMPLE_DROP_ENABLED, true);
    }

    public static boolean isSimpleRiseEnabled(Context context) {
        return prefs(context).getBoolean(PREF_NOTIFICATION_SIMPLE_RISE_ENABLED, false);
    }

    public static double getSimpleThresholdHpa(Context context) {
        String value = prefs(context).getString(PREF_NOTIFICATION_SIMPLE_THRESHOLD, "1.0");
        return Math.max(0.1, ParseUtils.parseFlexibleDouble(value, 1.0));
    }

    public static int getSimpleWindowHours(Context context) {
        String value = prefs(context).getString(PREF_NOTIFICATION_SIMPLE_WINDOW_HOURS, "3");
        int hours;
        try {
            hours = Integer.parseInt(value);
        } catch (Exception ex) {
            hours = 3;
        }
        if (hours <= 1) return 1;
        if (hours <= 3) return 3;
        return 6;
    }

    public static String getSilentModeKind(Context context) {
        return prefs(context).getString(PREF_SILENT_MODE_KIND, "off");
    }

    public static boolean isSilentModeManuallyEnabled(Context context) {
        return prefs(context).getBoolean(PREF_SILENT_MODE_ENABLED, false);
    }

    public static int getSilentModeStartMinutes(Context context) {
        return prefs(context).getInt(PREF_SILENT_MODE_START_MIN, 23 * 60);
    }

    public static int getSilentModeEndMinutes(Context context) {
        return prefs(context).getInt(PREF_SILENT_MODE_END_MIN, 8 * 60);
    }

    public static void setSilentModeTimeMinutes(Context context, boolean isStart, int totalMinutes) {
        prefs(context)
                .edit()
                .putInt(isStart ? PREF_SILENT_MODE_START_MIN : PREF_SILENT_MODE_END_MIN, totalMinutes)
                .apply();
    }

    public static boolean isSilentModeActive(Context context, long nowMs) {
        String kind = getSilentModeKind(context);
        if ("manual".equals(kind)) {
            return isSilentModeManuallyEnabled(context);
        }
        if (!"schedule".equals(kind)) {
            return false;
        }

        int start = getSilentModeStartMinutes(context);
        int end = getSilentModeEndMinutes(context);
        if (start == end) {
            return false;
        }

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(nowMs);
        int minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);

        if (start < end) {
            return minuteOfDay >= start && minuteOfDay < end;
        }
        return minuteOfDay >= start || minuteOfDay < end;
    }

    public static String formatTime(Context context, int totalMinutes) {
        int hours = ((totalMinutes / 60) % 24 + 24) % 24;
        int minutes = ((totalMinutes % 60) + 60) % 60;
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hours);
        calendar.set(Calendar.MINUTE, minutes);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return DateFormat.getTimeFormat(context).format(calendar.getTime());
    }

    public static String buildSilentModeSummary(Context context) {
        String kind = getSilentModeKind(context);
        switch (kind) {
            case "manual":
                return isSilentModeManuallyEnabled(context)
                        ? "включён вручную"
                        : "вручную сейчас выключен";
            case "schedule":
                return String.format(
                        Locale.getDefault(),
                        "%s – %s",
                        formatTime(context, getSilentModeStartMinutes(context)),
                        formatTime(context, getSilentModeEndMinutes(context))
                );
            case "off":
            default:
                return "выключен";
        }
    }
}
