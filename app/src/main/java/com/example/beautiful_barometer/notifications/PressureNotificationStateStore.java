package com.example.beautiful_barometer.notifications;

import android.content.Context;
import android.content.SharedPreferences;

public final class PressureNotificationStateStore {

    private static final String PREF_CANDIDATE_TYPE = "pref_notification_candidate_type";
    private static final String PREF_CANDIDATE_SINCE = "pref_notification_candidate_since";
    private static final String PREF_CANDIDATE_HITS = "pref_notification_candidate_hits";
    private static final String PREF_ACTIVE_TYPE = "pref_notification_active_type";
    private static final String PREF_ACTIVE_SINCE = "pref_notification_active_since";
    private static final String PREF_PREV_FORECAST_STATE = "pref_notification_prev_forecast_state";
    private static final String PREF_RECENT_WORSENING_AT = "pref_notification_recent_worsening_at";
    private static final String PREF_LAST_SENT_AT = "pref_notification_last_sent_at";
    private static final String PREF_LAST_SENT_TYPE = "pref_notification_last_sent_type";
    private static final String PREF_LAST_NOTIFICATION_TITLE = "pref_notification_last_title";
    private static final String PREF_LAST_NOTIFICATION_AT = "pref_notification_last_title_at";

    private PressureNotificationStateStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return PressureNotificationPrefs.prefs(context);
    }

    public static final class State {
        public PressureNotificationType candidateType;
        public long candidateSince;
        public int candidateHits;
        public PressureNotificationType activeType;
        public long activeSince;
        public String prevForecastState;
        public long recentWorseningAt;
        public long lastSentAt;
        public PressureNotificationType lastSentType;
        public String lastNotificationTitle;
        public long lastNotificationAt;
    }

    public static State load(Context context) {
        SharedPreferences prefs = prefs(context);
        State state = new State();
        state.candidateType = PressureNotificationType.fromPrefValue(
                prefs.getString(PREF_CANDIDATE_TYPE, null)
        );
        state.candidateSince = prefs.getLong(PREF_CANDIDATE_SINCE, 0L);
        state.candidateHits = prefs.getInt(PREF_CANDIDATE_HITS, 0);
        state.activeType = PressureNotificationType.fromPrefValue(
                prefs.getString(PREF_ACTIVE_TYPE, null)
        );
        state.activeSince = prefs.getLong(PREF_ACTIVE_SINCE, 0L);
        state.prevForecastState = prefs.getString(PREF_PREV_FORECAST_STATE, null);
        state.recentWorseningAt = prefs.getLong(PREF_RECENT_WORSENING_AT, 0L);
        state.lastSentAt = prefs.getLong(PREF_LAST_SENT_AT, 0L);
        state.lastSentType = PressureNotificationType.fromPrefValue(
                prefs.getString(PREF_LAST_SENT_TYPE, null)
        );
        state.lastNotificationTitle = prefs.getString(PREF_LAST_NOTIFICATION_TITLE, null);
        state.lastNotificationAt = prefs.getLong(PREF_LAST_NOTIFICATION_AT, 0L);
        return state;
    }

    public static void save(Context context, State state) {
        if (state == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putString(PREF_CANDIDATE_TYPE, state.candidateType != null ? state.candidateType.prefValue() : null);
        editor.putLong(PREF_CANDIDATE_SINCE, state.candidateSince);
        editor.putInt(PREF_CANDIDATE_HITS, state.candidateHits);
        editor.putString(PREF_ACTIVE_TYPE, state.activeType != null ? state.activeType.prefValue() : null);
        editor.putLong(PREF_ACTIVE_SINCE, state.activeSince);
        editor.putString(PREF_PREV_FORECAST_STATE, state.prevForecastState);
        editor.putLong(PREF_RECENT_WORSENING_AT, state.recentWorseningAt);
        editor.putLong(PREF_LAST_SENT_AT, state.lastSentAt);
        editor.putString(PREF_LAST_SENT_TYPE, state.lastSentType != null ? state.lastSentType.prefValue() : null);
        editor.putString(PREF_LAST_NOTIFICATION_TITLE, state.lastNotificationTitle);
        editor.putLong(PREF_LAST_NOTIFICATION_AT, state.lastNotificationAt);
        editor.apply();
    }

    public static void clearTransientState(Context context) {
        prefs(context)
                .edit()
                .remove(PREF_CANDIDATE_TYPE)
                .remove(PREF_CANDIDATE_SINCE)
                .remove(PREF_CANDIDATE_HITS)
                .remove(PREF_ACTIVE_TYPE)
                .remove(PREF_ACTIVE_SINCE)
                .remove(PREF_PREV_FORECAST_STATE)
                .remove(PREF_RECENT_WORSENING_AT)
                .apply();
    }
}
