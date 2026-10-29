package app.pendant.codec

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Encodes PCM to Opus and muxes it into an .opus (Ogg) file.
 *
 * The encoder starts immediately (low onset latency), but the file is only
 * created once [minDurationMs] of audio has been encoded; segments shorter than
 * that leave no file behind at all.
 */
class OpusSegmentWriter(
    private val file: File,
    private val sampleRate: Int,
    channels: Int,
    bitrateBps: Int,
    private val minDurationMs: Long,
) : SegmentWriter {

    private val encoder = OpusEncoder(sampleRate, channels, bitrateBps)
    override var samplesWritten: Long = 0
        private set
    private var stream: BufferedOutputStream? = null
    private var muxer: OggOpusWriter? = null
    /** Encoded packets held back until the segment proves its length. */
    private val held = ArrayList<Pair<ByteArray, Long>>()
    private var closed = false

    override fun writePcm(samples: ShortArray, count: Int) {
        encoder.encode(samples, count, final = false) { packet, d48 ->
            storeOrFlush(packet, d48)
        }
    }

    private fun storeOrFlush(packet: ByteArray, d48: Long) {
        samplesWritten += d48 * sampleRate / 48_000
        val m = muxer
        if (m != null) {
            m.writePacket(packet, d48)
        } else {
            held.add(packet to d48)
            if (durationMs >= minDurationMs) openFile()
        }
    }

    private val durationMs: Long
        get() = samplesWritten * 1000 / sampleRate

    private fun openFile() {
        val fos = FileOutputStream(file)
        stream = BufferedOutputStream(fos, 32 * 1024)
        val om = OggOpusWriter(stream!!)
        muxer = om
        om.writeHeaders(sampleRate, 1, encoder.preskip)
        for ((p, d) in held) om.writePacket(p, d)
        held.clear()
    }

    override fun finish() {
        if (closed) return
        closed = true
        try {
            encoder.encode(EMPTY, 0, final = true) { packet, d48 -> storeOrFlush(packet, d48) }
            muxer?.close()
            stream?.flush()
        } catch (_: Exception) {
        } finally {
            encoder.release()
            closeQuietly(stream)
        }
        if (muxer == null) file.delete()
    }

    override fun close() = finish()

    companion object {
        private val EMPTY = ShortArray(0)

        fun closeQuietly(s: OutputStream?) {
            if (s == null) return
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }
}
