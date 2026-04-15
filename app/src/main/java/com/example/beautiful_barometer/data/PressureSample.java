// app/src/main/java/com/example/beautiful_barometer/data/PressureSample.java
package com.example.beautiful_barometer.data;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "pressure_samples",
        indices = {@Index(value = {"timestamp"})}
)
public class PressureSample {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long timestamp;     // millis
    public double pressureHpa; // в hPa
}
