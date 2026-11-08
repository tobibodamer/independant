package app.pendant.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import app.pendant.audio.AudioRoutes
import app.pendant.audio.ScoController
import app.pendant.codec.SegmentWriters
import app.pendant.capture.CaptureProtocol
import app.pendant.capture.InputFacts
import app.pendant.events.EventLog
import app.pendant.recording.RecordingController
import app.pendant.segment.Segmenter
import app.pendant.settings.Detector
import app.pendant.settings.Settings
import app.pendant.storage.SegmentNames
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    private val recordings: app.pendant.storage.RecordingsSource,
) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val sco = ScoController(am)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val running = AtomicBoolean(false)
    /** The user's pause (button, tile, notification). */
    private val paused = AtomicBoolean(false)
    /** The capture service's `off` assignment; kept apart from [paused] so neither undoes the other. */
    private val remoteOff = AtomicBoolean(false)
    private fun idle() = paused.get() || remoteOff.get()
    @Volatile private var routeDirty = true

    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastErrorPostMs = 0L

    private val events = EventLog(context)
    /** Status lines for hippo connect, beside the clips; written only while a capture service is in use. */
    private val statusLog by lazy { EventLog(context, STATUS_DIR) }
    @Volatile private var captureManaged = false
    private var lastStatus: String? = null
    /** Called when the phone's input changes state or what it is doing (the capture agent sends a heartbeat). */
    @Volatile var onInputChanged: (() -> Unit)? = null
    /** Label of the opened route; shown again once another app stops silencing us. */
    @Volatile private var routeLabel = ""
    private var loggedRoute: Pair<String, Boolean>? = null
    /** Android hands our recording silence while a call or voice-chat app holds the mic. */
    @Volatile private var silenced = false

    // Heartbeat statistics (capture thread only).
    private var statWindowStartMs = 0L
    private var statFrames = 0
    private var statSpeechFrames = 0
    private var statLevelSumDb = 0.0

    // Live segment progress for the on-screen indicator (capture thread only;
    // -1 while no segment is open).
    private var liveSegmentMs = -1L
    private var liveSpeechMs = 0L
    private var liveSilenceMs = 0L

    // Overrun detection on the first source (guarded by recs).
    private var firstSourceFramesRead = 0L
    private var backlogBaselineFrames = -1L
    private var reportedLostFrames = 0L

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
        events.log("start") {
            put("detector", detectorLabel(settings))
            put("rate", settings.sampleRate)
            put("app", appVersion())
        }
        paused.set(false)
        recomputeFrameBuffers()
        registerDeviceCallback()
        registerRecordingCallback()
        acquireWakeLock()
        thread = Thread({ loop() }, "pendant-capture").apply { start() }
        RecordingController.postSourceLabel("Starting…")
        scope.launch {
            recordings.refresh() // staged-clip recovery can move clips without MediaStore churn
            refreshStats()
        }
    }

    fun pause() {
        if (!running.get() || paused.getAndSet(true)) return
        if (!remoteOff.get()) enterIdle("Paused", by = null)
        inputChanged()
    }

    fun resume() {
        if (!running.get() || !paused.getAndSet(false)) return
        if (!remoteOff.get()) leaveIdle(by = null)
        inputChanged()
    }

    /**
     * The capture service says another input records now (`off`) or this one should (`false`).
     * Stops after finishing the open clip, like a pause, but the user's pause stays theirs.
     */
    fun setRemoteOff(off: Boolean) {
        if (remoteOff.getAndSet(off) == off) return
        if (!running.get()) return
        if (!paused.get()) {
            if (off) enterIdle("Another device records", by = CAPTURE_BY) else leaveIdle(by = CAPTURE_BY)
        }
        inputChanged()
    }

    /** Gracefully ends any open segment and frees the mic while idle. */
    private fun enterIdle(label: String, by: String?) {
        segmenter?.finishCurrent(store = true)
        closeSources()
        events.log("pause") { if (by != null) put("by", by) }
        releaseWakeLock()
        RecordingController.postMeter(null)
        RecordingController.postLiveSegment(null)
        RecordingController.postSourceLabel(label)
    }

    private fun leaveIdle(by: String?) {
        events.log("resume") { if (by != null) put("by", by) }
        loggedRoute = null
        acquireWakeLock()
        routeDirty = true
    }

    /** What the capture service wants to know about this phone's input, right now. */
    fun inputFacts() = InputFacts(
        running = running.get(),
        permitted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        userPaused = paused.get(),
        silenced = silenced,
        remoteOff = remoteOff.get(),
    )

    /** Starts (or stops) writing status lines; the current state is written at once. */
    fun setCaptureManaged(on: Boolean) {
        if (captureManaged == on) return
        captureManaged = on
        lastStatus = null
        if (on) inputChanged() else setRemoteOff(false)
    }

    private fun inputChanged() {
        if (captureManaged) {
            val status = inputFacts().status
            if (status != lastStatus) {
                lastStatus = status
                statusLog.location = events.location
                statusLog.logRaw(CaptureProtocol.statusLine(Instant.now(), ZoneId.systemDefault(), status))
            }
        }
        onInputChanged?.invoke()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            unregisterDeviceCallback()
            unregisterRecordingCallback()
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
        unregisterRecordingCallback()
        paused.set(false)
        remoteOff.set(false)
        closeSources()
        events.log("stop")
        events.close()
        if (captureManaged) {
            captureManaged = false
            statusLog.close()
        }
        RecordingController.postMeter(null)
        RecordingController.postLiveSegment(null)
        segmenter = null
        releaseWakeLock()
        RecordingController.postSourceLabel("")
    }

    /** Applies new settings. Cheap changes take effect immediately; a sample-rate change restarts capture. */
    fun reconfigure(newSettings: Settings) {
        val oldRate = settings.sampleRate
        val rateChanged = newSettings.sampleRate != oldRate
        val oldDetector = detectorLabel(settings)
        this.settings = newSettings
        // Storage location takes effect for NEW segments (the open recording keeps
        // writing where it started).
        applyStorageLocation()
        if (!running.get()) return
        val detector = detectorLabel(newSettings)
        if (rateChanged || detector != oldDetector) {
            events.log("config") {
                put("detector", detector)
                put("rate", newSettings.sampleRate)
            }
        }
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
                if (idle()) {
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
                    statFrames++
                    if (speech) statSpeechFrames++
                    statLevelSumDb += vad.energyDb
                    if (metered && ++meterTick % 2L == 0L) {
                        val e = vad.energyDb
                        meterSmooth = if (meterSmooth.isNaN()) e
                        else meterSmooth + (e - meterSmooth) * (if (e > meterSmooth) 0.45f else 0.12f)
                        RecordingController.postMeter(
                            RecordingController.MicMeter(meterSmooth, vad.enterThresholdDb, speech),
                        )
                        if (liveSegmentMs >= 0) {
                            RecordingController.postLiveSegment(
                                RecordingController.LiveSegment(
                                    durationMs = liveSegmentMs,
                                    speechMs = liveSpeechMs,
                                    silenceMs = liveSilenceMs,
                                    minSegmentMs = settings.minSegmentMs,
                                    hangoverMs = settings.hangoverSec * 1000L,
                                ),
                            )
                        }
                    }
                    val frameEndMs = readEndMs - (total - off) * 1000L / settings.sampleRate
                    segmenter?.feed(mixed, count, speech, frameEndMs)
                }
                carry = total - off
                if (carry > 0) System.arraycopy(mixedBlock, off, mixedBlock, 0, carry)
                if (readEndMs - statWindowStartMs >= HEARTBEAT_MS) emitHeartbeat(readEndMs)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "capture loop error", e)
                // Surface the problem to the UI (throttled) — silent capture failures
                // otherwise look like "recording but nothing appears".
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastErrorPostMs > 15_000) {
                    lastErrorPostMs = nowMs
                    val message = e.message ?: e::class.java.simpleName
                    RecordingController.postError("Recording problem: $message")
                    events.log("error") { put("msg", message) }
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

        override fun createWriter(sink: SegmentSink, clipStartMs: Long) = try {
            SegmentWriters.create(
                openDestination = { sink.openOutput() },
                sampleRate = settings.sampleRate,
                channels = 1,
                bitrateBps = settings.bitrateBps,
                minDurationMs = settings.minSegmentMs,
                tags = clipTags(clipStartMs),
            )
        } catch (e: Exception) {
            Log.e(TAG, "cannot open encoder", e)
            null
        }

        override fun onSegmentStored(sink: SegmentSink, clipStartMs: Long, durationMs: Long, speechMs: Long) {
            liveSegmentMs = -1L
            RecordingController.postLiveSegment(null)
            try {
                sink.finalize(durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "finalize failed", e)
            }
            events.log("clip") {
                put("file", SegmentNames.finalizedName(sink.baseName, durationMs))
                put("start", clipStartMs)
                put("dur_ms", durationMs)
                put("speech_ms", speechMs)
            }
            scope.launch {
                store.cleanup(settings.maxStorageGb, settings.autoDeleteDays)
                events.prune(settings.autoDeleteDays)
                // Retention and the new clip change the listing even without
                // MediaStore (app-private mode); the Recordings flow rides the
                // same trigger instead of its own scheduled rescan.
                recordings.refresh()
                refreshStats()
            }
        }

        override fun onSegmentDiscarded() {
            liveSegmentMs = -1L
            RecordingController.postLiveSegment(null)
            RecordingController.postError(null)
        }

        override fun onSegmentProgress(durationMs: Long, speechMs: Long, silenceMs: Long) {
            liveSegmentMs = durationMs
            liveSpeechMs = speechMs
            liveSilenceMs = silenceMs
        }

        override fun onVoiceActive(active: Boolean) {
            // During the hangover hold this flips to false while the segment is
            // still open — the close callbacks below are what end the live view.
            RecordingController.postVoiceActive(active)
        }
    }

    private fun applyStorageLocation() {
        val location = SegmentStore.Location(mode = settings.storageMode, customTreeUri = settings.customTreeUri)
        events.location = location
        try {
            store.setLocation(location)
        } catch (e: Exception) {
            Log.w(TAG, "cannot apply storage location", e)
        }
    }

    // ------------------------------------------------------------------
    // Event log + clip metadata
    // ------------------------------------------------------------------

    private fun detectorLabel(s: Settings): String = when (s.detector) {
        Detector.SILERO -> "silero ${s.sileroThresholdPct}%"
        Detector.ENERGY -> if (s.vadTracksFloor) {
            val margin = if (s.sensitivity == EnergyVad.Sensitivity.CUSTOM) s.customThresholdDb else s.sensitivity.enterDb.toInt()
            "energy floor+${margin}dB"
        } else {
            "energy ${s.absoluteThresholdDb}dB"
        }
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** Opus comment tags that keep each clip self-describing without the event log. */
    private fun clipTags(clipStartMs: Long): List<Pair<String, String>> = listOf(
        "DATE" to DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(clipStartMs).atZone(ZoneId.systemDefault()),
        ),
        "PENDANT_START_MS" to clipStartMs.toString(),
        "PENDANT_MIC" to routeLabel,
        "PENDANT_DETECTOR" to detectorLabel(settings),
    )

    private fun resetHeartbeat(nowMs: Long) {
        statWindowStartMs = nowMs
        statFrames = 0
        statSpeechFrames = 0
        statLevelSumDb = 0.0
    }

    /**
     * Once a minute while listening: proof of life plus that minute's audio summary.
     * audio_s counts analyzed audio; silenced and warm-up frames are excluded.
     */
    private fun emitHeartbeat(nowMs: Long) {
        val frames = statFrames
        events.log(EventLog.ALIVE) {
            put("audio_s", frames * FRAME_MS / 1000)
            if (frames > 0) {
                put("level_db", (statLevelSumDb / frames).toFloat())
                put("floor_db", vad.floorDb)
                put("speech", statSpeechFrames.toFloat() / frames, decimals = 2)
            }
            if (silenced) put("silenced", true)
        }
        val lostMs = lostAudioMs()
        if (lostMs > 0) events.log("gap") { put("ms", lostMs) }
        resetHeartbeat(nowMs)
    }

    /**
     * Audio dropped by buffer overruns since the last report, in ms. Compares the
     * first source's capture position against what we read: the backlog between
     * them only grows when frames were lost.
     */
    private fun lostAudioMs(): Long = synchronized(recs) {
        val r = recs.firstOrNull() ?: return 0
        val ts = AudioTimestamp()
        if (r.record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return 0
        val rate = settings.sampleRate
        val position = ts.framePosition + (System.nanoTime() - ts.nanoTime) * rate / 1_000_000_000L
        val backlog = position - firstSourceFramesRead
        if (backlogBaselineFrames < 0) {
            backlogBaselineFrames = backlog
            return 0
        }
        val lost = backlog - backlogBaselineFrames - reportedLostFrames
        val lostMs = lost * 1000 / rate
        if (lostMs < GAP_REPORT_MS) return 0
        reportedLostFrames += lost
        lostMs
    }

    private fun registerRecordingCallback() {
        try {
            am.registerAudioRecordingCallback(recordingCallback, null)
        } catch (_: Exception) {
        }
    }

    private fun unregisterRecordingCallback() {
        try {
            am.unregisterAudioRecordingCallback(recordingCallback)
        } catch (_: Exception) {
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val sessions = synchronized(recs) { recs.map { it.record.audioSessionId } }
            if (sessions.isEmpty()) return
            val now = configs.any { it.clientAudioSessionId in sessions && it.isClientSilenced }
            if (now == silenced) return
            silenced = now
            events.log(if (now) "silenced" else "unsilenced")
            RecordingController.postSourceLabel(if (now) "Mic in use by another app" else routeLabel)
            inputChanged()
        }
    }

    private fun refreshStats() {
        // Fast path only: this runs at service start and after every finalized
        // clip, where a SAF-tree crawl (which list() only performs for the
        // Recordings UI append) or a separate bytes scan must not appear.
        val segments = store.list()
        val startOfDay = DayKey.startOfDayMs(System.currentTimeMillis())
        val today = segments.filter { it.lastModifiedMs >= startOfDay }
        RecordingController.postStats(
            RecordingController.Stats(
                segmentsToday = today.size.toLong(),
                secondsToday = today.sumOf { it.durationMs } / 1000,
                // Fast-backend bytes; clips living only in a custom SAF tree are
                // left out rather than re-enumerating the tree for an exact total.
                totalBytes = segments.sumOf { it.sizeBytes },
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
        if (idle()) return
        val label = openPlannedRoute()
        val open = synchronized(recs) { recs.isNotEmpty() }
        routeLabel = label
        RecordingController.postSourceLabel(label)
        resetHeartbeat(System.currentTimeMillis())
        if (loggedRoute != label to open) {
            loggedRoute = label to open
            events.log("mic") {
                put("mic", label)
                put("open", open)
            }
        }
    }

    /** Opens the sources for the current route plan; returns the label of what it chose. */
    private fun openPlannedRoute(): String {
        val plan = planRoute()
        RecordingController.postSourceLabel(plan.label)
        if (plan.needsScoReset) sco.disable()

        if (plan.scoDevice != null) {
            val ok = sco.enable(plan.scoDevice)
            if (!ok) {
                // SCO refused: fall back like AUTOMATIC would.
                sco.disable()
                val builtin = pickBuiltin()
                if (builtin.isEmpty()) return "No microphone available"
                openRecords(builtin, voiceFirst = !settings.phoneProcessing)
                return AudioRoutes.label(builtin[0])
            }
        }

        if (plan.targets.isNotEmpty()) {
            val voiceFirst = plan.targets[0].type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO && !settings.phoneProcessing
            openRecords(plan.targets, voiceFirst)
        }
        // If nothing got opened (e.g. BLUETOOTH_ONLY with no headset) we simply idle.
        return plan.label
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

    // RECORD_AUDIO is verified by every entry point (MainActivity, PauseTileService)
    // before the engine starts; candidates are retried/broken out of on failure.
    @SuppressLint("MissingPermission")
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
            firstSourceFramesRead = 0
            backlogBaselineFrames = -1
            reportedLostFrames = 0
        }
        silenced = false
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
            firstSourceFramesRead += count
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
        private const val STATUS_DIR = "status"
        private const val CAPTURE_BY = "capture"
        const val FRAME_MS = 20
        /** Frames per drain cycle while nobody watches the level meter. */
        private const val BLOCK_FRAMES = 25
        private const val BLOCK_MS = BLOCK_FRAMES * FRAME_MS
        /** AudioRecord buffer capacity in blocks: slack for a late-scheduled capture thread. */
        private const val BUFFER_BLOCKS = 4
        private const val HEARTBEAT_MS = 60_000L
        /** Smaller backlog growth is drain jitter, not lost audio. */
        private const val GAP_REPORT_MS = 200L
    }
}
