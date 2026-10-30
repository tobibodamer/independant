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
import app.pendant.recording.RecordingController
import app.pendant.segment.Segmenter
import app.pendant.settings.Detector
import app.pendant.settings.Settings
import app.pendant.storage.SegmentSink
import app.pendant.storage.SegmentStore
import app.pendant.settings.StorageMode
import app.pendant.vad.EnergyVad
import app.pendant.vad.SileroVad
import app.pendant.vad.Vad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private var lastErrorPostMs = 0L

    // Capture state (touched by the capture thread only, except closeSources from pause)
    private class Rec(val record: AudioRecord, val buffer: ShortArray)
    private val recs = ArrayList<Rec>()
    @Volatile private var vad: Vad = newVad(settings)
    /** Replaced detector still resident until the capture thread can close it. */
    @Volatile private var staleVad: Vad? = null
    private var segmenter: Segmenter? = null
    /** Mixed capture awaiting the VAD; the first [carry] samples are a partial frame from the last read. */
    private lateinit var mixedBlock: ShortArray
    private lateinit var mixed: ShortArray
    private var frameSamples = 0
    private var blockSamples = 0
    private var carry = 0
    /** Sleep between drains, bounded by the smallest AudioRecord buffer actually granted. */
    private var drainSleepMs = BLOCK_MS.toLong()

    /** Silero runs at 16 kHz internally; other rates only matter to the energy detector. */
    private fun newVad(s: Settings): Vad = when (s.detector) {
        Detector.SILERO -> SileroVad(context, s.sampleRate, s.sileroThresholdPct / 100f)
        Detector.ENERGY -> EnergyVad(
            s.sampleRate,
            s.sensitivity,
            s.customThresholdDb,
            fixedEnterDb = if (s.vadTracksFloor) null else s.absoluteThresholdDb.toFloat(),
        )
    }

    /** Swaps the live detector and defers the old session's close to the capture thread. */
    private fun swapVad(v: Vad) {
        val old = vad
        vad = v
        if (old !== v) staleVad = old
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        applyStorageLocation()
        paused.set(false)
        recomputeFrameBuffers()
        registerDeviceCallback()
        acquireWakeLock()
        thread = Thread({ loop() }, "pendant-capture").apply { start() }
        RecordingController.postSourceLabel("Starting…")
        scope.launch { refreshStats() }
    }

    fun pause() {
        if (!running.get() || paused.getAndSet(true)) return
        // Gracefully end any open segment and free the mic while paused.
        segmenter?.finishCurrent(store = true)
        closeSources()
        releaseWakeLock()
        RecordingController.postMeter(null)
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
            // The capture thread may be suspended inside a stuck codec call;
            // interrupt it so stop doesn't spend seconds waiting it out (and
            // can't leave a ghost capture loop running).
            thread?.interrupt()
            thread?.join(2500)
        } catch (_: InterruptedException) {
        }
        thread = null
        unregisterDeviceCallback()
        paused.set(false)
        closeSources()
        RecordingController.postMeter(null)
        segmenter = null
        releaseWakeLock()
        RecordingController.postSourceLabel("")
    }

    /** Applies new settings. Cheap changes take effect immediately; a sample-rate change restarts capture. */
    fun reconfigure(newSettings: Settings) {
        val oldRate = settings.sampleRate
        val rateChanged = newSettings.sampleRate != oldRate
        this.settings = newSettings
        // Storage location takes effect for NEW segments (the open recording keeps
        // writing where it started).
        applyStorageLocation()
        if (!running.get()) return
        if (rateChanged) {
            segmenter?.finishCurrent(store = true)
            segmenter = null
            closeSources()
            recomputeFrameBuffers()
            routeDirty = true
        } else {
            swapVad(newVad(newSettings))
            routeDirty = true
        }
    }

    // ------------------------------------------------------------------
    // Capture loop
    // ------------------------------------------------------------------

    private fun loop() {
        // Large blocks plus a multi-block AudioRecord buffer leave ample slack,
        // so the thread needs no audio priority (which favors the big cores).
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        var readFails = 0
        var meterTick = 0L
        var meterSmooth = Float.NaN
        var backlog = false
        while (running.get()) {
            try {
                // A reconfigure may have swapped the detector mid-frame; the
                // old one is closed only here, after the capture thread's last
                // use of it, so close() never races with process().
                staleVad?.let { try { it.close() } catch (_: Throwable) {} ; staleVad = null }
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
                // Unwatched, the thread sleeps and then drains whatever piled up
                // without blocking: a blocking read would still wake per audio-server
                // period. The live level meter needs per-frame updates, so it gets
                // blocking single-frame reads while observed.
                val metered = RecordingController.meterObserved
                if (!metered && !backlog) Thread.sleep(drainSleepMs)
                val want = if (metered) frameSamples else mixedBlock.size - carry
                val n = readMixed(carry, want, blocking = metered)
                backlog = !metered && n == want
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
                val readEndMs = System.currentTimeMillis()
                val total = carry + n
                var off = 0
                while (total - off >= frameSamples) {
                    val count = frameSamples
                    System.arraycopy(mixedBlock, off, mixed, 0, count)
                    off += count
                    if (mixed.all { it == 0.toShort() }) {
                        // Mic warm-up: AudioRecord streams all-zero frames for ~1 s at
                        // startup. Feeding them would seed the VAD noise floor at the
                        // digital-silence level and pin speech=true until the floor
                        // crawled back up (~1 min of "You're speaking").
                        continue
                    }
                    val speech = vad.process(mixed, count)
                    if (metered && ++meterTick % 2L == 0L) {
                        val e = vad.energyDb
                        meterSmooth = if (meterSmooth.isNaN()) e
                        else meterSmooth + (e - meterSmooth) * (if (e > meterSmooth) 0.45f else 0.12f)
                        RecordingController.postMeter(
                            RecordingController.MicMeter(meterSmooth, vad.enterThresholdDb, speech),
                        )
                    }
                    val frameEndMs = readEndMs - (total - off) * 1000L / settings.sampleRate
                    segmenter?.feed(mixed, count, speech, frameEndMs)
                }
                carry = total - off
                if (carry > 0) System.arraycopy(mixedBlock, off, mixedBlock, 0, carry)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "capture loop error", e)
                // Surface the problem to the UI (throttled) — silent capture failures
                // otherwise look like "recording but nothing appears".
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastErrorPostMs > 15_000) {
                    lastErrorPostMs = nowMs
                    RecordingController.postError("Recording problem: ${e.message ?: e::class.java.simpleName}")
                }
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
        blockSamples = frameSamples * BLOCK_FRAMES
        mixed = ShortArray(frameSamples)
        mixedBlock = ShortArray(blockSamples * 2)
        carry = 0
        segmenter = createSegmenter()
        swapVad(newVad(settings))
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
        override fun nextSegmentSink(wallClockMs: Long): SegmentSink? = try {
            store.newSegmentSink(wallClockMs)
        } catch (e: Exception) {
            Log.e(TAG, "cannot create segment destination", e)
            RecordingController.postError("Storage unavailable: ${e.message}")
            null
        }

        override fun createWriter(sink: SegmentSink, startWallClockMs: Long) = try {
            SegmentWriters.create(
                openDestination = { sink.openOutput() },
                sampleRate = settings.sampleRate,
                channels = 1,
                bitrateBps = settings.bitrateBps,
                minDurationMs = settings.minSegmentMs,
            )
        } catch (e: Exception) {
            Log.e(TAG, "cannot open encoder", e)
            null
        }

        override fun onSegmentStored(sink: SegmentSink, durationMs: Long) {
            try {
                sink.finalize(durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "finalize failed", e)
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

    private fun applyStorageLocation() {
        try {
            store.setLocation(
                SegmentStore.Location(
                    mode = settings.storageMode,
                    customTreeUri = settings.customTreeUri,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "cannot apply storage location", e)
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
        carry = 0
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
            if (recs.isNotEmpty()) break
        }
        val grantedMs = synchronized(recs) { recs.minOfOrNull { it.record.bufferSizeInFrames } }
            ?.let { it * 1000L / settings.sampleRate } ?: return
        // Wake well before the smallest buffer could overrun.
        drainSleepMs = minOf(BLOCK_MS.toLong(), grantedMs / 3).coerceAtLeast(FRAME_MS.toLong())
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
                .setBufferSizeInBytes(maxOf(minBuf * 2, blockSamples * 2 * BUFFER_BLOCKS))
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
        return Rec(rec, ShortArray(mixedBlock.size))
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

    /**
     * Reads up to [samples] from every source into [mixedBlock] at [offset]; returns
     * the count mixed (0 if a non-blocking read found nothing yet) or -1 on error.
     * Non-blocking mode takes what the first source has buffered and reads the same
     * amount from the others, which run on the same clock.
     */
    private fun readMixed(offset: Int, samples: Int, blocking: Boolean): Int {
        synchronized(recs) {
            if (recs.isEmpty()) return -1
            var count = samples
            for ((i, r) in recs.withIndex()) {
                val mode = if (blocking || i > 0) AudioRecord.READ_BLOCKING else AudioRecord.READ_NON_BLOCKING
                val n = try {
                    r.record.read(r.buffer, 0, count, mode)
                } catch (e: Exception) {
                    return -1
                }
                if (n < 0 || (n == 0 && (blocking || i > 0))) return -1
                if (n == 0) return 0
                count = minOf(count, n)
            }
            val first = recs[0].buffer
            if (recs.size == 1) {
                System.arraycopy(first, 0, mixedBlock, offset, count)
            } else {
                val second = recs[1].buffer
                for (i in 0 until count) {
                    val sum = (first[i] + second[i]) / 2
                    mixedBlock[offset + i] = sum.toShort()
                }
            }
            return count
        }
    }

    companion object {
        private const val TAG = "PendantEngine"
        const val FRAME_MS = 20
        /** Frames per drain cycle while nobody watches the level meter. */
        private const val BLOCK_FRAMES = 25
        private const val BLOCK_MS = BLOCK_FRAMES * FRAME_MS
        /** AudioRecord buffer capacity in blocks: slack for a late-scheduled capture thread. */
        private const val BUFFER_BLOCKS = 4
    }
}
