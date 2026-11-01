package app.pendant.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Clip-start tiers (event → name → fallback), key matching and day-title text. */
class ClipStartsAndDayNamesTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun t(dayTime: String): Long =
        LocalDateTime.parse("2026-10-31 $dayTime", fmt).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun clipEvent(file: String?, start: Long?, durMs: Long?): TimelineEvent = TimelineEvent(
        t = 0, tz = ZoneOffset.UTC, kind = "clip", open = null, silencedFlag = null,
        audioS = null, speech = null, ms = null, file = file, start = start, durMs = durMs,
    )

    @Test
    fun `keys strip extension and duration suffix both ways`() {
        assertEquals("20261031_120000", ClipStarts.keyOf("20261031_120000_45s.opus"))
        assertEquals("20261031_120000", ClipStarts.keyOf("20261031_120000.opus"))
        assertEquals("20261031_120000", ClipStarts.keyOf("20261031_120000"))
        assertEquals("plain future-name", ClipStarts.keyOf("plain future-name"))
    }

    @Test
    fun `tier one clip event wins wherever a name would land`() {
        val ev = clipEvent("20261031_120000_45s.opus", start = t("12:00:00"), durMs = 45_000)
        // Both the final suffixed name and the suffix-less MediaStore row match it.
        val withSuffix = ClipStarts.derive("20261031_120000_45s.opus", ev, zone, 9, 45_000)
        assertEquals(ClipStarts.FROM_CLIP_EVENT to t("12:00:00"), withSuffix.source to withSuffix.epochMs)
        val row = ClipStarts.derive("20261031_120000.opus", ev, zone, 9, 0)
        assertEquals(ClipStarts.FROM_CLIP_EVENT to t("12:00:00"), row.source to row.epochMs)
        // Wrong file, wrong kind or missing start → the event is ignored.
        assertEquals(ClipStarts.FROM_NAME, ClipStarts.derive("20261031_130000.opus", ev, zone, 9, 0).source)
        assertEquals(
            ClipStarts.FROM_NAME,
            ClipStarts.derive("20261031_120000_45s.opus", clipEvent("20261031_120000_45s.opus", null, 45_000), zone, 9, 45_000).source,
        )
        assertEquals(
            ClipStarts.FROM_NAME,
            ClipStarts.derive("20261031_120000_45s.opus", clipEvent("20261031_120000_45s.opus", t("12:00:00"), 45_000).copy(kind = "mic"), zone, 9, 45_000).source,
        )
    }

    @Test
    fun `tier two parses the name timestamp prefix in the given zone`() {
        val paris = ZoneId.of("Europe/Paris")
        val epoch = ClipStarts.fromName("20261031_120000_45s.opus", paris)!!
        // 12:00:00 Paris = 11:00:00 UTC on a CET day.
        assertEquals(t("12:00:00") - 3600_000L, epoch)
        assertNull(ClipStarts.fromName("no_timestamp_here.opus", zone))
        assertNull(ClipStarts.fromName("20261031_996688.opus", zone))
        assertNull(ClipStarts.fromName("20261031_12000", zone))
    }

    @Test
    fun `tier three is lastModified minus duration`() {
        val got = ClipStarts.derive("something-else.opus", null, zone, 1000L, 400L)
        assertEquals(ClipStarts.FROM_FALLBACK, got.source)
        assertEquals(600L, got.epochMs)
        // A negative fallback must not explode the caller — it is returned as-is.
        assertEquals(-300L, ClipStarts.derive("x.opus", null, zone, 200L, 500L).epochMs)
    }

    @Test
    fun `extents wall positions use the event tz over zone rules`() {
        // Event in +02:00: its clip start lands 2 wall-clock hours earlier than UTC rules say.
        val paris = ZoneOffset.of("+02:00")
        val ev = clipEvent("20261031_120000_30s.opus", t("10:00:00"), 30_000).copy(tz = paris)
        val extents = ClipStarts.extents(
            listOf(ClipRef("20261031_120000.opus", 30_000, 0)),
            listOf(ev),
            zone,
        )
        val ext = extents[ClipStarts.keyOf("20261031_120000.opus")]!!
        assertEquals(12 * 3600_000L, ext.wallStartMs) // 10:00 UTC + 2h offset = 12:00 wall
        assertEquals(ClipStarts.FROM_CLIP_EVENT, ext.source)
    }

    @Test
    fun `day titles today yesterday and older`() {
        val today = LocalDate.of(2026, 11, 1)
        assertEquals("Today", DayNames.title(today, today, Locale.US))
        assertEquals("Yesterday", DayNames.title(today.minusDays(1), today, Locale.US))
        assertEquals("Sat, Oct 31", DayNames.title(LocalDate.of(1998, 10, 31), today, Locale.US))
        assertEquals("Sa., Okt. 31", DayNames.title(LocalDate.of(1998, 10, 31), today, Locale.GERMANY))
    }
}
