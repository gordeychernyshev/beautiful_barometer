package com.example.beautiful_barometer.trip;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public final class TripSuggestionPrefs {

    public static final String PREF_TRIP_SUGGESTION_ENABLED = "pref_trip_suggestion_enabled";
    private static final String PREF_LAST_SHOWN_AT = "pref_trip_suggestion_last_shown_at";
    private static final String PREF_LAST_DISMISSED_AT = "pref_trip_suggestion_last_dismissed_at";
    private static final String PREF_LAST_TRIP_MODE_CHANGE_AT = "pref_trip_suggestion_last_trip_mode_change_at";

    public static final long COOLDOWN_MS = 60L * 60_000L;

    private TripSuggestionPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(PREF_TRIP_SUGGESTION_ENABLED, true);
    }

    public static void markShown(Context context, long nowMs) {
        prefs(context).edit().putLong(PREF_LAST_SHOWN_AT, nowMs).apply();
    }

    public static void markDismissed(Context context, long nowMs) {
        prefs(context).edit().putLong(PREF_LAST_DISMISSED_AT, nowMs).apply();
    }

    public static void markTripModeChanged(Context context, long nowMs) {
        prefs(context).edit().putLong(PREF_LAST_TRIP_MODE_CHANGE_AT, nowMs).apply();
    }

    public static long getLastRelevantAt(Context context) {
        SharedPreferences prefs = prefs(context);
        long shown = prefs.getLong(PREF_LAST_SHOWN_AT, 0L);
        long dismissed = prefs.getLong(PREF_LAST_DISMISSED_AT, 0L);
        long tripChanged = prefs.getLong(PREF_LAST_TRIP_MODE_CHANGE_AT, 0L);
        return Math.max(Math.max(shown, dismissed), tripChanged);
    }

    public static boolean canSuggestNow(Context context, long nowMs) {
        return nowMs - getLastRelevantAt(context) >= COOLDOWN_MS;
    }
}
