package app.pendant.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.pendant.codec.SegmentDestination
import java.io.File

class SegmentNamesTest {

    @Test
    fun `base name uses timestamp and extension`() {
        // 2026-10-29 10:15:30 UTC != local; just assert the shape, not the absolute value.
        val name = SegmentNames.baseName("opus", 1_796_716_530_000L)
        assertTrue("got $name", name.matches(Regex("""\d{8}_\d{6}\.opus""")))
    }

    @Test
    fun `final name inserts duration suffix before extension`() {
        assertEquals(
            "20261029_101530_42s.opus",
            SegmentNames.finalizedName("20261029_101530.opus", 42_500),
        )
        assertEquals(
            "20261029_101530_0s.opus",
            SegmentNames.finalizedName("20261029_101530.opus", 900),
        )
    }

    @Test
    fun `parse duration reads both schemes`() {
        // Duration-suffixed files (app-private and SAF paths).
        assertEquals(42_000, SegmentNames.parseDurationMs("20261029_101530_42s.opus"))
        // MediaStore names carry no suffix -> 0; duration comes from the DURATION column.
        assertEquals(0, SegmentNames.parseDurationMs("20261029_101530.opus"))
        assertEquals(0, SegmentNames.parseDurationMs("random.mp3"))
    }

    @Test
    fun `suffix parse survives underscores in stem`() {
        assertEquals(3_000, SegmentNames.parseDurationMs("20261029_101530_3s.m4a"))
        assertFalse(SegmentNames.parseDurationMs("20261029_101530.opus") > 0)
    }

    @Test
    fun `file sink finalizes with suffix and discards without artifacts`() {
        val dir = java.io.File.createTempFile("pendant_sink", "").let { f ->
            f.delete()
            java.io.File(f.parentFile, "pendant_sink_${f.name}").apply { mkdirs() }
        }
        val sink = FileSegmentSink(dir, "20261029_101530.opus")
        val dest = sink.openOutput()
        val file = (dest as SegmentDestination.AsFile).file
        file.writeBytes(byteArrayOf(1, 2, 3))
        sink.finalize(15_000)
        val renamed = File(dir, "20261029_101530_15s.opus")
        assertTrue(renamed.isFile)
        assertFalse(file.isFile)
        assertEquals("20261029_101530_15s.opus", renamed.name)

        val sink2 = FileSegmentSink(dir, "20261029_101531.opus")
        // Never opened (short segment): finalize/discard leave nothing behind.
        sink2.finalize(1_000)
        sink2.discard()
        assertFalse(sink2.file.exists())
        // Same-second second segment does not clobber the first.
        assertTrue(renamed.isFile)
    }
}
