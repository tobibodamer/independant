package app.pendant.codec

import android.os.ParcelFileDescriptor
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Encodes PCM to Opus and muxes it into an .opus (Ogg) file.
 *
 * The encoder starts immediately (low onset latency), but the destination is only
 * opened once [minDurationMs] of audio has been encoded; segments shorter than
 * that leave no file/row behind at all. Keeps that lazy behavior for plain file
 * destinations AND for content-URI destinations (the storage layer creates the
 * MediaStore/SAF row up front, the bytes stream only after proven length).
 */
class OpusSegmentWriter(
    private val openDestination: SegmentDestination.Opener,
    sampleRate: Int,
    channels: Int,
    bitrateBps: Int,
    private val minDurationMs: Long,
) : SegmentWriter {

    private val encoder = OpusEncoder(sampleRate, channels, bitrateBps)
    private val sampleRate: Int = sampleRate
    override var samplesWritten: Long = 0
        private set
    private var broken = false
    private var stream: OutputStream? = null
    private var pfd: ParcelFileDescriptor? = null
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
        if (broken) return
        samplesWritten += d48 * sampleRate / 48_000
        val m = muxer
        if (m != null) {
            m.writePacket(packet, d48)
        } else {
            held.add(packet to d48)
            if (durationMs >= minDurationMs) openDest()
        }
    }

    private val durationMs: Long
        get() = samplesWritten * 1000 / sampleRate

    private fun openDest() {
        try {
            val dest = openDestination.open()
            when (dest) {
                null -> throw java.io.IOException("storage destination unavailable")
                is SegmentDestination.AsFile -> attach(FileOutputStream(dest.file), null)
                is SegmentDestination.AsStream -> attach(dest.stream, null)
                is SegmentDestination.AsFd -> attach(FileOutputStream(dest.pfd.fileDescriptor), dest.pfd)
            }
        } catch (e: Exception) {
            // Storage vanished mid-segment: drop the audio instead of crashing the capture loop.
            broken = true
            held.clear()
            closeQuietly(stream)
            stream = null
        }
    }

    private fun attach(sink: OutputStream, pfd: ParcelFileDescriptor?) {
        val buffered = BufferedOutputStream(sink, 32 * 1024)
        stream = buffered
        this.pfd = pfd
        val om = OggOpusWriter(buffered)
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
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
        }
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
