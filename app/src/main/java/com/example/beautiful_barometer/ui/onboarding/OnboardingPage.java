package com.example.beautiful_barometer.ui.onboarding;

public final class OnboardingPage {

    public static final int TYPE_HOME = 1;
    public static final int TYPE_GRAPH = 2;
    public static final int TYPE_NOTIFICATIONS = 3;
    public static final int TYPE_TRIP = 4;
    public static final int TYPE_ADAPTIVE = 5;
    public static final int TYPE_EXPORT = 6;
    public static final int TYPE_THEME_HELP = 7;

    public final int titleRes;
    public final int bodyRes;
    public final int previewType;

    public OnboardingPage(int titleRes, int bodyRes, int previewType) {
        this.titleRes = titleRes;
        this.bodyRes = bodyRes;
        this.previewType = previewType;
    }
}
