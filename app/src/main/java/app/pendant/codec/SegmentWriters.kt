package app.pendant.codec

import java.io.File

/** Chooses between the Opus/Ogg writer and the AAC/M4A fallback. */
object SegmentWriters {

    enum class Format(val extension: String, val mimeType: String) {
        OPUS("opus", "audio/opus"),
        AAC("m4a", "audio/mp4"),
    }

    val format: Format by lazy { if (OpusEncoder.isSupported()) Format.OPUS else Format.AAC }

    fun create(
        file: File,
        sampleRate: Int,
        channels: Int,
        bitrateBps: Int,
        minDurationMs: Long,
    ): SegmentWriter = when (format) {
        Format.OPUS -> OpusSegmentWriter(file, sampleRate, channels, bitrateBps, minDurationMs)
        Format.AAC -> AacSegmentWriter(file, sampleRate, bitrateBps, minDurationMs)
    }
}
