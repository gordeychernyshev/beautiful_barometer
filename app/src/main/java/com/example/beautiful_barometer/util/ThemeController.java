package com.example.beautiful_barometer.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.preference.PreferenceManager;

import com.example.beautiful_barometer.R;

public final class ThemeController {

    public static final String PREF_THEME_MODE = "pref_theme_mode";
    public static final String PREF_HIDE_STATUS_BAR = "pref_hide_status_bar";
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";
    public static final String MODE_AMOLED = "amoled";

    private ThemeController() {
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    @NonNull
    public static String getThemeMode(Context context) {
        String value = prefs(context).getString(PREF_THEME_MODE, MODE_SYSTEM);
        if (MODE_LIGHT.equals(value) || MODE_DARK.equals(value) || MODE_AMOLED.equals(value)) {
            return value;
        }
        return MODE_SYSTEM;
    }

    public static void setThemeMode(Context context, String mode) {
        prefs(context).edit().putString(PREF_THEME_MODE, mode).apply();
        applyGlobalNightMode(context);
    }


    public static boolean isStatusBarHidden(Context context) {
        return prefs(context).getBoolean(PREF_HIDE_STATUS_BAR, false);
    }

    public static void setStatusBarHidden(Context context, boolean hidden) {
        prefs(context).edit().putBoolean(PREF_HIDE_STATUS_BAR, hidden).apply();
    }

    public static void applySystemUi(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), true);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        if (controller == null) return;
        if (isStatusBarHidden(activity)) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.statusBars());
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars());
        }
    }

    public static void applyGlobalNightMode(Context context) {
        String mode = getThemeMode(context);
        if (MODE_LIGHT.equals(mode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if (MODE_DARK.equals(mode) || MODE_AMOLED.equals(mode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    public static void applyToActivity(Activity activity) {
        applyGlobalNightMode(activity);
        if (MODE_AMOLED.equals(getThemeMode(activity))) {
            activity.setTheme(R.style.Theme_Barometer_Amoled);
        } else {
            activity.setTheme(R.style.Theme_Barometer);
        }
    }
}
