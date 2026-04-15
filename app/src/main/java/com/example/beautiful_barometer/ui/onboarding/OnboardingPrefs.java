package com.example.beautiful_barometer.ui.onboarding;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public final class OnboardingPrefs {

    public static final String PREF_ONBOARDING_COMPLETED = "pref_onboarding_completed";

    private OnboardingPrefs() {
    }

    public static boolean shouldShow(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return !prefs.getBoolean(PREF_ONBOARDING_COMPLETED, false);
    }

    public static void markCompleted(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit().putBoolean(PREF_ONBOARDING_COMPLETED, true).apply();
    }
}
