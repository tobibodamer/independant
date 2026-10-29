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
) {
    enum class Sensitivity(val enterDb: Float) {
        LOW(15f),
        MEDIUM(11f),
        HIGH(7f),
    }

    private val hpPrevX = DoubleArray(1)
    private val hpPrevY = DoubleArray(1)

    private val enterDb = sensitivity.enterDb
    private val exitDb = sensitivity.enterDb * 0.55f

    private var noiseFloorDb = -70f
    private var frame1Db = -120f
    private var frame2Db = -120f
    private var speech = false
    private var framesSeen = 0

    /** While starting up we always track the floor (the app opens in silence). */
    private val primingFrames = 25

    /** Process one frame; returns true while speech is considered active. */
    fun process(frame: ShortArray, count: Int): Boolean {
        val energy = frameEnergyDb(frame, count)
        frame2Db = frame1Db
        frame1Db = energy
        framesSeen++

        // Track the noise floor while silent (and during the priming phase).
        // Fast down / bounded slow up keeps the floor near a low quantile of the
        // silence energy instead of an unbounded minimum (more robust than
        // classic min-statistics under stationary noise).
        if (framesSeen == 1) {
            // Prime hard from the very first frame so capture never dead-locks.
            noiseFloorDb = energy
        } else if (!speech || framesSeen <= primingFrames) {
            noiseFloorDb = if (energy < noiseFloorDb) {
                noiseFloorDb * 0.9f + energy * 0.1f
            } else {
                min(noiseFloorDb + FLOOR_RISE_PER_FRAME, energy)
            }
        }

        val aboveEnter = energy > noiseFloorDb + enterDb
        val nearEnter = frame1Db > noiseFloorDb + enterDb * 0.6f || frame2Db > noiseFloorDb + enterDb * 0.6f

        if (!speech) {
            if (aboveEnter && nearEnter) {
                speech = true
            }
        } else {
            if (energy < noiseFloorDb + exitDb && frame2Db < noiseFloorDb + exitDb) {
                speech = false
            }
        }
        return speech
    }

    /** Current signal level above the noise floor, in dB (for UI debug). */
    val marginDb: Float
        get() = frame1Db - noiseFloorDb

    fun reset() {
        noiseFloorDb = -70f
        frame1Db = -120f
        frame2Db = -120f
        speech = false
        framesSeen = 0
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
    }
}
