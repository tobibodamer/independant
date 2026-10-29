package app.pendant.segment

import app.pendant.codec.SegmentDestination
import app.pendant.codec.SegmentWriter
import app.pendant.storage.SegmentSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SegmenterTest {

    private class FakeWriter : SegmentWriter {
        val chunks = ArrayList<Int>()
        var finished = false
        private var total = 0L
        override val samplesWritten: Long get() = total

        override fun writePcm(samples: ShortArray, count: Int) {
            chunks.add(count)
            total += count
        }

        override fun finish() {
            finished = true
        }

        override fun close() {
            finish()
        }
    }

    private class FakeListener : Segmenter.Listener {
        val writers = ArrayList<FakeWriter>()
        val stored = ArrayList<Pair<SegmentSink, Long>>()
        var discarded = 0
        var nextFileIdx = 0
        var failFiles = false

        override fun nextSegmentSink(wallClockMs: Long): SegmentSink? =
            if (failFiles) null else FakeSink()

        override fun createWriter(sink: SegmentSink, startWallClockMs: Long): SegmentWriter? {
            if (failFiles) return null
            val w = FakeWriter()
            writers.add(w)
            return w
        }

        override fun onSegmentStored(sink: SegmentSink, durationMs: Long) {
            stored.add(sink to durationMs)
        }

        override fun onSegmentDiscarded() {
            discarded++
        }

        override fun onVoiceActive(active: Boolean) {
        }
    }

    private class FakeSink : SegmentSink() {
        var finalized: Long? = null
        var discarded = 0

        override fun openOutput(): SegmentDestination? = null
        override fun finalize(durationMs: Long) {
            finalized = durationMs
        }
        override fun discard() {
            discarded++
        }
    }

    private val frameSamples = 320 // 20 ms at 16 kHz

    private fun frame(level: Int = 100): ShortArray = ShortArray(frameSamples) { level.toShort() }

    private fun makeSegmenter(
        listener: FakeListener,
        prerollMs: Int = 500,
        hangoverSec: Int = 3,
        minSegmentMs: Long = 600,
        maxSegmentSec: Long = 60,
    ) = Segmenter(
        sampleRate = 16_000,
        frameSamples = frameSamples,
        prerollMs = prerollMs,
        hangoverSec = hangoverSec,
        minSegmentMs = minSegmentMs,
        maxSegmentSec = maxSegmentSec,
        listener = listener,
    )

    @Test
    fun `speech burst produces one file including preroll`() {
        val listener = FakeListener()
        val seg = makeSegmenter(listener, prerollMs = 500, hangoverSec = 3)
        var t = 1000L
        // 2 s silence
        repeat(100) { seg.feed(frame(0), frameSamples, false, t); t += 20 }
        // 1 s speech
        repeat(50) { seg.feed(frame(), frameSamples, true, t); t += 20 }
        // 5 s silence (hangover 3 s) -> should close after 3 s
        repeat(250) { seg.feed(frame(0), frameSamples, false, t); t += 20 }
        assertEquals(1, listener.stored.size)
        // duration must include ~500 ms preroll + 1 s speech + 3 s hangover tail
        assertTrue("duration=${listener.stored[0].second}", listener.stored[0].second >= 4000)
        assertTrue(listener.stored[0].second <= 5500)
        // first chunk fed to the writer is the pre-roll
        val w = listener.writers[0]
        assertTrue(w.chunks.first() > 0)
    }

    @Test
    fun `one second think pause does not split one utterance`() {
        val listener = FakeListener()
        val seg = makeSegmenter(listener, hangoverSec = 5)
        var t = 0L
        repeat(50) { seg.feed(frame(), frameSamples, true, t); t += 20 } // 1 s speech
        repeat(50) { seg.feed(frame(0), frameSamples, false, t); t += 20 } // 1 s pause
        repeat(50) { seg.feed(frame(), frameSamples, true, t); t += 20 } // 1 s more speech
        assertTrue("no file yet before hangover elapses", listener.stored.isEmpty())
        repeat(300) { seg.feed(frame(0), frameSamples, false, t); t += 20 }
        assertEquals("single clip expected", 1, listener.stored.size)
    }

    @Test
    fun `short blip is discarded without a file`() {
        val listener = FakeListener()
        val seg = makeSegmenter(listener, hangoverSec = 2, minSegmentMs = 800)
        var t = 0L
        seg.feed(frame(), frameSamples, true, t) // single 20 ms blip
        repeat(150) { seg.feed(frame(0), frameSamples, false, t); t += 20 } // 3 s silence
        assertEquals(1, listener.discarded)
        assertEquals(0, listener.stored.size)
        // the folder must not keep an orphan: writer existed but file deleted by store in prod; here verify finish called
        assertTrue(listener.writers.first().finished)
    }

    @Test
    fun `continuous speech rotates at max length`() {
        val listener = FakeListener()
        val seg = makeSegmenter(listener, hangoverSec = 3, maxSegmentSec = 2)
        var t = 0L
        repeat(350) { seg.feed(frame(), frameSamples, true, t); t += 20 } // 7 s speech, rotate every 2 s
        assertTrue(listener.stored.size >= 3)
        listener.stored.forEach { (_, d) -> assertTrue("each clip <= ~2.5 s, got $d", d <= 2700) }
    }

    @Test
    fun `failure to create files does not crash the pipeline`() {
        val listener = FakeListener().apply { failFiles = true }
        val seg = makeSegmenter(listener)
        seg.feed(frame(), frameSamples, true, 0)
        seg.feed(frame(), frameSamples, true, 20)
        seg.release()
    }
}
