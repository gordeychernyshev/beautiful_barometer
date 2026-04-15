package com.example.beautiful_barometer.ui.forecast;

import com.example.beautiful_barometer.util.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ForecastUiModel {
    public ForecastState state = ForecastState.INSUFFICIENT_DATA;

    public String icon = "⛅";
    public String title = "Прогноз";
    public String subtitle = "";
    public String description = "Собираем данные для прогноза";

    public String confidenceLabel = "низкая";
    public int confidencePercent = 0;
    public String confidenceDetail = "";

    public String chipDelta1h = "Δ1ч —";
    public String chipDelta3h = "Δ3ч —";
    public String chipQuality = "Качество: —";
    public String chipNoise = "Шум: —";

    public String reasonsSummary = "";
    public String compareYesterday = "Как вчера: —";
    public String advice = "";
    public String explainTitle = "Как мы считаем прогноз";
    public String explainBody = "";

    public boolean showChips = true;
    public boolean showSparkline = true;
    public boolean showReasons = true;
    public boolean showConfidence = true;
    public boolean pulse = false;

    public List<Float> sparklineSeries = new ArrayList<>();

    public static ForecastUiModel fromResult(ForecastResult result, Units.System units) {
        ForecastUiModel m = new ForecastUiModel();
        m.state = result.state;
        m.title = result.headline;
        m.subtitle = result.subtitle == null ? "" : result.subtitle;
        m.description = result.summary == null ? "" : result.summary;
        m.confidenceLabel = confidenceLabel(result.confidence);
        m.confidencePercent = clamp(result.confidencePercent);
        m.confidenceDetail = result.confidenceDetail == null ? "" : result.confidenceDetail;
        m.chipDelta1h = "Δ1ч: " + fmtDelta(result.trend1hDelta, units);
        m.chipDelta3h = "Δ3ч: " + fmtDelta(result.trend3hDelta, units);
        m.chipQuality = "Качество: " + qualityLabel(result.quality);
        m.chipNoise = "Шум: " + noiseLabel(result.noiseStd1h, result.noiseStd3h);
        m.reasonsSummary = joinReasons(result.reasons);
        m.compareYesterday = result.compareYesterday == null ? "Как вчера: —" : result.compareYesterday;
        m.advice = result.advice == null ? "" : result.advice;
        m.explainBody = result.explainBody == null ? "" : result.explainBody;
        m.sparklineSeries = result.sparklineSeries == null ? new ArrayList<>() : result.sparklineSeries;
        m.pulse = result.pulse;

        m.icon = iconForState(result.state);
        m.showSparkline = m.sparklineSeries != null && m.sparklineSeries.size() >= 2;
        m.showChips = result.state != ForecastState.NO_DATA && result.state != ForecastState.TRIP_MODE;
        m.showReasons = m.reasonsSummary != null && !m.reasonsSummary.isEmpty()
                && result.state != ForecastState.TRIP_MODE;
        m.showConfidence = result.state != ForecastState.TRIP_MODE;

        return m;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String confidenceLabel(ForecastConfidence confidence) {
        if (confidence == ForecastConfidence.HIGH) return "высокая";
        if (confidence == ForecastConfidence.MEDIUM) return "средняя";
        return "низкая";
    }

    private static String qualityLabel(ForecastQuality quality) {
        if (quality == ForecastQuality.HIGH) return "высокое";
        if (quality == ForecastQuality.MEDIUM) return "среднее";
        return "низкое";
    }

    private static String noiseLabel(double noise1h, double noise3h) {
        double noise = Math.max(safe(noise1h), safe(noise3h));
        if (noise >= 0.22) return "высокий";
        if (noise >= 0.10) return "средний";
        return "низкий";
    }

    private static double safe(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }

    private static String fmtDelta(double value, Units.System units) {
        if (Double.isNaN(value)) {
            return "—";
        }
        return Units.formatPressureDelta(value, units);
    }

    private static String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append("• " ).append(reasons.get(i));
        }
        return sb.toString();
    }

    private static String iconForState(ForecastState state) {
        switch (state) {
            case FAST_WORSENING:
                return "🌧️";
            case SLOW_WORSENING:
                return "☁️";
            case FAST_IMPROVING:
                return "🌤️";
            case SLOW_IMPROVING:
                return "⛅";
            case UNSTABLE:
                return "🌬️";
            case STABLE:
                return "😌";
            case TRIP_MODE:
                return "🚗";
            case NO_DATA:
            case INSUFFICIENT_DATA:
            default:
                return "⏳";
        }
    }
}
