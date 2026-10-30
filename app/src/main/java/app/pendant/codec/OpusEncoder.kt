package app.pendant.codec

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around a platform Opus encoder (MediaCodec, "audio/opus").
 * Fixed 20 ms frames -> one Opus packet per input chunk.
 *
 * Implemented in ASYNC mode on a dedicated handler thread. Rationale: on
 * Samsung OneUI 6+ this build's software opus encoder (c2.android.opus.encoder,
 * hosted in media.swcodec) wedges in SYNC mode — codec metrics show instances
 * that encode a handful of frames and then never deliver another input buffer,
 * while other apps' opus decoders run fine concurrently. The async callback
 * path is the one vendor stacks actively exercise; wedged instances are
 * detected by buffer starvation and the encoder rotates to the next
 * implementation (only one exists on this device — rotation still forces a
 * fresh instance, which usually revives the pipeline).
 */
class OpusEncoder(sampleRate: Int, channels: Int, bitrateBps: Int) {

    private val serializer = Any() // encode/release may interleave with callbacks

    private val candidates: List<String> = opusEncoderNames()
    private var candidateIdx = preferredCandidateIdx.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
    private val sampleRate: Int = sampleRate
    private val inputSize: Int
    private var samplesFed = 0L

    private val handlerThread: HandlerThread
    private val codecHandler: Handler
    private var codec: MediaCodec? = null
    private val callbacks = Callbacks()

    /** Free input buffer indexes delivered by onInputBufferAvailable. */
    private val freeInputs = LinkedBlockingQueue<Int>()

    /** Encoded packets awaiting the caller's drain, FIFO. */
    private val packets = LinkedBlockingQueue<Packet>()

    @Volatile private var eosSeen = false
    @Volatile private var closed = false
    @Volatile private var fedAnyInput = false

    /** Codec delay in samples at 48 kHz, parsed from the encoder's OpusHead config. */
    @Volatile var preskip: Int = 312
        private set

    private val bitrateBps: Int = bitrateBps

    private class Packet(val bytes: ByteArray, val duration48k: Long)

    private inner class Callbacks : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            freeInputs.add(index)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            try {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    val cfg = codec.getOutputBuffer(index)
                    if (cfg != null && info.size >= 12) {
                        val b = ByteArray(info.size)
                        cfg.position(info.offset)
                        cfg.get(b)
                        preskip = (b[10].toInt() and 0xFF) or ((b[11].toInt() and 0xFF) shl 8)
                    }
                } else if (info.size > 0) {
                    val buf = codec.getOutputBuffer(index)!!
                    buf.position(info.offset)
                    val packet = ByteArray(info.size)
                    buf.get(packet)
                    packets.add(Packet(packet, FRAME_48K_PER_PACKET))
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosSeen = true
            } catch (e: Exception) {
                Log.w(TAG, "output callback failed", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "codec error ${e.errorCode}: ${e.message}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (format.containsKey(KEY_ENCODER_DELAY)) preskip = format.getInteger(KEY_ENCODER_DELAY)
        }
    }

    init {
        require(candidates.isNotEmpty()) { "no audio/opus encoder available" }
        Log.i(TAG, "opus encoders available: $candidates")
        inputSize = (sampleRate * FRAME_MS_US * 1 / 1_000_000L).toInt() // samples per 20 ms frame
        handlerThread = HandlerThread("pendant-opus-c2").apply { start() }
        codecHandler = Handler(handlerThread.looper)
        codec = createCodec()
    }

    private fun createCodec(): MediaCodec {
        val name = candidates[candidateIdx]
        val c = MediaCodec.createByCodecName(name)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        c.setCallback(callbacks, codecHandler)
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        eosSeen = false
        Log.i(TAG, "opus encoder (async): $name")
        return c
    }

    /** Releases the wedged codec and moves to the next encoder implementation. */
    private fun rotateCodec() {
        val stale = candidates[candidateIdx]
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        codec = null
        freeInputs.clear()
        candidateIdx = (candidateIdx + 1) % candidates.size
        preferredCandidateIdx = candidateIdx
        Log.w(TAG, "opus encoder wedged ($stale); switching to ${candidates[candidateIdx]}")
        try {
            Thread.sleep(100) // don't hammer the codec service with instant re-creation
        } catch (_: InterruptedException) {
        }
        codec = createCodec()
    }

    /**
     * Feeds one chunk of PCM and drains all finished packets.
     * @param onPacket raw Opus packet + duration at 48 kHz.
     */
    fun encode(input: ShortArray, count: Int, final: Boolean, onPacket: (ByteArray, Long) -> Unit) {
        if (closed) throw IllegalStateException("encoder already released")
        var remaining = count
        var offset = 0
        do {
            val chunk = minOf(remaining, inputSize)
            queueChunk(input, offset, chunk, flush = final && chunk == remaining)
            offset += chunk
            remaining -= chunk
        } while (remaining > 0)

        if (final) {
            // Wait a bounded time for the flushed tail so finish() sees every packet.
            val deadline = System.currentTimeMillis() + FLUSH_WAIT_MS
            while (!eosSeen && System.currentTimeMillis() < deadline) {
                drainPackets(onPacket)
                if (!eosSeen) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        drainPackets(onPacket)
    }

    private fun queueChunk(input: ShortArray, offset: Int, count: Int, flush: Boolean) {
        var attemptsLeft = candidates.size
        while (true) {
            val budget = if (fedAnyInput) INPUT_POLL_MS else FIRST_INPUT_POLL_MS
            val index = freeInputs.poll(budget, TimeUnit.MILLISECONDS)
            if (index != null) {
                val buffer: ByteBuffer = requireNotNull(codec?.getInputBuffer(index)) { "input buffer gone" }
                buffer.clear()
                for (i in 0 until count) buffer.putShort(input[offset + i])
                val flags = if (flush) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                val ptsUs = samplesFed * 1_000_000L / sampleRate
                samplesFed += count
                fedAnyInput = true
                codec?.queueInputBuffer(index, 0, count * 2, ptsUs, flags)
                return
            }
            if (closed) throw IllegalStateException("encoder released while waiting for input buffer")
            if (--attemptsLeft <= 0) {
                throw IllegalStateException("opus encoder did not accept input (${candidates[candidateIdx]}, ${if (fedAnyInput) "after previous frames" else "first input"})")
            }
            rotateCodec()
        }
    }

    private fun drainPackets(onPacket: (ByteArray, Long) -> Unit) {
        while (true) {
            val p = packets.poll() ?: return
            onPacket(p.bytes, p.duration48k)
        }
    }

    fun release() {
        closed = true
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        codec = null
        handlerThread.quitSafely()
    }

    companion object {
        private const val TAG = "PendantEngine"
        const val MIMETYPE = MediaFormat.MIMETYPE_AUDIO_OPUS
        const val FRAME_MS_US = 20_000L
        /** Every standard 20 ms Opus frame decodes to 960 samples at 48 kHz. */
        const val FRAME_48K_PER_PACKET = 960L

        /** Healthy codecs deliver input buffers in <10 ms; a cold start sometimes
         *  needs a few hundred ms — but never seconds. Longer waits block the whole
         *  capture loop (mic read, VAD, UI), so selection is deliberately short. */
        private const val FIRST_INPUT_POLL_MS = 600L
        private const val INPUT_POLL_MS = 1_500L
        /** Bounded wait for the EOS-flushed tail packets at finish(). */
        private const val FLUSH_WAIT_MS = 800L
        private const val KEY_ENCODER_DELAY = "encoder-delay"

        /** Process-wide position in the encoder implementation list. */
        @Volatile private var preferredCandidateIdx = 0

        private fun opusEncoderNames(): List<String> = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.contains(MIMETYPE) }
                .map { it.name }
        } catch (_: Exception) {
            emptyList()
        }

        fun isSupported(): Boolean = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
                it.isEncoder && it.supportedTypes.contains(MIMETYPE)
            }
        } catch (_: Exception) {
            false
        }
    }
}
