package com.example.beautiful_barometer.trip;

import com.example.beautiful_barometer.data.PressureSample;
import com.example.beautiful_barometer.util.Units;

import java.util.List;

public final class TripEndSuggestionEngine {

    public static final long WINDOW_MS = 60L * 60_000L;
    private static final long MIN_SPAN_MS = 45L * 60_000L;
    private static final long MAX_ALLOWED_GAP_MS = 12L * 60_000L;
    private static final int MIN_POINTS = 10;
    private static final double SIGNIFICANT_DELTA_HPA = 0.05d;
    private static final int MAX_DIRECTION_CHANGES = 2;
    private static final double MAX_RANGE_ALTITUDE_M = 8.0d;
    private static final double MAX_NOISE_STD_HPA = 0.11d;
    private static final double MAX_TOTAL_ABS_DELTA_HPA = 1.20d;
    private static final double MAX_NET_DELTA_HPA = 0.28d;

    private TripEndSuggestionEngine() {
    }

    public static final class Result {
        public final boolean shouldSuggest;
        public final String reasonCode;

        private Result(boolean shouldSuggest, String reasonCode) {
            this.shouldSuggest = shouldSuggest;
            this.reasonCode = reasonCode;
        }

        public static Result no(String reasonCode) {
            return new Result(false, reasonCode);
        }

        public static Result yes(String reasonCode) {
            return new Result(true, reasonCode);
        }
    }

    public static Result evaluate(List<PressureSample> samples) {
        if (samples == null || samples.size() < MIN_POINTS) {
            return Result.no("few_points");
        }

        PressureSample first = samples.get(0);
        PressureSample last = samples.get(samples.size() - 1);
        long spanMs = Math.max(0L, last.timestamp - first.timestamp);
        if (spanMs < MIN_SPAN_MS) {
            return Result.no("short_span");
        }

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0.0d;
        double totalAbsDelta = 0.0d;
        long maxGapMs = 0L;
        int directionChanges = 0;
        int prevDir = 0;

        for (int i = 0; i < samples.size(); i++) {
            double hpa = samples.get(i).pressureHpa;
            min = Math.min(min, hpa);
            max = Math.max(max, hpa);
            sum += hpa;

            if (i == 0) {
                continue;
            }

            PressureSample prev = samples.get(i - 1);
            maxGapMs = Math.max(maxGapMs, samples.get(i).timestamp - prev.timestamp);
            double delta = hpa - prev.pressureHpa;
            double absDelta = Math.abs(delta);
            totalAbsDelta += absDelta;
            if (absDelta >= SIGNIFICANT_DELTA_HPA) {
                int dir = delta > 0.0d ? 1 : -1;
                if (prevDir != 0 && dir != prevDir) {
                    directionChanges += 1;
                }
                prevDir = dir;
            }
        }

        if (maxGapMs > MAX_ALLOWED_GAP_MS) {
            return Result.no("large_gap");
        }

        double avg = sum / samples.size();
        double variance = 0.0d;
        for (PressureSample sample : samples) {
            double d = sample.pressureHpa - avg;
            variance += d * d;
        }
        variance /= Math.max(1, samples.size());
        double noiseStd = Math.sqrt(variance);

        double rangeAltitudeM = Math.abs(Units.altitudeFromPressure(min) - Units.altitudeFromPressure(max));
        double netDelta = Math.abs(last.pressureHpa - first.pressureHpa);

        boolean stableEnough = rangeAltitudeM <= MAX_RANGE_ALTITUDE_M
                && noiseStd <= MAX_NOISE_STD_HPA
                && totalAbsDelta <= MAX_TOTAL_ABS_DELTA_HPA
                && netDelta <= MAX_NET_DELTA_HPA
                && directionChanges <= MAX_DIRECTION_CHANGES;

        return stableEnough ? Result.yes("stable_after_motion") : Result.no("still_motion_like");
    }
}
