package app.pendant.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** JVM round-trip over the index codec; the fake keeps Android URIs untouched. */
class RecordingIndexTest {

    /** Stubbed android.jar throws on any Uri method, so idle overrides stand in. */
    private class FakeSeg(
        name: String,
        sizeBytes: Long,
        durationMs: Long,
        lastModifiedMs: Long,
        private val fakeKey: String,
        backing: File? = null,
    ) : SegmentInfo(name, sizeBytes, durationMs, lastModifiedMs, backing) {
        override val key: String = fakeKey
        override fun playbackUri(): android.net.Uri = throw AssertionError("codec must not touch URIs")
        override fun shareContentUri(): android.net.Uri? = null
        override fun deleteFromBackend(): Boolean = throw AssertionError("codec must not touch backends")
    }

    @Test
    fun `extraction classifies the three backends`() {
        val backing = File("/tmp/a.opus")
        val fileRow = IndexRow.of(
            FakeSeg("a.opus", 10, 2000, 30, fakeKey = backing.path, backing = backing),
        )
        assertEquals(KIND_FILE, fileRow.kind)
        assertEquals(backing.absolutePath, fileRow.uri)

        val mediaRow = IndexRow.of(
            FakeSeg("b.opus", 11, 2000, 31, fakeKey = "content://media/external_primary/audio/7"),
        )
        assertEquals(KIND_MEDIA_ROW, mediaRow.kind)
        assertEquals("content://media/external_primary/audio/7", mediaRow.uri)

        val safRow = IndexRow.of(
            FakeSeg("c.opus", 12, 2000, 32, fakeKey = "content://com.android.externalstorage.documents/document/primary%3APendant%2Fc.opus"),
        )
        assertEquals(KIND_SAF_DOC, safRow.kind)
    }

    @Test
    fun `lines round-trip the persisted rows`() {
        val rows = listOf(
            IndexRow(KIND_FILE, "/tmp/a_\"weird\" \n\\ name.opus", "a_\"weird\" \n\\ name.opus", 1024, 2000, 3),
            IndexRow(KIND_MEDIA_ROW, "content://media/external_primary/audio/8", "b.opus", 2053, 0, 1790_000_000_000),
            IndexRow(KIND_SAF_DOC, "content://x/y", "n@me %2F", 0, 12_345, Long.MAX_VALUE),
            IndexRow(KIND_FILE, "/tmp/empty.opus", "empty.opus", 0, 0, 0),
        )
        assertEquals(rows, rows.map { IndexRow.fromLine(it.toLine()) })
    }

    @Test
    fun `foreign or corrupt lines parse to null`() {
        assertNull(IndexRow.fromLine(""))
        assertNull(IndexRow.fromLine("""{"x":1}"""))
        assertNull(IndexRow.fromLine("""{"k":0,"u":"u","n":"n","s":1,"d":2}"""))
        assertNull(IndexRow.fromLine("""{"k":9,"u":"u","n":"n","s":1,"d":2,"m":3}"""))
        assertNull(IndexRow.fromLine("""{"k":0,"u":,"n":"n","s":1,"d":2,"m":3}"""))
    }
}
