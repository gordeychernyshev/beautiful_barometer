package com.example.beautiful_barometer.feedback;

import android.content.Context;
import android.text.format.DateFormat;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class AppEventLogger {

    private static final String TAG = "AppEventLogger";
    private static final String LOG_FILE_NAME = "app_event_log.txt";
    private static final int MAX_LINES = 400;
    private static final Object LOCK = new Object();

    private AppEventLogger() {
    }

    public static void log(Context context, String tag, String message) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            List<String> lines = readLinesInternal(appContext);
            long now = System.currentTimeMillis();
            String ts = DateFormat.format("yyyy-MM-dd HH:mm:ss", now).toString();
            String safeTag = tag == null ? "APP" : tag;
            String safeMessage = message == null ? "" : message.replace('\n', ' ');
            lines.add(String.format(Locale.US, "%s | %s | %s", ts, safeTag, safeMessage));
            trimAndWrite(appContext, lines);
        }
    }

    public static List<String> readRecentLines(Context context) {
        if (context == null) return Collections.emptyList();
        synchronized (LOCK) {
            return new ArrayList<>(readLinesInternal(context.getApplicationContext()));
        }
    }

    public static String getRecentLogText(Context context) {
        List<String> lines = readRecentLines(context);
        if (lines.isEmpty()) return "(пусто)\n";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static List<String> readLinesInternal(Context context) {
        File file = new File(context.getFilesDir(), LOG_FILE_NAME);
        if (!file.exists()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.add(line);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read app event log", e);
        }
        return out;
    }

    private static void trimAndWrite(Context context, List<String> lines) {
        int from = Math.max(0, lines.size() - MAX_LINES);
        List<String> tail = lines.subList(from, lines.size());
        File file = new File(context.getFilesDir(), LOG_FILE_NAME);
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
            for (String line : tail) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to write app event log", e);
        }
    }
}
