package app.pendant.recording

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing state singleton. The service writes, the UI reads.
 */
object RecordingController {

    enum class State { IDLE, STARTING, RECORDING, PAUSED }

    data class Stats(
        val segmentsToday: Long = 0,
        val secondsToday: Long = 0,
        val totalBytes: Long = 0,
        val voiceActive: Boolean = false,
        val sourceLabel: String = "",
    )

    /** Live values for the settings-screen level meter, published by the
     *  running engine so the meter mirrors the real detector and mic route. */
    data class MicMeter(val levelDb: Float, val thresholdDb: Float, val speaking: Boolean)

    private val _meter = MutableStateFlow<MicMeter?>(null)
    val meter: StateFlow<MicMeter?> = _meter

    fun postMeter(m: MicMeter?) {
        _meter.value = m
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun post(state: State) {
        _state.value = state
        if (state != State.PAUSED && state != State.RECORDING) {
            _stats.value = _stats.value.copy(voiceActive = false)
        }
    }

    fun postStats(stats: Stats) {
        _stats.value = stats
    }

    fun postSourceLabel(label: String) {
        _stats.value = _stats.value.copy(sourceLabel = label)
    }

    fun postVoiceActive(active: Boolean) {
        if (_stats.value.voiceActive != active) _stats.value = _stats.value.copy(voiceActive = active)
    }

    fun postError(message: String?) {
        _error.value = message
    }

    // ------------------------------------------------------------------
    // Commands (only valid once the service exists / foreground service rules)
    // ------------------------------------------------------------------

    fun start(context: Context) {
        android.util.Log.i("PendantService", "controller.start requested")
        context.startForegroundService(serviceIntent(context, RecordingService.ACTION_START))
    }

    fun pause(context: Context) {
        context.startService(serviceIntent(context, RecordingService.ACTION_PAUSE))
    }

    fun resume(context: Context) {
        context.startService(serviceIntent(context, RecordingService.ACTION_RESUME))
    }

    fun stop(context: Context) {
        context.startService(serviceIntent(context, RecordingService.ACTION_STOP))
    }

    private fun serviceIntent(context: Context, action: String) =
        Intent(context, RecordingService::class.java).setAction(action)
}
