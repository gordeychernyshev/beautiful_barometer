// app/src/main/java/com/example/beautiful_barometer/data/PressureDao.java
package com.example.beautiful_barometer.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PressureDao {

    // --- Вставка ---
    @Insert
    long insert(PressureSample s);

    // --- «Лайв» последняя точка (для наблюдений из UI) ---
    @Query("SELECT * FROM pressure_samples ORDER BY timestamp DESC LIMIT 1")
    LiveData<PressureSample> latestLive();

    // --- Последние N точек (новые сверху) ---
    @Query("SELECT * FROM pressure_samples ORDER BY timestamp DESC LIMIT :limit")
    List<PressureSample> latest(int limit);

    // --- Полный список по возрастанию времени (для экспорта/графика) ---
    @Query("SELECT * FROM pressure_samples ORDER BY timestamp ASC")
    List<PressureSample> allAsc();

    // --- Срез по времени (для графиков по диапазону) ---
    @Query("SELECT * FROM pressure_samples WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    List<PressureSample> betweenAsc(long from, long to);

    // --- Агрегаты по всей истории ---
    @Query("SELECT MIN(pressureHpa) FROM pressure_samples")
    Double minHpa();

    @Query("SELECT MAX(pressureHpa) FROM pressure_samples")
    Double maxHpa();

    @Query("SELECT AVG(pressureHpa) FROM pressure_samples")
    Double avgHpa();

    @Query("SELECT COUNT(*) FROM pressure_samples")
    int count();

    // --- Трим истории до последних keep записей ---
    @Query("DELETE FROM pressure_samples " +
            "WHERE id NOT IN (SELECT id FROM pressure_samples ORDER BY timestamp DESC LIMIT :keep)")
    void trimTo(int keep);

    // ======================
    // === Оконные методы ===
    // ======================

    // Самая ранняя точка в окне [from, to]
    @Query("SELECT pressureHpa FROM pressure_samples " +
            "WHERE timestamp BETWEEN :from AND :to " +
            "ORDER BY timestamp ASC LIMIT 1")
    Double firstHpaIn(long from, long to);

    // Самая поздняя точка в окне [from, to]
    @Query("SELECT pressureHpa FROM pressure_samples " +
            "WHERE timestamp BETWEEN :from AND :to " +
            "ORDER BY timestamp DESC LIMIT 1")
    Double lastHpaIn(long from, long to);

    // Среднее давление в окне [from, to]
    @Query("SELECT AVG(pressureHpa) FROM pressure_samples " +
            "WHERE timestamp BETWEEN :from AND :to")
    Double avgHpaBetween(long from, long to);

    // Количество точек в окне [from, to] — удобно, чтобы быстро понять, пустое окно или нет
    @Query("SELECT COUNT(*) FROM pressure_samples " +
            "WHERE timestamp BETWEEN :from AND :to")
    int countBetween(long from, long to);

    // Минимум/максимум в окне — бывает полезно для автоподбора шкалы на графике
    @Query("SELECT MIN(pressureHpa) FROM pressure_samples " +
            "WHERE timestamp BETWEEN :from AND :to")
    Double minHpaBetween(long from, long to);

    @Query("SELECT MAX(pressureHpa) FROM pressure_samples " +
            "WHERE timestamp BETWEEN :from AND :to")
    Double maxHpaBetween(long from, long to);

    // ==========================
    // === Агрегация для графика ===
    // ==========================

    /**
     * Агрегирует давление по "корзинам" времени (bucketMs) в окне [from, to].
     * Возвращает точки по возрастанию времени.
     */
    @Query("SELECT (timestamp / :bucketMs) * :bucketMs AS t, " +
            "AVG(pressureHpa) AS v " +
            "FROM pressure_samples " +
            "WHERE timestamp BETWEEN :from AND :to " +
            "GROUP BY t " +
            "ORDER BY t ASC")
    List<AggPoint> aggPressure(long from, long to, long bucketMs);


    @Query("DELETE FROM pressure_samples")
    void deleteAll();
}
