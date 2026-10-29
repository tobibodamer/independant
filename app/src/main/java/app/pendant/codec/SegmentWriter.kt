package app.pendant.codec

import java.io.Closeable

/**
 * A streaming writer for one speech segment. Receives 16-bit mono PCM frames;
 * encodes + muxes to a file on disk. Implementations must be safe to close early
 * (before any bytes hit the disk) without leaving artefacts behind.
 */
interface SegmentWriter : Closeable {
    /** Total PCM samples handed to the writer so far (all channels summed). */
    val samplesWritten: Long

    fun writePcm(samples: ShortArray, count: Int)

    /** Flush everything and finalize the file. Safe to call twice. */
    fun finish()
}
