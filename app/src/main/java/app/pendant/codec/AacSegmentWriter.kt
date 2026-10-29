package app.pendant.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * AAC fallback segment writer (.m4a via MediaMuxer), used only on devices whose
 * MediaCodec list contains no Opus encoder. Creates its file immediately and
 * deletes it again if the segment is closed before [minDurationMs].
 */
class AacSegmentWriter(
    private val file: File,
    private val sampleRate: Int,
    bitrateBps: Int,
    private val minDurationMs: Long,
) : SegmentWriter {

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private val maxInput = sampleRate * 2 * AAC_FRAME_MS / 1000 // bytes per 20 ms frame

    override var samplesWritten: Long = 0
        private set
    private var trackIndex = -1
    private var muxerStarted = false
    private var durationMs = 0L
    private var closed = false
    private val pendingInput = ArrayList<Short>(maxInput)

    init {
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInput * 2)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    override fun writePcm(samples: ShortArray, count: Int) {
        for (i in 0 until count) pendingInput.add(samples[i])
        durationMs += count * 1000L / sampleRate
        samplesWritten += count
        while (pendingInput.size >= maxInput) {
            feedChunk(pendingInput, maxInput, eos = false)
        }
        drain(eos = false)
    }

    private fun feedChunk(pending: ArrayList<Short>, size: Int, eos: Boolean) {
        var index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return
        val buf: ByteBuffer = codec.getInputBuffer(index)!!
        buf.clear()
        for (i in 0 until size) buf.putShort(pending[i])
        val pts = samplesWritten * 1_000_000L / sampleRate
        codec.queueInputBuffer(index, 0, size * 2, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        for (i in 0 until size) pending.removeAt(0)
    }

    private fun drain(eos: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buf: ByteBuffer = codec.getOutputBuffer(index)!!
                    if (bufferInfo.size > 0 && trackIndex >= 0 && muxerStarted) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, buf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun finish() {
        if (closed) return
        closed = true
        try {
            if (pendingInput.isNotEmpty()) feedChunk(pendingInput, pendingInput.size, eos = true)
            else {
                var idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(eos = true)
        } catch (_: Exception) {
        } finally {
            try {
                if (muxerStarted) muxer.stop()
            } catch (_: Exception) {
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
            codec.release()
        }
        if (durationMs < minDurationMs) file.delete()
    }

    override fun close() = finish()

    companion object {
        const val AAC_FRAME_MS = 20
    }
}
