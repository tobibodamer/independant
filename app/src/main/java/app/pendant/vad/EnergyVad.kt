package app.pendant.vad

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight, adaptive energy VAD.
 *
 * First-order high-pass + frame RMS in dBFS, tracked against a slowly
 * adapting noise floor with hysteresis. Costs a few integer MACs per frame
 * (far below a millisecond per 20 ms frame) which is what makes 24/7 capture
 * affordable on battery.
 *
 * Speech detection additionally needs ~2 of the last 3 frames above threshold
 * to confirm an onset, which suppresses clicks and keyboard taps.
 */
class EnergyVad(
    sampleRate: Int,
    sensitivity: Sensitivity = Sensitivity.MEDIUM,
    /** Enter margin used when sensitivity == CUSTOM (see SettingsScreen level meter). */
    customEnterDb: Int = Sensitivity.MEDIUM.enterDb.toInt(),
    /** Absolute enter threshold in dB. When set, triggering ignores the adaptive
     *  noise floor entirely (the "fixed level" option in the settings meter). */
    fixedEnterDb: Float? = null,
) : Vad {
    enum class Sensitivity(val enterDb: Float) {
        LOW(15f),
        MEDIUM(11f),
        HIGH(7f),
        /** Manual threshold from settings; the settings screen has a live level meter to pick it. */
        CUSTOM(11f),
    }

    private val hpPrevX = DoubleArray(1)
    private val hpPrevY = DoubleArray(1)

    private val enterDb = if (sensitivity == Sensitivity.CUSTOM) customEnterDb.toFloat() else sensitivity.enterDb
    private val exitDb = enterDb * 0.55f
    private val fixedEnter = fixedEnterDb

    private var noiseFloorDb = Float.MAX_VALUE // min-of-priming bootstrap starts clean
    private var frame1Db = -120f
    private var frame2Db = -120f
    private var speech = false
    private var framesSeen = 0

    /** While starting up we always track the floor (the app opens in silence). */
    private val primingFrames = 25

    /** Process one frame; returns true while speech is considered active. */
    override fun process(frame: ShortArray, count: Int): Boolean {
        val energy = frameEnergyDb(frame, count)
        frame2Db = frame1Db
        frame1Db = energy
        framesSeen++

        // Track the noise floor:
        // - priming (first ~500 ms): floor = lowest frame energy seen so far. No
        //   hard first-frame seed (a loud burst at capture start must not pin the
        //   floor high and deafen the detector), and no slowly-crawling seed
        //   (that would let the enter threshold fire spuriously mid-prime).
        // - silent: fast down / bounded slow up keeps the floor near a low quantile
        //   of the silence energy (robust vs an unbounded minimum).
        // - during an open segment: slow upward drift toward ambient noise (bounded
        //   below speech bursts), so a rise in room noise can never wedge the exit
        //   threshold and keep a segment open forever.
        when {
            framesSeen <= primingFrames -> noiseFloorDb = min(noiseFloorDb, energy)
            !speech -> noiseFloorDb = if (energy < noiseFloorDb) {
                noiseFloorDb * 0.9f + energy * 0.1f
            } else {
                min(noiseFloorDb + floorRisePerFrame(), energy)
            }
            else -> noiseFloorDb = min(noiseFloorDb + FLOOR_RISE_PER_FRAME, energy)
        }
        if (framesSeen % DIAG_FRAME_INTERVAL == 1) {
            // println (not android.util.Log) keeps this JVM-testable; it surfaces in
            // logcat as "System.out" anyway. Used to plot level vs. thresholds.
            println(
                "PendantEngine: vad energy=%.1f floor=%.1f enter=%.1f exit=%.1f speech=%b"
                    .format(energy, noiseFloorDb, noiseFloorDb + enterDb, noiseFloorDb + exitDb, speech),
            )
        }

        // Fixed mode judges against one absolute level (-enterAbs-); floor-relative
        // mode judges against the adaptive floor + margin. The "confirm" pre-window
        // is roughly proportional in both cases (~70% of the margin).
        val enterAbs = fixedEnter ?: (noiseFloorDb + enterDb)
        val exitAbs = fixedEnter?.let { it - exitDb } ?: (noiseFloorDb + exitDb)
        val nearWindow = fixedEnter?.let { 5f } ?: (enterDb * 0.6f)

        val aboveEnter = energy > enterAbs
        val nearEnter = frame1Db > enterAbs - nearWindow || frame2Db > enterAbs - nearWindow

        if (!speech) {
            if (aboveEnter && nearEnter) {
                speech = true
            }
        } else {
            if (energy < exitAbs && frame2Db < exitAbs) {
                speech = false
            }
        }
        return speech
    }

    /** Current signal level above the noise floor, in dB (for UI debug). */
    val marginDb: Float
        get() = frame1Db - noiseFloorDb

    // --- Live values for the settings-screen level meter ---
    /** Absolute energy (dB) of the most recent frame. */
    override val energyDb: Float
        get() = frame1Db

    /** Current adaptive noise floor (dB, absolute). */
    override val floorDb: Float
        get() = noiseFloorDb

    /** Absolute enter threshold the current frame is judged against. */
    override val enterThresholdDb: Float
        get() = fixedEnter ?: (noiseFloorDb + enterDb)

    /** Absolute exit threshold (hysteresis). */
    override val exitThresholdDb: Float
        get() {
            val f = fixedEnter
            return f?.let { it - exitDb } ?: (noiseFloorDb + exitDb)
        }

    /** Whether speech is currently active. */
    override val speechActive: Boolean
        get() = speech

    /** dB added to the floor per frame (fast while priming, slow afterwards). */
    private fun floorRisePerFrame(): Float =
        if (framesSeen <= primingFrames) PRIME_RISE_PER_FRAME else FLOOR_RISE_PER_FRAME

    override fun reset() {
        noiseFloorDb = Float.MAX_VALUE // min-of-priming bootstrap starts clean
        frame1Db = -120f
        frame2Db = -120f
        speech = false
        framesSeen = 0
    }

    override fun close() {
        // Nothing stateful to release.
    }

    private fun frameEnergyDb(frame: ShortArray, count: Int): Float {
        var acc = 0.0
        for (i in 0 until count) {
            val y = hpFilter(frame[i].toDouble())
            acc += y * y
        }
        val rms = acc / max(count, 1)
        return (10.0 * log10(max(rms, 1e-10))).toFloat()
    }

    private fun hpFilter(x: Double): Double {
        val prevX = hpPrevX[0]
        val y = (x - prevX) + HIGHPASS_DECAY * hpPrevY[0]
        hpPrevX[0] = x
        hpPrevY[0] = y
        return y
    }

    companion object {
        const val HIGHPASS_HZ = 80f
        const val HIGHPASS_DECAY = 0.995
        /** ~2.5 dB/s upward drift cap for the noise floor. */
        const val FLOOR_RISE_PER_FRAME = 0.05f
        /** ~25 dB/s while priming, so cold start converges within a second. */
        const val PRIME_RISE_PER_FRAME = 0.5f
        /** Diagnostic cadence: one line every 25 frames (~0.5 s) for level plots. */
        const val DIAG_FRAME_INTERVAL = 25
    }
}
