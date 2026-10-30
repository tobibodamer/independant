package app.pendant.recording

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.pendant.Notifications
import app.pendant.PendantApp
import app.pendant.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/**
 * Foreground service ([ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE]) hosting
 * the recording engine round the clock.
 */
class RecordingService : Service() {

    private var engine: RecordingEngine? = null
    private var settingsSnap: Settings? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service onCreate")
        scope.launch {
            var previous: Settings? = null
            PendantApp.get(this@RecordingService).settings.settings.collectLatest { s ->
                val prev = previous
                settingsSnap = s
                if (prev != null && s != prev) {
                    Log.i(TAG, "settings changed, reconfiguring")
                    this@RecordingService.engine?.reconfigure(s)
                    updateNotification()
                }
                previous = s
            }
        }
        scope.launch {
            RecordingController.state.collectLatest { state ->
                if (state == RecordingController.State.RECORDING || state == RecordingController.State.PAUSED) {
                    updateNotification()
                }
                updateTile()
            }
        }
        scope.launch {
            var lastNotifMs = 0L
            RecordingController.stats.collectLatest { stats ->
                val now = System.currentTimeMillis()
                if (now - lastNotifMs > 2_500) {
                    lastNotifMs = now
                    updateNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                if (engine == null) startEngine()
            }
            ACTION_PAUSE -> {
                engine?.pause()
                RecordingController.post(RecordingController.State.PAUSED)
            }
            ACTION_RESUME -> {
                engine?.resume()
                RecordingController.post(RecordingController.State.RECORDING)
            }
            ACTION_STOP -> {
                engine?.stop()
                engine = null
                RecordingController.post(RecordingController.State.IDLE)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Service restarted by the system: keep recording if we were.
                if (engine == null) startEngine()
            }
        }
        updateTile()
        return START_STICKY
    }

    private fun startEngine() {
        val app = PendantApp.get(this)
        val s = runBlocking { app.settings.settings.first() }
        settingsSnap = s
        RecordingController.post(RecordingController.State.STARTING)
        try {
            goForeground(s.statusIndicator)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground rejected", e)
            Notifications.showAlert(
                this,
                "Pendant could not start recording",
                "Android blocked starting the microphone service from the background. Open Pendant and press start.",
            )
            RecordingController.post(RecordingController.State.IDLE)
            stopSelf()
            return
        }
        engine = RecordingEngine(this, s, app.store)
        engine!!.start()
        RecordingController.post(RecordingController.State.RECORDING)
    }

    private fun goForeground(indicator: app.pendant.settings.StatusBarIndicator) {
        val notif = Notifications.buildStatus(
            this,
            RecordingController.State.STARTING,
            "Starting capture…",
            indicator,
        ).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.NOTIF_FG_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(Notifications.NOTIF_FG_ID, notif)
        }
    }

    private fun updateNotification() {
        if (engine == null) return
        val state = RecordingController.state.value
        if (state != RecordingController.State.RECORDING && state != RecordingController.State.PAUSED) return
        val stats = RecordingController.stats.value
        val settings = settingsSnap ?: return
        val text = buildString {
            append(stats.sourceLabel.ifEmpty { "listening" })
            if (state == RecordingController.State.RECORDING) {
                if (stats.voiceActive) append("  •  voice")
                if (stats.segmentsToday > 0) append("  •  ${stats.segmentsToday} clips today")
            }
        }
        val notif = Notifications.buildStatus(this, state, text, settings.statusIndicator).build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        try {
            nm.notify(Notifications.NOTIF_FG_ID, notif)
        } catch (_: Exception) {
        }
    }

    private fun updateTile() {
        try {
            android.service.quicksettings.TileService.requestListeningState(
                this,
                android.content.ComponentName(this, app.pendant.tile.PauseTileService::class.java),
            )
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        scope.cancel()
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.pendant.action.START"
        const val ACTION_PAUSE = "app.pendant.action.PAUSE"
        const val ACTION_RESUME = "app.pendant.action.RESUME"
        const val ACTION_STOP = "app.pendant.action.STOP"
        private const val TAG = "PendantService"
    }
}
