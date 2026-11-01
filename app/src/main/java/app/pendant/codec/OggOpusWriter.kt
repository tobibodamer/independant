package app.pendant.codec

import java.io.OutputStream
import kotlin.random.Random

/**
 * Minimal streaming Ogg Opus muxer (RFC 7845).
 *
 * Writes an OpusHead + OpusTags header and then packs Opus packets into Ogg
 * pages (~400 ms of audio per page) as they arrive, so long segments never
 * buffer in memory. The final page is flagged end-of-stream.
 */
class OggOpusWriter(private val out: OutputStream) {

    private val serialNo = Random.nextInt()
    private var pageSeq = 0
    private var granule = 0L // in 48 kHz samples, starts at preskip
    private var headersWritten = false

    private val pending = ArrayList<ByteArray>()
    private var pendingDuration48k = 0L

    private var closed = false

    /** @param tags Vorbis-style user comments (`KEY=value`, UTF-8) for the OpusTags header. */
    fun writeHeaders(
        sampleRate: Int,
        channels: Int,
        preskip: Int,
        vendor: String = OPUS_TAGS_VENDOR,
        tags: List<Pair<String, String>> = emptyList(),
    ) {
        check(!headersWritten) { "headers already written" }
        writePage(listOf(makeOpusHead(sampleRate, channels, preskip)), granulePos = 0, bos = true, eos = false)
        writePage(listOf(makeOpusTags(vendor, tags)), granulePos = 0, bos = false, eos = false)
        this.granule = preskip.toLong()
        headersWritten = true
    }

    /**
     * @param duration48k number of 48 kHz PCM samples this packet decodes to
     *                    (one standard 20 ms Opus frame = 960).
     */
    fun writePacket(packet: ByteArray, duration48k: Long) {
        check(headersWritten) { "call writeHeaders() first" }
        check(!closed) { "writer closed" }
        granule += duration48k
        pending.add(packet)
        pendingDuration48k += duration48k
        if (pendingDuration48k >= PACKETS_PER_PAGE * duration48k) flushPage(eos = false)
    }

    fun close() {
        if (closed) return
        closed = true
        if (headersWritten) {
            if (pending.isEmpty()) {
                // Terminating page with no packets, carrying the EOS flag.
                writePage(emptyList(), granule, bos = false, eos = true)
            } else {
                flushPage(eos = true)
            }
        }
        out.flush()
    }

    private fun flushPage(eos: Boolean) {
        if (pending.isEmpty()) return
        var idx = 0
        while (idx < pending.size) {
            // Group packets so the lacing table (273-byte page header limit is 255 entries) fits.
            val group = ArrayList<ByteArray>(PACKETS_PER_PAGE)
            var lacing = 0
            while (idx < pending.size) {
                val l = (pending[idx].size + SEGMENT_MAX - 1) / SEGMENT_MAX
                if (lacing + l > 255 - 1) break
                group.add(pending[idx])
                lacing += l
                idx++
            }
            if (group.isEmpty()) group.add(pending[idx++])
            writePage(group, granule, bos = false, eos = eos && idx >= pending.size)
        }
        pending.clear()
        pendingDuration48k = 0
    }

    private fun makeOpusHead(sampleRate: Int, channels: Int, preskip: Int): ByteArray {
        val b = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        b[8] = 1 // version
        b[9] = channels.toByte()
        putU16Le(b, 10, preskip)
        putU32Le(b, 12, sampleRate)
        putU16Le(b, 16, 0) // output gain
        b[18] = 0 // mapping family
        return b
    }

    private fun makeOpusTags(vendor: String, tags: List<Pair<String, String>>): ByteArray {
        val v = vendor.toByteArray(Charsets.US_ASCII)
        val comments = tags.map { (k, value) -> "$k=$value".toByteArray(Charsets.UTF_8) }
        val b = ByteArray(8 + 4 + v.size + 4 + comments.sumOf { 4 + it.size })
        "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        putU32Le(b, 8, v.size)
        v.copyInto(b, 12)
        var pos = 12 + v.size
        putU32Le(b, pos, comments.size)
        pos += 4
        for (c in comments) {
            putU32Le(b, pos, c.size)
            c.copyInto(b, pos + 4)
            pos += 4 + c.size
        }
        return b
    }

    private fun writePage(packets: List<ByteArray>, granulePos: Long, bos: Boolean, eos: Boolean) {
        var segTableSize = 0
        for (p in packets) {
            var l = p.size
            while (l >= SEGMENT_MAX) {
                segTableSize++
                l -= SEGMENT_MAX
            }
            segTableSize++
        }
        val header = ByteArray(27 + segTableSize)
        header[0] = 'O'.code.toByte()
        header[1] = 'g'.code.toByte()
        header[2] = 'g'.code.toByte()
        header[3] = 'S'.code.toByte()
        header[4] = 0 // stream structure version
        header[5] = ((if (bos) 0x02 else 0) or (if (eos) 0x04 else 0)).toByte()
        putU64Le(header, 6, granulePos)
        putU32Le(header, 14, serialNo)
        putU32Le(header, 18, pageSeq++)
        // bytes 22..25 = CRC, zeroed for checksum computation
        header[26] = segTableSize.toByte()
        var pos = 27
        for (p in packets) {
            var l = p.size
            while (l >= SEGMENT_MAX) {
                header[pos++] = SEGMENT_MAX.toByte()
                l -= SEGMENT_MAX
            }
            header[pos++] = l.toByte()
        }
        var crc = OggCrc.crc(header, 0, header.size)
        for (p in packets) crc = OggCrc.crc(p, 0, p.size, seed = crc)
        putU32Le(header, 22, crc)

        out.write(header)
        for (p in packets) out.write(p)
    }

    companion object {
        const val OPUS_TAGS_VENDOR = "Pendant 0.1.0"
        /** 20 packets per page = 400 ms of audio at 20 ms frames. */
        const val PACKETS_PER_PAGE = 20
        const val SEGMENT_MAX = 255

        private fun putU16Le(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        private fun putU32Le(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte()
            b[off + 3] = ((v shr 24) and 0xFF).toByte()
        }

        private fun putU64Le(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
    }
}

/** Ogg page CRC-32: polynomial 0x04c11db7, no reflection, no final XOR, seed 0. */
object OggCrc {
    private val table = IntArray(256).also { t ->
        for (i in 0..255) {
            var r = i shl 24
            repeat(8) {
                r = if (r < 0) (r shl 1) xor 0x04c11db7 else r shl 1
            }
            t[i] = r
        }
    }

    fun crc(data: ByteArray, off: Int, len: Int, seed: Int = 0): Int {
        var r = seed
        for (i in off until off + len) {
            r = (r shl 8) xor table[(r ushr 24) xor (data[i].toInt() and 0xFF)]
        }
        return r
    }
}
