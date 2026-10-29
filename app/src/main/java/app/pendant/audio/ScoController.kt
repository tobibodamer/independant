package app.pendant.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Starts/stops the Bluetooth SCO (HFP) link so the headset mic can be recorded.
 * Requires MODIFY_AUDIO_SETTINGS. Must be called from a worker thread: enabling
 * blocks until the link is up or times out.
 */
class ScoController(private val am: AudioManager) {

    private var previousMode = AudioManager.MODE_NORMAL
    private var scoActive = false

    fun enable(device: AudioDeviceInfo): Boolean {
        if (scoActive) return true
        previousMode = am.mode
        return try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val ok = am.setCommunicationDevice(device)
                if (!ok) {
                    Log.w(TAG, "setCommunicationDevice returned false")
                }
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
            }
            if (awaitScoUp()) {
                scoActive = true
                true
            } else {
                Log.w(TAG, "SCO link did not come up in time")
                teardown()
                false
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SCO blocked: ${e.message}")
            teardown()
            false
        }
    }

    fun disable() {
        if (!scoActive) return
        scoActive = false
        teardown()
    }

    private fun awaitScoUp(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2500
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Build.VERSION.SDK_INT >= 31) {
                val cur = am.communicationDevice
                if (cur != null && cur.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    am.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ) {
                    return true
                }
            } else {
                @Suppress("DEPRECATION")
                if (am.isBluetoothScoOn) return true
            }
            try {
                Thread.sleep(60)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun teardown() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
            }
        } catch (_: Exception) {
        }
        try {
            am.mode = previousMode
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "PendantSco"
    }
}
