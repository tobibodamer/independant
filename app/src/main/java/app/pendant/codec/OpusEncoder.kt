package app.pendant.codec

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Thin wrapper around the platform Opus encoder (MediaCodec, "audio/opus").
 * Fixed 20 ms frames -> one Opus packet per input chunk.
 */
class OpusEncoder(sampleRate: Int, channels: Int, bitrateBps: Int) {

    private val codec: MediaCodec
    private val sampleRate: Int
    private val inputSize: Int
    private var samplesFed = 0L

    /** Codec delay in samples at 48 kHz, parsed from the encoder's OpusHead config. */
    var preskip: Int = 312
        private set

    init {
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        this.sampleRate = sampleRate
        inputSize = (sampleRate * FRAME_MS_US * channels / 1_000_000L).toInt() // samples per 20 ms frame
    }

    /**
     * Feeds one chunk of PCM and drains all finished packets.
     * @param onPacket raw Opus packet + duration at 48 kHz.
     */
    fun encode(input: ShortArray, count: Int, final: Boolean, onPacket: (ByteArray, Long) -> Unit) {
        var remaining = count
        var offset = 0
        do {
            val chunk = minOf(remaining, inputSize)
            queueInput(input, offset, chunk, flush = final && chunk == remaining)
            offset += chunk
            remaining -= chunk
        } while (remaining > 0)
        drain(onPacket)
    }

    private fun queueInput(input: ShortArray, offset: Int, count: Int, flush: Boolean) {
        var index = codec.dequeueInputBuffer(QUEUE_TIMEOUT_US)
        var retries = 0
        while (index < 0 && retries < 50) {
            Thread.sleep(2)
            index = codec.dequeueInputBuffer(QUEUE_TIMEOUT_US)
            retries++
        }
        if (index < 0) throw IllegalStateException("Opus encoder did not accept input")
        val buffer: ByteBuffer = codec.getInputBuffer(index)!!
        buffer.clear()
        for (i in 0 until count) buffer.putShort(input[offset + i])
        val flags = if (flush) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        val ptsUs = samplesFed * 1_000_000L / sampleRate
        samplesFed += count
        codec.queueInputBuffer(index, 0, count * 2, ptsUs, flags)
    }

    private fun drain(onPacket: (ByteArray, Long) -> Unit) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val delay = codec.outputFormat.getInteger(KEY_ENCODER_DELAY, -1)
                    if (delay >= 0) preskip = delay
                }
                index >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        val cfg = codec.getOutputBuffer(index)
                        if (cfg != null && info.size >= 12) {
                            val b = ByteArray(info.size)
                            cfg.position(info.offset)
                            cfg.get(b)
                            preskip = (b[10].toInt() and 0xFF) or ((b[11].toInt() and 0xFF) shl 8)
                        }
                    } else if (info.size > 0) {
                        val buf: ByteBuffer = codec.getOutputBuffer(index)!!
                        buf.position(info.offset)
                        val packet = ByteArray(info.size)
                        buf.get(packet)
                        onPacket(packet, FRAME_48K_PER_PACKET)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun release() {
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
    }

    companion object {
        const val MIMETYPE = MediaFormat.MIMETYPE_AUDIO_OPUS
        const val FRAME_MS_US = 20_000L
        /** Every standard 20 ms Opus frame decodes to 960 samples at 48 kHz. */
        const val FRAME_48K_PER_PACKET = 960L
        private const val QUEUE_TIMEOUT_US = 2_000L
        private const val DRAIN_TIMEOUT_US = 2_000L
        private const val KEY_ENCODER_DELAY = "encoder-delay"

        fun isSupported(): Boolean = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
                it.isEncoder && it.supportedTypes.contains(MIMETYPE)
            }
        } catch (_: Exception) {
            false
        }
    }
}
