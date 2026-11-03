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

    /** True while a screen collects [meter]; the engine only produces it then. */
    val meterObserved: Boolean
        get() = _meter.subscriptionCount.value > 0

    fun postMeter(m: MicMeter?) {
        _meter.value = m
    }

    /**
     * Live progress of the segment currently being recorded. Published on the same
     * "only while a screen watches" budget as [meter], so the capture loop stays
     * cheap whenever the app is in the background. [silenceMs]/[hangoverMs] let the
     * UI show the hold window: the segment stays open for [hangoverMs] of silence
     * before it closes, and speech within it continues the same clip.
     */
    data class LiveSegment(
        val durationMs: Long,
        val speechMs: Long,
        val silenceMs: Long,
        val minSegmentMs: Long,
        val hangoverMs: Long,
    )

    private val _liveSegment = MutableStateFlow<LiveSegment?>(null)
    val liveSegment: StateFlow<LiveSegment?> = _liveSegment

    fun postLiveSegment(s: LiveSegment?) {
        _liveSegment.value = s
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
            _liveSegment.value = null
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
