package com.example.beautiful_barometer.trip;

import com.example.beautiful_barometer.data.PressureSample;
import com.example.beautiful_barometer.util.Units;

import java.util.List;

public final class TripSuggestionEngine {

    public static final long WINDOW_MS = 30L * 60_000L;
    private static final long MIN_SPAN_MS = 15L * 60_000L;
    private static final long MAX_ALLOWED_GAP_MS = 8L * 60_000L;
    private static final double SIGNIFICANT_DELTA_HPA = 0.08d;
    private static final int MIN_POINTS = 8;
    private static final int MIN_DIRECTION_CHANGES = 4;
    private static final int MIN_SIGNIFICANT_MOVES = 6;
    private static final double MIN_RANGE_ALTITUDE_M = 18.0d;
    private static final double STRONG_RANGE_ALTITUDE_M = 30.0d;
    private static final double MIN_TOTAL_ABS_DELTA_HPA = 2.2d;
    private static final double MIN_NOISE_STD_HPA = 0.18d;
    private static final double MAX_NET_TO_TOTAL_RATIO = 0.55d;

    private TripSuggestionEngine() {
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
        int significantMoves = 0;
        int directionChanges = 0;
        long maxGapMs = 0L;
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
            if (absDelta >= SIGNIFICANT_DELTA_HPA) {
                significantMoves += 1;
                totalAbsDelta += absDelta;
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

        double rangeHpa = max - min;
        double rangeAltitudeM = Math.abs(Units.altitudeFromPressure(min) - Units.altitudeFromPressure(max));
        double netDelta = Math.abs(last.pressureHpa - first.pressureHpa);
        double netToTotalRatio = totalAbsDelta <= 0.0d ? 1.0d : (netDelta / totalAbsDelta);

        boolean oscillating = directionChanges >= MIN_DIRECTION_CHANGES
                && significantMoves >= MIN_SIGNIFICANT_MOVES
                && netToTotalRatio <= MAX_NET_TO_TOTAL_RATIO;

        boolean strongMovement = rangeAltitudeM >= MIN_RANGE_ALTITUDE_M
                && totalAbsDelta >= MIN_TOTAL_ABS_DELTA_HPA
                && noiseStd >= MIN_NOISE_STD_HPA;

        boolean veryStrongMovement = rangeAltitudeM >= STRONG_RANGE_ALTITUDE_M
                && significantMoves >= 4;

        if ((oscillating && strongMovement) || veryStrongMovement) {
            return Result.yes("motion_like_pressure_pattern");
        }

        return Result.no("not_motion_like");
    }
}
