package com.example.beautiful_barometer;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.example.beautiful_barometer.feedback.AppEventLogger;
import com.example.beautiful_barometer.feedback.IssueReportManager;
import com.example.beautiful_barometer.util.ThemeController;
import com.google.android.material.color.DynamicColors;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemeController.applyGlobalNightMode(this);
        DynamicColors.applyToActivitiesIfAvailable(this);
        AppEventLogger.log(this, "APP", "Application started");
        IssueReportManager.installCrashHandler(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) {
                ThemeController.applySystemUi(activity);
                IssueReportManager.markScreenVisible(activity, activity.getClass().getSimpleName());
            }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }
}
