package app.pendant.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class OggOpusWriterTest {

    @Test
    fun `CRC implementation matches a real ffmpeg-produced opus stream`() {
        val ref = javaClass.getResourceAsStream("/reference.opus")?.readBytes()!!
        val pages = TestOggReader.readAll(ref)
        assertTrue("reference should have several pages", pages.size >= 3)
        // First two pages carry OpusHead and OpusTags
        assertEquals("OpusHead", String(pages[0].packets[0], 0, 8, Charsets.US_ASCII))
        assertTrue(pages[0].bos)
        assertEquals("OpusTags", String(pages[1].packets[0], 0, 8, Charsets.US_ASCII))
        assertTrue(pages[1].granule == 0L)
        assertTrue("last page must be EOS", pages.last().eos)
    }

    @Test
    fun `our muxer produces a structurally valid, CRC-correct stream`() {
        val out = ByteArrayOutputStream()
        val w = OggOpusWriter(out)
        val preskip = 312
        w.writeHeaders(16000, 1, preskip)
        val packets = ArrayList<ByteArray>()
        var granule = preskip.toLong()
        for (i in 0 until 100) { // 2 seconds of audio
            val p = ByteArray(80) { idx -> (idx + i).toByte() } // typical ~25 kbps packet
            packets.add(p)
            granule += 960
            if (i % 20 == 19) {
                // writePacket accumulates internally; call per packet
            }
            w.writePacket(p, 960)
        }
        w.close()

        val pages = TestOggReader.readAll(out.toByteArray())
        assertEquals(pages[0].packets[0].decodeToString(0, 8), "OpusHead")
        assertEquals(pages[1].packets[0].decodeToString(0, 8), "OpusTags")
        assertTrue(pages.last().eos)
        assertTrue("no page except last may be EOS", pages.dropLast(1).none { it.eos })

        // All audio packets accounted for, in order, on audio pages
        val audioPackets = pages.drop(2).flatMap { it.packets }
        assertEquals(100, audioPackets.size)
        packets.forEachIndexed { idx, p -> assertTrue("packet $idx differs", Arrays.equals(p, audioPackets[idx])) }

        // granule positions: monotonic. RFC 7845: the first audio page's granule
        // already includes the pre-skip, so start counting from there.
        var expected = preskip.toLong()
        for (page in pages.drop(2)) {
            val countInPage = page.packets.size
            expected += countInPage * 960L
            assertEquals(expected, page.granule)
        }
        assertEquals(preskip.toLong() + 100 * 960L, pages.last().granule)
        // serial numbers identical, sequence strictly increasing
        pages.forEachIndexed { idx, page ->
            assertEquals(pages[0].serial, page.serial)
            assertEquals(idx, page.seq)
        }
        // OpusHead content check
        val head = pages[0].packets[0]
        assertEquals(19, head.size)
        assertEquals(1, head[8].toInt())
        assertEquals(1, head[9].toInt())
        val preSkipRead = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
        assertEquals(preskip, preSkipRead)
        val rate = (head[12].toLong() and 0xFF) or
            ((head[13].toLong() and 0xFF) shl 8) or
            ((head[14].toLong() and 0xFF) shl 16) or
            ((head[15].toLong() and 0xFF) shl 24)
        assertEquals(16000L, rate)
    }

    @Test
    fun `large packets are laced over multiple segments`() {
        val out = ByteArrayOutputStream()
        val w = OggOpusWriter(out)
        w.writeHeaders(16000, 1, 312)
        // 1275 bytes is the max legal Opus packet
        w.writePacket(ByteArray(600), 960)
        w.writePacket(ByteArray(1275), 960)
        w.close()
        val pages = TestOggReader.readAll(out.toByteArray())
        val audio = pages.drop(2).flatMap { it.packets }
        assertEquals(2, audio.size)
        assertEquals(600, audio[0].size)
        assertEquals(1275, audio[1].size)
    }

    @Test
    fun `user comments land in the OpusTags header`() {
        val out = ByteArrayOutputStream()
        val w = OggOpusWriter(out)
        w.writeHeaders(16000, 1, 312, tags = listOf("DATE" to "2026-10-30T05:54:32.123+01:00", "PENDANT_MIC" to "Bluetooth: Bügel"))
        w.close()
        val tags = TestOggReader.readAll(out.toByteArray())[1].packets[0]
        fun u32(off: Int) = (0..3).sumOf { (tags[off + it].toInt() and 0xFF) shl (8 * it) }
        var pos = 12 + u32(8)
        assertEquals(2, u32(pos))
        pos += 4
        val comments = List(2) {
            val len = u32(pos)
            String(tags, pos + 4, len, Charsets.UTF_8).also { pos += 4 + len }
        }
        assertEquals(listOf("DATE=2026-10-30T05:54:32.123+01:00", "PENDANT_MIC=Bluetooth: Bügel"), comments)
        assertEquals(tags.size, pos)
    }
}
