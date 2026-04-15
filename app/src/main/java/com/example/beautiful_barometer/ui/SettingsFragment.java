// app/src/main/java/com/example/beautiful_barometer/ui/SettingsFragment.java
package com.example.beautiful_barometer.ui;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.ListPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.example.beautiful_barometer.R;
import com.example.beautiful_barometer.data.AppDatabase;
import com.example.beautiful_barometer.feedback.AppEventLogger;
import com.example.beautiful_barometer.feedback.IssueReportManager;
import com.example.beautiful_barometer.data.EventDao;
import com.example.beautiful_barometer.data.EventSample;
import com.example.beautiful_barometer.data.PressureDao;
import com.example.beautiful_barometer.data.PressureSample;
import com.example.beautiful_barometer.notifications.PressureNotificationPrefs;
import com.example.beautiful_barometer.notifications.PressureNotificationStateStore;
import com.example.beautiful_barometer.util.BackgroundProtectionHelper;
import com.example.beautiful_barometer.util.DeviceCapabilities;
import com.example.beautiful_barometer.util.ParseUtils;
import com.example.beautiful_barometer.util.ServiceController;
import com.example.beautiful_barometer.util.ThemeController;
import com.example.beautiful_barometer.util.TripModeController;
import com.example.beautiful_barometer.util.Units;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.button.MaterialButton;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends PreferenceFragmentCompat {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<String> createCsvLauncher;
    private FusedLocationProviderClient fusedClient;
    private ActivityResultLauncher<String> fineLocationPerm;


    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (key == null) return;
                if ("pref_ref_altitude_m".equals(key) || "pref_units".equals(key)) {
                    updateCalibrationPreferenceSummary();
                }
                if ("pref_recording_enabled".equals(key)) {
                    updateRecordingUi();
                }
                if (PressureNotificationPrefs.PREF_NOTIFICATION_MODE.equals(key)
                        || PressureNotificationPrefs.PREF_NOTIFICATION_SENSITIVITY.equals(key)
                        || PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_DROP_ENABLED.equals(key)
                        || PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_RISE_ENABLED.equals(key)
                        || PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_THRESHOLD.equals(key)
                        || PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_WINDOW_HOURS.equals(key)
                        || PressureNotificationPrefs.PREF_NOTIFICATION_MIN_INTERVAL_MIN.equals(key)
                        || PressureNotificationPrefs.PREF_SILENT_MODE_KIND.equals(key)
                        || PressureNotificationPrefs.PREF_SILENT_MODE_ENABLED.equals(key)
                        || PressureNotificationPrefs.PREF_SILENT_MODE_START_MIN.equals(key)
                        || PressureNotificationPrefs.PREF_SILENT_MODE_END_MIN.equals(key)) {
                    updateNotificationsUi();
                    updateSilentModeUi();
                }
            };

    private Context getAppContextOrNull() {
        Context context = getContext();
        return context != null ? context.getApplicationContext() : null;
    }

    private void showToast(int resId, int duration) {
        Context appContext = getAppContextOrNull();
        if (appContext == null) return;
        mainHandler.post(() -> Toast.makeText(appContext, resId, duration).show());
    }

    private void showToastText(String text, int duration) {
        Context appContext = getAppContextOrNull();
        if (appContext == null) return;
        mainHandler.post(() -> Toast.makeText(appContext, text, duration).show());
    }

    private void logEvent(String type, String value) {
        Context appContext = getAppContextOrNull();
        if (appContext == null) return;

        io.execute(() -> {
            try {
                EventDao ed = AppDatabase.get(appContext).eventDao();
                ed.insert(new EventSample(type, System.currentTimeMillis(), value));
            } catch (Exception e) {
                AppEventLogger.log(appContext, "SETTINGS", "event log failed: " + e.getClass().getSimpleName());
            }
        });
    }


    private void logPreferenceChange(String key, Object value) {
        Context appContext = getAppContextOrNull();
        if (appContext == null) return;
        AppEventLogger.log(appContext, "SETTINGS", key + "=" + String.valueOf(value));
    }

    private void logUiAction(String message) {
        Context appContext = getAppContextOrNull();
        if (appContext == null) return;
        AppEventLogger.log(appContext, "SETTINGS", message);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        fusedClient = LocationServices.getFusedLocationProviderClient(requireContext());
        fineLocationPerm = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) requestSingleGpsAltitude();
                    else showToast(R.string.perm_denied_location, Toast.LENGTH_SHORT);
                }
        );
    }

    @Override
    public void onPause() {
        Context context = getContext();
        if (context != null) {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        super.onPause();
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        ListPreference unitsPref = findPreference("pref_units");
        if (unitsPref != null) {
            unitsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                logPreferenceChange("units", newValue);
                return true;
            });
        }

        createCsvLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/csv"),
                uri -> {
                    if (uri != null) {
                        exportCsvToUri(uri);
                    }
                }
        );

        setupThemePreference();
        setupClearDatabasePreference();
        setupHistoryPreference();
        setupIntervalPreference();
        setupRecordingPreference();
        setupAdaptiveRecordingPreference();
        setupTripModePreference();
        setupNavigationPreferences();
        updateCalibrationPreferenceSummary();
        setupExportPreference();
        setupBatteryPreferences();
        setupNotificationPreferences();
        setupStatusBarPreference();
        setupReportProblemPreference();
    }

    private void setupThemePreference() {
        ListPreference themePref = findPreference(ThemeController.PREF_THEME_MODE);
        if (themePref == null) return;
        themePref.setOnPreferenceChangeListener((preference, newValue) -> {
            Context context = getContext();
            if (context == null) return false;
            String modeValue = String.valueOf(newValue);
            AppEventLogger.log(context, "SETTINGS", "Theme changed to " + modeValue);
            ThemeController.setThemeMode(context, modeValue);
            if (getActivity() != null) {
                mainHandler.post(() -> {
                    if (getActivity() != null) {
                        getActivity().recreate();
                    }
                });
            }
            return true;
        });
    }

    private void setupClearDatabasePreference() {
        Preference clearDb = findPreference("pref_clear_db");
        if (clearDb == null) return;
        clearDb.setOnPreferenceClickListener(p -> {
            Context context = getContext();
            if (context == null) return true;
            new AlertDialog.Builder(context)
                    .setTitle("Очистить базу данных?")
                    .setMessage("Будут удалены все измерения и события. Отменить нельзя.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Очистить", (d, w) -> {
                        logUiAction("Clear database confirmed");
                        clearDatabase();
                    })
                    .show();
            return true;
        });
    }


    private void setupHistoryPreference() {
        Preference pref = findPreference("pref_history_size");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(p -> {
            logUiAction("Open history size dialog");
            showHistorySizeDialog();
            return true;
        });
        updateHistoryPreferenceSummary();
    }

    private void setupIntervalPreference() {
        Preference pref = findPreference("pref_interval_ms");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(p -> {
            logUiAction("Open poll interval dialog");
            showIntervalDialog();
            return true;
        });
        updateIntervalPreferenceSummary();
    }

    private void updateHistoryPreferenceSummary() {
        Context context = getContext();
        Preference pref = findPreference("pref_history_size");
        if (context == null || pref == null) return;
        String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("pref_history_size", "10000");
        pref.setSummary(getString(R.string.pref_history_summary_fmt, value));
    }

    private void updateIntervalPreferenceSummary() {
        Context context = getContext();
        Preference pref = findPreference("pref_interval_ms");
        if (context == null || pref == null) return;
        String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("pref_interval_ms", "1000");
        pref.setSummary(formatIntervalSummary(value));
    }

    private void updateRecordingUi() {
        Context context = getContext();
        SwitchPreferenceCompat recordingPref = findPreference("pref_recording_enabled");
        if (context == null || recordingPref == null) return;
        boolean hasBarometer = DeviceCapabilities.hasBarometer(context);
        if (!hasBarometer) {
            recordingPref.setChecked(false);
            recordingPref.setEnabled(false);
            recordingPref.setSummary(getString(R.string.no_barometer_sensor));
            return;
        }
        recordingPref.setEnabled(true);
        boolean enabled = ServiceController.isRecordingEnabled(context);
        recordingPref.setSummary(getString(enabled ? R.string.pref_recording_state_on : R.string.pref_recording_state_off));
    }

    private int dp(int value) {
        Context context = getContext();
        if (context == null) return value;
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void showHistorySizeDialog() {
        Context context = getContext();
        if (context == null) return;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String current = sp.getString("pref_history_size", "10000");
        String[] presetValues = context.getResources().getStringArray(R.array.history_values);
        String[] presetLabels = context.getResources().getStringArray(R.array.history_entries);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(12), pad, dp(4));
        scrollView.addView(root);

        TextView presetsLabel = new TextView(context);
        presetsLabel.setText(R.string.pref_dialog_presets);
        root.addView(presetsLabel);

        RadioGroup group = new RadioGroup(context);
        group.setOrientation(LinearLayout.VERTICAL);
        root.addView(group);

        for (int i = 0; i < presetValues.length; i++) {
            RadioButton rb = new RadioButton(context);
            rb.setText(presetLabels[i]);
            rb.setTag(presetValues[i]);
            group.addView(rb);
            if (presetValues[i].equals(current)) {
                rb.setChecked(true);
            }
        }

        TextView customLabel = new TextView(context);
        customLabel.setText(R.string.pref_dialog_custom_value);
        customLabel.setPadding(0, dp(12), 0, dp(6));
        root.addView(customLabel);

        EditText customInput = new EditText(context);
        customInput.setHint(R.string.pref_history_custom_hint);
        customInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(customInput);

        new AlertDialog.Builder(context)
                .setTitle(R.string.pref_title_history)
                .setView(scrollView)
                .setNegativeButton(R.string.pref_dialog_cancel, null)
                .setPositiveButton(R.string.pref_dialog_save, (dialog, which) -> {
                    String custom = customInput.getText() != null ? customInput.getText().toString().trim() : "";
                    String value;
                    if (!custom.isEmpty()) {
                        int count = (int) Math.round(ParseUtils.parseFlexibleDouble(custom, -1));
                        if (count <= 0) {
                            showToast(R.string.pref_invalid_number, Toast.LENGTH_SHORT);
                            return;
                        }
                        value = String.valueOf(count);
                    } else {
                        int checkedId = group.getCheckedRadioButtonId();
                        RadioButton checked = checkedId != -1 ? group.findViewById(checkedId) : null;
                        if (checked == null || checked.getTag() == null) {
                            value = current;
                        } else {
                            value = String.valueOf(checked.getTag());
                        }
                    }
                    sp.edit().putString("pref_history_size", value).apply();
                    logPreferenceChange("history_size", value);
                    updateHistoryPreferenceSummary();
                })
                .show();
    }

    private void showIntervalDialog() {
        Context context = getContext();
        if (context == null) return;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String current = sp.getString("pref_interval_ms", "1000");
        String[] presetValues = context.getResources().getStringArray(R.array.interval_values);
        String[] presetLabels = context.getResources().getStringArray(R.array.interval_entries);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(12), pad, dp(4));
        scrollView.addView(root);

        TextView presetsLabel = new TextView(context);
        presetsLabel.setText(R.string.pref_dialog_presets);
        root.addView(presetsLabel);

        RadioGroup group = new RadioGroup(context);
        group.setOrientation(LinearLayout.VERTICAL);
        root.addView(group);

        for (int i = 0; i < presetValues.length; i++) {
            RadioButton rb = new RadioButton(context);
            rb.setText(presetLabels[i]);
            rb.setTag(presetValues[i]);
            group.addView(rb);
            if (presetValues[i].equals(current)) {
                rb.setChecked(true);
            }
        }

        TextView customLabel = new TextView(context);
        customLabel.setText(R.string.pref_dialog_custom_value);
        customLabel.setPadding(0, dp(12), 0, dp(6));
        root.addView(customLabel);

        LinearLayout customRow = new LinearLayout(context);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(customRow);

        EditText customInput = new EditText(context);
        customInput.setHint(R.string.pref_interval_custom_hint);
        customInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        customRow.addView(customInput, inputLp);

        Spinner unitSpinner = new Spinner(context);
        String[] units = new String[] {
                getString(R.string.pref_interval_unit_seconds),
                getString(R.string.pref_interval_unit_minutes),
                getString(R.string.pref_interval_unit_hours),
                getString(R.string.pref_interval_unit_days)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, units);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        unitSpinner.setAdapter(adapter);
        customRow.addView(unitSpinner);

        new AlertDialog.Builder(context)
                .setTitle(R.string.pref_title_interval)
                .setView(scrollView)
                .setNegativeButton(R.string.pref_dialog_cancel, null)
                .setPositiveButton(R.string.pref_dialog_save, (dialog, which) -> {
                    String custom = customInput.getText() != null ? customInput.getText().toString().trim() : "";
                    String value;
                    if (!custom.isEmpty()) {
                        double amount = ParseUtils.parseFlexibleDouble(custom, -1);
                        if (amount <= 0) {
                            showToast(R.string.pref_invalid_number, Toast.LENGTH_SHORT);
                            return;
                        }
                        long multiplier;
                        switch (unitSpinner.getSelectedItemPosition()) {
                            case 1: multiplier = 60_000L; break;
                            case 2: multiplier = 3_600_000L; break;
                            case 3: multiplier = 86_400_000L; break;
                            case 0:
                            default: multiplier = 1000L; break;
                        }
                        long ms = Math.max(1000L, Math.round(amount * multiplier));
                        value = String.valueOf(ms);
                    } else {
                        int checkedId = group.getCheckedRadioButtonId();
                        RadioButton checked = checkedId != -1 ? group.findViewById(checkedId) : null;
                        if (checked == null || checked.getTag() == null) {
                            value = current;
                        } else {
                            value = String.valueOf(checked.getTag());
                        }
                    }
                    sp.edit().putString("pref_interval_ms", value).apply();
                    logPreferenceChange("poll_interval_ms", value);
                    updateIntervalPreferenceSummary();
                })
                .show();
    }

    private String formatIntervalSummary(String value) {
        long ms;
        try {
            ms = Long.parseLong(value);
        } catch (Exception e) {
            ms = 1000L;
        }
        if (ms % 86_400_000L == 0L) {
            return getString(R.string.pref_interval_days_fmt, trimTrailingZero(ms / 86_400_000d));
        }
        if (ms % 3_600_000L == 0L) {
            return getString(R.string.pref_interval_hours_fmt, trimTrailingZero(ms / 3_600_000d));
        }
        if (ms % 60_000L == 0L) {
            return getString(R.string.pref_interval_minutes_fmt, trimTrailingZero(ms / 60_000d));
        }
        return getString(R.string.pref_interval_seconds_fmt, trimTrailingZero(ms / 1000d));
    }

    private String trimTrailingZero(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001d) {
            return String.format(Locale.getDefault(), "%d", (long) Math.rint(value));
        }
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private void setupRecordingPreference() {
        SwitchPreferenceCompat recordingPref = findPreference("pref_recording_enabled");
        if (recordingPref == null) return;
        recordingPref.setOnPreferenceChangeListener((preference, newValue) -> {
            Context context = getContext();
            if (context == null) return false;
            boolean enabled = Boolean.TRUE.equals(newValue);
            AppEventLogger.log(context, "SETTINGS", "Recording enabled=" + enabled);
            if (enabled) {
                ServiceController.startRecording(context);
            } else {
                ServiceController.stopRecording(context);
                PressureNotificationStateStore.clearTransientState(context);
            }
            mainHandler.post(this::updateRecordingUi);
            return true;
        });
        updateRecordingUi();
    }

    private void setupAdaptiveRecordingPreference() {
        Preference adaptivePref = findPreference("pref_adaptive_recording_enabled");
        if (adaptivePref == null) return;
        adaptivePref.setOnPreferenceChangeListener((preference, newValue) -> {
            Context context = getContext();
            if (context == null) return false;
            boolean enabled = Boolean.TRUE.equals(newValue);
            logPreferenceChange("adaptive_recording", enabled);
            ServiceController.setAdaptiveRecordingEnabled(context, enabled);
            if (ServiceController.isRecordingEnabled(context)) {
                ServiceController.setAdaptiveMode(context, enabled ? "normal" : "fixed");
            } else {
                ServiceController.setAdaptiveMode(context, "stopped");
            }
            return true;
        });
    }

    private void setupTripModePreference() {
        Preference tripModePref = findPreference("pref_trip_mode_enabled");
        if (tripModePref == null) return;
        tripModePref.setOnPreferenceChangeListener((preference, newValue) -> {
            Context context = getContext();
            if (context == null) return false;
            boolean enabled = Boolean.TRUE.equals(newValue);
            logPreferenceChange("trip_mode", enabled);
            TripModeController.setTripModeEnabled(context, enabled);
            PressureNotificationStateStore.clearTransientState(context);
            logEvent(enabled ? "TRIP_MODE_ON" : "TRIP_MODE_OFF", String.valueOf(System.currentTimeMillis()));
            return true;
        });
    }

    private void setupNavigationPreferences() {
        Preference calibrationPref = findPreference("pref_open_calibration");
        if (calibrationPref != null) {
            calibrationPref.setOnPreferenceClickListener(pref -> {
                Context context = getContext();
                if (context != null) {
                    AppEventLogger.log(context, "SETTINGS", "Open CalibrationWizardActivity");
                    startActivity(new Intent(context, CalibrationWizardActivity.class));
                }
                return true;
            });
        }

        Preference statusPref = findPreference("pref_open_status");
        if (statusPref != null) {
            statusPref.setOnPreferenceClickListener(pref -> {
                Context context = getContext();
                if (context != null) {
                    AppEventLogger.log(context, "SETTINGS", "Open StatusActivity");
                    startActivity(new Intent(context, StatusActivity.class));
                }
                return true;
            });
        }
    }

    private void updateCalibrationPreferenceSummary() {
        Context context = getContext();
        Preference calibrationPref = findPreference("pref_open_calibration");
        if (context == null || calibrationPref == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String rawValue = prefs.getString("pref_ref_altitude_m", "0");
        double altitudeMeters = ParseUtils.parseFlexibleDouble(rawValue, 0.0);
        String altitudeText = Units.formatAltitude(altitudeMeters, Units.getSystem(prefs));
        calibrationPref.setSummary(getString(R.string.base_altitude_fmt, altitudeText));
    }

    private void setupExportPreference() {
        Preference exportPref = findPreference("pref_export_csv");
        if (exportPref == null) return;
        exportPref.setOnPreferenceClickListener(p -> {
            Context context = getContext();
            if (context == null) return true;
            AppEventLogger.log(context, "SETTINGS", "Export CSV requested");
            Toast.makeText(context, getString(R.string.export_started), Toast.LENGTH_SHORT).show();
            String fname = "barometer_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new java.util.Date())
                    + ".csv";
            createCsvLauncher.launch(fname);
            return true;
        });
    }

    private void setupBatteryPreferences() {
        Preference batt = findPreference("pref_battery_whitelist");
        if (batt != null) {
            updateBatteryWhitelistSummary(batt);
            batt.setOnPreferenceClickListener(p -> {
                Context context = getContext();
                if (context == null) return true;
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    Toast.makeText(context, R.string.pref_battery_not_supported, Toast.LENGTH_SHORT).show();
                    return true;
                }
                logUiAction("Battery optimization action opened");
                if (BackgroundProtectionHelper.isIgnoringBatteryOptimizations(context)) {
                    openIgnoreBatteryList();
                } else {
                    requestIgnoreBatteryOptimizations();
                }
                return true;
            });
        }

        Preference bgHelp = findPreference("pref_background_help");
        if (bgHelp != null) {
            bgHelp.setOnPreferenceClickListener(preference -> {
                logUiAction("Open background protection help");
                showBackgroundProtectionDialog();
                return true;
            });
        }
    }



    private void setupStatusBarPreference() {
        SwitchPreferenceCompat pref = findPreference(ThemeController.PREF_HIDE_STATUS_BAR);
        if (pref == null) return;
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            Context context = getContext();
            if (context == null) return false;
            boolean hidden = Boolean.TRUE.equals(newValue);
            ThemeController.setStatusBarHidden(context, hidden);
            logPreferenceChange("hide_status_bar", hidden);
            if (getActivity() != null) {
                ThemeController.applySystemUi(getActivity());
            }
            return true;
        });
    }

    private void setupReportProblemPreference() {
        Preference reportPref = findPreference("pref_report_problem");
        if (reportPref == null) return;
        reportPref.setOnPreferenceClickListener(preference -> {
            Context context = getContext();
            if (context != null) {
                AppEventLogger.log(context, "SETTINGS", "Manual issue report requested");
            }
            if (getActivity() != null) {
                IssueReportManager.sendManualReport(getActivity());
            }
            return true;
        });
    }

    private void setupNotificationPreferences() {
        Preference modePref = findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_MODE);
        if (modePref != null) {
            modePref.setOnPreferenceChangeListener((preference, newValue) -> {
                Context context = getContext();
                if (context == null) return true;
                logPreferenceChange("notification_mode", newValue);
                PressureNotificationStateStore.clearTransientState(context);
                mainHandler.post(() -> {
                    updateNotificationsUi();
                });
                return true;
            });
        }

        Preference[] resettablePrefs = new Preference[] {
                findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_DROP_ENABLED),
                findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_RISE_ENABLED),
                findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_THRESHOLD),
                findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_WINDOW_HOURS),
                findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_SENSITIVITY),
                findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_MIN_INTERVAL_MIN)
        };
        for (Preference pref : resettablePrefs) {
            if (pref == null) continue;
            pref.setOnPreferenceChangeListener((preference, newValue) -> {
                Context context = getContext();
                if (context != null) {
                    logPreferenceChange(preference.getKey(), newValue);
                    PressureNotificationStateStore.clearTransientState(context);
                    mainHandler.post(this::updateNotificationsUi);
                }
                return true;
            });
        }

        Preference silentKind = findPreference(PressureNotificationPrefs.PREF_SILENT_MODE_KIND);
        if (silentKind != null) {
            silentKind.setOnPreferenceChangeListener((preference, newValue) -> {
                Context context = getContext();
                if (context == null) return true;
                logPreferenceChange("silent_mode_kind", newValue);
                PressureNotificationStateStore.clearTransientState(context);
                mainHandler.post(() -> {
                    updateSilentModeUi();
                });
                return true;
            });
        }

        SwitchPreferenceCompat silentManual = findPreference(PressureNotificationPrefs.PREF_SILENT_MODE_ENABLED);
        if (silentManual != null) {
            silentManual.setOnPreferenceChangeListener((preference, newValue) -> {
                Context context = getContext();
                if (context != null) {
                    logPreferenceChange("silent_mode_manual", newValue);
                    PressureNotificationStateStore.clearTransientState(context);
                }
                return true;
            });
        }

        Preference silentSchedule = findPreference("pref_silent_mode_schedule");
        if (silentSchedule != null) {
            silentSchedule.setOnPreferenceClickListener(preference -> {
                logUiAction("Open silent schedule dialog");
                showSilentScheduleDialog();
                return true;
            });
        }

        updateNotificationsUi();
        updateSilentModeUi();
    }

    private void updateNotificationsUi() {
        Context context = getContext();
        if (context == null) return;

        String mode = PressureNotificationPrefs.getNotificationMode(context);
        boolean simple = PressureNotificationPrefs.MODE_SIMPLE.equals(mode);
        boolean smart = PressureNotificationPrefs.MODE_SMART.equals(mode);
        boolean enabled = !PressureNotificationPrefs.MODE_OFF.equals(mode);

        Preference simpleDrop = findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_DROP_ENABLED);
        Preference simpleRise = findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_RISE_ENABLED);
        Preference simpleThreshold = findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_THRESHOLD);
        Preference simpleWindow = findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_SIMPLE_WINDOW_HOURS);
        Preference smartSensitivity = findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_SENSITIVITY);
        Preference minInterval = findPreference(PressureNotificationPrefs.PREF_NOTIFICATION_MIN_INTERVAL_MIN);
        Preference silentKind = findPreference(PressureNotificationPrefs.PREF_SILENT_MODE_KIND);
        Preference modeDescription = findPreference("pref_notification_mode_description");
        Preference sensitivityDescription = findPreference("pref_notification_sensitivity_description");

        if (modeDescription != null) {
            modeDescription.setVisible(enabled);
            if (smart) modeDescription.setSummary(R.string.pref_notifications_mode_description_smart);
            else if (simple) modeDescription.setSummary(R.string.pref_notifications_mode_description_simple);
            else modeDescription.setSummary(R.string.pref_notifications_mode_description_off);
        }

        if (simpleDrop != null) simpleDrop.setVisible(simple);
        if (simpleRise != null) simpleRise.setVisible(simple);
        if (simpleThreshold != null) simpleThreshold.setVisible(simple);
        if (simpleWindow != null) simpleWindow.setVisible(simple);
        if (smartSensitivity != null) smartSensitivity.setVisible(smart);
        if (sensitivityDescription != null) {
            sensitivityDescription.setVisible(smart);
            String sensitivity = PressureNotificationPrefs.getSensitivity(context).prefValue();
            if ("low".equals(sensitivity)) sensitivityDescription.setSummary(R.string.pref_notification_sensitivity_description_low);
            else if ("high".equals(sensitivity)) sensitivityDescription.setSummary(R.string.pref_notification_sensitivity_description_high);
            else sensitivityDescription.setSummary(R.string.pref_notification_sensitivity_description_medium);
        }
        if (minInterval != null) minInterval.setVisible(enabled);
        if (silentKind != null) silentKind.setVisible(enabled);

        updateSilentModeUi();
    }

    private void updateSilentModeUi() {
        Context context = getContext();
        if (context == null) return;

        boolean notificationsEnabled = PressureNotificationPrefs.areAnyNotificationsEnabled(context);
        String mode = PressureNotificationPrefs.getSilentModeKind(context);
        boolean manual = notificationsEnabled && "manual".equals(mode);
        boolean schedule = notificationsEnabled && "schedule".equals(mode);

        Preference manualPref = findPreference(PressureNotificationPrefs.PREF_SILENT_MODE_ENABLED);
        Preference schedulePref = findPreference("pref_silent_mode_schedule");

        if (manualPref != null) {
            manualPref.setVisible(manual);
        }
        if (schedulePref != null) {
            schedulePref.setVisible(schedule);
            schedulePref.setSummary(getString(
                    R.string.pref_silent_mode_schedule_summary_fmt,
                    PressureNotificationPrefs.formatTime(
                            context,
                            PressureNotificationPrefs.getSilentModeStartMinutes(context)
                    ),
                    PressureNotificationPrefs.formatTime(
                            context,
                            PressureNotificationPrefs.getSilentModeEndMinutes(context)
                    )
            ));
        }
    }

    private void pickSilentModeTime(boolean isStart) {
        Context context = getContext();
        if (context == null) return;
        int totalMinutes = isStart
                ? PressureNotificationPrefs.getSilentModeStartMinutes(context)
                : PressureNotificationPrefs.getSilentModeEndMinutes(context);
        pickSilentModeTime(isStart, totalMinutes, selectedTotal -> {
            PressureNotificationPrefs.setSilentModeTimeMinutes(context, isStart, selectedTotal);
            PressureNotificationStateStore.clearTransientState(context);
            updateSilentModeUi();
        });
    }

    private interface TimePickedCallback {
        void onTimePicked(int totalMinutes);
    }

    private void pickSilentModeTime(boolean isStart, int initialMinutes, TimePickedCallback callback) {
        Context context = getContext();
        if (context == null) return;
        int hour = initialMinutes / 60;
        int minute = initialMinutes % 60;

        TimePickerDialog dialog = new TimePickerDialog(
                context,
                (view, selectedHour, selectedMinute) -> {
                    int selectedTotal = selectedHour * 60 + selectedMinute;
                    if (callback != null) callback.onTimePicked(selectedTotal);
                },
                hour,
                minute,
                android.text.format.DateFormat.is24HourFormat(context)
        );
        dialog.setTitle(isStart ? R.string.pref_time_picker_title_start : R.string.pref_time_picker_title_end);
        dialog.show();
    }

    private void showSilentScheduleDialog() {
        Context context = getContext();
        if (context == null) return;

        final int[] startMinutes = {PressureNotificationPrefs.getSilentModeStartMinutes(context)};
        final int[] endMinutes = {PressureNotificationPrefs.getSilentModeEndMinutes(context)};

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(12), pad, dp(4));

        TextView startLabel = new TextView(context);
        startLabel.setText(R.string.pref_silent_mode_schedule_pick_start);
        root.addView(startLabel);

        MaterialButton startButton = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        startButton.setText(PressureNotificationPrefs.formatTime(context, startMinutes[0]));
        root.addView(startButton);

        TextView endLabel = new TextView(context);
        endLabel.setText(R.string.pref_silent_mode_schedule_pick_end);
        endLabel.setPadding(0, dp(12), 0, 0);
        root.addView(endLabel);

        MaterialButton endButton = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        endButton.setText(PressureNotificationPrefs.formatTime(context, endMinutes[0]));
        root.addView(endButton);

        startButton.setOnClickListener(v -> pickSilentModeTime(true, startMinutes[0], selected -> {
            startMinutes[0] = selected;
            startButton.setText(PressureNotificationPrefs.formatTime(context, selected));
        }));

        endButton.setOnClickListener(v -> pickSilentModeTime(false, endMinutes[0], selected -> {
            endMinutes[0] = selected;
            endButton.setText(PressureNotificationPrefs.formatTime(context, selected));
        }));

        new AlertDialog.Builder(context)
                .setTitle(R.string.pref_silent_mode_schedule)
                .setView(root)
                .setNegativeButton(R.string.pref_dialog_cancel, null)
                .setPositiveButton(R.string.pref_dialog_save, (dialog, which) -> {
                    PressureNotificationPrefs.setSilentModeTimeMinutes(context, true, startMinutes[0]);
                    PressureNotificationPrefs.setSilentModeTimeMinutes(context, false, endMinutes[0]);
                    PressureNotificationStateStore.clearTransientState(context);
                    updateSilentModeUi();
                })
                .show();
    }

    private void requestSingleGpsAltitude() {
        Context context = getContext();
        if (context == null) return;

        Context appContext = context.getApplicationContext();
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            showToast(R.string.perm_denied_location, Toast.LENGTH_SHORT);
            return;
        }

        try {
            CancellationTokenSource cts = new CancellationTokenSource();
            mainHandler.postDelayed(cts::cancel, 15_000);

            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null && loc.hasAltitude()) {
                            double altMeters = loc.getAltitude();
                            PreferenceManager.getDefaultSharedPreferences(appContext)
                                    .edit()
                                    .putString("pref_ref_altitude_m", String.valueOf(altMeters))
                                    .apply();

                            logEvent("GPS_BASELINE", String.valueOf(altMeters));
                            showToast(R.string.base_altitude_set_ok, Toast.LENGTH_SHORT);
                        } else {
                            showToast(R.string.gps_no_fix, Toast.LENGTH_SHORT);
                        }
                    })
                    .addOnFailureListener(e -> showToast(R.string.gps_error, Toast.LENGTH_SHORT));
        } catch (SecurityException se) {
            showToast(R.string.perm_denied_location, Toast.LENGTH_SHORT);
        }
    }

    private void exportCsvToUri(Uri uri) {
        Context appContext = getAppContextOrNull();
        if (appContext == null) return;

        io.execute(() -> {
            try {
                PressureDao dao = AppDatabase.get(appContext).pressureDao();
                int keep = Integer.parseInt(
                        PreferenceManager.getDefaultSharedPreferences(appContext)
                                .getString("pref_history_size", "10000")
                );

                List<PressureSample> data = dao.latest(keep);
                if (data.isEmpty()) {
                    showToast(R.string.export_empty, Toast.LENGTH_SHORT);
                    return;
                }
                Collections.reverse(data);

                OutputStream os = appContext.getContentResolver().openOutputStream(uri, "w");
                if (os == null) {
                    throw new IllegalStateException("Failed to open output stream for CSV export");
                }

                try (OutputStream safeOs = os;
                     BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(safeOs))) {

                    bw.write("epoch_ms,local_time,hPa,mmHg,inHg,altitude_m");
                    bw.newLine();

                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    sdf.setTimeZone(TimeZone.getDefault());

                    final double HPA_TO_MMHG = 0.750061683;
                    final double HPA_TO_INHG = 0.0295299830714;

                    for (PressureSample s : data) {
                        long t = s.timestamp;
                        double hpa = s.pressureHpa;
                        String time = sdf.format(new java.util.Date(t));
                        double mmhg = hpa * HPA_TO_MMHG;
                        double inhg = hpa * HPA_TO_INHG;
                        double alt = Units.altitudeFromPressure(hpa);

                        bw.write(String.format(Locale.US,
                                "%d,%s,%.2f,%.2f,%.3f,%.2f",
                                t, time, hpa, mmhg, inhg, alt));
                        bw.newLine();
                    }
                    bw.flush();
                }

                showToast(R.string.export_done, Toast.LENGTH_SHORT);
            } catch (Exception e) {
                showToast(R.string.export_failed, Toast.LENGTH_LONG);
            }
        });
    }

    private void updateBatteryWhitelistSummary(Preference p) {
        Context context = getContext();
        if (context == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            p.setSummary(getString(R.string.pref_battery_not_supported));
            p.setEnabled(false);
            return;
        }
        boolean on = BackgroundProtectionHelper.isIgnoringBatteryOptimizations(context);
        p.setSummary(getString(on ? R.string.pref_battery_summary_on : R.string.pref_battery_summary_off));
    }

    private void showBackgroundProtectionDialog() {
        Context context = getContext();
        if (context == null) return;

        new AlertDialog.Builder(context)
                .setTitle(R.string.pref_background_help_title)
                .setMessage(BackgroundProtectionHelper.buildGuidanceText(context))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.pref_background_help_battery, (dialog, which) -> {
                    try {
                        BackgroundProtectionHelper.openBatteryOptimizationScreen(context);
                    } catch (Exception e) {
                        Toast.makeText(context, R.string.pref_battery_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton(R.string.pref_background_help_autostart, (dialog, which) -> {
                    boolean opened = BackgroundProtectionHelper.openVendorAutostartSettings(context);
                    if (!opened) {
                        try {
                            BackgroundProtectionHelper.openAppDetails(context);
                        } catch (Exception e) {
                            Toast.makeText(context, R.string.pref_battery_failed, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void requestIgnoreBatteryOptimizations() {
        Context context = getContext();
        if (context == null) return;
        try {
            BackgroundProtectionHelper.openBatteryOptimizationScreen(context);
        } catch (ActivityNotFoundException e) {
            openIgnoreBatteryList();
        } catch (Exception e) {
            Toast.makeText(context, R.string.pref_battery_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openIgnoreBatteryList() {
        Context context = getContext();
        if (context == null) return;
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(context, R.string.pref_battery_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void clearDatabase() {
        Context appContext = getAppContextOrNull();
        if (appContext == null) return;

        io.execute(() -> {
            try {
                PressureDao dao = AppDatabase.get(appContext).pressureDao();
                dao.deleteAll();

                try {
                    EventDao ed = AppDatabase.get(appContext).eventDao();
                    ed.deleteAll();
                } catch (Throwable ignored) {
                }

                showToastText("База очищена", Toast.LENGTH_SHORT);
            } catch (Throwable e) {
                showToastText("Не удалось очистить базу", Toast.LENGTH_SHORT);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        Context context = getContext();
        if (context != null) {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .registerOnSharedPreferenceChangeListener(prefListener);
        }
        Preference batt = findPreference("pref_battery_whitelist");
        if (batt != null) {
            updateBatteryWhitelistSummary(batt);
        }
        updateCalibrationPreferenceSummary();
        updateHistoryPreferenceSummary();
        updateIntervalPreferenceSummary();
        updateRecordingUi();
        updateNotificationsUi();
        updateSilentModeUi();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
