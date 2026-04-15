package com.example.beautiful_barometer.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "events",
        indices = {@Index(value = {"timestamp"})}
)
public class EventSample {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestamp;

    @NonNull
    public String type;

    /** Доп. данные (например значение высоты/падения). */
    public String value;

    public EventSample(@NonNull String type, long timestamp, String value) {
        this.type = type;
        this.timestamp = timestamp;
        this.value = value;
    }
}
