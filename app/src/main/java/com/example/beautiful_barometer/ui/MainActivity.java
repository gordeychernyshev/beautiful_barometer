// app/src/main/java/com/example/beautiful_barometer/ui/MainActivity.java
package com.example.beautiful_barometer.ui;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.example.beautiful_barometer.R;
import com.example.beautiful_barometer.data.AppDatabase;
import com.example.beautiful_barometer.data.PressureDao;
import com.example.beautiful_barometer.data.PressureSample;
import com.example.beautiful_barometer.databinding.ActivityMainBinding;
import com.example.beautiful_barometer.feedback.AppEventLogger;
import com.example.beautiful_barometer.feedback.IssueReportManager;
import com.example.beautiful_barometer.service.SensorService;
import com.example.beautiful_barometer.ui.forecast.ForecastEngine;
import com.example.beautiful_barometer.ui.onboarding.OnboardingPrefs;
import com.example.beautiful_barometer.ui.forecast.ForecastUiModel;
import com.example.beautiful_barometer.util.ParseUtils;
import com.example.beautiful_barometer.util.DeviceCapabilities;
import com.example.beautiful_barometer.util.ServiceController;
import com.example.beautiful_barometer.util.ThemeController;
import com.example.beautiful_barometer.util.TripModeController;
import com.example.beautiful_barometer.util.Units;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.color.MaterialColors;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long lastRangeUpdateMs = 0L;
    private long lastTrendsUpdateMs = 0L;
    private static final long TRENDS_THROTTLE_MS = 10_000L;

    private static final String PREF_NO_BAROMETER_DIALOG_SHOWN =
            "pref_no_barometer_dialog_shown";

    private long lastForecastUpdateMs = 0L;
    private static final long FORECAST_THROTTLE_MS = 15_000L;
    private ValueAnimator forecastPulseAnimator;

    private ActivityMainBinding vb;
    private boolean receiverRegistered = false;
    private SharedPreferences prefs;
    private ForecastUiModel currentForecastModel;
    private String appliedThemeMode;

    private boolean isUiAlive() {
        return !isFinishing() && !isDestroyed() && vb != null;
    }

    private void runOnUiThreadIfAlive(Runnable action) {
        runOnUiThread(() -> {
            if (isUiAlive()) {
                action.run();
            }
        });
    }

    // Пороги и геометрия наклона тренд-стрелок
    private static final double T_FLAT = 0.10; // до 0.10 hPa считаем «ровно», угол 0°
    private static final double T_MAX  = 1.60; // >= 1.60 hPa — максимальный наклон
    private static final float  MAX_DEG = 60f; // максимальный угол наклона

    // === хелпер для шкалы (анти-одинаковые подписи) ===
    private float minSpanForUniqueTicks(Units.System sys) {
        final float HPA_PER_MMHG = 1f / 0.7500617f;    // ≈ 1.33322
        final float HPA_PER_INHG = 1f / 0.0295299831f; // ≈ 33.8639

        switch (sys) {
            case MMHG:
                // чтобы соседние подписи в mmHg (целые) отличались минимум на 1
                return 10f * HPA_PER_MMHG; // ≈ 13.3322 hPa
            case IMPERIAL:
                // в inHg с 2 знаками: хотим хотя бы 0.01 inHg на шаг
                return 10f * (0.01f * HPA_PER_INHG); // ≈ 3.3864 hPa
            case METRIC:
            default:
                // hPa целые → минимум 1 hPa на шаг
                return 10f; // hPa
        }
    }

    // === auto-scale диапазона шкалы ===
    private void autoScaleGaugeRangeAsync() {
        final long now = System.currentTimeMillis();
        if (now - lastRangeUpdateMs < 2000) return; // троттлинг 2с
        lastRangeUpdateMs = now;

        io.execute(() -> {
            PressureDao dao = AppDatabase.get(this).pressureDao();
            List<PressureSample> last = dao.latest(100);
            if (last.isEmpty()) return;

            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (PressureSample s : last) {
                double p = s.pressureHpa;
                if (p < min) min = p;
                if (p > max) max = p;
            }

            // базовый «подушечный» диапазон
            float span = (float) Math.max(0.0001, max - min);
            float padding = Math.max(1.5f, span * 0.10f); // ≥1.5 hPa или 10%
            float newMin = (float) min - padding;
            float newMax = (float) max + padding;

            // НЕ позволяем сжать диапазон ниже минимума,
            // при котором соседние риски имеют разные подписи в текущих единицах
            Units.System sys = Units.getSystem(prefs);
            float needSpan = minSpanForUniqueTicks(sys);
            float haveSpan = newMax - newMin;

            if (haveSpan < needSpan) {
                float center = (float) ((max + min) * 0.5);
                float half   = needSpan * 0.5f;
                newMin = center - half;
                newMax = center + half;
            }

            final float fMin = newMin;
            final float fMax = newMax;
            runOnUiThreadIfAlive(() -> vb.gauge.setRange(fMin, fMax));
        });
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (SensorService.ACTION_SAMPLE_BROADCAST.equals(intent.getAction())) {
                double hpa = intent.getDoubleExtra(SensorService.EXTRA_HPA, Double.NaN);
                if (!Double.isNaN(hpa)) updateUi(hpa);
            }
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> {
                if ("pref_units".equals(key) || "pref_ref_altitude_m".equals(key)) {
                    vb.gauge.setUnits(Units.getSystem(sp));
                }
                if (TripModeController.PREF_TRIP_MODE_ENABLED.equals(key)
                        || TripModeController.PREF_FORECAST_BASELINE_TS.equals(key)) {
                    lastForecastUpdateMs = 0L;
                    updateForecastAsync();
                }
            };

    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> { /* можно показать подсказку, если нужно */ });

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeController.applyToActivity(this);
        super.onCreate(savedInstanceState);
        if (OnboardingPrefs.shouldShow(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        vb = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(vb.getRoot());

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        appliedThemeMode = ThemeController.getThemeMode(this);
        vb.gauge.setUnits(Units.getSystem(prefs));

        vb.btnGraph.setOnClickListener(v -> {
            AppEventLogger.log(this, "UI", "Open GraphActivity");
            startActivity(new Intent(this, GraphActivity.class));
        });
        vb.btnSettings.setOnClickListener(v -> {
            AppEventLogger.log(this, "UI", "Open SettingsActivity");
            startActivity(new Intent(this, SettingsActivity.class));
        });

        vb.cardForecast.setOnClickListener(v -> showForecastExplain());
        applyForecastTripModeStateIfNeeded();


        if (DeviceCapabilities.hasBarometer(this)) {
            if (ServiceController.isRecordingEnabled(this) && !ServiceController.isServiceRunning(this)) {
                ensureNotificationPermission();
                ServiceController.startRecording(this);
            }
        } else {
            ServiceController.setRecordingEnabled(this, false);
            ServiceController.setServiceRunning(this, false);
            ServiceController.setAdaptiveMode(this, "stopped");
            AppEventLogger.log(this, "UI", "No barometer sensor on device");
        }

        IssueReportManager.maybePromptToSendPendingCrash(this);
    }


    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void maybeShowNoBarometerDialog() {
        if (prefs == null || DeviceCapabilities.hasBarometer(this)) {
            return;
        }
        if (prefs.getBoolean(PREF_NO_BAROMETER_DIALOG_SHOWN, false)) {
            return;
        }
        if (isFinishing() || isDestroyed()) {
            return;
        }

        prefs.edit().putBoolean(PREF_NO_BAROMETER_DIALOG_SHOWN, true).apply();

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.no_barometer_dialog_title)
                .setMessage(R.string.no_barometer_dialog_message)
                .setPositiveButton(R.string.got_it, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void updateUi(double hpa) {
        Units.System sys = Units.getSystem(prefs);

        vb.textPressure.setText(Units.formatPressure(hpa, sys));
        vb.gauge.setUnits(sys);
        vb.gauge.setPressure(hpa);

        double currentAlt = Units.altitudeFromPressure(hpa);
        double baseAlt = getRefAltitudeMeters();
        double delta = currentAlt - baseAlt;

        vb.textAltitudeCurrent.setText(formatSeaLevelAltitude(currentAlt, sys));
        vb.textAltitudeDelta.setText(getString(R.string.altitude_delta_card_fmt, formatSignedAltitude(delta, sys)));

        String baseStr = Units.formatAltitude(baseAlt, sys);
        vb.textAltitudeBase.setText(getString(R.string.base_altitude_fmt, baseStr));

        updateTrendsAsync();
        autoScaleGaugeRangeAsync();
        updateForecastAsync();
    }

    private double getRefAltitudeMeters() {
        String s = prefs.getString("pref_ref_altitude_m", "0");
        return ParseUtils.parseFlexibleDouble(s, 0.0);
    }
    private String formatSeaLevelAltitude(double meters, Units.System sys) {
        String value = formatUnsignedAltitude(meters, sys);
        if (meters < 0) {
            return getString(R.string.altitude_below_sea_fmt, value);
        }
        return getString(R.string.altitude_above_sea_fmt, value);
    }

    private String formatUnsignedAltitude(double meters, Units.System sys) {
        double abs = Math.abs(meters);
        if (sys == Units.System.IMPERIAL) {
            return String.format(java.util.Locale.getDefault(), "%.0f ft", abs * 3.28084);
        }
        return String.format(java.util.Locale.getDefault(), "%.0f м", abs);
    }

    private void resetUiForNoData() {
        Units.System sys = Units.getSystem(prefs);
        vb.gauge.setUnits(sys);
        vb.gauge.setRange(950f, 1050f);
        vb.gauge.setPressure(1013.25f);

        vb.textPressure.setText("—");
        vb.textAltitudeCurrent.setText("—");
        vb.textAltitudeDelta.setText("—");

        String baseStr = Units.formatAltitude(getRefAltitudeMeters(), sys);
        vb.textAltitudeBase.setText(getString(R.string.base_altitude_fmt, baseStr));

        vb.ivTrend3Arrow.setImageResource(R.drawable.ic_trend_arrow);
        vb.ivTrend24Arrow.setImageResource(R.drawable.ic_trend_arrow);
        vb.ivTrend3Arrow.setRotation(90f);
        vb.ivTrend24Arrow.setRotation(90f);
        int neutral = MaterialColors.getColor(vb.getRoot(), com.google.android.material.R.attr.colorOutlineVariant);
        vb.ivTrend3Arrow.setImageTintList(ColorStateList.valueOf(neutral));
        vb.ivTrend24Arrow.setImageTintList(ColorStateList.valueOf(neutral));
        vb.tvTrend3Delta.setText("—");
        vb.tvTrend24Delta.setText("—");

        stopForecastPulse();
        currentForecastModel = null;
        vb.tvForecastIcon.setText("🌥️");
        vb.tvForecastTitle.setText(getString(R.string.forecast_not_enough_data_title));
        vb.tvForecastSubtitle.setText(getString(R.string.forecast_not_enough_data_desc));
        vb.tvForecastDesc.setText(getString(R.string.forecast_not_enough_data_summary));
        vb.forecastSparkline.setVisibility(View.GONE);
        vb.forecastChipsRow.setVisibility(View.GONE);
        vb.forecastConfidenceRow.setVisibility(View.GONE);
        vb.tvForecastReasons.setVisibility(View.GONE);
        vb.tvForecastCompare.setText(getString(R.string.forecast_compare_unknown));
        vb.tvForecastAdvice.setVisibility(View.GONE);

        lastRangeUpdateMs = 0L;
        lastTrendsUpdateMs = 0L;
        lastForecastUpdateMs = 0L;
    }

    private String formatSignedAltitude(double meters, Units.System sys) {
        double value = meters;
        if (sys == Units.System.IMPERIAL) {
            value = meters * 3.28084;
            return String.format(java.util.Locale.getDefault(), "%+.0f ft", value);
        }
        return String.format(java.util.Locale.getDefault(), "%+.0f м", value);
    }


    @Override protected void onResume() {
        super.onResume();

        String currentThemeMode = ThemeController.getThemeMode(this);
        if (appliedThemeMode != null && !appliedThemeMode.equals(currentThemeMode)) {
            appliedThemeMode = currentThemeMode;
            recreate();
            return;
        }

        IntentFilter filter = new IntentFilter(SensorService.ACTION_SAMPLE_BROADCAST);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;

        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        applyForecastTripModeStateIfNeeded();

        if (!DeviceCapabilities.hasBarometer(this)) {
            resetUiForNoData();
            vb.textPressure.setText(getString(R.string.no_barometer_sensor));
            vb.textAltitudeCurrent.setText("—");
            vb.textAltitudeDelta.setText("—");
            vb.tvForecastTitle.setText(getString(R.string.no_barometer_sensor));
            vb.tvForecastSubtitle.setText(getString(R.string.forecast_not_enough_data_desc));
            vb.tvForecastDesc.setText(getString(R.string.forecast_not_enough_data_summary));
            maybeShowNoBarometerDialog();
            return;
        }

        io.execute(() -> {
            PressureDao dao = AppDatabase.get(this).pressureDao();
            List<PressureSample> last = dao.latest(1);
            if (!last.isEmpty()) {
                double hpa = last.get(0).pressureHpa;
                runOnUiThreadIfAlive(() -> updateUi(hpa));
            } else {
                runOnUiThreadIfAlive(this::resetUiForNoData);
            }
        });
    }

    @Override protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
    }

    @Override protected void onDestroy() {
        stopForecastPulse();
        super.onDestroy();
        io.shutdown();
    }

    // === ПРОГНОЗ (вау-плашка) ===

    private void updateForecastAsync() {
        final long now = System.currentTimeMillis();
        if (TripModeController.isTripModeEnabled(this)) {
            applyTripModeForecastUi();
            return;
        }
        if (now - lastForecastUpdateMs < FORECAST_THROTTLE_MS) return;
        lastForecastUpdateMs = now;

        final long baselineMs = TripModeController.getForecastBaselineTimestamp(this);
        final PressureDao dao = AppDatabase.get(this).pressureDao();
        final Units.System sys = Units.getSystem(prefs);

        io.execute(() -> {
            ForecastUiModel model = ForecastEngine.build(dao, now, baselineMs, sys);
            runOnUiThreadIfAlive(() -> applyForecast(model));
        });
    }

    private void applyForecast(ForecastUiModel m) {
        currentForecastModel = m;
        vb.tvForecastIcon.setText(m.icon);
        vb.tvForecastTitle.setText(m.title);
        vb.tvForecastSubtitle.setText(m.subtitle);
        vb.tvForecastDesc.setText(m.description);

        if (m.showSparkline) {
            vb.forecastSparkline.setVisibility(View.VISIBLE);
            vb.forecastSparkline.setSeries(m.sparklineSeries);
        } else {
            vb.forecastSparkline.setVisibility(View.GONE);
        }

        if (m.showChips) {
            vb.forecastChipsRow.setVisibility(View.VISIBLE);
            vb.chipDelta1h.setText(m.chipDelta1h);
            vb.chipDelta3h.setText(m.chipDelta3h);
            vb.chipQuality.setText(m.chipQuality);
            vb.chipNoise.setText(m.chipNoise);
        } else {
            vb.forecastChipsRow.setVisibility(View.GONE);
        }

        if (m.showConfidence) {
            vb.forecastConfidenceRow.setVisibility(View.VISIBLE);
            vb.tvForecastConfidenceTitle.setText(
                    getString(R.string.forecast_confidence_fmt, m.confidenceLabel)
            );
            vb.progressForecastConfidence.setProgressCompat(m.confidencePercent, true);
            vb.tvForecastConfidenceHint.setText(m.confidenceDetail);
        } else {
            vb.forecastConfidenceRow.setVisibility(View.GONE);
        }

        if (m.showReasons) {
            vb.tvForecastReasons.setVisibility(View.VISIBLE);
            vb.tvForecastReasons.setText(m.reasonsSummary);
        } else {
            vb.tvForecastReasons.setVisibility(View.GONE);
        }

        vb.tvForecastCompare.setText(m.compareYesterday);

        if (m.advice != null && !m.advice.isEmpty()) {
            vb.tvForecastAdvice.setVisibility(View.VISIBLE);
            vb.tvForecastAdvice.setText(m.advice);
        } else {
            vb.tvForecastAdvice.setVisibility(View.GONE);
        }

        if (m.pulse) startForecastPulse();
        else stopForecastPulse();
    }

    private void applyForecastTripModeStateIfNeeded() {
        if (TripModeController.isTripModeEnabled(this)) {
            applyTripModeForecastUi();
        }
    }

    private void applyTripModeForecastUi() {
        stopForecastPulse();
        ForecastUiModel model = new ForecastUiModel();
        model.state = com.example.beautiful_barometer.ui.forecast.ForecastState.TRIP_MODE;
        model.icon = "🚗";
        model.title = getString(R.string.forecast_trip_mode_title);
        model.subtitle = getString(R.string.forecast_trip_mode_desc);
        model.description = getString(R.string.forecast_trip_mode_summary);
        model.showSparkline = false;
        model.showChips = false;
        model.showReasons = false;
        model.showConfidence = false;
        model.compareYesterday = getString(R.string.forecast_compare_unknown);
        model.advice = "";
        model.explainTitle = getString(R.string.forecast_trip_mode_title);
        model.explainBody = getString(R.string.forecast_trip_mode_desc);
        currentForecastModel = model;

        vb.tvForecastIcon.setText(model.icon);
        vb.tvForecastTitle.setText(model.title);
        vb.tvForecastSubtitle.setText(model.subtitle);
        vb.tvForecastDesc.setText(model.description);
        vb.forecastSparkline.setVisibility(View.GONE);
        vb.forecastChipsRow.setVisibility(View.GONE);
        vb.forecastConfidenceRow.setVisibility(View.GONE);
        vb.tvForecastReasons.setVisibility(View.GONE);
        vb.tvForecastCompare.setText(model.compareYesterday);
        vb.tvForecastAdvice.setVisibility(View.GONE);
    }

    private void startForecastPulse() {
        if (forecastPulseAnimator != null && forecastPulseAnimator.isRunning()) return;

        forecastPulseAnimator = ValueAnimator.ofFloat(1f, 0.93f);
        forecastPulseAnimator.setDuration(900);
        forecastPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        forecastPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        forecastPulseAnimator.addUpdateListener(a ->
                vb.cardForecast.setAlpha((float) a.getAnimatedValue())
        );
        forecastPulseAnimator.start();
    }

    private void stopForecastPulse() {
        if (forecastPulseAnimator != null) {
            try { forecastPulseAnimator.cancel(); } catch (Exception ignored) {}
            forecastPulseAnimator = null;
        }
        if (vb != null && vb.cardForecast != null) {
            vb.cardForecast.setAlpha(1f);
        }
    }

    private void showForecastExplain() {
        AppEventLogger.log(this, "UI", "Open forecast explain sheet");
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.bottomsheet_forecast_explain, null, false);

        TextView title = v.findViewById(R.id.tvExplainTitle);
        TextView body  = v.findViewById(R.id.tvExplainBody);

        ForecastUiModel model = currentForecastModel;
        if (model != null) {
            if (title != null) title.setText(model.title);
            if (body != null) {
                String detail = model.explainBody;
                if (detail == null || detail.isEmpty()) {
                    detail = getString(R.string.forecast_explain_body);
                }
                body.setText(detail);
            }
        } else {
            if (title != null) title.setText(getString(R.string.forecast_explain_title));
            if (body != null) body.setText(getString(R.string.forecast_explain_body));
        }

        dialog.setContentView(v);
        dialog.show();
    }

    // === ТРЕНДЫ ===

    private void updateTrendsAsync() {
        final long now = System.currentTimeMillis();
        if (now - lastTrendsUpdateMs < TRENDS_THROTTLE_MS) return;
        lastTrendsUpdateMs = now;

        final Units.System sys = Units.getSystem(prefs);
        final PressureDao dao = AppDatabase.get(this).pressureDao();

        io.execute(() -> {
            // 3 часа: дельта = последняя - первая в окне
            long w3  = 3L * 3600_000L;
            long from3 = now - w3;
            Double f3 = dao.firstHpaIn(from3, now);
            Double l3 = dao.lastHpaIn(from3, now);
            Double d3 = (f3 == null || l3 == null) ? null : (l3 - f3);

            // 24 часа
            long w24 = 24L * 3600_000L;
            long from24 = now - w24;
            Double f24 = dao.firstHpaIn(from24, now);
            Double l24 = dao.lastHpaIn(from24, now);
            Double d24 = (f24 == null || l24 == null) ? null : (l24 - f24);

            runOnUiThreadIfAlive(() -> {
                applyTrendToViews(d3 == null ? Double.NaN : d3, sys, true);
                applyTrendToViews(d24 == null ? Double.NaN : d24, sys, false);
            });
        });
    }

    // drawable стрелки изначально смотрит ВВЕРХ.
    // Базовое "ровно" для карточек тенденции — по оси X, то есть вправо (90°).
    // Рост: отклоняем вверх-вправо (меньше 90°).
    // Падение: отклоняем вниз-вправо (больше 90°).
    private float iconRotationFor(double deltaHpa) {
        final float baseRight = 90f;
        if (Double.isNaN(deltaHpa) || Math.abs(deltaHpa) <= T_FLAT) return baseRight;
        final float maxTilt = 72f;
        final double fullScaleHpa = 2.0d;
        float tilt = (float) Math.min(maxTilt, (Math.abs(deltaHpa) / fullScaleHpa) * maxTilt);
        return deltaHpa > 0 ? (baseRight - tilt) : (baseRight + tilt);
    }

    // подбираем цвет стрелки
    private int tintFor(double deltaHpa) {
        final int primary = MaterialColors.getColor(vb.getRoot(), com.google.android.material.R.attr.colorPrimary);
        final int error   = MaterialColors.getColor(vb.getRoot(), com.google.android.material.R.attr.colorError);
        final int neutral = MaterialColors.getColor(vb.getRoot(), com.google.android.material.R.attr.colorOutlineVariant);
        if (Double.isNaN(deltaHpa) || Math.abs(deltaHpa) <= T_FLAT) return neutral;
        return deltaHpa > 0 ? primary : error;
    }

    private String formatDeltaUnits(double deltaHpa, Units.System sys) {
        if (Double.isNaN(deltaHpa)) return "—";
        return Units.formatPressureDelta(deltaHpa, sys);
    }

    // применяем наклон/цвет и текст дельты в карточки
    private void applyTrendToViews(double deltaHpa, Units.System sys, boolean is3h) {
        final int onSurface = MaterialColors.getColor(vb.getRoot(),
                com.google.android.material.R.attr.colorOnSurface);

        float rot = iconRotationFor(deltaHpa);
        int tint = tintFor(deltaHpa);
        String deltaText = formatDeltaUnits(deltaHpa, sys);

        if (is3h) {
            vb.ivTrend3Arrow.setImageResource(R.drawable.ic_trend_arrow);
            vb.ivTrend3Arrow.setRotation(rot);
            vb.ivTrend3Arrow.setImageTintList(ColorStateList.valueOf(tint));

            vb.tvTrend3Delta.setText(deltaText);
            vb.tvTrend3Delta.setTextColor(onSurface);
        } else {
            vb.ivTrend24Arrow.setImageResource(R.drawable.ic_trend_arrow);
            vb.ivTrend24Arrow.setRotation(rot);
            vb.ivTrend24Arrow.setImageTintList(ColorStateList.valueOf(tint));

            vb.tvTrend24Delta.setText(deltaText);
            vb.tvTrend24Delta.setTextColor(onSurface);
        }
    }
}
