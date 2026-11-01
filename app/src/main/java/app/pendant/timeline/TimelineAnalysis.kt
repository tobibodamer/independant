package app.pendant.timeline

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure state machine for the per-day capture timeline (JVM-testable): one day's
 * events become LISTENING / MIC_LOST / PAUSED / DOWN wall-clock segments plus
 * totals, gap ticks and clip extents.
 *
 * Semantics (mirrors RecordingEngine): Pendant is LISTENING from `start`,
 * `resume`, `unsilenced` or `mic open:true` until `pause`, `stop`, `silenced`
 * or `mic open:false` (`start` begins the post-boot session and a later
 * pause/unsilenced may refine it; unspecified `mic open:false` counts as
 * MIC_LOST too — a route with no open source captures nothing). While
 * listening, an `alive` heartbeat proves life once a minute; when nothing
 * follows within [ALIVE_TIMEOUT_MS] after the last alive (or session start),
 * the state collapses to DOWN — that is the service-killed rule. `gap` events
 * keep the state, they only total buffer losses.
 *
 * Strip positions are local WALL-CLOCK minutes of day: each event's own `tz`
 * maps its epoch into the strip, so DST shifts land on the wall-clock side
 * they happened on. Day bounds (positions when an event lacks a valid `tz`,
 * and clip-start fallbacks) use [zone]. Source-of-truth day files are one per
 * local day already, so [date] is the file's day; the day's first event opens
 * the day — a leading `alive` proves the session spanned midnight — and
 * everything before it counts as DOWN.
 */
object TimelineAnalysis {

    /** One late heartbeat: treated as dead this far after the last `alive`. */
    const val ALIVE_TIMEOUT_MS = 60_000L

    private const val DAY_MS = 86_400_000L
    private const val ALIVE = "alive"

    fun analyze(
        date: LocalDate,
        events: List<TimelineEvent>,
        clips: List<ClipRef>,
        zone: ZoneId,
        /** Today's tail is cut here; fully past days end naturally at midnight. */
        nowMs: Long = Long.MAX_VALUE,
    ): DayAnalysis {
        val sorted = events.sortedBy { it.t }
        val segments = ArrayList<TimelineSegment>()
        val gapTicks = ArrayList<GapTick>()
        var gapsMs = 0L
        var speechMs = 0L
        var speechKnown = false

        var state = TimelineState.DOWN
        // Listening liveness anchor; the timeout turns stale listening into DOWN.
        var lastAlive = Long.MIN_VALUE / 2
        var aliveOffset: ZoneOffset? = null

        fun offsetOf(ev: TimelineEvent): ZoneOffset =
            ev.tz ?: zone.rules.getOffset(Instant.ofEpochMilli(ev.t))

        /** Wall-clock position of an epoch in the day: local midnight == 0. */
        fun wallOf(t: Long, offset: ZoneOffset?): Long {
            val off = (offset?.totalSeconds ?: 0) * 1000L
            return ((t + off) % DAY_MS + DAY_MS) % DAY_MS
        }

        fun emit(fromWall: Long, toWall: Long, s: TimelineState) {
            if (toWall > fromWall) segments.add(TimelineSegment(s, fromWall, toWall))
        }

        /** Runs [state] over [fromT, toT) in wall space; the listening timeout may split it. */
        fun span(fromT: Long, toT: Long, fromOff: ZoneOffset?, toOff: ZoneOffset?, toWall: Long? = null) {
            val fromWall = wallOf(fromT, fromOff)
            val endWall = (toWall ?: wallOf(toT, toOff)).coerceIn(0, DAY_MS)
            if (endWall <= fromWall) return
            if (state == TimelineState.LISTENING && lastAlive > Long.MIN_VALUE / 2) {
                val cutoff = lastAlive + ALIVE_TIMEOUT_MS
                if (cutoff < toT) {
                    val cutWall = wallOf(cutoff, aliveOffset).coerceIn(fromWall, endWall)
                    emit(fromWall, cutWall, TimelineState.LISTENING)
                    emit(cutWall, endWall, TimelineState.DOWN)
                    return
                }
            }
            emit(fromWall, endWall, state)
        }

        fun apply(ev: TimelineEvent) {
            when (ev.kind) {
                "start", "resume", "unsilenced" -> {
                    state = TimelineState.LISTENING
                    lastAlive = ev.t
                    aliveOffset = offsetOf(ev)
                }
                "mic" -> if (ev.open == true) {
                    state = TimelineState.LISTENING
                    lastAlive = ev.t
                    aliveOffset = offsetOf(ev)
                } else {
                    // No source opened (e.g. waiting for a headset): nothing is captured.
                    state = TimelineState.MIC_LOST
                }
                "silenced" -> state = TimelineState.MIC_LOST
                "pause" -> state = TimelineState.PAUSED
                "stop" -> state = TimelineState.DOWN
                "alive" -> {
                    // An alive re-proves capture through any DOWN gap (wall jumps).
                    state = if (ev.silencedFlag == true) TimelineState.MIC_LOST else TimelineState.LISTENING
                    lastAlive = ev.t
                    aliveOffset = offsetOf(ev)
                    val audio = ev.audioS
                    val share = ev.speech
                    if (audio != null && audio > 0 && share != null) {
                        speechMs += (audio * 1000 * share).toLong()
                        speechKnown = true
                    }
                }
                "gap" -> {
                    val ms = ev.ms ?: return
                    gapsMs += ms
                    gapTicks.add(GapTick(wallOf(ev.t, offsetOf(ev)), ms))
                }
                else -> Unit // config / clip / error: no state effect
            }
        }

        val dayStartUtc = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndUtc = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        if (sorted.isEmpty()) {
            // No log data at all: the whole day is simply not captured.
            segments.add(TimelineSegment(TimelineState.DOWN, 0, DAY_MS))
        } else {
            val first = sorted.first()
            // An `alive` at the head of the day file proves the session spanned midnight.
            state = if (first.kind == ALIVE) {
                if (first.silencedFlag == true) TimelineState.MIC_LOST else TimelineState.LISTENING
            } else {
                TimelineState.DOWN
            }
            emit(0, wallOf(first.t, offsetOf(first)), state)
            apply(first)
            var prevT = first.t
            var prevOff = offsetOf(first)
            for (i in 1 until sorted.size) {
                val ev = sorted[i]
                // Each span BEFORE an event carries the state as of the previous event.
                span(prevT, ev.t, prevOff, offsetOf(ev))
                apply(ev)
                prevT = ev.t
                prevOff = offsetOf(ev)
            }
            // Tail to the local day's end (or to "now" — today may still be running).
            val tailEnd = minOf(dayEndUtc, nowMs)
            if (tailEnd > prevT) {
                span(
                    fromT = prevT,
                    toT = tailEnd,
                    fromOff = prevOff,
                    toOff = zone.rules.getOffset(Instant.ofEpochMilli(tailEnd)),
                    // wallOf next midnight wraps to 0; the tail must reach the right edge.
                    toWall = if (tailEnd >= dayEndUtc) DAY_MS else wallOf(tailEnd, zone.rules.getOffset(Instant.ofEpochMilli(tailEnd))),
                )
            }
        }

        // Wall-space ordering and merge: wall-clock jumps can shuffle or overlap pieces.
        val merged = ArrayList<TimelineSegment>()
        for (seg in segments.sortedBy { it.fromMs }) {
            val last = merged.lastOrNull()
            when {
                last == null -> merged.add(seg)
                last.state == seg.state && seg.fromMs <= last.toMs ->
                    if (seg.toMs > last.toMs) merged[merged.size - 1] = last.copy(toMs = seg.toMs)
                seg.fromMs < last.toMs -> {
                    // Different state overlapping backwards: clamp so the strip stays ordered.
                    val from = maxOf(seg.fromMs, last.toMs)
                    if (from < seg.toMs) merged.add(seg.copy(fromMs = from))
                }
                else -> merged.add(seg)
            }
        }

        var listeningMs = 0L
        var micLostMs = 0L
        var pausedMs = 0L
        var downMs = 0L
        for (s in merged) {
            when (s.state) {
                TimelineState.LISTENING -> listeningMs += s.toMs - s.fromMs
                TimelineState.MIC_LOST -> micLostMs += s.toMs - s.fromMs
                TimelineState.PAUSED -> pausedMs += s.toMs - s.fromMs
                TimelineState.DOWN -> downMs += s.toMs - s.fromMs
            }
        }
        return DayAnalysis(
            date = date,
            segments = merged,
            listeningMs = listeningMs,
            micLostMs = micLostMs,
            pausedMs = pausedMs,
            downMs = downMs,
            speechMs = speechMs,
            speechKnown = speechKnown,
            gapsMs = gapsMs,
            gapTicks = gapTicks,
            clipExtents = ClipStarts.extents(clips, sorted, zone),
        )
    }
}
