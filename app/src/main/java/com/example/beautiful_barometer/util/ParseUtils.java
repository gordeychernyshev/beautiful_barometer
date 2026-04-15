package com.example.beautiful_barometer.util;

public final class ParseUtils {

    private ParseUtils() {
    }

    public static double parseFlexibleDouble(String value, double fallback) {
        if (value == null) return fallback;

        String normalized = value.trim()
                .replace(" ", "")
                .replace(" ", "")
                .replace(',', '.');

        if (normalized.isEmpty()) return fallback;

        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
