package com.example.beautiful_barometer.util;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.example.beautiful_barometer.notifications.PressureNotificationStateStore;
import com.example.beautiful_barometer.feedback.AppEventLogger;
import com.example.beautiful_barometer.service.RecordingTileService;
import com.example.beautiful_barometer.service.SensorService;
import com.example.beautiful_barometer.util.DeviceCapabilities;

import java.util.List;

public final class ServiceController {

    public static final String PREF_RECORDING_ENABLED = "pref_recording_enabled";
    public static final String PREF_SERVICE_RUNNING = "pref_service_running";
    public static final String PREF_ADAPTIVE_RECORDING_ENABLED = "pref_adaptive_recording_enabled";
    public static final String PREF_ADAPTIVE_RECORDING_MODE = "pref_adaptive_recording_mode";
    public static final String PREF_LAST_START_FAILURE_AT = "pref_recording_start_failure_at";
    public static final String PREF_LAST_START_FAILURE_REASON = "pref_recording_start_failure_reason";

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

    public static long getLastStartFailureAt(Context context) {
        return prefs(context).getLong(PREF_LAST_START_FAILURE_AT, 0L);
    }

    public static String getLastStartFailureReason(Context context) {
        return prefs(context).getString(PREF_LAST_START_FAILURE_REASON, "");
    }

    public static void clearLastStartFailure(Context context) {
        prefs(context)
                .edit()
                .remove(PREF_LAST_START_FAILURE_AT)
                .remove(PREF_LAST_START_FAILURE_REASON)
                .apply();
    }

    public static void markStartFailure(Context context, String source, Throwable throwable) {
        Context appContext = context.getApplicationContext();
        String type = throwable != null ? throwable.getClass().getSimpleName() : "unknown";
        String message = throwable != null ? throwable.getMessage() : "";
        String reason = source + ": " + type;
        if (message != null && !message.trim().isEmpty()) {
            reason = reason + ": " + message.trim();
        }
        if (reason.length() > 260) {
            reason = reason.substring(0, 260) + "...";
        }

        prefs(appContext)
                .edit()
                .putLong(PREF_LAST_START_FAILURE_AT, System.currentTimeMillis())
                .putString(PREF_LAST_START_FAILURE_REASON, reason)
                .apply();
        setServiceRunning(appContext, false);
        setAdaptiveMode(appContext, "stopped");
        AppEventLogger.log(appContext, "RECORDING", "start failed; " + reason);
        RecordingTileService.requestTileRefresh(appContext);
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

    public static boolean startRecording(Context context) {
        return startRecording(context, "user");
    }

    public static boolean startRecording(Context context, String source) {
        Context appContext = context.getApplicationContext();
        if (!DeviceCapabilities.hasBarometer(appContext)) {
            setRecordingEnabled(appContext, false);
            setServiceRunning(appContext, false);
            setAdaptiveMode(appContext, "stopped");
            AppEventLogger.log(appContext, "RECORDING", "start ignored: no barometer sensor");
            RecordingTileService.requestTileRefresh(appContext);
            return false;
        }
        setRecordingEnabled(appContext, true);
        setAdaptiveMode(appContext, isAdaptiveRecordingEnabled(appContext) ? "normal" : "fixed");
        AppEventLogger.log(appContext, "RECORDING", "start requested from " + source
                + "; adaptive=" + isAdaptiveRecordingEnabled(appContext));
        try {
            ContextCompat.startForegroundService(appContext, new Intent(appContext, SensorService.class));
            clearLastStartFailure(appContext);
        } catch (RuntimeException e) {
            markStartFailure(appContext, source, e);
            return false;
        }
        RecordingTileService.requestTileRefresh(appContext);
        return true;
    }

    public static void stopRecording(Context context) {
        Context appContext = context.getApplicationContext();
        setRecordingEnabled(appContext, false);
        setAdaptiveMode(appContext, "stopped");
        AppEventLogger.log(appContext, "RECORDING", "stop requested");
        PressureNotificationStateStore.clearTransientState(appContext);
        appContext.stopService(new Intent(appContext, SensorService.class));
        RecordingTileService.requestTileRefresh(appContext);
    }
}
