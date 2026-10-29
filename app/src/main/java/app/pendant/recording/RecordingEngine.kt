package app.pendant.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import app.pendant.audio.AudioRoutes
import app.pendant.audio.ScoController
import app.pendant.codec.SegmentWriters
import app.pendant.segment.Segmenter
import app.pendant.settings.Settings
import app.pendant.storage.SegmentStore
import app.pendant.vad.EnergyVad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the 24/7 capture loop:
 *
 *   AudioRecord(s) -> mix -> VAD -> Segmenter -> Opus/AAC writer -> files
 *
 * Runs on a dedicated audio-priority thread. Route changes (Bluetooth
 * connect/disconnect, wired headsets) are detected via an AudioDeviceCallback
 * and applied between frames without losing VAD state or the current segment.
 */
class RecordingEngine(
    private val context: Context,
    private var settings: Settings,
    private val store: SegmentStore,
) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val sco = ScoController(am)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    @Volatile private var routeDirty = true

    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Capture state (touched by the capture thread only, except closeSources from pause)
    private class Rec(val record: AudioRecord, val buffer: ShortArray)
    private val recs = ArrayList<Rec>()
    @Volatile private var vad = EnergyVad(settings.sampleRate, settings.sensitivity)
    private var segmenter: Segmenter? = null
    private lateinit var mixed: ShortArray
    private var frameSamples = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        paused.set(false)
        recomputeFrameBuffers()
        registerDeviceCallback()
        acquireWakeLock()
        thread = Thread({ loop() }, "pendant-capture").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
        RecordingController.postSourceLabel("Starting…")
        scope.launch { refreshStats() }
    }

    fun pause() {
        if (!running.get() || paused.getAndSet(true)) return
        // Gracefully end any open segment and free the mic while paused.
        segmenter?.finishCurrent(store = true)
        closeSources()
        releaseWakeLock()
        RecordingController.postSourceLabel("Paused")
    }

    fun resume() {
        if (!running.get() || !paused.getAndSet(false)) return
        acquireWakeLock()
        routeDirty = true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            unregisterDeviceCallback()
            return
        }
        try {
            thread?.join(3000)
        } catch (_: InterruptedException) {
        }
        thread = null
        unregisterDeviceCallback()
        paused.set(false)
        closeSources()
        segmenter = null
        releaseWakeLock()
        RecordingController.postSourceLabel("")
    }

    /** Applies new settings. Cheap changes take effect immediately; a sample-rate change restarts capture. */
    fun reconfigure(newSettings: Settings) {
        val oldRate = settings.sampleRate
        val rateChanged = newSettings.sampleRate != oldRate
        this.settings = newSettings
        if (!running.get()) return
        if (rateChanged) {
            segmenter?.finishCurrent(store = true)
            segmenter = null
            closeSources()
            recomputeFrameBuffers()
            routeDirty = true
        } else {
            vad = EnergyVad(newSettings.sampleRate, newSettings.sensitivity)
            routeDirty = true
        }
    }

    // ------------------------------------------------------------------
    // Capture loop
    // ------------------------------------------------------------------

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var readFails = 0
        while (running.get()) {
            try {
                if (routeDirty) {
                    routeDirty = false
                    openSources()
                    continue
                }
                if (paused.get()) {
                    Thread.sleep(200)
                    continue
                }
                if (synchronized(recs) { recs.isEmpty() }) {
                    Thread.sleep(300)
                    continue
                }
                val n = readMixed()
                if (n < 0) {
                    if (++readFails >= 20) {
                        readFails = 0
                        Log.w(TAG, "read loop error: rebuilding sources")
                        closeSources()
                        routeDirty = true
                    }
                    Thread.sleep(20)
                    continue
                }
                readFails = 0
                val speech = vad.process(mixed, n)
                segmenter?.feed(mixed, n, speech, System.currentTimeMillis())
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "capture loop error", e)
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        segmenter?.finishCurrent(store = true)
        closeSources()
    }

    private fun recomputeFrameBuffers() {
        frameSamples = settings.sampleRate * FRAME_MS / 1000
        mixed = ShortArray(frameSamples)
        segmenter = createSegmenter()
        vad = EnergyVad(settings.sampleRate, settings.sensitivity)
    }

    private fun createSegmenter() = Segmenter(
        sampleRate = settings.sampleRate,
        frameSamples = frameSamples,
        prerollMs = settings.prerollMs,
        hangoverSec = settings.hangoverSec,
        minSegmentMs = settings.minSegmentMs,
        maxSegmentSec = settings.maxSegmentSec,
        listener = segmentListener,
    )

    private val segmentListener = object : Segmenter.Listener {
        override fun nextSegmentFile(wallClockMs: Long): File? = try {
            store.newSegmentFile(wallClockMs)
        } catch (e: Exception) {
            Log.e(TAG, "cannot create segment file", e)
            RecordingController.postError("Storage unavailable: ${e.message}")
            null
        }

        override fun createWriter(file: File, startWallClockMs: Long) = try {
            SegmentWriters.create(
                file = file,
                sampleRate = settings.sampleRate,
                channels = 1,
                bitrateBps = settings.bitrateBps,
                minDurationMs = settings.minSegmentMs,
            )
        } catch (e: Exception) {
            Log.e(TAG, "cannot open encoder", e)
            null
        }

        override fun onSegmentStored(file: File, durationMs: Long) {
            try {
                store.finalizeName(file, durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "rename failed", e)
            }
            scope.launch {
                store.cleanup(settings.maxStorageGb, settings.autoDeleteDays)
                refreshStats()
            }
        }

        override fun onSegmentDiscarded() {
            RecordingController.postError(null)
        }

        override fun onVoiceActive(active: Boolean) {
            RecordingController.postVoiceActive(active)
        }
    }

    private fun refreshStats() {
        val segments = store.list()
        val startOfDay = DayKey.startOfDayMs(System.currentTimeMillis())
        val today = segments.filter { it.lastModifiedMs >= startOfDay }
        RecordingController.postStats(
            RecordingController.Stats(
                segmentsToday = today.size.toLong(),
                secondsToday = today.sumOf { it.durationMs } / 1000,
                totalBytes = store.totalBytes(),
                voiceActive = RecordingController.stats.value.voiceActive,
                sourceLabel = RecordingController.stats.value.sourceLabel,
            ),
        )
    }

    private object DayKey {
        fun startOfDayMs(nowMs: Long): Long {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = nowMs
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }

    // ------------------------------------------------------------------
    // Source management (route switching)
    // ------------------------------------------------------------------

    private fun registerDeviceCallback() {
        try {
            am.registerAudioDeviceCallback(deviceCallback, null)
        } catch (_: Exception) {
        }
    }

    private fun unregisterDeviceCallback() {
        try {
            am.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) {
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = markRouteDirty()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = markRouteDirty()
        private fun markRouteDirty() {
            routeDirty = true
        }
    }

    private fun openSources() {
        closeSources()
        if (paused.get()) return

        val plan = planRoute()
        RecordingController.postSourceLabel(plan.label)
        if (plan.needsScoReset) sco.disable()

        if (plan.scoDevice != null) {
            val ok = sco.enable(plan.scoDevice)
            if (!ok) {
                // SCO refused: fall back like AUTOMATIC would.
                sco.disable()
                val builtin = pickBuiltin()
                if (builtin.isNotEmpty()) {
                    openRecords(builtin, voiceFirst = !settings.phoneProcessing)
                }
                return
            }
        }

        if (plan.targets.isNotEmpty()) {
            val voiceFirst = plan.targets[0].type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO && !settings.phoneProcessing
            openRecords(plan.targets, voiceFirst)
        }
        // If nothing got opened (e.g. BLUETOOTH_ONLY with no headset) we simply idle.
    }

    private class RoutePlan(
        val label: String,
        val targets: List<AudioDeviceInfo>,
        val scoDevice: AudioDeviceInfo? = null,
        val needsScoReset: Boolean = false,
    )

    private fun planRoute(): RoutePlan {
        val bt = preferredBtMic()
        return when (settings.routeMode) {
            app.pendant.settings.RouteMode.BLUETOOTH_ONLY ->
                if (bt != null) RoutePlan(AudioRoutes.label(bt), listOf(bt), scoDevice = bt, needsScoReset = true)
                else RoutePlan("Waiting for Bluetooth headset…", emptyList(), needsScoReset = true)

            app.pendant.settings.RouteMode.BLUETOOTH_PREFERRED ->
                if (bt != null) RoutePlan(AudioRoutes.label(bt), listOf(bt), scoDevice = bt, needsScoReset = true)
                else {
                    val b = pickBuiltin()
                    RoutePlan(if (b.isEmpty()) "No microphone available" else AudioRoutes.label(b[0]), b, needsScoReset = true)
                }

            app.pendant.settings.RouteMode.AUTOMATIC ->
                if (bt != null) {
                    RoutePlan(AudioRoutes.label(bt), listOf(bt), scoDevice = bt)
                } else {
                    val wired = AudioRoutes.wiredMics(am)
                    if (wired.isNotEmpty()) RoutePlan(AudioRoutes.label(wired[0]), listOf(wired[0]))
                    else {
                        val b = pickBuiltin()
                        RoutePlan(if (b.isEmpty()) "No microphone available" else AudioRoutes.label(b[0]), b)
                    }
                }

            app.pendant.settings.RouteMode.INTERNAL_ONLY -> {
                val b = pickBuiltin()
                val label = when {
                    b.isEmpty() -> "No microphone available"
                    b.size > 1 -> "${AudioRoutes.label(b[0])} + ${AudioRoutes.label(b[1])}"
                    else -> AudioRoutes.label(b[0])
                }
                RoutePlan(label, b)
            }
        }
    }

    private fun preferredBtMic(): AudioDeviceInfo? {
        val all = AudioRoutes.bluetoothMics(am)
        if (all.isEmpty()) return null
        val pref = settings.preferredBtAddress
        if (pref.isBlank()) return all.first()
        return all.firstOrNull { it.address == pref } ?: all.first()
    }

    private fun pickBuiltin(): List<AudioDeviceInfo> =
        AudioRoutes.select(AudioRoutes.builtinMics(am), settings.builtinMic)

    @SuppressLint("MissingPermission")
    private fun openRecords(targets: List<AudioDeviceInfo>, voiceFirst: Boolean) {
        val sources = if (voiceFirst) {
            intArrayOf(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, android.media.MediaRecorder.AudioSource.MIC)
        } else {
            intArrayOf(android.media.MediaRecorder.AudioSource.MIC, android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }
        outer@ for (source in sources) {
            for (target in targets) {
                val rec = openRecord(source, target)
                if (rec == null) continue@outer // mic busy/unavailable: try next source
                synchronized(recs) { recs.add(rec) }
            }
            if (recs.isNotEmpty()) return
        }
    }

    private fun openRecord(source: Int, target: AudioDeviceInfo?): Rec? {
        val minBuf = AudioRecord.getMinBufferSize(settings.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(settings.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, frameSamples * 8))
                .build()
        } catch (e: Throwable) {
            Log.w(TAG, "cannot create AudioRecord: ${e.message}")
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        if (target != null) {
            try {
                rec.preferredDevice = target
            } catch (e: Exception) {
                Log.w(TAG, "preferredDevice failed: ${e.message}")
            }
        }
        rec.startRecording()
        return Rec(rec, ShortArray(frameSamples))
    }

    /** Releases all AudioRecords and drops the SCO link. Thread-safe. */
    private fun closeSources() {
        synchronized(recs) {
            for (r in recs) {
                try {
                    r.record.stop()
                } catch (_: Exception) {
                }
                try {
                    r.record.release()
                } catch (_: Exception) {
                }
            }
            recs.clear()
        }
        sco.disable()
    }

    private fun acquireWakeLock() {
        if (!settings.keepCpuAwake || wakeLock != null) return
        wakeLock = try {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pendant:capture").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    // ------------------------------------------------------------------
    // Frame reading + mixing
    // ------------------------------------------------------------------

    private fun readMixed(): Int {
        synchronized(recs) {
            if (recs.isEmpty()) return -1
            var min = Int.MAX_VALUE
            for (r in recs) {
                val n = try {
                    r.record.read(r.buffer, 0, frameSamples)
                } catch (e: Exception) {
                    return -1
                }
                if (n <= 0) return -1
                min = minOf(min, n)
            }
            val first = recs[0].buffer
            if (recs.size == 1) {
                System.arraycopy(first, 0, mixed, 0, min)
            } else {
                val second = recs[1].buffer
                for (i in 0 until min) {
                    val sum = (first[i] + second[i]) / 2
                    mixed[i] = sum.toShort()
                }
            }
            return min
        }
    }

    companion object {
        private const val TAG = "PendantEngine"
        const val FRAME_MS = 20
    }
}
