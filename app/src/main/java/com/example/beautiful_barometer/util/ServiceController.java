package com.example.beautiful_barometer.util;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.example.beautiful_barometer.notifications.PressureNotificationStateStore;
import com.example.beautiful_barometer.feedback.AppEventLogger;
import com.example.beautiful_barometer.service.SensorService;
import com.example.beautiful_barometer.util.DeviceCapabilities;

import java.util.List;

public final class ServiceController {

    public static final String PREF_RECORDING_ENABLED = "pref_recording_enabled";
    public static final String PREF_SERVICE_RUNNING = "pref_service_running";
    public static final String PREF_ADAPTIVE_RECORDING_ENABLED = "pref_adaptive_recording_enabled";
    public static final String PREF_ADAPTIVE_RECORDING_MODE = "pref_adaptive_recording_mode";

    private ServiceController() {
    }

    public static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static boolean isRecordingEnabled(Context context) {
        return prefs(context).getBoolean(PREF_RECORDING_ENABLED, true);
    }

    public static void setRecordingEnabled(Context context, boolean enabled) {
        prefs(context)
                .edit()
                .putBoolean(PREF_RECORDING_ENABLED, enabled)
                .apply();
    }

    public static void setServiceRunning(Context context, boolean running) {
        prefs(context)
                .edit()
                .putBoolean(PREF_SERVICE_RUNNING, running)
                .apply();
    }

    public static boolean isAdaptiveRecordingEnabled(Context context) {
        return prefs(context).getBoolean(PREF_ADAPTIVE_RECORDING_ENABLED, true);
    }

    public static void setAdaptiveRecordingEnabled(Context context, boolean enabled) {
        prefs(context)
                .edit()
                .putBoolean(PREF_ADAPTIVE_RECORDING_ENABLED, enabled)
                .apply();
    }

    public static String getAdaptiveMode(Context context) {
        return prefs(context).getString(
                PREF_ADAPTIVE_RECORDING_MODE,
                isAdaptiveRecordingEnabled(context) ? "normal" : "fixed"
        );
    }

    public static void setAdaptiveMode(Context context, String mode) {
        prefs(context)
                .edit()
                .putString(PREF_ADAPTIVE_RECORDING_MODE, mode)
                .apply();
    }

    public static boolean isServiceRunning(Context context) {
        Context appContext = context.getApplicationContext();
        try {
            ActivityManager manager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
                for (ActivityManager.RunningServiceInfo service : services) {
                    if (SensorService.class.getName().equals(service.service.getClassName())) {
                        return true;
                    }
                }
            }
        } catch (SecurityException ignored) {
            // Fallback to persisted state below.
        }
        return prefs(appContext).getBoolean(PREF_SERVICE_RUNNING, false);
    }

    public static void startRecording(Context context) {
        Context appContext = context.getApplicationContext();
        if (!DeviceCapabilities.hasBarometer(appContext)) {
            setRecordingEnabled(appContext, false);
            setServiceRunning(appContext, false);
            setAdaptiveMode(appContext, "stopped");
            AppEventLogger.log(appContext, "RECORDING", "start ignored: no barometer sensor");
            return;
        }
        setRecordingEnabled(appContext, true);
        setAdaptiveMode(appContext, isAdaptiveRecordingEnabled(appContext) ? "normal" : "fixed");
        AppEventLogger.log(appContext, "RECORDING", "start requested; adaptive=" + isAdaptiveRecordingEnabled(appContext));
        ContextCompat.startForegroundService(appContext, new Intent(appContext, SensorService.class));
    }

    public static void stopRecording(Context context) {
        Context appContext = context.getApplicationContext();
        setRecordingEnabled(appContext, false);
        setAdaptiveMode(appContext, "stopped");
        AppEventLogger.log(appContext, "RECORDING", "stop requested");
        PressureNotificationStateStore.clearTransientState(appContext);
        appContext.stopService(new Intent(appContext, SensorService.class));
    }
}
