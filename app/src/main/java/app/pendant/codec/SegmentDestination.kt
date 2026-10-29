package app.pendant.codec

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.OutputStream

/**
 * Destination for a segment's encoded bytes, resolved by the storage layer.
 *
 * Backends map their storage differently:
 * - plain file paths (`AsFile`),
 * - FileDescriptors for MediaMuxer-backed writers (AAC inside MediaStore/SAF containers, `AsFd`),
 * - pre-opened OutputStreams for content-URI-backed streaming (`AsStream`).
 */
sealed class SegmentDestination {
    class AsFile(val file: File) : SegmentDestination()
    class AsFd(val pfd: ParcelFileDescriptor) : SegmentDestination()
    class AsStream(val stream: OutputStream) : SegmentDestination()

    /** Supplies the destination once a writer is ready to persist (may be lazy or eager). */
    fun interface Opener {
        /** Returns null when storage became unavailable. */
        fun open(): SegmentDestination?
    }
}
