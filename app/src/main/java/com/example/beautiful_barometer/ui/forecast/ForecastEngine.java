package com.example.beautiful_barometer.ui.forecast;

import com.example.beautiful_barometer.data.PressureDao;
import com.example.beautiful_barometer.util.Units;
import com.example.beautiful_barometer.data.PressureSample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Детеминированный прогноз по барометрическому тренду.
 * Без сетевых данных и без обещаний конкретной погоды.
 */
public class ForecastEngine {

    private static final long H1 = 3_600_000L;
    private static final long H3 = 3L * H1;
    private static final long H6 = 6L * H1;
    private static final long MIN_ANALYSIS_SPAN = 45L * 60_000L;

    public static ForecastUiModel build(PressureDao dao, long nowMs, long baselineMs) {
        return build(dao, nowMs, baselineMs, Units.System.METRIC);
    }

    public static ForecastUiModel build(PressureDao dao, long nowMs, long baselineMs, Units.System units) {
        ForecastResult result = buildResult(dao, nowMs, baselineMs, units);
        return ForecastUiModel.fromResult(result, units);
    }

    public static ForecastResult buildResult(PressureDao dao, long nowMs, long baselineMs) {
        return buildResult(dao, nowMs, baselineMs, Units.System.METRIC);
    }

    public static ForecastResult buildResult(PressureDao dao, long nowMs, long baselineMs, Units.System units) {
        ForecastResult result = new ForecastResult();
        long effectiveBaselineMs = Math.max(0L, baselineMs);

        long historyFrom = effectiveBaselineMs > 0L
                ? Math.max(0L, nowMs - 6L * H1)
                : Math.max(0L, nowMs - 30L * H1);
        List<PressureSample> all = dao.betweenAsc(historyFrom, nowMs);
        if (all == null || all.isEmpty()) {
            fillNoData(result);
            return result;
        }

        long analysisFloor = effectiveBaselineMs > 0L ? effectiveBaselineMs : 0L;
        List<PressureSample> s6 = filterWindow(all, Math.max(analysisFloor, nowMs - H6), nowMs);
        result.sparklineSeries = compressForSparkline(smoothSeries(s6), 36);

        WindowAnalysis w1 = analyzeWindow(all, Math.max(analysisFloor, nowMs - H1), nowMs, H1);
        WindowAnalysis w3 = analyzeWindow(all, Math.max(analysisFloor, nowMs - H3), nowMs, H3);
        WindowAnalysis w6 = analyzeWindow(all, Math.max(analysisFloor, nowMs - H6), nowMs, H6);
        WindowAnalysis prev1 = analyzeWindow(all, Math.max(analysisFloor, nowMs - 2L * H1), nowMs - H1, H1);

        WindowAnalysis yesterday3 = effectiveBaselineMs > 0L
                ? null
                : analyzeWindow(all, nowMs - 27L * H1, nowMs - 24L * H1, H3);

        result.trend1hDelta = w1.equivalentDelta;
        result.trend3hDelta = w3.equivalentDelta;
        result.trend6hDelta = w6.equivalentDelta;
        result.noiseStd1h = w1.noiseStd;
        result.noiseStd3h = w3.noiseStd;
        result.coverage1h = w1.coverage;
        result.coverage3h = w3.coverage;

        if (!hasEnoughData(w1, w3, effectiveBaselineMs)) {
            fillInsufficientData(result, effectiveBaselineMs, w1, w3);
            result.sparklineSeries = compressForSparkline(smoothSeries(s6), 36);
            return result;
        }

        double accel = difference(w1.equivalentDelta, prev1.equivalentDelta);
        double signalStrength = computeSignalStrength(w1, w3, w6, accel);
        double directionAgreement = computeDirectionAgreement(w1, w3, w6);
        result.signalScore = signalStrength;

        result.quality = qualityFor(scoreQuality(w1, w3, nowMs));
        result.confidence = confidenceFor(w1, w3, signalStrength, directionAgreement, result.quality);
        result.confidencePercent = confidencePercent(result.confidence, result.quality, signalStrength, directionAgreement);
        result.confidenceDetail = confidenceDetail(result.confidence, result.quality, w1, w3, nowMs, effectiveBaselineMs);

        boolean unstable = isUnstable(w1, w3, accel, directionAgreement);

        if (unstable) {
            result.state = ForecastState.UNSTABLE;
        } else if (w3.equivalentDelta <= -1.5 || (w1.equivalentDelta <= -0.75 && accel <= -0.18)) {
            result.state = ForecastState.FAST_WORSENING;
        } else if (w3.equivalentDelta <= -0.55 || w1.equivalentDelta <= -0.30) {
            result.state = ForecastState.SLOW_WORSENING;
        } else if (w3.equivalentDelta >= 1.5 || (w1.equivalentDelta >= 0.75 && accel >= 0.18)) {
            result.state = ForecastState.FAST_IMPROVING;
        } else if (w3.equivalentDelta >= 0.55 || w1.equivalentDelta >= 0.30) {
            result.state = ForecastState.SLOW_IMPROVING;
        } else {
            result.state = ForecastState.STABLE;
        }

        fillNarrative(result, w1, w3, w6, prev1, yesterday3, accel, effectiveBaselineMs, units);
        return result;
    }

    private static void fillNoData(ForecastResult result) {
        result.state = ForecastState.NO_DATA;
        result.headline = "Нет данных для прогноза";
        result.subtitle = "Сначала нужно накопить измерения давления.";
        result.summary = "Когда появятся новые точки, карточка начнёт показывать тренд и уверенность.";
        result.confidenceDetail = "Без данных оценивать тренд нельзя.";
        result.confidence = ForecastConfidence.LOW;
        result.quality = ForecastQuality.LOW;
        result.confidencePercent = 0;
        result.explainBody = "Прогноз опирается только на историю измерений давления. Пока история пуста, выводы не строятся.";
        result.reasons.add("Нет сохранённых измерений давления");
    }

    private static void fillInsufficientData(
            ForecastResult result,
            long baselineMs,
            WindowAnalysis w1,
            WindowAnalysis w3
    ) {
        result.state = ForecastState.INSUFFICIENT_DATA;
        result.confidence = ForecastConfidence.LOW;
        result.quality = qualityFor(scoreQuality(w1, w3, System.currentTimeMillis()));
        result.confidencePercent = 18;
        result.headline = "Недостаточно данных";
        result.subtitle = baselineMs > 0L
                ? "После поездки набираем новую историю для прогноза."
                : "Нужно ещё немного времени, чтобы увидеть устойчивый тренд.";
        result.summary = baselineMs > 0L
                ? "Сейчас прогноз считает только точки после выхода из режима поездки."
                : "Как только окно в 1–3 часа станет плотнее, карточка начнёт показывать полноценный вывод.";
        result.confidenceDetail = baselineMs > 0L
                ? "После поездки окно анализа пока короткое."
                : "Данных в последних окнах пока маловато для уверенного вывода.";
        if (w1.count < 4) {
            result.reasons.add("За последний час слишком мало точек");
        }
        if (w3.coverage < 0.60) {
            result.reasons.add("Окно 3 часов покрыто не полностью");
        }
        if (w3.maxGapMs > 25L * 60_000L) {
            result.reasons.add("В записи есть большие пропуски");
        }
        if (result.reasons.isEmpty()) {
            result.reasons.add("Нужно ещё немного истории давления");
        }
        result.explainBody = result.subtitle + " \n\nПричины:\n" + bulletLines(result.reasons);
    }

    private static boolean hasEnoughData(WindowAnalysis w1, WindowAnalysis w3, long baselineMs) {
        if (w1.count < 4 || w3.count < 8) return false;
        if (w1.spanMs < 20L * 60_000L) return false;
        if (w3.spanMs < MIN_ANALYSIS_SPAN) return false;
        if (w3.coverage < 0.55) return false;
        if (baselineMs > 0L && w3.coverage < 0.75) return false;
        return true;
    }

    private static void fillNarrative(
            ForecastResult result,
            WindowAnalysis w1,
            WindowAnalysis w3,
            WindowAnalysis w6,
            WindowAnalysis prev1,
            WindowAnalysis yesterday3,
            double accel,
            long baselineMs,
            Units.System units
    ) {
        switch (result.state) {
            case FAST_WORSENING:
                result.headline = "Вероятно быстрое ухудшение";
                result.subtitle = "За последние 3 часа давление заметно и устойчиво падает.";
                result.summary = "Краткосрочный тренд усиливает снижение — погода может портиться в ближайшие часы.";
                result.advice = "Совет: на улицу лучше выходить с запасом по одежде или зонту.";
                result.pulse = true;
                break;
            case SLOW_WORSENING:
                result.headline = "Похоже, погода портится";
                result.subtitle = "Есть устойчивое снижение давления без резких скачков.";
                result.summary = "Тренд вниз пока умеренный, но уже читается в окне 1–3 часов.";
                result.advice = "Совет: полезно время от времени поглядывать на график.";
                break;
            case FAST_IMPROVING:
                result.headline = "Вероятно быстрое улучшение";
                result.subtitle = "Давление уверенно и быстро растёт.";
                result.summary = "Рост виден сразу в нескольких окнах, поэтому сигнал выглядит сильным.";
                result.advice = "Совет: это хорошее окно для прогулки или дороги.";
                break;
            case SLOW_IMPROVING:
                result.headline = "Намечается улучшение";
                result.subtitle = "Давление плавно растёт, без резких колебаний.";
                result.summary = "Сигнал вверх есть, но он пока спокойный и умеренный.";
                result.advice = "Совет: можно просто наблюдать за трендом ещё немного.";
                break;
            case UNSTABLE:
                result.headline = "Нестабильный фронт";
                result.subtitle = "Давление дёргается, и направление тренда пока неровное.";
                result.summary = "Сигнал есть, но скачки и разброс делают прогноз осторожным.";
                result.advice = "Совет: лучше ориентироваться не на одну точку, а на график за 3–6 часов.";
                result.pulse = true;
                break;
            case STABLE:
            default:
                result.headline = "Давление стабильно";
                result.subtitle = "Выраженного тренда вверх или вниз сейчас нет.";
                result.summary = "Изменения небольшие, а ряд выглядит достаточно спокойным.";
                result.advice = "Совет: можно не следить за барометром слишком часто.";
                break;
        }

        addReasons(result, w1, w3, w6, prev1, accel, baselineMs, units);
        result.compareYesterday = yesterdayCompareText(yesterday3, w3, units);
        result.explainBody = buildExplainBody(result, w1, w3, w6, yesterday3, units);
    }

    private static void addReasons(
            ForecastResult result,
            WindowAnalysis w1,
            WindowAnalysis w3,
            WindowAnalysis w6,
            WindowAnalysis prev1,
            double accel,
            long baselineMs,
            Units.System units
    ) {
        switch (result.state) {
            case FAST_WORSENING:
            case SLOW_WORSENING:
                result.reasons.add(directionLine("За 3 часа давление устойчиво падает", w3.equivalentDelta, units));
                if (accel < -0.12) {
                    result.reasons.add("Краткосрочный тренд ускоряется вниз");
                }
                break;
            case FAST_IMPROVING:
            case SLOW_IMPROVING:
                result.reasons.add(directionLine("За 3 часа давление устойчиво растёт", w3.equivalentDelta, units));
                if (accel > 0.12) {
                    result.reasons.add("Краткосрочный тренд ускоряется вверх");
                }
                break;
            case UNSTABLE:
                result.reasons.add("Внутри часа есть заметный разброс и рваность ряда");
                if (signum(w1.slopePerHour) != 0 && signum(w3.slopePerHour) != 0
                        && signum(w1.slopePerHour) != signum(w3.slopePerHour)) {
                    result.reasons.add("Короткое и длинное окна спорят по направлению");
                }
                break;
            case STABLE:
                result.reasons.add("За 3 часа выраженного тренда нет");
                result.reasons.add("Шум низкий, ряд выглядит ровным");
                break;
            default:
                break;
        }

        if (result.reasons.size() < 3) {
            if (maxNoise(w1, w3) < 0.10) {
                result.reasons.add("Шум низкий, поэтому вывод устойчивее");
            } else if (maxNoise(w1, w3) >= 0.22) {
                result.reasons.add("Шум высокий, поэтому прогноз осторожный");
            }
        }

        if (result.reasons.size() < 3) {
            if (w3.maxGapMs > 20L * 60_000L) {
                result.reasons.add("В записи есть заметные пропуски");
            } else if (w3.coverage >= 0.90) {
                result.reasons.add("Окно 3 часов покрыто почти полностью");
            }
        }

        if (baselineMs > 0L && result.reasons.size() < 3) {
            result.reasons.add("Прогноз использует только данные после выхода из режима поездки");
        }

        if (result.reasons.size() > 3) {
            result.reasons = new ArrayList<>(result.reasons.subList(0, 3));
        }
    }

    private static String buildExplainBody(
            ForecastResult result,
            WindowAnalysis w1,
            WindowAnalysis w3,
            WindowAnalysis w6,
            WindowAnalysis yesterday3,
            Units.System units
    ) {
        List<String> lines = new ArrayList<>();
        lines.add(result.subtitle);
        lines.add("");
        lines.add("Что видим по данным:");
        lines.add("• Нормализованный тренд 1ч: " + Units.formatPressureDelta(result.trend1hDelta, units));
        lines.add("• Нормализованный тренд 3ч: " + Units.formatPressureDelta(result.trend3hDelta, units));
        lines.add(String.format(Locale.getDefault(), "• Шум в последних окнах: %s", noiseText(maxNoise(w1, w3))));
        lines.add(String.format(Locale.getDefault(), "• Качество данных: %s", qualityText(result.quality)));
        if (yesterday3 != null && !Double.isNaN(yesterday3.equivalentDelta)) {
            lines.add("• Вчера в похожем окне было " + Units.formatPressureDelta(yesterday3.equivalentDelta, units) + " за 3ч");
        }
        if (!result.reasons.isEmpty()) {
            lines.add("");
            lines.add("Почему такой вывод:");
            for (String reason : result.reasons) {
                lines.add("• " + reason);
            }
        }
        lines.add("");
        lines.add("Важно: это локальный прогноз по барометру, без погодных API. Он хорошо показывает тренд и нестабильность, но не обещает точные осадки.");
        return joinLines(lines);
    }

    private static String confidenceDetail(
            ForecastConfidence confidence,
            ForecastQuality quality,
            WindowAnalysis w1,
            WindowAnalysis w3,
            long nowMs,
            long baselineMs
    ) {
        if (baselineMs > 0L && w3.coverage < 0.90) {
            return "После поездки история ещё короткая, поэтому прогноз осторожный.";
        }
        if (w3.maxGapMs > 25L * 60_000L) {
            return "В последних 3 часах есть большие разрывы между точками.";
        }
        if ((nowMs - w1.lastTimestampMs) > 15L * 60_000L) {
            return "Последняя точка уже не совсем свежая, поэтому уверенность ниже.";
        }
        if (confidence == ForecastConfidence.HIGH) {
            return quality == ForecastQuality.HIGH
                    ? "Данных достаточно, окна покрыты хорошо, а тренд выглядит согласованным."
                    : "Сигнал выражен отчётливо, хотя качество данных не идеальное.";
        }
        if (confidence == ForecastConfidence.MEDIUM) {
            return "Тренд читается, но сила сигнала или качество записи ещё не максимальные.";
        }
        return "Сигнал слабый или данные слишком шумные, поэтому прогноз предварительный.";
    }

    private static boolean isUnstable(WindowAnalysis w1, WindowAnalysis w3, double accel, double directionAgreement) {
        boolean highNoise = maxNoise(w1, w3) >= 0.22;
        boolean conflictingDirection = directionAgreement < 0.25
                && Math.abs(w1.equivalentDelta) >= 0.25
                && Math.abs(w3.equivalentDelta) >= 0.45;
        boolean jittery = Math.abs(accel) >= 0.35 && Math.abs(w3.equivalentDelta) < 1.0;
        return highNoise || conflictingDirection || jittery;
    }

    private static int scoreQuality(WindowAnalysis w1, WindowAnalysis w3, long nowMs) {
        double score = 0.0;
        score += clamp01(w1.coverage) * 22.0;
        score += clamp01(w3.coverage) * 38.0;
        score += clamp01((w1.count - 3.0) / 10.0) * 10.0;
        score += clamp01((w3.count - 7.0) / 18.0) * 15.0;
        score += clamp01(1.0 - (w3.maxGapMs / (double) (35L * 60_000L))) * 10.0;
        score += clamp01(1.0 - ((nowMs - w1.lastTimestampMs) / (double) (20L * 60_000L))) * 5.0;
        return (int) Math.round(score);
    }

    private static ForecastQuality qualityFor(int score) {
        if (score >= 72) return ForecastQuality.HIGH;
        if (score >= 48) return ForecastQuality.MEDIUM;
        return ForecastQuality.LOW;
    }

    private static double computeSignalStrength(WindowAnalysis w1, WindowAnalysis w3, WindowAnalysis w6, double accel) {
        double strength = 0.0;
        strength += Math.min(1.0, Math.abs(w1.equivalentDelta) / 0.8) * 35.0;
        strength += Math.min(1.0, Math.abs(w3.equivalentDelta) / 1.6) * 45.0;
        strength += Math.min(1.0, Math.abs(w6.equivalentDelta) / 2.4) * 15.0;
        strength += Math.min(1.0, Math.abs(accel) / 0.25) * 5.0;
        return strength;
    }

    private static double computeDirectionAgreement(WindowAnalysis w1, WindowAnalysis w3, WindowAnalysis w6) {
        int s1 = signum(w1.slopePerHour);
        int s3 = signum(w3.slopePerHour);
        int s6 = signum(w6.slopePerHour);
        if (s1 == 0 && s3 == 0) return 1.0;
        double score = 0.0;
        if (s1 == s3) score += 0.5;
        if (s1 == s6 || s6 == 0) score += 0.25;
        if (s3 == s6 || s6 == 0) score += 0.25;
        return score;
    }

    private static ForecastConfidence confidenceFor(
            WindowAnalysis w1,
            WindowAnalysis w3,
            double signalStrength,
            double directionAgreement,
            ForecastQuality quality
    ) {
        if (quality == ForecastQuality.HIGH
                && directionAgreement >= 0.75
                && ((Math.abs(w3.equivalentDelta) >= 0.9 && maxNoise(w1, w3) < 0.18)
                || (Math.abs(w3.equivalentDelta) < 0.35 && maxNoise(w1, w3) < 0.10))) {
            return ForecastConfidence.HIGH;
        }

        if (quality != ForecastQuality.LOW
                && directionAgreement >= 0.5
                && signalStrength >= 35.0
                && maxNoise(w1, w3) < 0.28) {
            return ForecastConfidence.MEDIUM;
        }

        return ForecastConfidence.LOW;
    }

    private static int confidencePercent(
            ForecastConfidence confidence,
            ForecastQuality quality,
            double signalStrength,
            double directionAgreement
    ) {
        int base;
        switch (confidence) {
            case HIGH:
                base = 82;
                break;
            case MEDIUM:
                base = 58;
                break;
            case LOW:
            default:
                base = 28;
                break;
        }

        int qualityAdj = (quality == ForecastQuality.HIGH) ? 8 : (quality == ForecastQuality.MEDIUM ? 0 : -6);
        int signalAdj = signalStrength >= 65.0 ? 6 : (signalStrength >= 35.0 ? 0 : -4);
        int agreementAdj = directionAgreement >= 0.75 ? 4 : (directionAgreement >= 0.5 ? 0 : -6);
        return Math.max(5, Math.min(98, base + qualityAdj + signalAdj + agreementAdj));
    }

    private static String yesterdayCompareText(WindowAnalysis yesterday3, WindowAnalysis current3, Units.System units) {
        if (yesterday3 == null || Double.isNaN(yesterday3.equivalentDelta)) {
            return "Как вчера: —";
        }
        double diff = current3.equivalentDelta - yesterday3.equivalentDelta;
        return "Вчера в похожем окне: " + Units.formatPressureDelta(yesterday3.equivalentDelta, units)
                + " • разница " + Units.formatPressureDelta(diff, units);
    }

    private static WindowAnalysis analyzeWindow(List<PressureSample> all, long from, long to, long nominalWindowMs) {
        List<PressureSample> window = filterWindow(all, from, to);
        return WindowAnalysis.fromSamples(window, nominalWindowMs);
    }

    private static List<PressureSample> filterWindow(List<PressureSample> source, long from, long to) {
        if (source == null || source.isEmpty() || from >= to) return Collections.emptyList();
        List<PressureSample> out = new ArrayList<>();
        for (PressureSample sample : source) {
            if (sample.timestamp < from) continue;
            if (sample.timestamp > to) break;
            out.add(sample);
        }
        return out;
    }

    private static List<PressureSample> smoothSeries(List<PressureSample> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        if (source.size() < 3) return new ArrayList<>(source);
        List<PressureSample> out = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            int from = Math.max(0, i - 2);
            int to = Math.min(source.size() - 1, i + 2);
            double sum = 0.0;
            int count = 0;
            for (int j = from; j <= to; j++) {
                sum += source.get(j).pressureHpa;
                count++;
            }
            PressureSample p = new PressureSample();
            p.timestamp = source.get(i).timestamp;
            p.pressureHpa = sum / count;
            out.add(p);
        }
        return out;
    }

    private static double maxNoise(WindowAnalysis w1, WindowAnalysis w3) {
        return Math.max(safe(w1.noiseStd), safe(w3.noiseStd));
    }

    private static double safe(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }

    private static double difference(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) return 0.0;
        return a - b;
    }

    private static int signum(double value) {
        if (Math.abs(value) < 0.05) return 0;
        return value > 0 ? 1 : -1;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String directionLine(String base, double delta, Units.System units) {
        return base + " (" + Units.formatPressureDelta(delta, units) + ")";
    }

    private static String bulletLines(List<String> lines) {
        return joinLines(prefixEach(lines, "• "));
    }

    private static List<String> prefixEach(List<String> lines, String prefix) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            out.add(prefix + line);
        }
        return out;
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static String qualityText(ForecastQuality quality) {
        if (quality == ForecastQuality.HIGH) return "высокое";
        if (quality == ForecastQuality.MEDIUM) return "среднее";
        return "низкое";
    }

    private static String noiseText(double noise) {
        if (noise >= 0.22) return "высокий";
        if (noise >= 0.10) return "средний";
        return "низкий";
    }

    private static List<Float> compressForSparkline(List<PressureSample> src, int target) {
        if (src == null || src.size() < 2) return new ArrayList<>();
        int n = src.size();
        if (n <= target) {
            ArrayList<Float> out = new ArrayList<>(n);
            for (PressureSample s : src) out.add((float) s.pressureHpa);
            return out;
        }
        ArrayList<Float> out = new ArrayList<>(target);
        for (int i = 0; i < target; i++) {
            int idx = (int) Math.round((i * (n - 1.0)) / (target - 1.0));
            out.add((float) src.get(idx).pressureHpa);
        }
        return out;
    }

    private static final class WindowAnalysis {
        final int count;
        final long spanMs;
        final long maxGapMs;
        final long lastTimestampMs;
        final double coverage;
        final double slopePerHour;
        final double equivalentDelta;
        final double noiseStd;

        private WindowAnalysis(
                int count,
                long spanMs,
                long maxGapMs,
                long lastTimestampMs,
                double coverage,
                double slopePerHour,
                double equivalentDelta,
                double noiseStd
        ) {
            this.count = count;
            this.spanMs = spanMs;
            this.maxGapMs = maxGapMs;
            this.lastTimestampMs = lastTimestampMs;
            this.coverage = coverage;
            this.slopePerHour = slopePerHour;
            this.equivalentDelta = equivalentDelta;
            this.noiseStd = noiseStd;
        }

        static WindowAnalysis fromSamples(List<PressureSample> source, long nominalWindowMs) {
            if (source == null || source.isEmpty()) {
                return new WindowAnalysis(0, 0L, nominalWindowMs, 0L, 0.0, Double.NaN, Double.NaN, Double.NaN);
            }
            if (source.size() == 1) {
                return new WindowAnalysis(1, 0L, nominalWindowMs, source.get(0).timestamp, 0.0, 0.0, 0.0, 0.0);
            }

            List<PressureSample> smoothed = smoothSeries(source);
            long firstTs = source.get(0).timestamp;
            long lastTs = source.get(source.size() - 1).timestamp;
            long spanMs = Math.max(0L, lastTs - firstTs);
            long maxGapMs = 0L;
            for (int i = 1; i < source.size(); i++) {
                maxGapMs = Math.max(maxGapMs, source.get(i).timestamp - source.get(i - 1).timestamp);
            }

            double slopePerHour = linearSlopePerHour(smoothed);
            double equivalentDelta = Double.isNaN(slopePerHour) ? Double.NaN : slopePerHour * (nominalWindowMs / (double) H1);
            double noiseStd = residualStd(smoothed, slopePerHour);
            double coverage = nominalWindowMs <= 0L ? 0.0 : Math.max(0.0, Math.min(1.0, spanMs / (double) nominalWindowMs));

            return new WindowAnalysis(
                    source.size(),
                    spanMs,
                    maxGapMs,
                    lastTs,
                    coverage,
                    slopePerHour,
                    equivalentDelta,
                    noiseStd
            );
        }

        private static double linearSlopePerHour(List<PressureSample> samples) {
            if (samples == null || samples.size() < 2) return Double.NaN;
            double meanT = 0.0;
            double meanP = 0.0;
            double t0 = samples.get(0).timestamp;
            for (PressureSample s : samples) {
                meanT += (s.timestamp - t0) / 3_600_000.0;
                meanP += s.pressureHpa;
            }
            meanT /= samples.size();
            meanP /= samples.size();
            double num = 0.0;
            double den = 0.0;
            for (PressureSample s : samples) {
                double x = (s.timestamp - t0) / 3_600_000.0 - meanT;
                double y = s.pressureHpa - meanP;
                num += x * y;
                den += x * x;
            }
            if (den <= 1e-9) return 0.0;
            return num / den;
        }

        private static double residualStd(List<PressureSample> samples, double slopePerHour) {
            if (samples == null || samples.size() < 3 || Double.isNaN(slopePerHour)) return 0.0;
            double t0 = samples.get(0).timestamp;
            double meanP = 0.0;
            double meanT = 0.0;
            for (PressureSample s : samples) {
                meanP += s.pressureHpa;
                meanT += (s.timestamp - t0) / 3_600_000.0;
            }
            meanP /= samples.size();
            meanT /= samples.size();
            double intercept = meanP - slopePerHour * meanT;
            double sum = 0.0;
            for (PressureSample s : samples) {
                double x = (s.timestamp - t0) / 3_600_000.0;
                double predicted = intercept + slopePerHour * x;
                double residual = s.pressureHpa - predicted;
                sum += residual * residual;
            }
            return Math.sqrt(sum / Math.max(1, samples.size() - 2));
        }
    }
}
