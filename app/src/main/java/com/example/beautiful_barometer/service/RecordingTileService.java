package com.example.beautiful_barometer.service;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.example.beautiful_barometer.R;
import com.example.beautiful_barometer.util.DeviceCapabilities;
import com.example.beautiful_barometer.util.ServiceController;

public class RecordingTileService extends TileService {

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        Context appContext = getApplicationContext();
        if (!DeviceCapabilities.hasBarometer(appContext)) {
            updateTile();
            return;
        }

        boolean recordingActive = ServiceController.isRecordingEnabled(appContext)
                && ServiceController.isServiceRunning(appContext);
        if (recordingActive) {
            ServiceController.stopRecording(appContext);
        } else {
            ServiceController.startRecording(appContext);
        }
        updateTile();
    }

    public static void requestTileRefresh(Context context) {
        Context appContext = context.getApplicationContext();
        try {
            TileService.requestListeningState(
                    appContext,
                    new ComponentName(appContext, RecordingTileService.class)
            );
        } catch (RuntimeException ignored) {
            // Tile refresh is best-effort; recording state must not depend on SystemUI.
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        Context appContext = getApplicationContext();
        boolean hasBarometer = DeviceCapabilities.hasBarometer(appContext);
        boolean recordingEnabled = hasBarometer && ServiceController.isRecordingEnabled(appContext);

        String subtitle;
        String description;
        if (!hasBarometer) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            subtitle = getString(R.string.qs_recording_tile_unavailable);
            description = getString(R.string.qs_recording_tile_unavailable_desc);
        } else if (recordingEnabled) {
            tile.setState(Tile.STATE_ACTIVE);
            subtitle = getString(R.string.qs_recording_tile_on);
            description = getString(R.string.qs_recording_tile_on_desc);
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            subtitle = getString(R.string.qs_recording_tile_off);
            description = getString(R.string.qs_recording_tile_off_desc);
        }

        tile.setLabel(getString(R.string.qs_recording_tile_label));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_pressure));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(subtitle);
            tile.setContentDescription(description);
        }
        tile.updateTile();
    }
}
