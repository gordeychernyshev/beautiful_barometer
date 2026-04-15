package com.example.beautiful_barometer.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.example.beautiful_barometer.R;
import com.example.beautiful_barometer.data.AppDatabase;
import com.example.beautiful_barometer.data.PressureDao;
import com.example.beautiful_barometer.data.PressureSample;
import com.example.beautiful_barometer.notifications.PressureNotificationPrefs;
import com.example.beautiful_barometer.notifications.PressureNotificationStateStore;
import com.example.beautiful_barometer.util.BackgroundProtectionHelper;
import com.example.beautiful_barometer.feedback.AppEventLogger;
import com.example.beautiful_barometer.util.ServiceController;
import com.example.beautiful_barometer.util.ThemeController;
import com.example.beautiful_barometer.util.TripModeController;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatusActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView tvSensor;
    private TextView tvRecording;
    private TextView tvService;
    private TextView tvInterval;
    private TextView tvHistory;
    private TextView tvAdaptive;
    private TextView tvAdaptiveMode;
    private TextView tvLastSample;
    private TextView tvDbCount;
    private TextView tvNotifications;
    private TextView tvBattery;
    private TextView tvAutostart;
    private TextView tvDevice;

    private boolean isUiAlive() {
        return !isFinishing() && !isDestroyed();
    }

    private void runOnUiThreadIfAlive(Runnable action) {
        runOnUiThread(() -> {
            if (isUiAlive()) {
                action.run();
            }
        });
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeController.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status);
        setTitle(R.string.status_title);

        tvSensor = findViewById(R.id.tvStatusSensor);
        tvRecording = findViewById(R.id.tvStatusRecording);
        tvService = findViewById(R.id.tvStatusService);
        tvInterval = findViewById(R.id.tvStatusInterval);
        tvHistory = findViewById(R.id.tvStatusHistory);
        tvAdaptive = findViewById(R.id.tvStatusAdaptive);
        tvAdaptiveMode = findViewById(R.id.tvStatusAdaptiveMode);
        tvLastSample = findViewById(R.id.tvStatusLastSample);
        tvDbCount = findViewById(R.id.tvStatusDbCount);
        tvNotifications = findViewById(R.id.tvStatusNotifications);
        tvBattery = findViewById(R.id.tvStatusBattery);
        tvAutostart = findViewById(R.id.tvStatusAutostart);
        tvDevice = findViewById(R.id.tvStatusDevice);

        findViewById(R.id.btnStatusBatteryHelp).setOnClickListener(v -> showBackgroundHelpDialog());
        findViewById(R.id.btnStatusCalibrate).setOnClickListener(v ->
                startActivity(new Intent(this, CalibrationWizardActivity.class))
        );
        findViewById(R.id.btnStatusRefresh).setOnClickListener(v -> refresh());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor pressureSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) : null;
        boolean recordingEnabled = ServiceController.isRecordingEnabled(this);
        boolean serviceRunning = ServiceController.isServiceRunning(this);
        boolean adaptiveEnabled = ServiceController.isAdaptiveRecordingEnabled(this);
        boolean ignoreBattery = BackgroundProtectionHelper.isIgnoringBatteryOptimizations(this);
        boolean notificationsGranted = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        String notificationMode = PressureNotificationPrefs.getNotificationMode(this);
        PressureNotificationStateStore.State notificationState = PressureNotificationStateStore.load(this);

        tvSensor.setText(pressureSensor != null
                ? getString(R.string.status_sensor_present)
                : getString(R.string.status_sensor_missing));
        tvRecording.setText(recordingEnabled
                ? getString(R.string.status_recording_enabled)
                : getString(R.string.status_recording_disabled));
        tvService.setText(serviceRunning
                ? getString(R.string.status_service_running)
                : getString(R.string.status_service_stopped));
        tvInterval.setText(getReadableInterval(prefs.getString("pref_interval_ms", "1000")));
        tvHistory.setText(getReadableHistory(prefs.getString("pref_history_size", "10000")));
        tvAdaptive.setText(adaptiveEnabled
                ? getString(R.string.status_adaptive_enabled)
                : getString(R.string.status_adaptive_disabled));
        String adaptiveModeLabel = getAdaptiveModeLabel(ServiceController.getAdaptiveMode(this));
        if (TripModeController.isTripModeEnabled(this)) {
            adaptiveModeLabel = adaptiveModeLabel + " • поездка";
        }
        tvAdaptiveMode.setText(getString(
                R.string.status_adaptive_mode_fmt,
                adaptiveModeLabel
        ));
        String notificationsFeatureLine;
        if (PressureNotificationPrefs.MODE_SIMPLE.equals(notificationMode)) {
            String thresholdText = String.format(
                    Locale.getDefault(),
                    "%.1f гПа",
                    PressureNotificationPrefs.getSimpleThresholdHpa(this)
            );
            notificationsFeatureLine = getString(R.string.status_notifications_feature_simple_fmt)
                    + "\n"
                    + getString(
                    R.string.status_notifications_feature_simple_details_fmt,
                    thresholdText,
                    getWindowLabel(PressureNotificationPrefs.getSimpleWindowHours(this))
            );
        } else if (PressureNotificationPrefs.MODE_SMART.equals(notificationMode)) {
            notificationsFeatureLine = getString(R.string.status_notifications_feature_smart_fmt)
                    + "\n"
                    + getString(
                    R.string.status_notifications_feature_smart_details_fmt,
                    getSensitivityLabel(PressureNotificationPrefs.getSensitivity(this).prefValue())
            );
        } else {
            notificationsFeatureLine = getString(R.string.status_notifications_feature_off);
        }
        String permissionLine = getString(
                R.string.status_notifications_permission_fmt,
                getString(notificationsGranted
                        ? R.string.status_notifications_permission_yes
                        : R.string.status_notifications_permission_no)
        );
        String silentModeLine = getString(
                R.string.status_silent_mode_fmt,
                PressureNotificationPrefs.buildSilentModeSummary(this)
        );
        String lastNotificationLine = notificationState.lastNotificationTitle == null
                ? getString(R.string.status_last_notification_none)
                : getString(
                R.string.status_last_notification_fmt,
                notificationState.lastNotificationTitle,
                DateFormat.format("dd.MM.yyyy HH:mm", new Date(notificationState.lastNotificationAt))
        );
        tvNotifications.setText(notificationsFeatureLine + "\n" + permissionLine + "\n" + silentModeLine + "\n" + lastNotificationLine);
        tvBattery.setText(ignoreBattery
                ? getString(R.string.status_battery_ok)
                : getString(R.string.status_battery_restricted));
        tvAutostart.setText(prefs.getBoolean("pref_autostart", false)
                ? getString(R.string.status_autostart_on)
                : getString(R.string.status_autostart_off));
        tvDevice.setText(String.format(Locale.getDefault(), "%s / Android %s / %s",
                Build.MANUFACTURER,
                Build.VERSION.RELEASE,
                Build.MODEL));

        io.execute(() -> {
            PressureDao dao = AppDatabase.get(this).pressureDao();
            int count = dao.count();
            List<PressureSample> latest = dao.latest(1);
            String lastSampleText = getString(R.string.status_last_sample_none);
            if (!latest.isEmpty()) {
                PressureSample sample = latest.get(0);
                CharSequence formattedTime = DateFormat.format("dd.MM.yyyy HH:mm:ss", new Date(sample.timestamp));
                lastSampleText = getString(R.string.status_last_sample_fmt, formattedTime);
            }
            final String dbCountText = getString(R.string.status_db_count_fmt, count);
            final String finalLastSampleText = lastSampleText;
            runOnUiThreadIfAlive(() -> {
                tvDbCount.setText(dbCountText);
                tvLastSample.setText(finalLastSampleText);
            });
        });
    }


    private void showBackgroundHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.pref_background_help_title)
                .setMessage(BackgroundProtectionHelper.buildGuidanceText(this))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.pref_background_help_battery, (dialog, which) -> {
                    try {
                        BackgroundProtectionHelper.openBatteryOptimizationScreen(this);
                    } catch (Exception e) {
                        AppEventLogger.log(this, "STATUS", "open battery optimization screen failed: " + e.getClass().getSimpleName());
                    }
                })
                .setPositiveButton(R.string.pref_background_help_autostart, (dialog, which) -> {
                    boolean opened = BackgroundProtectionHelper.openVendorAutostartSettings(this);
                    if (!opened) {
                        BackgroundProtectionHelper.openAppDetails(this);
                    }
                })
                .show();
    }

    private String getAdaptiveModeLabel(String modeValue) {
        switch (modeValue) {
            case "calm":
                return getString(R.string.adaptive_mode_calm);
            case "rapid":
                return getString(R.string.adaptive_mode_rapid);
            case "fixed":
                return getString(R.string.adaptive_mode_fixed);
            case "stopped":
                return getString(R.string.adaptive_mode_stopped);
            case "normal":
            default:
                return getString(R.string.adaptive_mode_normal);
        }
    }



    private String getWindowLabel(int hours) {
        switch (hours) {
            case 1:
                return getString(R.string.notification_window_1h);
            case 3:
                return getString(R.string.notification_window_3h);
            case 6:
            default:
                return getString(R.string.notification_window_6h);
        }
    }

    private String getSensitivityLabel(String value) {
        switch (value) {
            case "low":
                return getString(R.string.notification_sensitivity_low).toLowerCase(Locale.getDefault());
            case "high":
                return getString(R.string.notification_sensitivity_high).toLowerCase(Locale.getDefault());
            case "medium":
            default:
                return getString(R.string.notification_sensitivity_medium).toLowerCase(Locale.getDefault());
        }
    }

    private String getReadableInterval(String value) {
        long ms;
        try {
            ms = Long.parseLong(value);
        } catch (Exception e) {
            ms = 1000;
        }
        if (ms % 86_400_000L == 0L) {
            return String.format(java.util.Locale.getDefault(), "%.0f дн", ms / 86_400_000d);
        }
        if (ms % 3_600_000L == 0L) {
            return String.format(java.util.Locale.getDefault(), "%.0f ч", ms / 3_600_000d);
        }
        if (ms % 60_000L == 0L) {
            return String.format(java.util.Locale.getDefault(), "%.0f мин", ms / 60_000d);
        }
        if (ms >= 1000) {
            return getString(R.string.status_interval_seconds_fmt, ms / 1000.0);
        }
        return getString(R.string.status_interval_ms_fmt, ms);
    }

    private String getReadableHistory(String value) {
        try {
            int count = Integer.parseInt(value);
            return getString(R.string.status_history_fmt, count);
        } catch (Exception e) {
            return getString(R.string.status_history_fmt, 10000);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
