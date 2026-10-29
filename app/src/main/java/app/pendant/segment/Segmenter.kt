package app.pendant.segment

import app.pendant.codec.SegmentWriter
import app.pendant.vad.EnergyVad
import java.io.File

/**
 * Turns a continuous stream of VAD-judged frames into files.
 *
 * - Keeps a pre-roll ring buffer so the file starts *before* the first word.
 * - Holds the file open through silence up to [hangoverSec] so a short thinking
 *   pause does not chop one utterance into many files.
 * - Hard cap [maxSegmentSec]: continuous speech is rotated into a new file.
 */
class Segmenter(
    private val sampleRate: Int,
    private val frameSamples: Int,
    private val prerollMs: Int,
    private val hangoverSec: Int,
    private val minSegmentMs: Long,
    private val maxSegmentSec: Long,
    private val listener: Listener,
) {
    interface Listener {
        /** File to write the next segment into (created under the recordings dir). */
        fun nextSegmentFile(wallClockMs: Long): File?

        /** Creates the codec/muxer writer for this segment, or null to skip it. */
        fun createWriter(file: File, startWallClockMs: Long): SegmentWriter?

        /** Duration is wall-time from segment start; file is already finalized. */
        fun onSegmentStored(file: File, durationMs: Long)

        /** Called when a segment was discarded (too short to be useful). */
        fun onSegmentDiscarded()

        /** Live state for UI/battery: true while speech was recently heard (hangover running). */
        fun onVoiceActive(active: Boolean)
    }

    private val preroll = PrerollRing(prerollMs, sampleRate)

    private var writer: SegmentWriter? = null
    private var segmentFile: File? = null
    private var segmentStartMs = 0L
    private var durationMs = 0
    private var speechMs = 0L
    private var silenceMs = 0
    private var rotation = false

    /** Feeds one frame. */
    fun feed(samples: ShortArray, count: Int, speech: Boolean, wallClockMs: Long) {
        val w = writer
        if (w == null) {
            preroll.push(samples, count)
            if (speech) startSegment(wallClockMs, samples, count)
            return
        }

        w.writePcm(samples, count)
        durationMs += count * 1000 / sampleRate

        if (speech) {
            silenceMs = 0
            speechMs += count * 1000 / sampleRate
        } else {
            silenceMs += count * 1000 / sampleRate
        }
        listener.onVoiceActive(speech || silenceMs < hangoverSec * 1000)

        if (speech && durationMs >= maxSegmentSec * 1000 && !rotation) {
            rotation = true
            finishSegment(store = true)
            preroll.clear()
            startSegment(wallClockMs, samples, count)
            rotation = false
        } else if (silenceMs >= hangoverSec * 1000 && !rotation) {
            // Long enough silence: close the segment up. Store it only if it
            // contained real speech, so a single click leaves no file behind.
            finishSegment(store = speechMs >= minSegmentMs)
        }
    }

    /** Close whatever is open (pause, stop, shutdown). */
    fun finishCurrent(store: Boolean = true) {
        if (writer != null) finishSegment(store)
    }

    fun release() {
        finishCurrent(store = true)
    }

    private fun startSegment(wallClockMs: Long, firstSamples: ShortArray, firstCount: Int) {
        val file = listener.nextSegmentFile(wallClockMs) ?: return
        val w = listener.createWriter(file, wallClockMs) ?: return
        // Pre-roll first, then the triggering frame.
        preroll.drainInto(w)
        w.writePcm(firstSamples, firstCount)
        writer = w
        segmentFile = file
        segmentStartMs = wallClockMs
        durationMs = (firstCount + preroll.capacitySamples) * 1000 / sampleRate
        speechMs = 0
        silenceMs = 0
        listener.onVoiceActive(true)
    }

    private fun finishSegment(store: Boolean) {
        val w = writer ?: return
        val file = segmentFile ?: return
        writer = null
        segmentFile = null
        try {
            w.finish()
        } finally {
            if (store && w.samplesWritten > 0) {
                listener.onSegmentStored(file, maxOf(durationMs.toLong(), w.samplesWritten * 1000 / sampleRate))
            } else {
                listener.onSegmentDiscarded()
            }
        }
        listener.onVoiceActive(false)
    }
}

/** Ring buffer that keeps the last [capacityMs] of audio for segment pre-rolls. */
private class PrerollRing(val capacityMs: Int, val sampleRate: Int) {
    val capacitySamples = capacityMs * sampleRate / 1000
    private val buffer = ShortArray(capacitySamples)
    private var writeIdx = 0
    private var filled = 0

    fun push(samples: ShortArray, count: Int) {
        if (count >= capacitySamples) {
            System.arraycopy(samples, count - capacitySamples, buffer, 0, capacitySamples)
            writeIdx = 0
            filled = capacitySamples
            return
        }
        val first = minOf(count, capacitySamples - writeIdx)
        System.arraycopy(samples, 0, buffer, writeIdx, first)
        if (count > first) System.arraycopy(samples, first, buffer, 0, count - first)
        writeIdx = (writeIdx + count) % capacitySamples
        filled = minOf(capacitySamples, filled + count)
    }

    fun drainInto(writer: SegmentWriter) {
        if (filled == 0) return
        if (filled < capacitySamples) {
            // Ring not full yet: oldest data is at 0.
            writer.writePcm(buffer.copyOf(filled), filled)
        } else {
            // Full ring: data from writeIdx .. end is oldest.
            val tail = capacitySamples - writeIdx
            writer.writePcm(buffer.copyOfRange(writeIdx, capacitySamples), tail)
            if (writeIdx > 0) writer.writePcm(buffer.copyOf(writeIdx), writeIdx)
        }
    }

    fun clear() {
        writeIdx = 0
        filled = 0
    }
}
