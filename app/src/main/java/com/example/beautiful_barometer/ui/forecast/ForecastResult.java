package com.example.beautiful_barometer.ui.forecast;

import java.util.ArrayList;
import java.util.List;

public class ForecastResult {
    public ForecastState state = ForecastState.NO_DATA;
    public ForecastConfidence confidence = ForecastConfidence.LOW;
    public ForecastQuality quality = ForecastQuality.LOW;

    public String headline = "Прогноз";
    public String subtitle = "";
    public String summary = "";
    public String confidenceDetail = "";
    public String compareYesterday = "Как вчера: —";
    public String advice = "";
    public String explainBody = "";

    public double trend1hDelta = Double.NaN;
    public double trend3hDelta = Double.NaN;
    public double trend6hDelta = Double.NaN;
    public double noiseStd1h = Double.NaN;
    public double noiseStd3h = Double.NaN;
    public double coverage1h = 0.0;
    public double coverage3h = 0.0;
    public double signalScore = 0.0;
    public int confidencePercent = 0;

    public boolean pulse = false;
    public List<String> reasons = new ArrayList<>();
    public List<Float> sparklineSeries = new ArrayList<>();
}
