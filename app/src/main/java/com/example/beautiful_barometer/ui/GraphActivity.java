package com.example.beautiful_barometer.ui;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import androidx.core.util.Pair;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.content.Intent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.example.beautiful_barometer.R;
import com.example.beautiful_barometer.util.ThemeController;
import com.example.beautiful_barometer.data.AggPoint;
import com.example.beautiful_barometer.data.AppDatabase;
import com.example.beautiful_barometer.data.EventSample;
import com.example.beautiful_barometer.data.PressureDao;
import com.example.beautiful_barometer.data.PressureSample;
import com.example.beautiful_barometer.util.ServiceController;
import com.example.beautiful_barometer.feedback.AppEventLogger;
import com.example.beautiful_barometer.util.Units;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.datepicker.MaterialDatePicker;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GraphActivity extends AppCompatActivity {

    private static final String PREF_GRAPH_RANGE_MS = "pref_graph_range_ms";
    private static final String PREF_GRAPH_MODE = "pref_graph_mode";
    private static final String PREF_GRAPH_CUSTOM_FROM = "pref_graph_custom_from";
    private static final String PREF_GRAPH_CUSTOM_TO = "pref_graph_custom_to";
    private static final String PREF_GRAPH_USE_CUSTOM = "pref_graph_use_custom";

    private boolean suppressRangeCallbacks = false;

    private LineChart chart;
    private Chip chipHeaderMode;
    private Chip chipHeaderEvents;
    private ChipGroup chipGroup;
    private MaterialButtonToggleGroup toggleMode;

    // empty state
    private View emptyContainer;
    private TextView emptyTitle;
    private TextView emptySubtitle;
    private MaterialButton btnStartRecording;
    private MaterialButton btnGo1h;
    private MaterialButton btnOpenSettings;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private static final long MS_1H = 60L * 60_000L;
    private static final long MS_1D = 24L * MS_1H;

    private enum Mode { PRESSURE, ALTITUDE }

    private long rangeMs = 24L * MS_1H;
    private Mode mode = Mode.PRESSURE;

    // custom range
    private long customFromMs = -1L;
    private long customToMs = -1L;

    private String lastRangeLabel = "";

    // current series for marker / sheets
    private List<Point> lastSeries = Collections.emptyList();
    private List<EventSample> lastEvents = Collections.emptyList();
    private List<EventSample> lastDisplayEvents = Collections.emptyList();
    private long baseTsMs = 0L;
    private long lastBucketMs = 60_000L;
    private Stats lastStats = Stats.empty();
    private String appliedThemeMode;

    private static class Point {
        long t;
        double hpa;
        double yShown; // pressure in hPa OR altitude meters (depends on mode); filled at build-time
        Point(long t, double hpa) { this.t = t; this.hpa = hpa; }
    }


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

    private int colorAttr(int attrRes) {
        View anchor = chart != null ? chart : findViewById(android.R.id.content);
        if (anchor == null) {
            anchor = getWindow().getDecorView();
        }
        return MaterialColors.getColor(anchor, attrRes);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeController.applyToActivity(this);
        super.onCreate(savedInstanceState);
        appliedThemeMode = ThemeController.getThemeMode(this);
        setContentView(R.layout.activity_graph);

        chart = findViewById(R.id.lineChart);
        chipHeaderMode = findViewById(R.id.chipHeaderMode);
        chipHeaderEvents = findViewById(R.id.chipHeaderEvents);
        chipGroup = findViewById(R.id.chipGroupRange);
        toggleMode = findViewById(R.id.toggleMode);

        emptyContainer = findViewById(R.id.emptyContainer);
        emptyTitle = findViewById(R.id.emptyTitle);
        emptySubtitle = findViewById(R.id.emptySubtitle);
        btnStartRecording = findViewById(R.id.btnStartRecording);
        btnGo1h = findViewById(R.id.btnGo1h);
        btnOpenSettings = findViewById(R.id.btnOpenSettings);

        restoreGraphPreferences();
        AppEventLogger.log(this, "GRAPH", "open; restored range=" + rangeMs + ", mode=" + mode.name());

        setupChartBaseStyle();
        setupControls();
        setupEmptyActions();
        setupHeaderActions();
        clearSummaryCards();

        applyRestoredSelections();
        loadAndRender();
    }


    private void setupHeaderActions() {
        chipHeaderMode.setOnClickListener(v -> {
            AppEventLogger.log(this, "GRAPH", "open stats sheet; range=" + lastRangeLabel);
            showStatsSheet();
        });
        chipHeaderEvents.setOnClickListener(v -> {
            AppEventLogger.log(this, "GRAPH", "open timeline sheet; events=" + (lastDisplayEvents != null ? lastDisplayEvents.size() : 0));
            showTimelineSheet();
        });
    }

    private void setupEmptyActions() {
        btnStartRecording.setOnClickListener(v -> {
            AppEventLogger.log(this, "GRAPH", "start recording from empty state");
            ServiceController.startRecording(this);
            loadAndRender();
        });
        btnGo1h.setOnClickListener(v -> {
            AppEventLogger.log(this, "GRAPH", "empty state jump to 1h");
            rangeMs = MS_1H;
            customFromMs = -1L;
            customToMs = -1L;
            saveGraphPreferences(false);
            selectChipByTag(MS_1H);
            loadAndRender();
        });
        btnOpenSettings.setOnClickListener(v -> {
            AppEventLogger.log(this, "GRAPH", "open settings from empty state");
            startActivity(new Intent(this, SettingsActivity.class));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        String currentThemeMode = ThemeController.getThemeMode(this);
        if (appliedThemeMode != null && !appliedThemeMode.equals(currentThemeMode)) {
            appliedThemeMode = currentThemeMode;
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private void setupControls() {
        chipGroup.setSingleSelection(true);

        // XML tags come as Strings - normalize to Long where possible
        for (int i = 0; i < chipGroup.getChildCount(); i++) {
            if (chipGroup.getChildAt(i) instanceof Chip) {
                Chip c = (Chip) chipGroup.getChildAt(i);
                Object t = c.getTag();
                if (t instanceof String) {
                    String s = (String) t;
                    if (s.matches("\\d+")) {
                        try { c.setTag(Long.parseLong(s)); } catch (Exception ignored) {}
                    }
                }
            }
        }

        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (suppressRangeCallbacks || checkedIds == null || checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            Chip chip = group.findViewById(id);
            if (chip == null) return;

            Object tag = chip.getTag();
            if (tag instanceof String && "CUSTOM".equals(tag)) {
                AppEventLogger.log(this, "GRAPH", "open custom range picker");
                openCustomDateRangePicker();
                return;
            }
            if (tag instanceof Long) {
                rangeMs = (Long) tag;
                customFromMs = -1L;
                customToMs = -1L;
                AppEventLogger.log(this, "GRAPH", "range selected=" + rangeMs);
                saveGraphPreferences(false);
                loadAndRender();
            }
        });

        toggleMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnModePressure) mode = Mode.PRESSURE;
            else if (checkedId == R.id.btnModeAltitude) mode = Mode.ALTITUDE;
            AppEventLogger.log(this, "GRAPH", "mode selected=" + mode.name());
            saveGraphPreferences(isCustomSelected());
            loadAndRender();
        });
    }

    private void openCustomDateRangePicker() {
        // If we already have custom range — open picker anyway
        MaterialDatePicker<Pair<Long, Long>> picker = MaterialDatePicker.Builder
                .dateRangePicker()
                .setTitleText(getString(R.string.graph_custom_title))
                .build();

        picker.addOnPositiveButtonClickListener(sel -> {
            if (sel == null || sel.first == null || sel.second == null) return;
            customFromMs = pickerUtcDayToLocalStart(sel.first);
            customToMs = pickerUtcDayToLocalEnd(sel.second);
            rangeMs = Math.max(1L, customToMs - customFromMs);
            AppEventLogger.log(this, "GRAPH", "custom range selected from=" + customFromMs + ", to=" + customToMs);
            saveGraphPreferences(true);
            loadAndRender();
        });

        picker.addOnDismissListener(d -> {
            // if user dismissed without choosing and we have no custom range, fallback to 24h
            if (customFromMs < 0 || customToMs < 0) {
                rangeMs = MS_1D;
                saveGraphPreferences(false);
                selectChipByTag(MS_1D);
            }
        });

        picker.show(getSupportFragmentManager(), "customRange");
    }

    private long pickerUtcDayToLocalStart(long utcPickerMillis) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(utcPickerMillis);

        Calendar local = Calendar.getInstance();
        local.clear();
        local.set(
                utc.get(Calendar.YEAR),
                utc.get(Calendar.MONTH),
                utc.get(Calendar.DAY_OF_MONTH),
                0,
                0,
                0
        );
        return local.getTimeInMillis();
    }

    private long pickerUtcDayToLocalEnd(long utcPickerMillis) {
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(pickerUtcDayToLocalStart(utcPickerMillis));
        local.add(Calendar.DAY_OF_MONTH, 1);
        return local.getTimeInMillis() - 1L;
    }

    private void restoreGraphPreferences() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        rangeMs = sp.getLong(PREF_GRAPH_RANGE_MS, MS_1D);
        customFromMs = sp.getLong(PREF_GRAPH_CUSTOM_FROM, -1L);
        customToMs = sp.getLong(PREF_GRAPH_CUSTOM_TO, -1L);
        String modeValue = sp.getString(PREF_GRAPH_MODE, Mode.PRESSURE.name());
        try {
            mode = Mode.valueOf(modeValue);
        } catch (Exception ignored) {
            mode = Mode.PRESSURE;
        }
    }

    private void applyRestoredSelections() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useCustom = sp.getBoolean(PREF_GRAPH_USE_CUSTOM, false) && customFromMs >= 0L && customToMs >= 0L;
        suppressRangeCallbacks = true;
        if (useCustom) {
            for (int i = 0; i < chipGroup.getChildCount(); i++) {
                if (chipGroup.getChildAt(i) instanceof Chip) {
                    Chip c = (Chip) chipGroup.getChildAt(i);
                    if ("CUSTOM".equals(c.getTag())) {
                        c.setChecked(true);
                        break;
                    }
                }
            }
        } else {
            selectChipByTag(rangeMs);
        }
        toggleMode.check(mode == Mode.ALTITUDE ? R.id.btnModeAltitude : R.id.btnModePressure);
        suppressRangeCallbacks = false;
    }

    private void saveGraphPreferences(boolean useCustom) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        sp.edit()
                .putLong(PREF_GRAPH_RANGE_MS, rangeMs)
                .putString(PREF_GRAPH_MODE, mode.name())
                .putBoolean(PREF_GRAPH_USE_CUSTOM, useCustom)
                .putLong(PREF_GRAPH_CUSTOM_FROM, customFromMs)
                .putLong(PREF_GRAPH_CUSTOM_TO, customToMs)
                .apply();
    }

    private void selectChipByTag(long tagMs) {
        for (int i = 0; i < chipGroup.getChildCount(); i++) {
            if (chipGroup.getChildAt(i) instanceof Chip) {
                Chip c = (Chip) chipGroup.getChildAt(i);
                Object t = c.getTag();
                if (t instanceof Long && ((Long) t) == tagMs) {
                    c.setChecked(true);
                    return;
                }
            }
        }
    }

    private void setupChartBaseStyle() {
        chart.getDescription().setEnabled(false);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);
        // We show our own empty-state view instead of the chart's built-in text
        chart.setNoDataText("");

        chart.getAxisRight().setEnabled(false);

        Legend lg = chart.getLegend();
        lg.setEnabled(false);

        Description d = new Description();
        d.setText("");
        chart.setDescription(d);

        chart.setHighlightPerTapEnabled(true);
        chart.setHighlightPerDragEnabled(true);

        chart.setOnChartGestureListener(new OnChartGestureListener() {
            @Override public void onChartLongPressed(MotionEvent me) {
                Highlight h = chart.getHighlightByTouchPoint(me.getX(), me.getY());
                if (h != null) {
                    chart.highlightValue(h, true);
                    chart.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
            }
            @Override public void onChartGestureStart(MotionEvent me, com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture lastPerformedGesture) {}
            @Override public void onChartGestureEnd(MotionEvent me, com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture lastPerformedGesture) {}
            @Override public void onChartDoubleTapped(MotionEvent me) {
                chart.fitScreen();
            }
            @Override public void onChartSingleTapped(MotionEvent me) {}
            @Override public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) {}
            @Override public void onChartScale(MotionEvent me, float scaleX, float scaleY) {}
            @Override public void onChartTranslate(MotionEvent me, float dX, float dY) {}
        });

        chart.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
            @Override public void onValueSelected(Entry e, Highlight h) {
                if (h != null && h.getDataSetIndex() == 1) {
                    chart.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } else {
                    chart.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
            @Override public void onNothingSelected() {}
        });
    }

    private void loadAndRender() {
        final PressureDao dao = AppDatabase.get(this).pressureDao();
        final Units.System sys = Units.getSystem(PreferenceManager.getDefaultSharedPreferences(this));

        io.execute(() -> {
            long now = System.currentTimeMillis();
            long from;
            long to;

            if (isCustomSelected()) {
                from = customFromMs;
                to = customToMs;
            } else {
                from = now - rangeMs;
                to = now;
            }

            if (from < 0) from = 0;
            if (to <= from) to = from + 1;

            long periodMs = Math.max(1L, to - from);
            long prevTo = from;
            long prevFrom = Math.max(0L, prevTo - periodMs);

            lastRangeLabel = buildRangeLabel(from, to);

            boolean wantRaw = (rangeMs <= 6L * MS_1H) && !needsAggregationForRaw(dao, from, to);
            List<Point> points = new ArrayList<>();

            if (wantRaw) {
                List<PressureSample> raw = dao.betweenAsc(from, to);
                for (PressureSample s : raw) points.add(new Point(s.timestamp, s.pressureHpa));
                points = downsample(points, 800);
            } else {
                long bucketMs = bucketFor(rangeMs);
                List<AggPoint> agg = dao.aggPressure(from, to, bucketMs);
                for (AggPoint p : agg) points.add(new Point(p.t, p.v));
            }

            if (points.isEmpty()) {
                runOnUiThreadIfAlive(this::showEmptyState);
                return;
            }

            baseTsMs = points.get(0).t;
            long effectiveBucketMs = wantRaw ? estimateStepMs(points, bucketFor(rangeMs)) : bucketFor(rangeMs);
            lastBucketMs = effectiveBucketMs;

            for (Point p : points) {
                p.yShown = (mode == Mode.PRESSURE) ? p.hpa : Units.altitudeFromPressure(p.hpa);
            }

            List<Point> shown = points;
            long fallbackBucket = bucketFor(rangeMs);
            lastBucketMs = wantRaw ? estimateStepMs(shown, fallbackBucket) : fallbackBucket;

            List<EventSample> eventsAll = AppDatabase.get(this).eventDao().between(from, to);
            List<EventSample> events = filterEventsForChart(eventsAll);
            List<EventSample> displayEvents = filterEventsForDisplay(eventsAll);

            shown.sort(Comparator.comparingLong(a -> a.t));

            // previous period for comparison
            List<Point> prevShown = Collections.emptyList();
            List<EventSample> prevDisplayEvents = Collections.emptyList();
            if (prevTo > prevFrom) {
                boolean prevWantRaw = (periodMs <= 6L * MS_1H) && !needsAggregationForRaw(dao, prevFrom, prevTo);
                List<Point> prevPoints = new ArrayList<>();
                if (prevWantRaw) {
                    List<PressureSample> rawPrev = dao.betweenAsc(prevFrom, prevTo);
                    for (PressureSample s : rawPrev) prevPoints.add(new Point(s.timestamp, s.pressureHpa));
                    prevPoints = downsample(prevPoints, 800);
                } else {
                    long prevBucketMs = bucketFor(periodMs);
                    List<AggPoint> aggPrev = dao.aggPressure(prevFrom, prevTo, prevBucketMs);
                    for (AggPoint p : aggPrev) prevPoints.add(new Point(p.t, p.v));
                }
                for (Point p : prevPoints) {
                    p.yShown = (mode == Mode.PRESSURE) ? p.hpa : Units.altitudeFromPressure(p.hpa);
                }
                prevPoints.sort(Comparator.comparingLong(a -> a.t));
                prevShown = prevPoints;
                List<EventSample> prevEventsAll = AppDatabase.get(this).eventDao().between(prevFrom, prevTo);
                prevDisplayEvents = filterEventsForDisplay(prevEventsAll);
            }

            Stats stats = computeStats(
                    shown,
                    sys,
                    mode,
                    lastRangeLabel,
                    displayEvents,
                    periodMs,
                    prevShown,
                    prevDisplayEvents
            );

            List<Entry> entries = new ArrayList<>(shown.size());
            for (Point p : shown) {
                float xSec = (p.t - baseTsMs) / 1000f;
                entries.add(new Entry(xSec, (float) p.yShown));
            }

            lastSeries = shown;
            lastEvents = events;
            lastDisplayEvents = displayEvents;
            lastStats = stats;

            runOnUiThreadIfAlive(() -> {
                hideEmptyState();
                render(entries, stats, sys, mode, events, lastBucketMs);
            });
        });
    }


    private void showEmptyState() {
        chart.setVisibility(View.GONE);
        emptyContainer.setVisibility(View.VISIBLE);
        clearSummaryCards();
        emptyTitle.setText(getString(R.string.graph_empty_title, lastRangeLabel));
        emptySubtitle.setText(getString(R.string.graph_empty_subtitle));
    }

    private void hideEmptyState() {
        emptyContainer.setVisibility(View.GONE);
        chart.setVisibility(View.VISIBLE);
    }

    private String buildRangeLabel(long fromMs, long toMs) {
        // If user selected custom dates — show "с … по …"
        if (isCustomSelected()) {
            String a = DateFormat.format("d MMM", fromMs).toString();
            String b = DateFormat.format("d MMM", toMs).toString();
            return getString(R.string.range_label_custom) + ": " + a + " — " + b;
        }
        if (rangeMs <= 1L * MS_1H) return getString(R.string.range_label_1h);
        if (rangeMs <= 3L * MS_1H) return getString(R.string.range_label_3h);
        if (rangeMs <= 6L * MS_1H) return getString(R.string.range_label_6h);
        if (rangeMs <= 12L * MS_1H) return getString(R.string.range_label_12h);
        if (rangeMs <= 24L * MS_1H) return getString(R.string.range_label_24h);
        if (rangeMs <= 7L * MS_1D) return getString(R.string.range_label_7d);
        return getString(R.string.range_label_30d);
    }

    private boolean isCustomSelected() {
        int checkedId = chipGroup.getCheckedChipId();
        if (checkedId == -1) return false;
        Chip chip = chipGroup.findViewById(checkedId);
        if (chip == null) return false;
        return "CUSTOM".equals(chip.getTag()) && customFromMs >= 0 && customToMs >= 0;
    }

    /**
     * Если данных в окне слишком много (частый интервал записи), лучше сразу агрегировать.
     */
    private boolean needsAggregationForRaw(PressureDao dao, long from, long to) {
        try {
            int c = dao.countBetween(from, to);
            return c > 2000; // ~2k точек — уже заметно на слабых устройствах
        } catch (Exception e) {
            return true;
        }
    }

    private static long bucketFor(long rangeMs) {
        if (rangeMs <= 1L * MS_1H) return 10_000L;        // 1h -> 10s
        if (rangeMs <= 3L * MS_1H) return 20_000L;        // 3h -> 20s
        if (rangeMs <= 6L * MS_1H) return 40_000L;        // 6h -> 40s
        if (rangeMs <= 12L * MS_1H) return 90_000L;       // 12h -> 1.5m
        if (rangeMs <= 24L * MS_1H) return 120_000L;      // 24h -> 2m
        if (rangeMs <= 7L * MS_1D) return 1_200_000L;     // 7d -> 20m
        return 3_600_000L;                                // 30d -> 1h
    }


    private static long estimateStepMs(List<Point> points) {
        if (points == null || points.size() < 2) return 60_000L;
        long first = points.get(0).t;
        long last = points.get(points.size() - 1).t;
        long span = Math.max(1L, last - first);
        long step = span / Math.max(1, points.size() - 1);
        // clamp to sane range
        if (step < 1_000L) step = 1_000L;
        if (step > 10 * 60_000L) step = 10 * 60_000L;
        return step;
    }

    private static List<EventSample> filterEventsForDisplay(List<EventSample> events) {
        if (events == null || events.isEmpty()) return Collections.emptyList();
        List<EventSample> out = new ArrayList<>();
        for (EventSample e : events) {
            // We do NOT display service starts as "events" on the chart — there may be many.
            if ("SERVICE_START".equals(e.type)) continue;
            if ("ALARM".equals(e.type) || "GPS_BASELINE".equals(e.type) || "CALIBRATION".equals(e.type)
                    || "TRIP_MODE_ON".equals(e.type) || "TRIP_MODE_OFF".equals(e.type)
                    || e.type.startsWith("NOTIFICATION_")) {
                out.add(e);
            }
        }
        return out;
    }

    private static double valueAt(List<Point> series, long ts) {
        if (series == null || series.isEmpty()) return Double.NaN;
        int lo = 0, hi = series.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long t = series.get(mid).t;
            if (t < ts) lo = mid + 1;
            else if (t > ts) hi = mid - 1;
            else return series.get(mid).yShown;
        }
        int i = Math.max(0, Math.min(series.size() - 1, lo));
        return series.get(i).yShown;
    }

    private static List<EventSample> filterEventsForChart(List<EventSample> events) {
        if (events == null || events.isEmpty()) return Collections.emptyList();
        List<EventSample> out = new ArrayList<>();
        for (EventSample e : events) {
            // Запуски сервиса могут быть частыми — не засоряем график
            if ("SERVICE_START".equals(e.type)) continue;
            // Оставляем только важные события
            if ("ALARM".equals(e.type) || "GPS_BASELINE".equals(e.type) || "CALIBRATION".equals(e.type)
                    || "TRIP_MODE_ON".equals(e.type) || "TRIP_MODE_OFF".equals(e.type)
                    || e.type.startsWith("NOTIFICATION_")) {
                out.add(e);
            }
        }
        return out;
    }

    private static long estimateStepMs(List<Point> points, long fallbackMs) {
        if (points == null || points.size() < 2) return fallbackMs;
        long first = points.get(0).t;
        long last = points.get(points.size() - 1).t;
        long span = Math.max(1L, last - first);
        long n = Math.max(1, points.size() - 1);
        long step = span / n;
        // защита от странных данных
        if (step < 1000L) step = 1000L;
        if (step > 10 * 60_000L) step = 10 * 60_000L;
        return step;
    }

    private static Double valueAtSeries(List<Point> series, long ts) {
        if (series == null || series.isEmpty()) return null;
        int lo = 0, hi = series.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long t = series.get(mid).t;
            if (t < ts) lo = mid + 1;
            else if (t > ts) hi = mid - 1;
            else return series.get(mid).yShown;
        }
        int i = Math.max(0, Math.min(series.size() - 1, lo));
        return series.get(i).yShown;
    }

    private static List<Entry> buildEventEntries(List<EventSample> events, List<Point> series, long baseTsMs) {
        if (events == null || events.isEmpty()) return Collections.emptyList();
        List<Entry> out = new ArrayList<>();
        for (EventSample e : events) {
            Double y = valueAtSeries(series, e.timestamp);
            if (y == null) continue;
            float xSec = (e.timestamp - baseTsMs) / 1000f;
            out.add(new Entry(xSec, y.floatValue()));
        }
        return out;
    }

    private static List<Point> downsample(List<Point> in, int maxPoints) {
        if (in.size() <= maxPoints) return in;
        List<Point> out = new ArrayList<>(maxPoints);
        double step = (double) in.size() / (double) maxPoints;
        for (int i = 0; i < maxPoints; i++) {
            int idx = (int) Math.floor(i * step);
            if (idx < 0) idx = 0;
            if (idx >= in.size()) idx = in.size() - 1;
            out.add(in.get(idx));
        }
        return out;
    }

    private void render(
            List<Entry> entries,
            Stats stats,
            Units.System sys,
            Mode mode,
            List<EventSample> events,
            long bucketMs
    ) {
        bindSummaryCards(stats);

        final int onSurface = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorOnSurface);
        final int outline = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorOutline);

        // dataset
        LineDataSet set = new LineDataSet(entries, mode == Mode.PRESSURE ? getString(R.string.pressure) : getString(R.string.altitude));
        set.setDrawCircles(false);
        set.setLineWidth(2f);
        set.setDrawValues(false);
        set.setMode(LineDataSet.Mode.LINEAR);
        set.setDrawFilled(true);
        set.setFillColor(MaterialColors.getColor(chart, com.google.android.material.R.attr.colorPrimaryContainer));
        set.setFillAlpha(80);
        set.setDrawHorizontalHighlightIndicator(false);
        set.setDrawVerticalHighlightIndicator(true);
        set.setHighLightColor(onSurface);

        // events as bold points (not vertical spam)
        List<Entry> evEntries = buildEventEntries(events, lastSeries, baseTsMs);
        LineDataSet evSet = new LineDataSet(evEntries, "События");
        evSet.setDrawValues(false);
        evSet.setDrawCircles(true);
        evSet.setDrawCircleHole(false);
        evSet.setCircleRadius(6f);
        evSet.setLineWidth(0f);
        evSet.setDrawFilled(false);
        evSet.setDrawHorizontalHighlightIndicator(false);
        evSet.setDrawVerticalHighlightIndicator(true);
        // use a slightly stronger color for points
        int accent = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorPrimary);
        evSet.setCircleColor(accent);
        evSet.setHighLightColor(accent);

        chart.setData(new LineData(set, evSet));

        // marker
        GraphMarker marker = new GraphMarker(this, R.layout.marker_chart, baseTsMs, sys, mode, lastSeries, lastEvents, bucketMs, /*eventDataSetIndex*/1);
        marker.setChartView(chart);
        chart.setMarker(marker);

        // axes
        XAxis xAxis = chart.getXAxis();
        xAxis.removeAllLimitLines();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(onSurface);
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(outline);
        xAxis.setGridLineWidth(0.6f);
        xAxis.setLabelCount(5, false);
        xAxis.setValueFormatter(new TimeAxisFormatter(baseTsMs, rangeMs));
        xAxis.setGranularity(Math.max(1f, (bucketMs / 1000f)));

        YAxis yLeft = chart.getAxisLeft();
        yLeft.setDrawGridLines(true);
        yLeft.setGridColor(outline);
        yLeft.setTextColor(onSurface);
        yLeft.setValueFormatter(new YFormatter(sys, mode));
        float yMin = Float.POSITIVE_INFINITY;
        float yMax = Float.NEGATIVE_INFINITY;
        for (Entry entry : entries) {
            yMin = Math.min(yMin, entry.getY());
            yMax = Math.max(yMax, entry.getY());
        }
        if (Float.isFinite(yMin) && Float.isFinite(yMax)) {
            float pad = Math.max(1f, (yMax - yMin) * 0.10f);
            yLeft.setAxisMinimum(yMin - pad);
            yLeft.setAxisMaximum(yMax + pad);
            set.setFillFormatter((dataSet, dataProvider) -> yLeft.getAxisMinimum());
        } else {
            yLeft.resetAxisMinimum();
            yLeft.resetAxisMaximum();
        }

        // "Начало данных" — первая точка ряда (x=0)
        addStartOfDataLine(xAxis);

        chart.invalidate();
        chart.animateX(200);
    }

    private void addStartOfDataLine(XAxis xAxis) {
        final int onSurface = MaterialColors.getColor(chart, com.google.android.material.R.attr.colorOnSurface);
        LimitLine ll = new LimitLine(0f, getString(R.string.graph_start_of_data));
        ll.setLineWidth(1f);
        ll.enableDashedLine(10f, 10f, 0f);
        ll.setTextColor(onSurface);
        ll.setTextSize(10f);
        ll.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_TOP);
        xAxis.addLimitLine(ll);
    }

    private String labelForEventShort(EventSample e) {
        switch (e.type) {
            case "ALARM": return getString(R.string.event_alarm_short);
            case "GPS_BASELINE": return getString(R.string.event_gps_baseline_short);
            case "CALIBRATION": return getString(R.string.event_calibration_short);
            case "SERVICE_START": return getString(R.string.event_service_start_short);
            case "NOTIFICATION_SIMPLE_DROP": return getString(R.string.event_notification_simple_drop_short);
            case "NOTIFICATION_SIMPLE_RISE": return getString(R.string.event_notification_simple_rise_short);
            case "NOTIFICATION_FAST_WORSENING": return getString(R.string.event_notification_fast_worsening_short);
            case "NOTIFICATION_SUSTAINED_WORSENING": return getString(R.string.event_notification_sustained_worsening_short);
            case "NOTIFICATION_STABILIZING_AFTER_DROP": return getString(R.string.event_notification_stabilizing_short);
            case "NOTIFICATION_IMPROVING_AFTER_DROP": return getString(R.string.event_notification_improving_short);
            case "TRIP_MODE_ON": return getString(R.string.event_trip_mode_on_short);
            case "TRIP_MODE_OFF": return getString(R.string.event_trip_mode_off_short);
            default: return e.type;
        }
    }

    private String labelForEventLong(EventSample e) {
        switch (e.type) {
            case "ALARM": return getString(R.string.event_alarm);
            case "GPS_BASELINE": return getString(R.string.event_gps_baseline);
            case "CALIBRATION": return getString(R.string.event_calibration);
            case "SERVICE_START": return getString(R.string.event_service_start);
            case "NOTIFICATION_SIMPLE_DROP": return getString(R.string.event_notification_simple_drop);
            case "NOTIFICATION_SIMPLE_RISE": return getString(R.string.event_notification_simple_rise);
            case "NOTIFICATION_FAST_WORSENING": return getString(R.string.event_notification_fast_worsening);
            case "NOTIFICATION_SUSTAINED_WORSENING": return getString(R.string.event_notification_sustained_worsening);
            case "NOTIFICATION_STABILIZING_AFTER_DROP": return getString(R.string.event_notification_stabilizing);
            case "NOTIFICATION_IMPROVING_AFTER_DROP": return getString(R.string.event_notification_improving);
            case "TRIP_MODE_ON": return getString(R.string.event_trip_mode_on);
            case "TRIP_MODE_OFF": return getString(R.string.event_trip_mode_off);
            default: return e.type;
        }
    }

    // ===== formatters & marker =====

    private static class TimeAxisFormatter extends ValueFormatter {
        private final long baseTs;
        private final long rangeMs;

        TimeAxisFormatter(long baseTs, long rangeMs) {
            this.baseTs = baseTs;
            this.rangeMs = rangeMs;
        }

        @Override
        public String getAxisLabel(float value, AxisBase axis) {
            long ts = baseTs + (long) (value * 1000L);
            if (rangeMs <= 24L * MS_1H) {
                return DateFormat.format("HH:mm", ts).toString();
            }
            if (rangeMs <= 7L * MS_1D) {
                return DateFormat.format("dd MMM\nHH:mm", ts).toString();
            }
            return DateFormat.format("dd MMM", ts).toString();
        }
    }

    private static class YFormatter extends ValueFormatter {
        private final Units.System sys;
        private final Mode mode;

        private static final double HPA_TO_INHG = 0.0295299830714;
        private static final double HPA_TO_MMHG = 0.750061683;
        private static final double M_TO_FT = 3.28084;

        YFormatter(Units.System sys, Mode mode) {
            this.sys = sys;
            this.mode = mode;
        }

        @Override
        public String getAxisLabel(float value, AxisBase axis) {
            if (mode == Mode.PRESSURE) {
                double hpa = value;
                switch (sys) {
                    case IMPERIAL: return String.format(Locale.getDefault(), "%.2f", hpa * HPA_TO_INHG);
                    case MMHG:     return String.format(Locale.getDefault(), "%.0f", hpa * HPA_TO_MMHG);
                    case METRIC:
                    default:       return String.format(Locale.getDefault(), "%.0f", hpa);
                }
            } else {
                double m = value;
                if (sys == Units.System.IMPERIAL) {
                    return String.format(Locale.getDefault(), "%.0f", m * M_TO_FT);
                }
                return String.format(Locale.getDefault(), "%.0f", m);
            }
        }
    }

    private static class Stats {
        String rangeLabel = "";
        String minText = "—";
        String avgText = "—";
        String maxText = "—";
        String deltaText = "—";
        String noiseText = "—";
        String summaryText = "";
        double deltaValue = 0.0;
        int pointCount = 0;
        int eventCount = 0;

        double avgValue = Double.NaN;
        double amplitudeValue = Double.NaN;
        double noiseValue = Double.NaN;

        String amplitudeText = "—";
        String stabilityText = "—";
        String fastestDropText = "—";
        String fastestRiseText = "—";
        long fastestDropTs = -1L;
        long fastestRiseTs = -1L;
        String compareAvgText = "—";
        String compareAmplitudeText = "—";
        String compareEventsText = "—";
        String previousRangeHint = "";
        String insightText = "";
        String pointsText = "—";

        int simpleNotificationCount = 0;
        int smartNotificationCount = 0;
        int tripCount = 0;
        int calibrationCount = 0;
        int alarmCount = 0;

        static Stats empty() {
            return new Stats();
        }
    }

    private static class ExtremumWindow {
        double delta = Double.NaN;
        long atTs = -1L;
        long windowMs = 0L;
    }

    private Stats computeStats(
            List<Point> series,
            Units.System sys,
            Mode mode,
            String rangeLabel,
            List<EventSample> displayEvents,
            long periodMs,
            List<Point> previousSeries,
            List<EventSample> previousEvents
    ) {
        Stats stats = Stats.empty();
        stats.rangeLabel = rangeLabel;
        stats.pointsText = String.valueOf(series != null ? series.size() : 0);

        if (series == null || series.isEmpty()) {
            stats.insightText = "За выбранный период данных пока нет.";
            return stats;
        }

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0.0;
        for (Point p : series) {
            double v = p.yShown;
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }
        double avg = sum / series.size();
        double first = series.get(0).yShown;
        double last = series.get(series.size() - 1).yShown;
        double delta = last - first;
        double amplitude = max - min;

        double variance = 0.0;
        for (Point p : series) {
            double d = p.yShown - avg;
            variance += d * d;
        }
        variance /= Math.max(1, series.size());
        double stddev = Math.sqrt(variance);

        stats.avgValue = avg;
        stats.amplitudeValue = amplitude;
        stats.noiseValue = stddev;
        stats.deltaValue = delta;
        stats.pointCount = series.size();
        stats.eventCount = displayEvents != null ? displayEvents.size() : 0;

        stats.minText = formatValue(min, sys, mode, false);
        stats.avgText = formatValue(avg, sys, mode, false);
        stats.maxText = formatValue(max, sys, mode, false);
        stats.deltaText = formatValue(delta, sys, mode, true);
        stats.noiseText = formatValue(stddev, sys, mode, false);
        stats.amplitudeText = formatValue(amplitude, sys, mode, false);
        stats.stabilityText = describeStability(stddev, mode);

        long trendWindowMs = selectTrendWindow(periodMs);
        ExtremumWindow drop = findExtremum(series, trendWindowMs, true);
        ExtremumWindow rise = findExtremum(series, trendWindowMs, false);
        stats.fastestDropTs = drop.atTs;
        stats.fastestRiseTs = rise.atTs;
        stats.fastestDropText = Double.isNaN(drop.delta)
                ? "Недостаточно данных"
                : formatValue(drop.delta, sys, mode, true) + " за " + windowLabel(drop.windowMs);
        stats.fastestRiseText = Double.isNaN(rise.delta)
                ? "Недостаточно данных"
                : formatValue(rise.delta, sys, mode, true) + " за " + windowLabel(rise.windowMs);

        if (displayEvents != null) {
            for (EventSample event : displayEvents) {
                if (event.type.startsWith("NOTIFICATION_SIMPLE_")) {
                    stats.simpleNotificationCount += 1;
                } else if (event.type.startsWith("NOTIFICATION_")) {
                    stats.smartNotificationCount += 1;
                }
                if ("TRIP_MODE_ON".equals(event.type) || "TRIP_MODE_OFF".equals(event.type)) {
                    stats.tripCount += 1;
                }
                if ("CALIBRATION".equals(event.type) || "GPS_BASELINE".equals(event.type)) {
                    stats.calibrationCount += 1;
                }
                if ("ALARM".equals(event.type)) {
                    stats.alarmCount += 1;
                }
            }
        }

        stats.summaryText = "Период: " + rangeLabel
                + "\nТочек: " + stats.pointCount
                + "\nСобытий: " + stats.eventCount
                + "\nШум: " + stats.noiseText;

        if (previousSeries != null && !previousSeries.isEmpty()) {
            Stats prev = computeStats(previousSeries, sys, mode, "", previousEvents, periodMs, Collections.emptyList(), Collections.emptyList());
            stats.compareAvgText = formatValue(stats.avgValue - prev.avgValue, sys, mode, true);
            stats.compareAmplitudeText = formatValue(stats.amplitudeValue - prev.amplitudeValue, sys, mode, true);
            stats.compareEventsText = formatSignedCount(stats.eventCount - prev.eventCount);
            stats.previousRangeHint = "Сравнение с предыдущим периодом";
        } else {
            stats.compareAvgText = "Недостаточно данных";
            stats.compareAmplitudeText = "Недостаточно данных";
            stats.compareEventsText = "Недостаточно данных";
            stats.previousRangeHint = "Предыдущий период недоступен";
        }

        stats.insightText = buildInsight(stats, mode);
        return stats;
    }

    private String buildInsight(Stats stats, Mode mode) {
        double strongDelta = mode == Mode.PRESSURE ? 1.0 : 8.0;
        double mediumAmp = mode == Mode.PRESSURE ? 1.5 : 10.0;
        if (stats.pointCount < 8) {
            return "Данных пока мало: вывод по периоду предварительный.";
        }
        if (stats.tripCount > 0) {
            return "Внутри периода были поездки, поэтому часть динамики могла быть связана со сменой локации.";
        }
        if (stats.deltaValue <= -strongDelta) {
            return "Период в целом прошёл со снижением давления. Посмотри на участок самого быстрого спада — там был главный перелом.";
        }
        if (stats.deltaValue >= strongDelta) {
            return "Период в целом прошёл с ростом давления. Самый сильный рост отмечен в отдельном участке графика.";
        }
        if (stats.amplitudeValue >= mediumAmp) {
            return "Фон был заметно подвижным: амплитуда высокая, но без одного доминирующего тренда на весь период.";
        }
        return "Период был скорее ровным: сильных переломов не видно, основное движение умеренное.";
    }

    private ExtremumWindow findExtremum(List<Point> series, long windowMs, boolean lookingForDrop) {
        ExtremumWindow result = new ExtremumWindow();
        result.windowMs = windowMs;
        if (series == null || series.size() < 3) {
            return result;
        }
        double best = lookingForDrop ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        long earliest = series.get(0).t;
        for (Point point : series) {
            long prevTs = point.t - windowMs;
            if (prevTs < earliest) continue;
            Double prev = valueAtSeries(series, prevTs);
            if (prev == null) continue;
            double delta = point.yShown - prev;
            if ((lookingForDrop && delta < best) || (!lookingForDrop && delta > best)) {
                best = delta;
                result.delta = delta;
                result.atTs = point.t;
            }
        }
        return result;
    }

    private long selectTrendWindow(long periodMs) {
        if (periodMs <= 2L * MS_1H) return 10L * 60_000L;
        if (periodMs <= 8L * MS_1H) return 30L * 60_000L;
        if (periodMs <= 2L * MS_1D) return 1L * MS_1H;
        return 3L * MS_1H;
    }

    private String windowLabel(long windowMs) {
        if (windowMs <= 10L * 60_000L) return "10 мин";
        if (windowMs <= 30L * 60_000L) return "30 мин";
        if (windowMs <= 1L * MS_1H) return "1 час";
        if (windowMs <= 3L * MS_1H) return "3 часа";
        long hours = Math.max(1L, windowMs / MS_1H);
        return hours + " ч";
    }

    private String describeStability(double stddev, Mode mode) {
        double low = mode == Mode.PRESSURE ? 0.18 : 1.5;
        double medium = mode == Mode.PRESSURE ? 0.45 : 4.0;
        if (stddev < low) return "Спокойный фон";
        if (stddev < medium) return "Умеренная нестабильность";
        return "Высокая нестабильность";
    }

    private String formatSignedCount(int delta) {
        if (delta == 0) return "Без изменений";
        return (delta > 0 ? "+" : "") + delta + " к событиям";
    }

    private void bindSummaryCards(Stats stats) {
        bindHeaderMeta(stats);
    }

    private void clearSummaryCards() {
        bindHeaderMeta(Stats.empty());
    }

    private void bindHeaderMeta(Stats stats) {
        chipHeaderMode.setText(getString(R.string.graph_header_stats));
        chipHeaderEvents.setText(formatEventCountLabel(stats.eventCount));

        chipHeaderMode.setChipIconResource(R.drawable.ic_graph_stats);
        chipHeaderEvents.setChipIconResource(R.drawable.ic_graph_timeline);

        styleMetaChip(chipHeaderMode, colorAttr(com.google.android.material.R.attr.colorPrimary));
        styleMetaChip(chipHeaderEvents, stats.eventCount > 0
                ? colorAttr(com.google.android.material.R.attr.colorTertiary)
                : colorAttr(com.google.android.material.R.attr.colorOutline));
    }

    private void styleMetaChip(Chip chip, int accentColor) {
        int surface = colorAttr(com.google.android.material.R.attr.colorSurface);
        int tint = MaterialColors.layer(surface, accentColor, 0.16f);
        chip.setChipBackgroundColor(ColorStateList.valueOf(tint));
        chip.setChipStrokeColor(ColorStateList.valueOf(MaterialColors.layer(surface, accentColor, 0.32f)));
        chip.setChipIconTint(ColorStateList.valueOf(accentColor));
        chip.setTextColor(accentColor);
    }

    private String formatEventCountLabel(int count) {
        if (count <= 0) return getString(R.string.graph_header_events_empty);
        int mod10 = count % 10;
        int mod100 = count % 100;
        if (mod10 == 1 && mod100 != 11) return getString(R.string.graph_header_events_count_one, count);
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return getString(R.string.graph_header_events_count_few, count);
        return getString(R.string.graph_header_events_count_many, count);
    }

    private int colorForDelta(double deltaValue) {
        if (deltaValue > 0.05d) return colorAttr(com.google.android.material.R.attr.colorPrimary);
        if (deltaValue < -0.05d) return colorAttr(com.google.android.material.R.attr.colorError);
        return colorAttr(com.google.android.material.R.attr.colorOnSurface);
    }

    private int colorForEvent(EventSample event) {
        String type = event != null ? event.type : "";
        if (type.startsWith("NOTIFICATION_FAST_WORSENING") || type.startsWith("NOTIFICATION_SUSTAINED_WORSENING") || type.startsWith("NOTIFICATION_SIMPLE_DROP") || "ALARM".equals(type)) {
            return colorAttr(com.google.android.material.R.attr.colorError);
        }
        if (type.startsWith("NOTIFICATION_IMPROVING_AFTER_DROP") || type.startsWith("NOTIFICATION_SIMPLE_RISE")) {
            return colorAttr(com.google.android.material.R.attr.colorPrimary);
        }
        if (type.startsWith("NOTIFICATION_STABILIZING_AFTER_DROP")) {
            return colorAttr(com.google.android.material.R.attr.colorSecondary);
        }
        if ("GPS_BASELINE".equals(type) || "TRIP_MODE_ON".equals(type) || "TRIP_MODE_OFF".equals(type)) {
            return colorAttr(com.google.android.material.R.attr.colorTertiary);
        }
        return colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant);
    }

    private int iconForEvent(EventSample event) {
        String type = event != null ? event.type : "";
        if (type.startsWith("NOTIFICATION_FAST_WORSENING") || type.startsWith("NOTIFICATION_SUSTAINED_WORSENING") || type.startsWith("NOTIFICATION_SIMPLE_DROP")) {
            return R.drawable.ic_event_drop;
        }
        if (type.startsWith("NOTIFICATION_IMPROVING_AFTER_DROP") || type.startsWith("NOTIFICATION_SIMPLE_RISE")) {
            return R.drawable.ic_event_rise;
        }
        if (type.startsWith("NOTIFICATION_STABILIZING_AFTER_DROP")) {
            return R.drawable.ic_event_stable;
        }
        if ("GPS_BASELINE".equals(type) || "TRIP_MODE_ON".equals(type) || "TRIP_MODE_OFF".equals(type)) {
            return R.drawable.ic_event_location;
        }
        if ("CALIBRATION".equals(type)) {
            return R.drawable.ic_event_calibration;
        }
        return R.drawable.ic_event_warning;
    }

    private String badgeForEvent(EventSample event) {
        String type = event != null ? event.type : "";
        if (type.startsWith("NOTIFICATION_")) {
            return getString(R.string.graph_badge_notification);
        }
        if ("GPS_BASELINE".equals(type) || "TRIP_MODE_ON".equals(type) || "TRIP_MODE_OFF".equals(type)) {
            return getString(R.string.graph_badge_gps);
        }
        if ("CALIBRATION".equals(type)) {
            return getString(R.string.graph_badge_calibration);
        }
        return getString(R.string.graph_badge_signal);
    }

    private String timelineTitle(EventSample event) {
        String type = event != null ? event.type : "";
        switch (type) {
            case "NOTIFICATION_FAST_WORSENING":
                return "Началось быстрое ухудшение";
            case "NOTIFICATION_SUSTAINED_WORSENING":
                return "Наблюдается устойчивое ухудшение";
            case "NOTIFICATION_STABILIZING_AFTER_DROP":
                return "Давление стабилизировалось";
            case "NOTIFICATION_IMPROVING_AFTER_DROP":
                return "Есть признаки улучшения";
            case "NOTIFICATION_SIMPLE_DROP":
                return "Сработало обычное уведомление о падении";
            case "NOTIFICATION_SIMPLE_RISE":
                return "Сработало обычное уведомление о росте";
            case "TRIP_MODE_ON":
                return "Включён режим поездки";
            case "TRIP_MODE_OFF":
                return "Режим поездки завершён";
            case "GPS_BASELINE":
                return "GPS-база обновлена";
            case "CALIBRATION":
                return "Калибровка обновлена";
            case "ALARM":
                return "Сработало предупреждение";
            default:
                return labelForEventLong(event);
        }
    }

    private String timelineSubtitle(EventSample event, @Nullable Double yValue) {
        String type = event != null ? event.type : "";
        String valueText = yValue != null
                ? formatValue(yValue, Units.getSystem(PreferenceManager.getDefaultSharedPreferences(this)), mode, false)
                : null;
        String suffix = valueText != null ? " Значение в момент события: " + valueText + "." : "";
        switch (type) {
            case "NOTIFICATION_FAST_WORSENING":
                return "Тренд пошёл вниз резко и уверенно." + suffix;
            case "NOTIFICATION_SUSTAINED_WORSENING":
                return "Снижение держалось достаточно долго, чтобы система сочла его устойчивым." + suffix;
            case "NOTIFICATION_STABILIZING_AFTER_DROP":
                return "После снижения фон стал спокойнее, без нового ускорения вниз." + suffix;
            case "NOTIFICATION_IMPROVING_AFTER_DROP":
                return "После спада появились признаки восстановления давления." + suffix;
            case "NOTIFICATION_SIMPLE_DROP":
                return "Сработал порог обычного уведомления о падении давления." + suffix;
            case "NOTIFICATION_SIMPLE_RISE":
                return "Сработал порог обычного уведомления о росте давления." + suffix;
            case "TRIP_MODE_ON":
                return "Прогноз временно отключён, пока продолжается поездка.";
            case "TRIP_MODE_OFF":
                return "Прогноз снова строится только по новым данным после поездки.";
            case "GPS_BASELINE":
                return event != null && event.value != null && !event.value.isEmpty()
                        ? "За основу взята GPS-высота: " + event.value + " м."
                        : "GPS-высота обновила базовую точку для расчётов.";
            case "CALIBRATION":
                return event != null && event.value != null && !event.value.isEmpty()
                        ? "Новая базовая высота: " + event.value + " м."
                        : "Базовая высота была изменена вручную.";
            case "ALARM":
                return "Зафиксировано заметное изменение давления за короткий период." + suffix;
            default:
                return (valueText != null ? valueText + " • " : "") + getString(R.string.graph_timeline_hint);
        }
    }

    private String dayHeaderLabel(long ts) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ts);
        if (isSameDay(now, then)) return "Сегодня";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(now, then)) return "Вчера";
        return DateFormat.format("d MMMM", ts).toString();
    }

    private boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private View createTimelineDayHeader(String text) {
        TextView tv = new TextView(this);
        int padH = (int) (12f * getResources().getDisplayMetrics().density);
        int padV = (int) (8f * getResources().getDisplayMetrics().density);
        tv.setText(text);
        tv.setPadding(padH, padV, padH, padV);
        tv.setTextSize(14f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        return tv;
    }

    private void showStatsSheet() {
        AppEventLogger.log(this, "GRAPH", "build stats sheet content");
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.sheet_graph_stats, null, false);
        dialog.setContentView(view);

        ((TextView) view.findViewById(R.id.textSheetStatsTitle)).setText(getString(R.string.graph_stats_sheet_title));
        ((TextView) view.findViewById(R.id.textSheetStatsSubtitle)).setText(
                lastStats.rangeLabel == null || lastStats.rangeLabel.isEmpty()
                        ? getString(R.string.graph_range_loading)
                        : lastStats.rangeLabel
        );
        ((TextView) view.findViewById(R.id.textSheetInsight)).setText(lastStats.insightText);
        ((TextView) view.findViewById(R.id.textSheetStatMin)).setText(lastStats.minText);
        ((TextView) view.findViewById(R.id.textSheetStatAvg)).setText(lastStats.avgText);
        ((TextView) view.findViewById(R.id.textSheetStatMax)).setText(lastStats.maxText);

        TextView deltaView = view.findViewById(R.id.textSheetStatDelta);
        deltaView.setText(lastStats.deltaText);
        deltaView.setTextColor(colorForDelta(lastStats.deltaValue));

        ((TextView) view.findViewById(R.id.textSheetStatAmplitude)).setText(lastStats.amplitudeText);
        ((TextView) view.findViewById(R.id.textSheetStatFastDrop)).setText(lastStats.fastestDropText);
        ((TextView) view.findViewById(R.id.textSheetStatFastRise)).setText(lastStats.fastestRiseText);
        ((TextView) view.findViewById(R.id.textSheetStatStability)).setText(lastStats.stabilityText);
        ((TextView) view.findViewById(R.id.textSheetStatNoise)).setText(lastStats.noiseText);
        ((TextView) view.findViewById(R.id.textSheetStatPoints)).setText(lastStats.pointsText);
        ((TextView) view.findViewById(R.id.textSheetStatEvents)).setText(String.valueOf(lastStats.eventCount));
        ((TextView) view.findViewById(R.id.textSheetStatSimpleNotifications)).setText(String.valueOf(lastStats.simpleNotificationCount));
        ((TextView) view.findViewById(R.id.textSheetStatSmartNotifications)).setText(String.valueOf(lastStats.smartNotificationCount));
        ((TextView) view.findViewById(R.id.textSheetStatTrips)).setText(String.valueOf(lastStats.tripCount));
        ((TextView) view.findViewById(R.id.textSheetStatCalibrations)).setText(String.valueOf(lastStats.calibrationCount));
        ((TextView) view.findViewById(R.id.textSheetCompareSubtitle)).setText(lastStats.previousRangeHint);
        ((TextView) view.findViewById(R.id.textSheetCompareAvg)).setText(lastStats.compareAvgText);
        ((TextView) view.findViewById(R.id.textSheetCompareAmplitude)).setText(lastStats.compareAmplitudeText);
        ((TextView) view.findViewById(R.id.textSheetCompareEvents)).setText(lastStats.compareEventsText);

        View cardFastDrop = view.findViewById(R.id.cardFastDrop);
        View cardFastRise = view.findViewById(R.id.cardFastRise);
        cardFastDrop.setOnClickListener(v -> {
            if (lastStats.fastestDropTs > 0L) {
                AppEventLogger.log(this, "GRAPH", "jump to fastest drop ts=" + lastStats.fastestDropTs);
                dialog.dismiss();
                highlightTimestamp(lastStats.fastestDropTs, false);
            }
        });
        cardFastRise.setOnClickListener(v -> {
            if (lastStats.fastestRiseTs > 0L) {
                AppEventLogger.log(this, "GRAPH", "jump to fastest rise ts=" + lastStats.fastestRiseTs);
                dialog.dismiss();
                highlightTimestamp(lastStats.fastestRiseTs, false);
            }
        });

        dialog.show();
        configureBottomSheet(dialog);
    }

    private void showTimelineSheet() {
        AppEventLogger.log(this, "GRAPH", "build timeline sheet content");
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.sheet_graph_timeline, null, false);
        dialog.setContentView(view);

        ((TextView) view.findViewById(R.id.textTimelineTitle)).setText(getString(R.string.graph_timeline_title));
        ((TextView) view.findViewById(R.id.textTimelineSubtitle)).setText(
                lastStats.rangeLabel == null || lastStats.rangeLabel.isEmpty()
                        ? getString(R.string.graph_timeline_subtitle)
                        : "История важных событий за период: " + lastStats.rangeLabel
        );

        TextView empty = view.findViewById(R.id.textTimelineEmpty);
        LinearLayout container = view.findViewById(R.id.timelineContainer);
        container.removeAllViews();

        if (lastDisplayEvents == null || lastDisplayEvents.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
            String lastDayHeader = null;
            for (int idx = lastDisplayEvents.size() - 1; idx >= 0; idx--) {
                EventSample event = lastDisplayEvents.get(idx);
                String dayHeader = dayHeaderLabel(event.timestamp);
                if (!dayHeader.equals(lastDayHeader)) {
                    container.addView(createTimelineDayHeader(dayHeader));
                    lastDayHeader = dayHeader;
                }

                View row = LayoutInflater.from(this).inflate(R.layout.item_timeline_event, container, false);
                ((TextView) row.findViewById(R.id.textTimelineTime)).setText(DateFormat.format("HH:mm", event.timestamp));
                ((TextView) row.findViewById(R.id.textTimelineDate)).setText(DateFormat.format("d MMM", event.timestamp));
                ((TextView) row.findViewById(R.id.textTimelineEventTitle)).setText(timelineTitle(event));
                ((TextView) row.findViewById(R.id.textTimelineBadge)).setText(badgeForEvent(event));

                int accentColor = colorForEvent(event);
                View accentView = row.findViewById(R.id.viewTimelineAccent);
                accentView.setBackgroundTintList(ColorStateList.valueOf(accentColor));
                View iconContainer = row.findViewById(R.id.timelineIconContainer);
                iconContainer.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.layer(colorAttr(com.google.android.material.R.attr.colorSurface), accentColor, 0.16f)));
                ((ImageView) row.findViewById(R.id.imageTimelineEvent)).setImageResource(iconForEvent(event));
                ((ImageView) row.findViewById(R.id.imageTimelineEvent)).setImageTintList(ColorStateList.valueOf(accentColor));
                TextView badge = row.findViewById(R.id.textTimelineBadge);
                badge.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.layer(colorAttr(com.google.android.material.R.attr.colorSurface), accentColor, 0.12f)));
                badge.setTextColor(accentColor);

                Double y = valueAtSeries(lastSeries, event.timestamp);
                ((TextView) row.findViewById(R.id.textTimelineEventSubtitle)).setText(timelineSubtitle(event, y));

                row.setOnClickListener(v -> {
                    AppEventLogger.log(this, "GRAPH", "timeline event clicked=" + event.type);
                    dialog.dismiss();
                    highlightEvent(event);
                });
                container.addView(row);
            }
        }
        dialog.show();
        configureBottomSheet(dialog);
    }

    private void configureBottomSheet(BottomSheetDialog dialog) {
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet == null) return;
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
        behavior.setSkipCollapsed(true);
        behavior.setFitToContents(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    private void highlightEvent(EventSample event) {
        if (event == null) return;
        highlightTimestamp(event.timestamp, true);
    }

    private void highlightTimestamp(long timestamp, boolean preferEventDataset) {
        if (chart.getData() == null || baseTsMs <= 0L) return;
        Double y = valueAtSeries(lastSeries, timestamp);
        if (y == null) return;
        float xSec = (timestamp - baseTsMs) / 1000f;
        chart.highlightValue(xSec, (float) y.doubleValue(), preferEventDataset ? 1 : 0);
        chart.centerViewToAnimated(xSec, (float) y.doubleValue(), YAxis.AxisDependency.LEFT, 250L);
        chart.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private static String formatValue(double v, Units.System sys, Mode mode, boolean signed) {
        if (mode == Mode.PRESSURE) {
            // store is hPa
            double hpa = v;
            String fmt;
            switch (sys) {
                case IMPERIAL: fmt = signed ? "%+.2f inHg" : "%.2f inHg"; return String.format(Locale.getDefault(), fmt, hpa * 0.0295299830714);
                case MMHG:     fmt = signed ? "%+.1f mmHg" : "%.1f mmHg"; return String.format(Locale.getDefault(), fmt, hpa * 0.750061683);
                case METRIC:
                default:       fmt = signed ? "%+.1f hPa" : "%.1f hPa";   return String.format(Locale.getDefault(), fmt, hpa);
            }
        } else {
            double meters = v;
            if (sys == Units.System.IMPERIAL) {
                String fmt = signed ? "%+.0f ft" : "%.0f ft";
                return String.format(Locale.getDefault(), fmt, meters * 3.28084);
            }
            String fmt = signed ? "%+.0f м" : "%.0f м";
            return String.format(Locale.getDefault(), fmt, meters);
        }
    }

    private static class GraphMarker extends MarkerView {
        private final TextView tv;
        private final long baseTsMs;
        private final Units.System sys;
        private final Mode mode;
        private final List<Point> series;
        private final List<EventSample> events;
        private final long bucketMs;
        private final int eventDataSetIndex;

        GraphMarker(@NonNull android.content.Context ctx,
                    int layoutRes,
                    long baseTsMs,
                    Units.System sys,
                    Mode mode,
                    List<Point> series,
                    List<EventSample> events,
                    long bucketMs,
                    int eventDataSetIndex) {
            super(ctx, layoutRes);
            this.baseTsMs = baseTsMs;
            this.sys = sys;
            this.mode = mode;
            this.series = series != null ? series : Collections.emptyList();
            this.events = events != null ? events : Collections.emptyList();
            this.bucketMs = bucketMs;
            this.eventDataSetIndex = eventDataSetIndex;
            this.tv = findViewById(R.id.tvContent);
        }

        @Override
        public void refreshContent(Entry e, Highlight highlight) {
            long ts = baseTsMs + (long) (e.getX() * 1000L);
            String time = DateFormat.format("dd MMM HH:mm", ts).toString();

            Double vNow = valueAt(ts);
            Double d10 = delta(ts, 10 * 60_000L);
            Double d1h = delta(ts, 60 * 60_000L);

            String vStr = vNow == null ? "—" : formatValue(vNow, sys, mode, false);
            String d10Str = d10 == null ? "—" : formatValue(d10, sys, mode, true);
            String d1hStr = d1h == null ? "—" : formatValue(d1h, sys, mode, true);

            boolean isEventPoint = (highlight != null && highlight.getDataSetIndex() == eventDataSetIndex);
            long tolerance = Math.min(bucketMs / 2, 2 * 60_000L);
            String ev = isEventPoint ? nearestEventLabel(ts, tolerance) : null;

            String d10Label = getContext().getString(R.string.graph_delta_10m);
            String d1hLabel = getContext().getString(R.string.graph_delta_1h);

            String txt;
            if (ev != null) {
                txt = vStr + "\n" + time + "\n" + d10Label + ": " + d10Str + "\n" + d1hLabel + ": " + d1hStr + "\n" + ev;
            } else {
                txt = vStr + "\n" + time + "\n" + d10Label + ": " + d10Str + "\n" + d1hLabel + ": " + d1hStr;
            }
            tv.setText(txt);
            super.refreshContent(e, highlight);
        }

        private String nearestEventLabel(long ts, long windowMs) {
            EventSample best = null;
            long bestDist = Long.MAX_VALUE;
            for (EventSample e : events) {
                long d = Math.abs(e.timestamp - ts);
                if (d <= windowMs && d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
            if (best == null) return null;
            // Delegate string building to activity resources via context
            if (getContext() instanceof GraphActivity) {
                GraphActivity a = (GraphActivity) getContext();
                return getContext().getString(R.string.graph_marker_event, a.labelForEventLong(best));
            }
            return getContext().getString(R.string.graph_marker_event, best.type);
        }

        private Double valueAt(long ts) {
            if (series.isEmpty()) return null;
            // manual binary search (series must be sorted by time)
            int lo = 0, hi = series.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                long t = series.get(mid).t;
                if (t < ts) lo = mid + 1;
                else if (t > ts) hi = mid - 1;
                else return series.get(mid).yShown;
            }
            int i = Math.max(0, Math.min(series.size() - 1, lo));
            return series.get(i).yShown;
        }

        private Double delta(long ts, long backMs) {
            Double now = valueAt(ts);
            Double prev = valueAt(ts - backMs);
            if (now == null || prev == null) return null;
            return now - prev;
        }

        @Override
        public com.github.mikephil.charting.utils.MPPointF getOffset() {
            return new com.github.mikephil.charting.utils.MPPointF(-(getWidth() / 2f), -getHeight() - 12f);
        }
    }
}
