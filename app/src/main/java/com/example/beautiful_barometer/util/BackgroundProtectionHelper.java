package com.example.beautiful_barometer.util;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

public final class BackgroundProtectionHelper {

    private BackgroundProtectionHelper() {
    }

    public static boolean isIgnoringBatteryOptimizations(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    public static String vendorLabel() {
        String manufacturer = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER.trim();
        if (manufacturer.isEmpty()) {
            return "Android";
        }
        return Character.toUpperCase(manufacturer.charAt(0)) + manufacturer.substring(1);
    }

    public static String buildGuidanceText(Context context) {
        String vendor = vendorLabel();
        StringBuilder sb = new StringBuilder();
        sb.append("Чтобы сервис не выгружался, обычно нужны 3 вещи:\n\n")
                .append("1. Разрешить приложению работать без оптимизации батареи.\n")
                .append("2. Разрешить автозапуск после перезагрузки.\n")
                .append("3. Не ограничивать фоновую активность и уведомления.\n\n")
                .append("Производитель: ")
                .append(vendor)
                .append(".\n\n");

        String lower = vendor.toLowerCase();
        if (lower.contains("xiaomi") || lower.contains("redmi") || lower.contains("poco")) {
            sb.append("Для Xiaomi / MIUI / HyperOS:\n")
                    .append("• Безопасность → Автозапуск → включить для приложения.\n")
                    .append("• Батарея → Без ограничений.\n")
                    .append("• Закрепить приложение в недавних, если система его выгружает.\n")
                    .append("• Разрешить уведомления и работу в фоне.\n");
        } else if (lower.contains("samsung")) {
            sb.append("Для Samsung:\n")
                    .append("• Battery → Background usage limits → Never sleeping apps.\n")
                    .append("• Разрешить автозапуск и уведомления.\n");
        } else if (lower.contains("huawei") || lower.contains("honor")) {
            sb.append("Для Huawei / Honor:\n")
                    .append("• App launch → Manual manage → включить все тумблеры.\n")
                    .append("• Battery optimization → Don't allow.\n");
        } else if (lower.contains("oppo") || lower.contains("realme") || lower.contains("oneplus") || lower.contains("vivo")) {
            sb.append("Для ").append(vendor).append(":\n")
                    .append("• Разрешить автозапуск.\n")
                    .append("• Снять ограничения батареи / фоновой активности.\n");
        } else {
            sb.append("На этом устройстве проверьте: автозапуск, исключение из оптимизации батареи и разрешение фоновой работы.\n");
        }
        return sb.toString();
    }

    public static boolean openVendorAutostartSettings(Context context) {
        Intent[] intents = new Intent[] {
                componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                componentIntent("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                        .putExtra("package_name", context.getPackageName())
                        .putExtra("package_label", context.getApplicationInfo().loadLabel(context.getPackageManager())),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                componentIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                componentIntent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        };

        for (Intent intent : intents) {
            if (intent == null) {
                continue;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(context.getPackageManager()) != null) {
                try {
                    context.startActivity(intent);
                    return true;
                } catch (ActivityNotFoundException ignored) {
                    // Try next.
                }
            }
        }
        return false;
    }

    public static void openBatteryOptimizationScreen(Context context) {
        Intent i;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations(context)) {
            i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + context.getPackageName()));
        } else {
            i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    public static void openAppDetails(Context context) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + context.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    private static Intent componentIntent(String pkg, String cls) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(pkg, cls));
        return intent;
    }
}
