package com.example.beautiful_barometer.feedback;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.preference.PreferenceManager;

import com.example.beautiful_barometer.R;
import com.example.beautiful_barometer.data.AppDatabase;
import com.example.beautiful_barometer.data.EventSample;
import com.example.beautiful_barometer.data.PressureSample;
import com.example.beautiful_barometer.notifications.PressureNotificationPrefs;
import com.example.beautiful_barometer.util.ServiceController;
import com.example.beautiful_barometer.util.ThemeController;
import com.example.beautiful_barometer.util.TripModeController;
import com.example.beautiful_barometer.util.Units;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class IssueReportManager {

    private static final String CRASH_FILE_NAME = "pending_crash_report.txt";
    private static final String REPORTS_DIR_NAME = "reports";
    public static final String PREF_LAST_SCREEN = "pref_debug_last_screen";
    private static boolean crashPromptShownThisProcess = false;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private IssueReportManager() {
    }

    private static boolean isActivityAlive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void runOnUiThreadIfAlive(Activity activity, Runnable action) {
        if (!isActivityAlive(activity)) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (isActivityAlive(activity)) {
                action.run();
            }
        });
    }

    public static void installCrashHandler(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writePendingCrash(appContext, thread, throwable);
            } catch (Exception e) {
                AppEventLogger.log(appContext, "REPORT", "write pending crash failed: " + e.getClass().getSimpleName());
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                System.exit(10);
            }
        });
    }

    public static void markScreenVisible(Context context, String screenName) {
        if (context == null || screenName == null) return;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        sp.edit().putString(PREF_LAST_SCREEN, screenName).apply();
        AppEventLogger.log(context, "SCREEN", screenName);
    }

    public static void sendManualReport(Activity activity) {
        if (activity == null) return;
        Context appContext = activity.getApplicationContext();
        Toast.makeText(activity, R.string.report_problem_preparing, Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            try {
                File report = createReportFile(appContext, false);
                runOnUiThreadIfAlive(activity, () -> openEmailIntent(activity, report, false));
            } catch (Exception e) {
                AppEventLogger.log(appContext, "REPORT", "manual report failed: " + e.getClass().getSimpleName());
                runOnUiThreadIfAlive(activity, () -> Toast.makeText(activity, R.string.report_problem_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    public static void maybePromptToSendPendingCrash(Activity activity) {
        if (activity == null || crashPromptShownThisProcess) return;
        File pending = getPendingCrashFile(activity);
        if (!pending.exists()) return;
        crashPromptShownThisProcess = true;

        new AlertDialog.Builder(activity)
                .setTitle(R.string.crash_report_dialog_title)
                .setMessage(R.string.crash_report_dialog_message)
                .setNegativeButton(R.string.crash_report_dialog_delete, (d, w) -> clearPendingCrash(activity))
                .setNeutralButton(R.string.crash_report_dialog_later, null)
                .setPositiveButton(R.string.crash_report_dialog_send, (d, w) -> sendPendingCrashReport(activity))
                .show();
    }

    private static void sendPendingCrashReport(Activity activity) {
        Context appContext = activity.getApplicationContext();
        IO.execute(() -> {
            try {
                File report = createReportFile(appContext, true);
                runOnUiThreadIfAlive(activity, () -> {
                    openEmailIntent(activity, report, true);
                    clearPendingCrash(activity);
                });
            } catch (Exception e) {
                AppEventLogger.log(appContext, "REPORT", "pending crash report failed: " + e.getClass().getSimpleName());
                runOnUiThreadIfAlive(activity, () -> Toast.makeText(activity, R.string.report_problem_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void openEmailIntent(Activity activity, File attachment, boolean crashReport) {
        Uri uri = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                attachment
        );

        String subject = crashReport
                ? activity.getString(R.string.crash_report_email_subject)
                : activity.getString(R.string.report_problem_email_subject);

        String body = crashReport
                ? activity.getString(R.string.crash_report_email_body)
                : activity.getString(R.string.report_problem_email_body);

        PackageManager pm = activity.getPackageManager();
        Intent emailProbe = new Intent(Intent.ACTION_SENDTO);
        emailProbe.setData(Uri.parse("mailto:"));
        List<ResolveInfo> emailApps = pm.queryIntentActivities(emailProbe, 0);

        ArrayList<Intent> targeted = new ArrayList<>();
        if (emailApps != null) {
            for (ResolveInfo info : emailApps) {
                if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
                String packageName = info.activityInfo.packageName;
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setPackage(packageName);
                intent.setType("message/rfc822");
                intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"gordey.chernyshe@gmail.com"});
                intent.putExtra(Intent.EXTRA_SUBJECT, subject);
                intent.putExtra(Intent.EXTRA_TEXT, body);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.setClipData(ClipData.newRawUri("barometer_report", uri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (intent.resolveActivity(pm) != null) {
                    targeted.add(intent);
                }
            }
        }

        if (!targeted.isEmpty()) {
            try {
                AppEventLogger.log(activity, "REPORT", (crashReport ? "open crash email intent" : "open manual email intent") + "; file=" + attachment.getName() + "; targets=" + targeted.size());
                Intent chooser = Intent.createChooser(targeted.remove(0), activity.getString(R.string.report_problem_chooser_title));
                if (!targeted.isEmpty()) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, targeted.toArray(new Intent[0]));
                }
                activity.startActivity(chooser);
                return;
            } catch (ActivityNotFoundException e) {
                AppEventLogger.log(activity, "REPORT", "chooser open failed: " + e.getClass().getSimpleName());
            }
        }

        Intent fallback = new Intent(Intent.ACTION_SEND);
        fallback.setType("message/rfc822");
        fallback.putExtra(Intent.EXTRA_EMAIL, new String[]{"gordey.chernyshe@gmail.com"});
        fallback.putExtra(Intent.EXTRA_SUBJECT, subject);
        fallback.putExtra(Intent.EXTRA_TEXT, body);
        fallback.putExtra(Intent.EXTRA_STREAM, uri);
        fallback.setClipData(ClipData.newRawUri("barometer_report", uri));
        fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (fallback.resolveActivity(pm) != null) {
            try {
                AppEventLogger.log(activity, "REPORT", (crashReport ? "open crash email fallback" : "open manual email fallback") + "; file=" + attachment.getName());
                activity.startActivity(Intent.createChooser(fallback, activity.getString(R.string.report_problem_chooser_title)));
                return;
            } catch (ActivityNotFoundException e) {
                AppEventLogger.log(activity, "REPORT", "chooser open failed: " + e.getClass().getSimpleName());
            }
        }

        AppEventLogger.log(activity, "REPORT", "no email app available");
        Toast.makeText(activity, R.string.report_problem_no_email_app, Toast.LENGTH_LONG).show();
    }

    private static File createReportFile(Context context, boolean includePendingCrash) throws IOException {
        File reportsDir = new File(context.getCacheDir(), REPORTS_DIR_NAME);
        if (!reportsDir.exists() && !reportsDir.mkdirs()) {
            throw new IOException("Cannot create reports dir");
        }
        String suffix = includePendingCrash ? "crash" : "manual";
        String timestamp = DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis()).toString();
        File out = new File(reportsDir, "barometer_report_" + suffix + "_" + timestamp + ".txt");
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(out, false), StandardCharsets.UTF_8))) {
            writer.write(buildReportText(context, includePendingCrash));
        }
        AppEventLogger.log(context, "REPORT", "report file created=" + out.getName() + "; crash=" + includePendingCrash);
        return out;
    }

    private static String buildReportText(Context context, boolean includePendingCrash) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        StringBuilder sb = new StringBuilder();
        sb.append("BAROMETER REPORT\n");
        sb.append("Generated: ").append(DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())).append("\n\n");

        appendAppInfo(context, sb);
        appendDeviceInfo(context, sb);
        appendFeatureState(context, sp, sb);
        appendDatabaseInfo(context, sb);
        appendRecentLogs(context, sb);
        if (includePendingCrash) {
            appendPendingCrash(context, sb);
        }
        return sb.toString();
    }

    private static void appendAppInfo(Context context, StringBuilder sb) {
        sb.append("[App]\n");
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            sb.append("Package: ").append(info.packageName).append("\n");
            sb.append("Version: ").append(info.versionName).append(" (").append(PackageInfoCompat.getLongVersionCode(info)).append(")\n");
        } catch (PackageManager.NameNotFoundException e) {
            sb.append("Version: unknown\n");
        }
        sb.append("\n");
    }

    private static void appendDeviceInfo(Context context, StringBuilder sb) {
        sb.append("[Device]\n");
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("Device: ").append(Build.DEVICE).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor barometer = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) : null;
        sb.append("Barometer sensor: ").append(barometer != null ? "yes" : "no").append("\n");
        sb.append("\n");
    }

    private static void appendFeatureState(Context context, SharedPreferences sp, StringBuilder sb) {
        sb.append("[State]\n");
        sb.append("Last screen: ").append(sp.getString(PREF_LAST_SCREEN, "unknown")).append("\n");
        sb.append("Theme: ").append(ThemeController.getThemeMode(context)).append("\n");
        sb.append("Units: ").append(sp.getString("pref_units", "metric")).append("\n");
        sb.append("Recording enabled: ").append(ServiceController.isRecordingEnabled(context)).append("\n");
        sb.append("Service running: ").append(ServiceController.isServiceRunning(context)).append("\n");
        sb.append("Adaptive recording: ").append(ServiceController.isAdaptiveRecordingEnabled(context)).append("\n");
        sb.append("Trip mode: ").append(TripModeController.isTripModeEnabled(context)).append("\n");
        sb.append("Forecast baseline: ").append(TripModeController.getForecastBaselineTimestamp(context)).append("\n");
        sb.append("Notification mode: ").append(PressureNotificationPrefs.getNotificationMode(context)).append("\n");
        sb.append("Notification sensitivity: ").append(sp.getString(PressureNotificationPrefs.PREF_NOTIFICATION_SENSITIVITY, "medium")).append("\n");
        sb.append("Silent mode kind: ").append(PressureNotificationPrefs.getSilentModeKind(context)).append("\n");
        sb.append("Silent mode manual: ").append(sp.getBoolean(PressureNotificationPrefs.PREF_SILENT_MODE_ENABLED, false)).append("\n");
        sb.append("Graph range: ").append(sp.getLong("pref_graph_range_ms", 24L * 60L * 60_000L)).append("\n");
        sb.append("Graph mode: ").append(sp.getString("pref_graph_mode", "pressure")).append("\n");
        sb.append("Poll interval raw: ").append(sp.getString("pref_interval_ms", "1000")).append("\n");
        sb.append("History size raw: ").append(sp.getString("pref_history_size", "10000")).append("\n");
        sb.append("Reference altitude: ").append(sp.getString("pref_ref_altitude_m", "0")).append("\n");
        sb.append("\n");
    }

    private static void appendDatabaseInfo(Context context, StringBuilder sb) {
        sb.append("[Data]\n");
        try {
            AppDatabase db = AppDatabase.get(context);
            int sampleCount = db.pressureDao().count();
            List<PressureSample> lastSamples = db.pressureDao().latest(1);
            List<EventSample> allEvents = db.eventDao().between(0L, Long.MAX_VALUE);
            sb.append("Pressure samples: ").append(sampleCount).append("\n");
            sb.append("Events: ").append(allEvents != null ? allEvents.size() : 0).append("\n");
            if (lastSamples != null && !lastSamples.isEmpty()) {
                PressureSample s = lastSamples.get(0);
                sb.append("Last sample time: ").append(DateFormat.format("yyyy-MM-dd HH:mm:ss", s.timestamp)).append("\n");
                sb.append("Last sample pressure: ").append(Units.formatPressure(s.pressureHpa, Units.getSystem(PreferenceManager.getDefaultSharedPreferences(context)))).append("\n");
            } else {
                sb.append("Last sample time: none\n");
            }
        } catch (Exception e) {
            sb.append("Data section error: ").append(e.getClass().getSimpleName()).append("\n");
        }
        sb.append("\n");
    }

    private static void appendRecentLogs(Context context, StringBuilder sb) {
        sb.append("[Recent app log]\n");
        sb.append(AppEventLogger.getRecentLogText(context));
        sb.append("\n");
    }

    private static void appendPendingCrash(Context context, StringBuilder sb) {
        sb.append("[Pending crash]\n");
        File pending = getPendingCrashFile(context);
        if (!pending.exists()) {
            sb.append("(none)\n\n");
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(pending), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            sb.append("Cannot read pending crash: ").append(e.getClass().getSimpleName()).append('\n');
        }
        sb.append('\n');
    }

    private static void writePendingCrash(Context context, Thread thread, Throwable throwable) {
        File pending = getPendingCrashFile(context);
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(pending, false), StandardCharsets.UTF_8))) {
            writer.write("Crash time: " + DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()) + "\n");
            writer.write("Thread: " + (thread != null ? thread.getName() : "unknown") + "\n");
            writer.write("Exception: " + throwable.getClass().getName() + "\n");
            writer.write("Message: " + String.valueOf(throwable.getMessage()) + "\n");
            writer.write("Last screen: " + PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_LAST_SCREEN, "unknown") + "\n\n");
            writer.write("Stacktrace:\n");
            for (StackTraceElement element : throwable.getStackTrace()) {
                writer.write("    at " + element.toString() + "\n");
            }
            Throwable cause = throwable.getCause();
            int causeDepth = 0;
            while (cause != null && causeDepth < 5) {
                writer.write("Caused by: " + cause.getClass().getName() + ": " + String.valueOf(cause.getMessage()) + "\n");
                for (StackTraceElement element : cause.getStackTrace()) {
                    writer.write("    at " + element.toString() + "\n");
                }
                cause = cause.getCause();
                causeDepth++;
            }
            writer.write("\nRecent app log:\n");
            writer.write(AppEventLogger.getRecentLogText(context));
        } catch (IOException e) {
            AppEventLogger.log(context, "REPORT", "write pending crash file failed: " + e.getClass().getSimpleName());
        }
    }

    private static File getPendingCrashFile(Context context) {
        return new File(context.getFilesDir(), CRASH_FILE_NAME);
    }

    public static void clearPendingCrash(Context context) {
        File file = getPendingCrashFile(context);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
