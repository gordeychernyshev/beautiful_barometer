// app/src/main/java/com/example/beautiful_barometer/ui/SettingsActivity.java
package com.example.beautiful_barometer.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.beautiful_barometer.R;
import com.example.beautiful_barometer.util.ThemeController;

public class SettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeController.applyToActivity(this);
        super.onCreate(savedInstanceState);
        getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
    }
}
