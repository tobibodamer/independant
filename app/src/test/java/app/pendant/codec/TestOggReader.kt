package app.pendant.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Parses Ogg pages so we can validate output structure and CRCs. */
object TestOggReader {

    data class Page(
        val bos: Boolean,
        val eos: Boolean,
        val granule: Long,
        val serial: Int,
        val seq: Int,
        val packets: List<ByteArray>,
        val raw: ByteArray,
    )

    fun readAll(data: ByteArray): List<Page> {
        val pages = ArrayList<Page>()
        var off = 0
        while (off < data.size) {
            val start = off
            require(data[off] == 'O'.code.toByte()) { "bad capture at $off" }
            require(String(data, off, 4, Charsets.US_ASCII) == "OggS") { "not an Ogg page at $off" }
            val version = data[off + 4].toInt()
            require(version == 0)
            val flags = data[off + 5].toInt()
            var granule = 0L
            for (i in 0 until 8) granule = granule or ((data[off + 6 + i].toLong() and 0xFF) shl (8 * i))
            var serial = 0
            for (i in 0 until 4) serial = serial or ((data[off + 14 + i].toInt() and 0xFF) shl (8 * i))
            var seq = 0
            for (i in 0 until 4) seq = seq or ((data[off + 18 + i].toInt() and 0xFF) shl (8 * i))
            val storedCrc =
                (data[off + 22].toInt() and 0xFF) or
                    ((data[off + 23].toInt() and 0xFF) shl 8) or
                    ((data[off + 24].toInt() and 0xFF) shl 16) or
                    ((data[off + 25].toInt() and 0xFF) shl 24)
            val segCount = data[off + 26].toInt() and 0xFF
            val segTable = ByteArray(segCount)
            var bodyLen = 0
            for (i in 0 until segCount) {
                segTable[i] = data[off + 27 + i]
                bodyLen += (segTable[i].toInt() and 0xFF)
            }
            val bodyOff = off + 27 + segCount
            val body = data.copyOfRange(bodyOff, bodyOff + bodyLen)
            val raw = data.copyOfRange(start, bodyOff + bodyLen)

            // CRC verification with the production implementation
            val zeroed = raw.clone()
            for (i in 22..25) zeroed[i] = 0
            var crc = OggCrc.crc(zeroed, 0, 27 + segCount)
            crc = OggCrc.crc(body, 0, body.size, seed = crc)
            assertEquals("page CRC mismatch at seq $seq", storedCrc, crc)

            // decode packet boundaries
            val packets = ArrayList<ByteArray>()
            var b = 0
            var i = 0
            while (i < segCount) {
                var len = 0
                while (i < segCount && (segTable[i].toInt() and 0xFF) == 255) {
                    len += 255
                    i++
                }
                if (i < segCount) {
                    len += (segTable[i].toInt() and 0xFF)
                    i++
                    packets.add(body.copyOfRange(b, b + len))
                    b += len
                }
            }
            pages.add(
                Page(
                    bos = flags and 0x02 != 0,
                    eos = flags and 0x04 != 0,
                    granule = granule,
                    serial = serial,
                    seq = seq,
                    packets = packets,
                    raw = raw,
                ),
            )
            off = bodyOff + bodyLen
        }
        return pages
    }

    fun readStream(input: java.io.InputStream): ByteArray = input.readBytes()
}
