package com.example.beautiful_barometer.ui;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.example.beautiful_barometer.R;
import com.example.beautiful_barometer.util.ThemeController;
import com.example.beautiful_barometer.feedback.AppEventLogger;
import com.example.beautiful_barometer.data.AppDatabase;
import com.example.beautiful_barometer.data.EventDao;
import com.example.beautiful_barometer.data.EventSample;
import com.example.beautiful_barometer.data.PressureDao;
import com.example.beautiful_barometer.data.PressureSample;
import com.example.beautiful_barometer.util.ParseUtils;
import com.example.beautiful_barometer.util.Units;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CalibrationWizardActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private FusedLocationProviderClient fusedClient;
    private TextView tvBaseline;
    private TextView tvLastMeasured;
    private TextView tvTip;

    private final ActivityResultLauncher<String> fineLocationPerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    setBaseFromGps();
                } else {
                    Toast.makeText(this, R.string.perm_denied_location, Toast.LENGTH_SHORT).show();
                }
            });

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
        setContentView(R.layout.activity_calibration_wizard);
        setTitle(R.string.calibration_title);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        fusedClient = LocationServices.getFusedLocationProviderClient(this);

        tvBaseline = findViewById(R.id.tvCalibrationBaseline);
        tvLastMeasured = findViewById(R.id.tvCalibrationLastMeasured);
        tvTip = findViewById(R.id.tvCalibrationTip);

        findViewById(R.id.btnCalibrationBarometer).setOnClickListener(v -> setBaseFromBarometer());
        findViewById(R.id.btnCalibrationGps).setOnClickListener(v -> requestGpsFlow());
        findViewById(R.id.btnCalibrationManual).setOnClickListener(v -> showManualInputDialog());
        findViewById(R.id.btnCalibrationReset).setOnClickListener(v -> applyBaseline(0.0, "CALIBRATION", true));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void refreshUi() {
        Units.System sys = Units.getSystem(prefs);
        double baseline = ParseUtils.parseFlexibleDouble(prefs.getString("pref_ref_altitude_m", "0"), 0.0);
        tvBaseline.setText(getString(R.string.calibration_baseline_fmt, Units.formatAltitude(baseline, sys)));
        tvTip.setText(R.string.calibration_tip);

        io.execute(() -> {
            PressureDao dao = AppDatabase.get(this).pressureDao();
            List<PressureSample> latest = dao.latest(1);
            String text = getString(R.string.calibration_last_measured_none);
            if (!latest.isEmpty()) {
                PressureSample sample = latest.get(0);
                double altitude = Units.altitudeFromPressure(sample.pressureHpa);
                text = getString(R.string.calibration_last_measured_fmt,
                        Units.formatPressure(sample.pressureHpa, sys),
                        Units.formatAltitude(altitude, sys));
            }
            String finalText = text;
            runOnUiThreadIfAlive(() -> tvLastMeasured.setText(finalText));
        });
    }

    private void setBaseFromBarometer() {
        io.execute(() -> {
            PressureDao dao = AppDatabase.get(this).pressureDao();
            List<PressureSample> latest = dao.latest(1);
            if (latest.isEmpty()) {
                showToast(R.string.no_sensor_data);
                return;
            }
            double altitude = Units.altitudeFromPressure(latest.get(0).pressureHpa);
            applyBaseline(altitude, "CALIBRATION", false);
        });
    }

    private void requestGpsFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            setBaseFromGps();
        } else {
            fineLocationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    private void setBaseFromGps() {
        try {
            CancellationTokenSource cts = new CancellationTokenSource();
            mainHandler.postDelayed(cts::cancel, 15_000);
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null && loc.hasAltitude()) {
                            applyBaseline(loc.getAltitude(), "GPS_BASELINE", false);
                        } else {
                            showToast(R.string.gps_no_fix);
                        }
                    })
                    .addOnFailureListener(e -> showToast(R.string.gps_error));
        } catch (SecurityException e) {
            showToast(R.string.perm_denied_location);
        }
    }

    private void showManualInputDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setHint(getString(R.string.calibration_manual_hint));
        input.setText(prefs.getString("pref_ref_altitude_m", "0"));

        new AlertDialog.Builder(this)
                .setTitle(R.string.calibration_manual_title)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    double altitude = ParseUtils.parseFlexibleDouble(input.getText().toString(), Double.NaN);
                    if (Double.isNaN(altitude)) {
                        showToastText(getString(R.string.calibration_manual_invalid));
                        return;
                    }
                    applyBaseline(altitude, "CALIBRATION", false);
                })
                .show();
    }

    private void applyBaseline(double altitudeMeters, String eventType, boolean zeroReset) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("pref_ref_altitude_m", String.format(Locale.US, "%s", altitudeMeters));
        editor.apply();
        logEvent(eventType, String.valueOf(altitudeMeters));
        runOnUiThreadIfAlive(() -> {
            refreshUi();
            Toast.makeText(this,
                    zeroReset ? R.string.calibration_reset_done : R.string.base_altitude_set_ok,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void logEvent(String type, String value) {
        Context appContext = getApplicationContext();
        io.execute(() -> {
            try {
                EventDao eventDao = AppDatabase.get(appContext).eventDao();
                eventDao.insert(new EventSample(type, System.currentTimeMillis(), value));
            } catch (Exception e) {
                AppEventLogger.log(appContext, "CALIBRATION", "event log failed: " + e.getClass().getSimpleName());
            }
        });
    }

    private void showToast(int resId) {
        runOnUiThreadIfAlive(() -> Toast.makeText(this, resId, Toast.LENGTH_SHORT).show());
    }

    private void showToastText(String text) {
        runOnUiThreadIfAlive(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
