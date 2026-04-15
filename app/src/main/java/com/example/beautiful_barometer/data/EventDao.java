package com.example.beautiful_barometer.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface EventDao {

    @Insert
    long insert(EventSample e);

    @Query("SELECT * FROM events WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    List<EventSample> between(long from, long to);


    @Query("DELETE FROM events")
    void deleteAll();
}
