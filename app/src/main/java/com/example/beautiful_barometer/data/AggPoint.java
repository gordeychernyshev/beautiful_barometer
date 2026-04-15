package com.example.beautiful_barometer.data;

/**
 * Простая DTO для агрегированных точек графика.
 * Room заполнит поля по алиасам из SELECT.
 */
public class AggPoint {
    public long t;      // bucket timestamp (ms)
    public double v;    // averaged value (hPa)
}
