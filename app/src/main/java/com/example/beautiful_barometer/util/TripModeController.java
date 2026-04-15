package com.example.beautiful_barometer.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.beautiful_barometer.trip.TripSuggestionPrefs;

public final class TripModeController {

    public static final String PREF_TRIP_MODE_ENABLED = "pref_trip_mode_enabled";
    public static final String PREF_FORECAST_BASELINE_TS = "pref_forecast_baseline_ts";

    private TripModeController() {
    }

    public static SharedPreferences prefs(Context context) {
        return ServiceController.prefs(context);
    }

    public static boolean isTripModeEnabled(Context context) {
        return prefs(context).getBoolean(PREF_TRIP_MODE_ENABLED, false);
    }

    public static long getForecastBaselineTimestamp(Context context) {
        return prefs(context).getLong(PREF_FORECAST_BASELINE_TS, 0L);
    }

    public static void setTripModeEnabled(Context context, boolean enabled) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = prefs(appContext);
        boolean wasEnabled = prefs.getBoolean(PREF_TRIP_MODE_ENABLED, false);

        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(PREF_TRIP_MODE_ENABLED, enabled);
        if (wasEnabled && !enabled) {
            editor.putLong(PREF_FORECAST_BASELINE_TS, System.currentTimeMillis());
        }
        editor.apply();
        TripSuggestionPrefs.markTripModeChanged(appContext, System.currentTimeMillis());
    }
}
