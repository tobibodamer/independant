package app.pendant.codec

/** Chooses between the Opus/Ogg writer and the AAC/M4A fallback. */
object SegmentWriters {

    enum class Format(val extension: String, val mimeType: String) {
        OPUS("opus", "audio/opus"),
        AAC("m4a", "audio/mp4"),
    }

    val format: Format by lazy { if (OpusEncoder.isSupported()) Format.OPUS else Format.AAC }

    /** Stream/content MIME type for inserts into MediaStore/SAF ("audio/opus" is not broadly registered). */
    fun containerMimeType(): String = if (format == Format.OPUS) "audio/ogg" else format.mimeType

    /**
     * Creates the writer for one segment.
     *
     * @param openDestination supplies the place to write (file, FileDescriptor or stream).
     * For the Opus writer it is called lazily, only once the segment has proven its length
     * (preserving the no-file-for-short-segments behavior). The AAC fallback needs its
     * destination up front (MediaMuxer), so [openDestination] is invoked immediately;
     * if that fails, null is returned and the caller should discard the segment.
     */
    fun create(
        openDestination: SegmentDestination.Opener,
        sampleRate: Int,
        channels: Int,
        bitrateBps: Int,
        minDurationMs: Long,
    ): SegmentWriter? = try {
        when (format) {
            Format.OPUS -> OpusSegmentWriter(openDestination, sampleRate, channels, bitrateBps, minDurationMs)
            Format.AAC -> {
                val dest = openDestination.open() ?: return null
                AacSegmentWriter(dest, sampleRate, bitrateBps, minDurationMs)
            }
        }
    } catch (e: Exception) {
        null
    }
}
