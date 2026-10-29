package app.pendant.tile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.pendant.Notifications
import app.pendant.recording.RecordingController
import app.pendant.recording.RecordingService

/**
 * Quick-settings tile for the fastest possible pause/resume (+ start) toggle.
 */
class PauseTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        when (RecordingController.state.value) {
            RecordingController.State.RECORDING -> RecordingController.pause(applicationContext)
            RecordingController.State.PAUSED -> RecordingController.resume(applicationContext)
            else -> tryStart()
        }
        updateTile()
    }

    private fun tryStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Notifications.showAlert(
                this,
                "Pendant",
                "Open Pendant once and grant microphone access, then the tile can start recording.",
            )
            return
        }
        try {
            RecordingController.start(applicationContext)
        } catch (e: Exception) {
            Notifications.showAlert(this, "Pendant", "Cannot start from the quick settings here. Open the app instead.")
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when (val state = RecordingController.state.value) {
            RecordingController.State.RECORDING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Recording"
            }
            RecordingController.State.PAUSED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Paused"
            }
            RecordingController.State.STARTING -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Starting…"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Tap to listen"
            }
        }
        tile.updateTile()
    }
}
