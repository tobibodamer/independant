package app.pendant.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** State machine over a synthetic day, clip-start precedence, day titles and summaries. */
class TimelineAnalysisTest {

    // Everything on 2026-11-01 in UTC: wall clock == UTC.
    private val zone: ZoneId = ZoneId.of("UTC")
    private val day: LocalDate = LocalDate.of(2026, 11, 1)
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DAY_MS = 86_400_000L

    private fun t(s: String): Long =
        LocalDateTime.parse("2026-11-01 $s", fmt).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun ev(
        time: String,
        kind: String,
        open: Boolean? = null,
        silenced: Boolean? = null,
        audioS: Long? = null,
        speech: Float? = null,
        ms: Long? = null,
    ) = TimelineEvent(
        t = t(time), tz = ZoneOffset.UTC, kind = kind, open = open, silencedFlag = silenced,
        audioS = audioS, speech = speech, ms = ms, file = null, start = null, durMs = null,
    )

    /** Heartbeat every minute keeps a listening interval from collapsing to DOWN. */
    private fun alives(from: String, to: String, speech: Float = 0.5f): List<TimelineEvent> {
        val out = ArrayList<TimelineEvent>()
        var at = t(from)
        val end = t(to)
        while (at <= end) {
            out.add(ev(timeOf(at), "alive", audioS = 60, speech = speech))
            at += 60_000
        }
        return out
    }

    private fun events(vararg parts: Any): List<TimelineEvent> = parts.flatMap { p ->
        @Suppress("UNCHECKED_CAST")
        when (p) {
            is List<*> -> p as List<TimelineEvent>
            else -> listOf(p as TimelineEvent)
        }
    }

    private fun timeOf(epochMs: Long): String =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), zone).format(fmt).substring(11)

    private fun clipEv(time: String, file: String, start: String, durMs: Long) = TimelineEvent(
        t = t(time), tz = ZoneOffset.UTC, kind = "clip", open = null, silencedFlag = null,
        audioS = null, speech = null, ms = null, file = file, start = t(start), durMs = durMs,
    )

    private fun analyze(events: List<TimelineEvent>, nowMs: Long = Long.MAX_VALUE): DayAnalysis =
        TimelineAnalysis.analyze(day, events, emptyList(), zone, nowMs)

    @Test
    fun `listen pause silence resume classify a booked day`() {
        val events = buildList {
            add(ev("08:00:00", "mic", open = true))
            addAll(alives("08:00:30", "08:59:30"))
            add(ev("09:00:00", "pause"))
            add(ev("09:30:00", "resume"))
            addAll(alives("09:30:30", "09:39:30"))
            add(ev("09:40:00", "silenced"))
            add(ev("09:42:00", "unsilenced"))
            addAll(alives("09:42:30", "10:14:30"))
            add(ev("10:15:00", "stop"))
        }
        val a = analyze(events)
        // 08:00–09:00 listen, 09:00–09:30 paused, 09:30–09:40 listen, 09:40–09:42 mic-lost,
        // 09:42–10:15 listen, then DOWN to midnight (plus the dark pre-first-event start).
        assertEquals(103 * 60_000, a.listeningMs)
        assertEquals(30 * 60_000, a.pausedMs)
        assertEquals(2 * 60_000, a.micLostMs)
        assertEquals(8 * 3600_000L + 13 * 3600_000L + 45 * 60_000L, a.downMs)
        // Speech share rides the five tracked heartbeats… half of the listening time.
        assertEquals((103 * 60_000 * 0.5f).toLong(), a.speechMs)
        assertTrue(a.speechKnown)
    }

    @Test
    fun `no heartbeat within a minute kills the listening classification`() {
        val a = analyze(
            listOf(
                ev("08:00:00", "mic", open = true),
                ev("09:00:00", "pause"),
            ),
        )
        // Listening until the timeout, then DOWN until the pause proves the process lived.
        assertEquals(60_000, a.listeningMs)
        assertEquals(
            listOf(TimelineSegment(TimelineState.LISTENING, 8 * 3600_000L, 8 * 3600_000L + 60_000)),
            a.segments.filter { it.state == TimelineState.LISTENING },
        )
        assertEquals(15 * 3600_000L, a.pausedMs)
    }

    @Test
    fun `start begins listening and collapses without heartbeats`() {
        val a = analyze(
            listOf(
                ev("08:00:00", "start"),
                ev("08:30:00", "stop"),
            ),
        )
        assertEquals(60_000, a.listeningMs)
        // Everything dark: the 8 pre-start hours, the post-timeout collapse and the tail.
        assertEquals(DAY_MS - 60_000, a.downMs)
    }

    @Test
    fun `leading alive means the session spanned midnight`() {
        val a = analyze(listOf(ev("00:00:30", "alive", audioS = 60, speech = null)))
        // Listening from midnight (proven), the timeout piece after the heartbeat, DOWN after.
        assertEquals(90_000, a.listeningMs)
        assertEquals(
            listOf(TimelineSegment(TimelineState.LISTENING, 0, 90_000)),
            a.segments.filter { it.state == TimelineState.LISTENING },
        )
    }

    @Test
    fun `silenced lasts until stop`() {
        val a = analyze(
            events(
                ev("08:00:00", "mic", open = true),
                alives("08:00:30", "08:01:30"),
                ev("08:02:00", "silenced"),
                ev("09:00:00", "stop"),
            ),
        )
        assertEquals(2 * 60_000, a.listeningMs)
        assertEquals(58 * 60_000, a.micLostMs)
    }

    @Test
    fun `gaps total ms and place ticks without changing state`() {
        val a = analyze(
            events(
                ev("08:00:00", "mic", open = true),
                alives("08:00:30", "08:01:30"),
                ev("08:02:00", "gap", ms = 500),
                ev("08:03:00", "gap", ms = 250),
            ),
        )
        assertEquals(750, a.gapsMs)
        assertEquals(
            listOf(
                GapTick(8 * 3600_000L + 2 * 60_000L, 500),
                GapTick(8 * 3600_000L + 3 * 60_000L, 250),
            ),
            a.gapTicks,
        )
        // The one-minute timeout ran out mid-day — gaps still never vetoed listening.
        assertEquals(150_000, a.listeningMs)
    }

    @Test
    fun `today is cut at now`() {
        val a = analyze(
            events(
                ev("08:00:00", "mic", open = true),
                alives("08:00:30", "08:59:30"),
            ),
            nowMs = t("09:00:10"),
        )
        // No painting beyond "now": the tail stops after the last heartbeat.
        assertEquals(3_610_000, a.listeningMs)
        assertTrue(a.segments.maxOf { it.toMs } <= 9 * 3600_000L + 10_000)
    }

    @Test
    fun `event-less day is all down`() {
        val a = analyze(emptyList())
        assertEquals(listOf(TimelineSegment(TimelineState.DOWN, 0, DAY_MS)), a.segments)
        assertEquals(DAY_MS, a.downMs)
    }

    @Test
    fun `clip events refine extents and rows match without the suffix`() {
        val clips = listOf(
            ClipRef("20261101_120000_45s.opus", 45_000, t("12:01:00")),
            ClipRef("20261101_130000.opus", 30_000, t("13:30:40")), // MediaStore row: no suffix
            ClipRef("un-parsable-name.opus", 10_000, t("14:00:00")),
        )
        val events = listOf(
            clipEv("12:01:00", "20261101_120000_45s.opus", "12:00:00", 45_000),
            clipEv("13:30:40", "20261101_130000_30s.opus", "12:59:55", 30_000),
            clipEv("14:00:00", "un-parsable-name_10s.opus", "13:59:50", 10_000),
        )
        val a = TimelineAnalysis.analyze(day, events, clips, zone)
        // Tier-1 start epoch from the log, both for suffixed and suffix-less names.
        assertEquals(t("12:00:00"), a.clipExtents[ClipStarts.keyOf(clips[0].name)]!!.startEpochMs)
        assertEquals(t("12:59:55"), a.clipExtents[ClipStarts.keyOf(clips[1].name)]!!.startEpochMs)
        assertEquals(ClipStarts.FROM_CLIP_EVENT, a.clipExtents[ClipStarts.keyOf(clips[0].name)]!!.source)
        // Row names match suffixed event files because keys strip both.
        assertEquals(ClipStarts.FROM_CLIP_EVENT, a.clipExtents[ClipStarts.keyOf(clips[1].name)]!!.source)
    }
}
