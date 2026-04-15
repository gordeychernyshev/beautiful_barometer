package com.example.beautiful_barometer.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;

public final class DeviceCapabilities {
    private DeviceCapabilities() {}

    public static boolean hasBarometer(Context context) {
        Context app = context.getApplicationContext();
        PackageManager pm = app.getPackageManager();
        if (pm != null && pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_BAROMETER)) {
            return true;
        }
        SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null;
    }
}
