package com.example.beautiful_barometer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;


public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String a = intent.getAction();
        if (
                "android.intent.action.BOOT_COMPLETED".equals(a) ||
                        "android.intent.action.MY_PACKAGE_REPLACED".equals(a)
            // Если захочешь — добавь сюда "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {

            android.content.SharedPreferences p = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
            if (p.getBoolean("pref_autostart", false) && p.getBoolean("pref_recording_enabled", true)) {
                Intent svc = new Intent(context, SensorService.class);
                ContextCompat.startForegroundService(context, svc);
            }
        }
    }
}
