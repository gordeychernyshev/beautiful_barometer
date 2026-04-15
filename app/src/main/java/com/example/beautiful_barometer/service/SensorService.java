// app/src/main/java/com/example/beautiful_barometer/service/SensorService.java
package com.example.beautiful_barometer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.preference.PreferenceManager;

import com.example.beautiful_barometer.R;
import com.example.beautiful_barometer.data.AppDatabase;
import com.example.beautiful_barometer.data.EventDao;
import com.example.beautiful_barometer.data.EventSample;
import com.example.beautiful_barometer.data.PressureDao;
import com.example.beautiful_barometer.data.PressureSample;
import com.example.beautiful_barometer.feedback.AppEventLogger;
import com.example.beautiful_barometer.notifications.PressureNotificationDecision;
import com.example.beautiful_barometer.notifications.PressureNotificationEngine;
import com.example.beautiful_barometer.notifications.PressureNotificationPrefs;
import com.example.beautiful_barometer.notifications.PressureNotificationStateStore;
import com.example.beautiful_barometer.ui.forecast.ForecastResult;
import com.example.beautiful_barometer.ui.forecast.ForecastEngine;
import com.example.beautiful_barometer.util.AdaptiveRecordingPolicy;
import com.example.beautiful_barometer.trip.TripEndSuggestionEngine;
import com.example.beautiful_barometer.trip.TripSuggestionEngine;
import com.example.beautiful_barometer.trip.TripSuggestionPrefs;
import com.example.beautiful_barometer.util.ServiceController;
import com.example.beautiful_barometer.util.Units;
import com.example.beautiful_barometer.util.TripModeController;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SensorService extends Service implements SensorEventListener {

    private static final class AdaptivePoint {
        final long timestamp;
        final double hpa;

        AdaptivePoint(long timestamp, double hpa) {
            this.timestamp = timestamp;
            this.hpa = hpa;
        }
    }


    public static final String CHANNEL_ID = "barometer_channel";
    private static final String ALERTS_CHANNEL_ID = "barometer_alerts";

    public static final String ACTION_SAMPLE_BROADCAST = "com.example.beautiful_barometer.SAMPLE";
    public static final String ACTION_STOP_RECORDING = "com.example.beautiful_barometer.action.STOP_RECORDING";
    public static final String ACTION_ENABLE_TRIP_MODE = "com.example.beautiful_barometer.action.ENABLE_TRIP_MODE";
    public static final String ACTION_DISABLE_TRIP_MODE = "com.example.beautiful_barometer.action.DISABLE_TRIP_MODE";
    public static final String ACTION_DISMISS_TRIP_SUGGESTION = "com.example.beautiful_barometer.action.DISMISS_TRIP_SUGGESTION";
    public static final String EXTRA_HPA = "extra_hpa";
    public static final int NOTIF_ID = 101;

    private SensorManager sensorManager;
    private Sensor pressureSensor;

    private volatile double lastHpa = Double.NaN;
    private Handler handler;
    private Runnable sampler;

    private SharedPreferences prefs;
    private PressureDao dao;
    private EventDao eventDao;
    private ExecutorService io;

    private volatile String notifTrendsLine = "—";
    private long lastTrendCalcAt = 0L;
    private static final long TREND_REFRESH_MS = 60_000L;

    private long lastNotificationEvalAt = 0L;
    private static final long NOTIFICATION_EVAL_REFRESH_MS = 5 * 60_000L;
    private static final int TRIP_SUGGESTION_NOTIFICATION_ID = 1003;

    private long lastTripSuggestionEvalAt = 0L;
    private static final long TRIP_SUGGESTION_EVAL_REFRESH_MS = 5 * 60_000L;

    private final ArrayDeque<AdaptivePoint> adaptiveWindow = new ArrayDeque<>();
    private volatile long lastSavedAt = 0L;
    private volatile double lastSavedHpa = Double.NaN;
    private volatile long pendingSavedAt = 0L;
    private volatile double pendingSavedHpa = Double.NaN;
    private AdaptiveRecordingPolicy.Mode adaptiveMode = AdaptiveRecordingPolicy.Mode.NORMAL;

    private void executeIoSafely(String tag, Runnable task) {
        ExecutorService executor = io;
        if (executor == null || executor.isShutdown()) {
            AppEventLogger.log(this, tag, "Skipped async task because executor is unavailable");
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    AppEventLogger.log(this, tag, "Async task failed: "
                            + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
                }
            });
        } catch (Exception e) {
            AppEventLogger.log(this, tag, "Failed to schedule async task: "
                    + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }


    private void startAsForeground(Notification notification) {
        ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        );
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        AppEventLogger.log(this, "SERVICE", "SensorService onCreate");
        dao = AppDatabase.get(this).pressureDao();
        eventDao = AppDatabase.get(this).eventDao();
        io = Executors.newSingleThreadExecutor();

        createChannel();
        createAlertsChannel();

        if (!ServiceController.isRecordingEnabled(this)) {
            ServiceController.setServiceRunning(this, false);
            PressureNotificationStateStore.clearTransientState(this);
            AppEventLogger.log(this, "SERVICE", "Stop self because recording disabled");
            stopSelf();
            return;
        }

        ServiceController.setServiceRunning(this, true);
        syncAdaptiveModePreference();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        pressureSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) : null;
        if (pressureSensor != null && sensorManager != null) {
            boolean registered = sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
            if (!registered) {
                AppEventLogger.log(this, "SERVICE", "Failed to register pressure sensor listener");
            }
        }

        if (pressureSensor == null) {
            startAsForeground(buildNotification(getString(R.string.no_barometer_sensor), "—"));
            ServiceController.setServiceRunning(this, false);
            AppEventLogger.log(this, "SERVICE", "No barometer sensor, stopping");
            stopSelf();
            return;
        }

        startAsForeground(buildNotification("--", "—"));

        executeIoSafely("SERVICE", () -> eventDao.insert(new EventSample("SERVICE_START", System.currentTimeMillis(), null)));

        handler = new Handler(getMainLooper());
        sampler = new Runnable() {
            @Override
            public void run() {
                long interval = getIntervalMs();

                if (!Double.isNaN(lastHpa)) {
                    final double hpa = lastHpa;
                    final long now = System.currentTimeMillis();

                    updateAdaptiveState(now, hpa);
                    if (shouldPersistSample(now, hpa)) {
                        persistSample(now, hpa);
                    }

                    maybeUpdateTrendsAsync();

                    Units.System sys = Units.getSystem(prefs);
                    String line1 = Units.formatPressure(hpa, sys);
                    Notification n = buildNotification(line1, notifTrendsLine);
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.notify(NOTIF_ID, n);
                    }

                    Intent i = new Intent(ACTION_SAMPLE_BROADCAST);
                    i.setPackage(getPackageName());
                    i.putExtra(EXTRA_HPA, hpa);
                    sendBroadcast(i);

                    maybeEvaluateNotificationsAsync();
                    maybeEvaluateTripSuggestionAsync();
                }

                if (handler != null) {
                    handler.postDelayed(this, interval);
                }
            }
        };
        handler.postDelayed(sampler, getIntervalMs());
    }

    private boolean isAdaptiveRecordingEnabled() {
        return ServiceController.isAdaptiveRecordingEnabled(this);
    }

    private void syncAdaptiveModePreference() {
        if (!ServiceController.isRecordingEnabled(this)) {
            setAdaptiveMode(AdaptiveRecordingPolicy.Mode.STOPPED);
            return;
        }
        if (!isAdaptiveRecordingEnabled()) {
            setAdaptiveMode(AdaptiveRecordingPolicy.Mode.FIXED);
            return;
        }
        if (adaptiveMode == AdaptiveRecordingPolicy.Mode.FIXED
                || adaptiveMode == AdaptiveRecordingPolicy.Mode.STOPPED) {
            adaptiveMode = AdaptiveRecordingPolicy.Mode.NORMAL;
        }
        setAdaptiveMode(adaptiveMode);
    }

    private void setAdaptiveMode(AdaptiveRecordingPolicy.Mode mode) {
        adaptiveMode = mode;
        ServiceController.setAdaptiveMode(this, mode.getPrefValue());
    }

    private void updateAdaptiveState(long now, double hpa) {
        if (!isAdaptiveRecordingEnabled()) {
            if (adaptiveMode != AdaptiveRecordingPolicy.Mode.FIXED) {
                setAdaptiveMode(AdaptiveRecordingPolicy.Mode.FIXED);
            }
            adaptiveWindow.clear();
            return;
        }

        adaptiveWindow.addLast(new AdaptivePoint(now, hpa));
        long cutoff = now - AdaptiveRecordingPolicy.getWindowMs();
        while (!adaptiveWindow.isEmpty() && adaptiveWindow.peekFirst().timestamp < cutoff) {
            adaptiveWindow.removeFirst();
        }

        double windowDelta = 0.0;
        AdaptivePoint first = adaptiveWindow.peekFirst();
        if (first != null) {
            windowDelta = Math.abs(hpa - first.hpa);
        }

        AdaptiveRecordingPolicy.Mode nextMode = AdaptiveRecordingPolicy.resolveMode(adaptiveMode, windowDelta);
        if (nextMode != adaptiveMode) {
            setAdaptiveMode(nextMode);
            return;
        }

        String persistedMode = ServiceController.getAdaptiveMode(this);
        if (!adaptiveMode.getPrefValue().equals(persistedMode)) {
            ServiceController.setAdaptiveMode(this, adaptiveMode.getPrefValue());
        }
    }

    private boolean shouldPersistSample(long now, double hpa) {
        long effectiveLastSavedAt = pendingSavedAt > 0L ? pendingSavedAt : lastSavedAt;
        double effectiveLastSavedHpa = !Double.isNaN(pendingSavedHpa) ? pendingSavedHpa : lastSavedHpa;

        if (effectiveLastSavedAt <= 0L || Double.isNaN(effectiveLastSavedHpa)) {
            return true;
        }

        if (!isAdaptiveRecordingEnabled()) {
            return true;
        }

        long elapsed = now - effectiveLastSavedAt;
        if (elapsed >= AdaptiveRecordingPolicy.getMinSaveIntervalMs(adaptiveMode)) {
            return true;
        }

        double delta = Math.abs(hpa - effectiveLastSavedHpa);
        return delta >= AdaptiveRecordingPolicy.getMinDeltaHpa(adaptiveMode);
    }

    private void persistSample(long now, double hpa) {
        ExecutorService executor = io;
        if (executor == null || executor.isShutdown()) {
            AppEventLogger.log(this, "DB", "Skipped sample persist because executor is unavailable");
            return;
        }

        pendingSavedAt = now;
        pendingSavedHpa = hpa;

        try {
            executor.execute(() -> {
                try {
                    PressureSample s = new PressureSample();
                    s.timestamp = now;
                    s.pressureHpa = hpa;
                    dao.insert(s);
                    int keep = getHistorySize();
                    if (keep > 0) {
                        dao.trimTo(keep);
                    } else {
                        dao.deleteAll();
                    }
                    lastSavedAt = now;
                    lastSavedHpa = hpa;
                } catch (Exception e) {
                    AppEventLogger.log(this, "DB", "Persist sample failed: "
                            + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
                } finally {
                    if (pendingSavedAt == now) {
                        pendingSavedAt = 0L;
                        pendingSavedHpa = Double.NaN;
                    }
                }
            });
        } catch (Exception e) {
            pendingSavedAt = 0L;
            pendingSavedHpa = Double.NaN;
            AppEventLogger.log(this, "DB", "Failed to schedule sample persist: "
                    + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }

    private long getIntervalMs() {
        String v = prefs.getString("pref_interval_ms", "1000");
        try {
            return Long.parseLong(v);
        } catch (Exception e) {
            return 1000;
        }
    }

    private int getHistorySize() {
        String v = prefs.getString("pref_history_size", "10000");
        try {
            return Math.max(1, Integer.parseInt(v));
        } catch (Exception e) {
            return 10000;
        }
    }

    private void maybeEvaluateNotificationsAsync() {
        long now = System.currentTimeMillis();
        if (now - lastNotificationEvalAt < NOTIFICATION_EVAL_REFRESH_MS) {
            return;
        }
        lastNotificationEvalAt = now;

        if (!PressureNotificationPrefs.areAnyNotificationsEnabled(this)) {
            return;
        }

        executeIoSafely("NOTIFY", () -> {
            long evalNow = System.currentTimeMillis();
            if (TripModeController.isTripModeEnabled(this)) {
                PressureNotificationStateStore.clearTransientState(this);
                return;
            }

            long baselineMs = TripModeController.getForecastBaselineTimestamp(this);
            Units.System units = Units.getSystem(PreferenceManager.getDefaultSharedPreferences(this));
            ForecastResult result = ForecastEngine.buildResult(dao, evalNow, baselineMs, units);
            PressureNotificationDecision decision = PressureNotificationEngine.evaluate(this, result, evalNow);
            if (decision.shouldNotify) {
                AppEventLogger.log(this, "NOTIFY", "Forecast notification: " + decision.type.name());
                notifyForecastDecision(decision);
            }
        });
    }

    private void maybeEvaluateTripSuggestionAsync() {
        long now = System.currentTimeMillis();
        if (now - lastTripSuggestionEvalAt < TRIP_SUGGESTION_EVAL_REFRESH_MS) {
            return;
        }
        lastTripSuggestionEvalAt = now;

        if (!TripSuggestionPrefs.isEnabled(this)) {
            cancelTripSuggestionNotification();
            return;
        }

        executeIoSafely("TRIP", () -> {
            long evalNow = System.currentTimeMillis();
            if (!TripSuggestionPrefs.canSuggestNow(this, evalNow)) {
                return;
            }

            if (TripModeController.isTripModeEnabled(this)) {
                long from = Math.max(0L, evalNow - TripEndSuggestionEngine.WINDOW_MS);
                java.util.List<PressureSample> samples = dao.betweenAsc(from, evalNow);
                TripEndSuggestionEngine.Result result = TripEndSuggestionEngine.evaluate(samples);
                if (!result.shouldSuggest) {
                    cancelTripSuggestionNotification();
                    return;
                }
                TripSuggestionPrefs.markShown(this, evalNow);
                AppEventLogger.log(this, "TRIP", "Suggested disabling trip mode");
                notifyTripModeDisableSuggestion();
                return;
            }

            long from = Math.max(0L, evalNow - TripSuggestionEngine.WINDOW_MS);
            long baselineMs = TripModeController.getForecastBaselineTimestamp(this);
            if (baselineMs > 0L) {
                from = Math.max(from, baselineMs);
            }

            java.util.List<PressureSample> samples = dao.betweenAsc(from, evalNow);
            TripSuggestionEngine.Result result = TripSuggestionEngine.evaluate(samples);
            if (!result.shouldSuggest) {
                cancelTripSuggestionNotification();
                return;
            }

            TripSuggestionPrefs.markShown(this, evalNow);
            AppEventLogger.log(this, "TRIP", "Suggested enabling trip mode");
            notifyTripModeSuggestion();
        });
    }

    private void notifyTripModeSuggestion() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_pressure)
                .setContentTitle(getString(R.string.trip_suggestion_title))
                .setContentText(getString(R.string.trip_suggestion_body))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(getString(R.string.trip_suggestion_body)))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(mainContentPendingIntent());

        builder.addAction(new NotificationCompat.Action(0, getString(R.string.trip_suggestion_action_enable), enableTripModePendingIntent()));
        builder.addAction(new NotificationCompat.Action(0, getString(R.string.trip_suggestion_action_dismiss), dismissTripSuggestionPendingIntent()));

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(TRIP_SUGGESTION_NOTIFICATION_ID, builder.build());
        }
    }

    private void notifyTripModeDisableSuggestion() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_pressure)
                .setContentTitle(getString(R.string.trip_end_suggestion_title))
                .setContentText(getString(R.string.trip_end_suggestion_body))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(getString(R.string.trip_end_suggestion_body)))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(mainContentPendingIntent());

        builder.addAction(new NotificationCompat.Action(0, getString(R.string.trip_end_suggestion_action_disable), disableTripModePendingIntent()));
        builder.addAction(new NotificationCompat.Action(0, getString(R.string.trip_suggestion_action_dismiss), dismissTripSuggestionPendingIntent()));

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(TRIP_SUGGESTION_NOTIFICATION_ID, builder.build());
        }
    }

    private void cancelTripSuggestionNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(TRIP_SUGGESTION_NOTIFICATION_ID);
        }
    }

    private void enableTripModeFromSuggestion() {
        TripModeController.setTripModeEnabled(this, true);
        AppEventLogger.log(this, "TRIP", "Trip mode enabled from suggestion");
        PressureNotificationStateStore.clearTransientState(this);
        cancelTripSuggestionNotification();
        executeIoSafely("TRIP", () -> eventDao.insert(new EventSample("TRIP_MODE_ON", System.currentTimeMillis(), "suggested")));
    }

    private void disableTripModeFromSuggestion() {
        TripModeController.setTripModeEnabled(this, false);
        AppEventLogger.log(this, "TRIP", "Trip mode disabled from suggestion");
        PressureNotificationStateStore.clearTransientState(this);
        cancelTripSuggestionNotification();
        executeIoSafely("TRIP", () -> eventDao.insert(new EventSample("TRIP_MODE_OFF", System.currentTimeMillis(), "suggested")));
    }

    private void dismissTripSuggestion() {
        long now = System.currentTimeMillis();
        AppEventLogger.log(this, "TRIP", "Trip suggestion dismissed");
        TripSuggestionPrefs.markDismissed(this, now);
        cancelTripSuggestionNotification();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription(getString(R.string.notification_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void createAlertsChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    ALERTS_CHANNEL_ID,
                    getString(R.string.notifications_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            ch.setDescription(getString(R.string.notifications_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private int piFlagsUpdateImmutable() {
        return Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private PendingIntent mainContentPendingIntent() {
        Intent i = new Intent(this, com.example.beautiful_barometer.ui.MainActivity.class);
        return TaskStackBuilder.create(this)
                .addNextIntentWithParentStack(i)
                .getPendingIntent(100, piFlagsUpdateImmutable());
    }

    private PendingIntent graphPendingIntent() {
        Intent i = new Intent(this, com.example.beautiful_barometer.ui.GraphActivity.class);
        return TaskStackBuilder.create(this)
                .addNextIntentWithParentStack(i)
                .getPendingIntent(101, piFlagsUpdateImmutable());
    }

    private PendingIntent stopPendingIntent() {
        Intent i = new Intent(this, SensorService.class).setAction(ACTION_STOP_RECORDING);
        return PendingIntent.getService(this, 102, i, piFlagsUpdateImmutable());
    }

    private PendingIntent enableTripModePendingIntent() {
        Intent i = new Intent(this, SensorService.class).setAction(ACTION_ENABLE_TRIP_MODE);
        return PendingIntent.getService(this, 103, i, piFlagsUpdateImmutable());
    }

    private PendingIntent disableTripModePendingIntent() {
        Intent i = new Intent(this, SensorService.class).setAction(ACTION_DISABLE_TRIP_MODE);
        return PendingIntent.getService(this, 104, i, piFlagsUpdateImmutable());
    }

    private PendingIntent dismissTripSuggestionPendingIntent() {
        Intent i = new Intent(this, SensorService.class).setAction(ACTION_DISMISS_TRIP_SUGGESTION);
        return PendingIntent.getService(this, 105, i, piFlagsUpdateImmutable());
    }

    private Notification buildNotification(String line1, String line2) {
        NotificationCompat.BigTextStyle style = new NotificationCompat.BigTextStyle()
                .bigText(line1 + "\n" + line2)
                .setSummaryText(line2);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_pressure)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(line1)
                .setSubText(line2)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(mainContentPendingIntent());

        b.addAction(new NotificationCompat.Action(0, getString(R.string.btn_graph), graphPendingIntent()));
        b.addAction(new NotificationCompat.Action(0, getString(R.string.btn_pause_recording), stopPendingIntent()));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void maybeUpdateTrendsAsync() {
        long now = System.currentTimeMillis();
        if (now - lastTrendCalcAt < TREND_REFRESH_MS) {
            return;
        }
        lastTrendCalcAt = now;

        final Units.System sys = Units.getSystem(prefs);
        executeIoSafely("TRENDS", () -> {
            long w3 = 3L * 3600_000L;
            long w24 = 24L * 3600_000L;

            Double f3 = dao.firstHpaIn(now - w3, now);
            Double l3 = dao.lastHpaIn(now - w3, now);
            Double f24 = dao.firstHpaIn(now - w24, now);
            Double l24 = dao.lastHpaIn(now - w24, now);

            String d3 = formatDelta(sys, f3, l3);
            String d24 = formatDelta(sys, f24, l24);

            notifTrendsLine = getString(R.string.trend_3h) + ": " + d3 + " • "
                    + getString(R.string.trend_24h) + ": " + d24;

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && !Double.isNaN(lastHpa)) {
                String main = Units.formatPressure(lastHpa, sys);
                nm.notify(NOTIF_ID, buildNotification(main, notifTrendsLine));
            }
        });
    }

    private String formatDelta(Units.System sys, @Nullable Double first, @Nullable Double last) {
        if (first == null || last == null) {
            return "—";
        }
        double deltaHpa = last - first;
        return Units.formatPressureDelta(deltaHpa, sys);
    }

    private void notifyForecastDecision(PressureNotificationDecision decision) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_pressure)
                .setContentTitle(decision.title)
                .setContentText(decision.body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(decision.body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(mainContentPendingIntent());

        builder.addAction(new NotificationCompat.Action(0, getString(R.string.btn_graph), graphPendingIntent()));

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(1002, builder.build());
        }

        executeIoSafely("NOTIFY", () -> eventDao.insert(new EventSample(
                "NOTIFICATION_" + decision.type.name(),
                System.currentTimeMillis(),
                decision.title
        )));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP_RECORDING.equals(action)) {
                ServiceController.stopRecording(this);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_ENABLE_TRIP_MODE.equals(action)) {
                enableTripModeFromSuggestion();
                return START_STICKY;
            }
            if (ACTION_DISABLE_TRIP_MODE.equals(action)) {
                disableTripModeFromSuggestion();
                return START_STICKY;
            }
            if (ACTION_DISMISS_TRIP_SUGGESTION.equals(action)) {
                dismissTripSuggestion();
                return START_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        AppEventLogger.log(this, "SERVICE", "SensorService onDestroy");
        super.onDestroy();
        ServiceController.setServiceRunning(this, false);
        setAdaptiveMode(AdaptiveRecordingPolicy.Mode.STOPPED);
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (handler != null) {
            handler.removeCallbacks(sampler);
        }
        if (io != null) {
            io.shutdown();
        }
        PressureNotificationStateStore.clearTransientState(this);
        cancelTripSuggestionNotification();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            lastHpa = event.values[0];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // no-op
    }
}
