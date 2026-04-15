package com.example.beautiful_barometer.notifications;

import android.content.Context;

import com.example.beautiful_barometer.ui.forecast.ForecastConfidence;
import com.example.beautiful_barometer.ui.forecast.ForecastQuality;
import com.example.beautiful_barometer.ui.forecast.ForecastResult;
import com.example.beautiful_barometer.ui.forecast.ForecastState;
import com.example.beautiful_barometer.util.Units;

import java.util.Locale;

public final class PressureNotificationEngine {

    private PressureNotificationEngine() {
    }

    public static PressureNotificationDecision evaluate(
            Context context,
            ForecastResult result,
            long nowMs
    ) {
        Context appContext = context.getApplicationContext();
        PressureNotificationStateStore.State state = PressureNotificationStateStore.load(appContext);
        String mode = PressureNotificationPrefs.getNotificationMode(appContext);

        if (PressureNotificationPrefs.MODE_OFF.equals(mode)) {
            clearCandidate(state);
            clearActive(state);
            state.prevForecastState = result != null && result.state != null ? result.state.name() : null;
            PressureNotificationStateStore.save(appContext, state);
            return PressureNotificationDecision.NONE;
        }

        if (PressureNotificationPrefs.MODE_SIMPLE.equals(mode)) {
            return evaluateSimple(appContext, result, nowMs, state);
        }
        return evaluateSmart(appContext, result, nowMs, state);
    }

    private static PressureNotificationDecision evaluateSimple(
            Context context,
            ForecastResult result,
            long nowMs,
            PressureNotificationStateStore.State state
    ) {
        if (!hasUsableForecast(result)) {
            clearCandidate(state);
            clearActive(state);
            state.prevForecastState = result != null && result.state != null ? result.state.name() : null;
            PressureNotificationStateStore.save(context, state);
            return PressureNotificationDecision.NONE;
        }

        PressureNotificationType candidateType = detectSimpleCandidate(context, result);
        if (candidateType == null) {
            if (isSimpleType(state.activeType)) {
                clearActive(state);
            }
            clearCandidate(state);
            state.prevForecastState = result.state.name();
            PressureNotificationStateStore.save(context, state);
            return PressureNotificationDecision.NONE;
        }

        if (state.activeType == candidateType) {
            state.prevForecastState = result.state.name();
            PressureNotificationStateStore.save(context, state);
            return PressureNotificationDecision.NONE;
        }

        if (PressureNotificationPrefs.isSilentModeActive(context, nowMs)) {
            state.prevForecastState = result.state.name();
            PressureNotificationStateStore.save(context, state);
            return PressureNotificationDecision.NONE;
        }

        if (nowMs - state.lastSentAt < PressureNotificationPrefs.getMinIntervalMs(context)) {
            state.prevForecastState = result.state.name();
            PressureNotificationStateStore.save(context, state);
            return PressureNotificationDecision.NONE;
        }

        PressureNotificationDecision decision = buildSimpleDecision(context, candidateType, result);
        state.activeType = candidateType;
        state.activeSince = nowMs;
        state.lastSentType = candidateType;
        state.lastSentAt = nowMs;
        state.lastNotificationTitle = decision.title;
        state.lastNotificationAt = nowMs;
        clearCandidate(state);
        state.prevForecastState = result.state.name();
        PressureNotificationStateStore.save(context, state);
        return decision;
    }

    private static PressureNotificationType detectSimpleCandidate(Context context, ForecastResult result) {
        int windowHours = PressureNotificationPrefs.getSimpleWindowHours(context);
        double delta = getSimpleWindowDelta(result, windowHours);
        if (!valid(delta)) {
            return null;
        }

        double threshold = PressureNotificationPrefs.getSimpleThresholdHpa(context);
        if (PressureNotificationPrefs.isSimpleDropEnabled(context) && delta <= -threshold) {
            return PressureNotificationType.SIMPLE_DROP;
        }
        if (PressureNotificationPrefs.isSimpleRiseEnabled(context) && delta >= threshold) {
            return PressureNotificationType.SIMPLE_RISE;
        }
        return null;
    }

    private static double getSimpleWindowDelta(ForecastResult result, int windowHours) {
        switch (windowHours) {
            case 1:
                return result.trend1hDelta;
            case 3:
                return valid(result.trend3hDelta) ? result.trend3hDelta : result.trend1hDelta;
            case 6:
            default:
                return valid(result.trend6hDelta)
                        ? result.trend6hDelta
                        : (valid(result.trend3hDelta) ? result.trend3hDelta : result.trend1hDelta);
        }
    }

    private static PressureNotificationDecision buildSimpleDecision(
            Context context,
            PressureNotificationType type,
            ForecastResult result
    ) {
        int windowHours = PressureNotificationPrefs.getSimpleWindowHours(context);
        double delta = getSimpleWindowDelta(result, windowHours);
        String title = type == PressureNotificationType.SIMPLE_DROP
                ? context.getString(com.example.beautiful_barometer.R.string.notification_simple_drop_title)
                : context.getString(com.example.beautiful_barometer.R.string.notification_simple_rise_title);
        String deltaText = formatDeltaForUnits(delta, Units.getSystem(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)));
        String windowText = formatWindow(context, windowHours);
        String body = context.getString(
                com.example.beautiful_barometer.R.string.notification_simple_body_fmt,
                deltaText,
                windowText
        );
        return new PressureNotificationDecision(true, type, title, body, type.name());
    }

    private static String formatDeltaForUnits(double deltaHpa, Units.System units) {
        return Units.formatPressureDelta(deltaHpa, units);
    }

    private static String formatWindow(Context context, int windowHours) {
        switch (windowHours) {
            case 1:
                return context.getString(com.example.beautiful_barometer.R.string.notification_window_1h);
            case 3:
                return context.getString(com.example.beautiful_barometer.R.string.notification_window_3h);
            case 6:
            default:
                return context.getString(com.example.beautiful_barometer.R.string.notification_window_6h);
        }
    }

    private static PressureNotificationDecision evaluateSmart(
            Context appContext,
            ForecastResult result,
            long nowMs,
            PressureNotificationStateStore.State state
    ) {
        PressureNotificationSensitivity sensitivity = PressureNotificationPrefs.getSensitivity(appContext);

        if (result != null && isWorsening(result.state)) {
            state.recentWorseningAt = nowMs;
        }

        if (!hasUsableForecast(result)) {
            clearCandidate(state);
            if (result != null && result.state == ForecastState.TRIP_MODE) {
                clearActive(state);
            }
            state.prevForecastState = result != null && result.state != null ? result.state.name() : null;
            PressureNotificationStateStore.save(appContext, state);
            return PressureNotificationDecision.NONE;
        }

        PressureNotificationType candidateType = detectSmartCandidate(result, state, sensitivity, nowMs);

        if (candidateType == null) {
            clearCandidate(state);
            if (state.activeType != null && !isTypeStillActive(state.activeType, result, state, nowMs, sensitivity)) {
                clearActive(state);
            }
            state.prevForecastState = result.state.name();
            PressureNotificationStateStore.save(appContext, state);
            return PressureNotificationDecision.NONE;
        }

        updateCandidate(state, candidateType, nowMs);

        if (state.activeType != null
                && state.activeType == candidateType
                && isTypeStillActive(candidateType, result, state, nowMs, sensitivity)) {
            state.prevForecastState = result.state.name();
            PressureNotificationStateStore.save(appContext, state);
            return PressureNotificationDecision.NONE;
        }

        if (!isCandidateConfirmed(state, candidateType, sensitivity, nowMs)) {
            state.prevForecastState = result.state.name();
            PressureNotificationStateStore.save(appContext, state);
            return PressureNotificationDecision.NONE;
        }

        if (PressureNotificationPrefs.isSilentModeActive(appContext, nowMs)) {
            state.prevForecastState = result.state.name();
            PressureNotificationStateStore.save(appContext, state);
            return PressureNotificationDecision.NONE;
        }

        if (nowMs - state.lastSentAt < PressureNotificationPrefs.getMinIntervalMs(appContext)) {
            state.prevForecastState = result.state.name();
            PressureNotificationStateStore.save(appContext, state);
            return PressureNotificationDecision.NONE;
        }

        long typeCooldownMs = getTypeCooldownMs(candidateType, sensitivity);
        if (state.lastSentType == candidateType && nowMs - state.lastSentAt < typeCooldownMs) {
            state.prevForecastState = result.state.name();
            PressureNotificationStateStore.save(appContext, state);
            return PressureNotificationDecision.NONE;
        }

        PressureNotificationDecision decision = buildSmartDecision(appContext, candidateType, result);
        state.activeType = candidateType;
        state.activeSince = nowMs;
        state.lastSentType = candidateType;
        state.lastSentAt = nowMs;
        state.lastNotificationTitle = decision.title;
        state.lastNotificationAt = nowMs;
        clearCandidate(state);
        state.prevForecastState = result.state.name();
        PressureNotificationStateStore.save(appContext, state);
        return decision;
    }

    private static boolean hasUsableForecast(ForecastResult result) {
        return result != null
                && result.state != ForecastState.NO_DATA
                && result.state != ForecastState.INSUFFICIENT_DATA
                && result.state != ForecastState.TRIP_MODE;
    }

    private static PressureNotificationType detectSmartCandidate(
            ForecastResult result,
            PressureNotificationStateStore.State state,
            PressureNotificationSensitivity sensitivity,
            long nowMs
    ) {
        if (result.confidence == ForecastConfidence.LOW || result.quality == ForecastQuality.LOW) {
            return null;
        }

        double fast1h = thresholdFast1h(sensitivity);
        double fast3h = thresholdFast3h(sensitivity);
        double sustained3h = thresholdSustained3h(sensitivity);
        double improve1h = thresholdImprove1h(sensitivity);
        double stable1h = thresholdStable1h(sensitivity);
        double maxNoise = maxNoiseForStable(sensitivity);

        if (result.state == ForecastState.FAST_WORSENING
                && valid(result.trend1hDelta) && result.trend1hDelta <= fast1h
                && valid(result.trend3hDelta) && result.trend3hDelta <= fast3h) {
            return PressureNotificationType.FAST_WORSENING;
        }

        if (result.state == ForecastState.SLOW_WORSENING
                && valid(result.trend3hDelta) && result.trend3hDelta <= sustained3h
                && valid(result.trend1hDelta) && result.trend1hDelta < 0.0) {
            return PressureNotificationType.SUSTAINED_WORSENING;
        }

        boolean recentDrop = nowMs - state.recentWorseningAt <= 12L * 60L * 60L * 1000L;
        if (!recentDrop) {
            return null;
        }

        if (result.state == ForecastState.STABLE
                && valid(result.trend1hDelta)
                && Math.abs(result.trend1hDelta) <= stable1h
                && (!valid(result.noiseStd1h) || result.noiseStd1h <= maxNoise)) {
            return PressureNotificationType.STABILIZING_AFTER_DROP;
        }

        if ((result.state == ForecastState.SLOW_IMPROVING || result.state == ForecastState.FAST_IMPROVING)
                && valid(result.trend1hDelta)
                && result.trend1hDelta >= improve1h) {
            return PressureNotificationType.IMPROVING_AFTER_DROP;
        }

        return null;
    }

    private static boolean isTypeStillActive(
            PressureNotificationType type,
            ForecastResult result,
            PressureNotificationStateStore.State state,
            long nowMs,
            PressureNotificationSensitivity sensitivity
    ) {
        if (isSimpleType(type)) {
            return false;
        }
        PressureNotificationType current = detectSmartCandidate(result, state, sensitivity, nowMs);
        return current == type;
    }

    private static void updateCandidate(
            PressureNotificationStateStore.State state,
            PressureNotificationType candidateType,
            long nowMs
    ) {
        if (state.candidateType == candidateType) {
            state.candidateHits += 1;
            if (state.candidateSince <= 0L) {
                state.candidateSince = nowMs;
            }
            return;
        }
        state.candidateType = candidateType;
        state.candidateSince = nowMs;
        state.candidateHits = 1;
    }

    private static boolean isCandidateConfirmed(
            PressureNotificationStateStore.State state,
            PressureNotificationType type,
            PressureNotificationSensitivity sensitivity,
            long nowMs
    ) {
        if (state.candidateType != type || state.candidateSince <= 0L) {
            return false;
        }
        long neededDurationMs = getConfirmDurationMs(type, sensitivity);
        int neededHits = getConfirmHits(type, sensitivity);
        return nowMs - state.candidateSince >= neededDurationMs && state.candidateHits >= neededHits;
    }

    private static long getConfirmDurationMs(
            PressureNotificationType type,
            PressureNotificationSensitivity sensitivity
    ) {
        switch (type) {
            case FAST_WORSENING:
                switch (sensitivity) {
                    case HIGH:
                        return 5L * 60_000L;
                    case LOW:
                        return 12L * 60_000L;
                    case MEDIUM:
                    default:
                        return 8L * 60_000L;
                }
            case SUSTAINED_WORSENING:
                switch (sensitivity) {
                    case HIGH:
                        return 12L * 60_000L;
                    case LOW:
                        return 25L * 60_000L;
                    case MEDIUM:
                    default:
                        return 18L * 60_000L;
                }
            case STABILIZING_AFTER_DROP:
            case IMPROVING_AFTER_DROP:
            default:
                switch (sensitivity) {
                    case HIGH:
                        return 15L * 60_000L;
                    case LOW:
                        return 30L * 60_000L;
                    case MEDIUM:
                    default:
                        return 20L * 60_000L;
                }
        }
    }

    private static int getConfirmHits(
            PressureNotificationType type,
            PressureNotificationSensitivity sensitivity
    ) {
        switch (type) {
            case FAST_WORSENING:
            case SUSTAINED_WORSENING:
            case STABILIZING_AFTER_DROP:
            case IMPROVING_AFTER_DROP:
            default:
                return sensitivity == PressureNotificationSensitivity.HIGH ? 2 : 3;
        }
    }

    private static long getTypeCooldownMs(
            PressureNotificationType type,
            PressureNotificationSensitivity sensitivity
    ) {
        switch (type) {
            case FAST_WORSENING:
                return sensitivity == PressureNotificationSensitivity.HIGH
                        ? 2L * 60L * 60L * 1000L
                        : 3L * 60L * 60L * 1000L;
            case SUSTAINED_WORSENING:
                return 4L * 60L * 60L * 1000L;
            case STABILIZING_AFTER_DROP:
                return 3L * 60L * 60L * 1000L;
            case IMPROVING_AFTER_DROP:
            default:
                return 4L * 60L * 60L * 1000L;
        }
    }

    private static PressureNotificationDecision buildSmartDecision(
            Context appContext,
            PressureNotificationType type,
            ForecastResult result
    ) {
        String title;
        String body;
        switch (type) {
            case FAST_WORSENING:
                title = "Вероятно быстрое ухудшение";
                body = buildTrendBody(appContext,
                        "Давление заметно снижается, и спад усиливается.",
                        result.trend1hDelta,
                        result.trend3hDelta
                );
                break;
            case SUSTAINED_WORSENING:
                title = "Наблюдается устойчивое ухудшение";
                body = buildTrendBody(appContext,
                        "Давление снижается уже несколько часов.",
                        result.trend1hDelta,
                        result.trend3hDelta
                );
                break;
            case STABILIZING_AFTER_DROP:
                title = "Давление стабилизируется";
                body = buildTrendBody(appContext,
                        "После снижения последних часов тренд стал спокойнее.",
                        result.trend1hDelta,
                        result.trend3hDelta
                );
                break;
            case IMPROVING_AFTER_DROP:
            default:
                title = "Есть признаки улучшения";
                body = buildTrendBody(appContext,
                        "После спада давление начинает восстанавливаться.",
                        result.trend1hDelta,
                        result.trend3hDelta
                );
                break;
        }
        return new PressureNotificationDecision(true, type, title, body, type.name());
    }

    private static String buildTrendBody(Context context, String lead, double trend1h, double trend3h) {
        Units.System units = Units.getSystem(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context));
        String delta1 = valid(trend1h)
                ? "Δ1ч " + formatDeltaForUnits(trend1h, units)
                : "Δ1ч —";
        String delta3 = valid(trend3h)
                ? "Δ3ч " + formatDeltaForUnits(trend3h, units)
                : "Δ3ч —";
        return lead + " " + delta1 + " • " + delta3;
    }

    private static boolean isWorsening(ForecastState state) {
        return state == ForecastState.FAST_WORSENING || state == ForecastState.SLOW_WORSENING;
    }

    private static boolean isSimpleType(PressureNotificationType type) {
        return type == PressureNotificationType.SIMPLE_DROP || type == PressureNotificationType.SIMPLE_RISE;
    }

    private static boolean valid(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static double thresholdFast1h(PressureNotificationSensitivity sensitivity) {
        switch (sensitivity) {
            case HIGH:
                return -0.55;
            case LOW:
                return -0.85;
            case MEDIUM:
            default:
                return -0.70;
        }
    }

    private static double thresholdFast3h(PressureNotificationSensitivity sensitivity) {
        switch (sensitivity) {
            case HIGH:
                return -1.20;
            case LOW:
                return -1.70;
            case MEDIUM:
            default:
                return -1.45;
        }
    }

    private static double thresholdSustained3h(PressureNotificationSensitivity sensitivity) {
        switch (sensitivity) {
            case HIGH:
                return -0.80;
            case LOW:
                return -1.20;
            case MEDIUM:
            default:
                return -1.00;
        }
    }

    private static double thresholdImprove1h(PressureNotificationSensitivity sensitivity) {
        switch (sensitivity) {
            case HIGH:
                return 0.18;
            case LOW:
                return 0.35;
            case MEDIUM:
            default:
                return 0.25;
        }
    }

    private static double thresholdStable1h(PressureNotificationSensitivity sensitivity) {
        switch (sensitivity) {
            case HIGH:
                return 0.20;
            case LOW:
                return 0.12;
            case MEDIUM:
            default:
                return 0.16;
        }
    }

    private static double maxNoiseForStable(PressureNotificationSensitivity sensitivity) {
        switch (sensitivity) {
            case HIGH:
                return 0.45;
            case LOW:
                return 0.30;
            case MEDIUM:
            default:
                return 0.38;
        }
    }

    private static void clearCandidate(PressureNotificationStateStore.State state) {
        state.candidateType = null;
        state.candidateSince = 0L;
        state.candidateHits = 0;
    }

    private static void clearActive(PressureNotificationStateStore.State state) {
        state.activeType = null;
        state.activeSince = 0L;
    }
}
