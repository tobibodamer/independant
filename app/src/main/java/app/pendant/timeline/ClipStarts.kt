package app.pendant.timeline

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Clip start derivation, by precedence (pure, JVM-testable):
 *
 * 1. CLIP_EVENT — the day log's `clip` event for the file (keys match via
 *    [keyOf], so MediaStore rows without the duration suffix match the
 *    `_NNs`-suffixed `file` field too). Carries the engine's exact start epoch
 *    and the event's own `tz` for wall-clock placement.
 * 2. NAME — the `yyyyMMdd_HHMMSS` timestamp prefix of the clip name, parsed in
 *    [zone] (the local zone of whoever groups the listing).
 * 3. FALLBACK — lastModified minus durationMs (both listing fields).
 *
 * First-paint grouping uses 2/3 only (the day log is not loaded yet); once a
 * day file is loaded, the analysis refines the same clips through 1.
 */
object ClipStarts {

    const val FROM_CLIP_EVENT = 0
    const val FROM_NAME = 1
    const val FROM_FALLBACK = 2

    /** Newest clip an off-day fallback could produce before the epoch heuristic breaks. */
    private const val MAX_VALID_MS = 4_102_444_800_000L // ~2100

    private val DAY_MS = 86_400_000L
    private val DUR_SUFFIX = Regex("""_\d+s$""")
    private val NAME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    data class Start(val epochMs: Long, val source: Int)

    /** Name with extension and duration suffix stripped: the clip's identity key. */
    fun keyOf(name: String): String {
        val base = if (name.contains('.')) name.substringBeforeLast('.') else name
        val m = DUR_SUFFIX.find(base) ?: return base
        return base.substring(0, m.range.first)
    }

    /** Best available start for a clip; the fallback tier always yields something. */
    fun derive(
        name: String,
        clipEvent: TimelineEvent?,
        zone: ZoneId,
        lastModifiedMs: Long,
        durationMs: Long,
    ): Start {
        val matched = clipEvent != null &&
            clipEvent.kind == "clip" &&
            clipEvent.start != null &&
            (clipEvent.file == null || keyOf(clipEvent.file) == keyOf(name))
        if (matched) {
            return Start(clipEvent!!.start!!, FROM_CLIP_EVENT)
        }
        fromName(name, zone)?.let { return Start(it, FROM_NAME) }
        return Start(lastModifiedMs - durationMs, FROM_FALLBACK)
    }

    /** `20261031_120000…` prefix parsed in [zone]; null when the name carries none. */
    fun fromName(name: String, zone: ZoneId): Long? {
        if (name.length < 15 || name[8] != '_') return null
        if (name.substring(0, 8).any { !it.isDigit() }) return null
        if (name.substring(9, 15).any { !it.isDigit() }) return null
        val local = try {
            LocalDateTime.parse(name.substring(0, 15), NAME_FMT)
        } catch (e: DateTimeParseException) {
            return null
        }
        return local.atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * Extents for [clips] given the day's loaded [sortedEvents]: tier-1 where a
     * matching `clip` event exists, otherwise the same precedence as [derive].
     */
    fun extents(clips: List<ClipRef>, sortedEvents: List<TimelineEvent>, zone: ZoneId): Map<String, ClipExtent> {
        val eventsByFile = sortedEvents
            .filter { it.kind == "clip" && it.file != null && it.start != null }
            .associateBy { keyOf(it.file ?: "") }
        val out = HashMap<String, ClipExtent>(clips.size)
        for (c in clips) {
            val event = eventsByFile[keyOf(c.name)]
            val start = derive(c.name, event, zone, c.lastModifiedMs, c.durationMs)
            val duration = c.durationMs.takeIf { it > 0 } ?: event?.durMs ?: continue
            out[keyOf(c.name)] = ClipExtent(
                startEpochMs = start.epochMs,
                durationMs = duration,
                wallStartMs = wallStart(start.epochMs, event, zone),
                source = start.source,
            )
        }
        return out
    }

    /** Wall position of a clip start; the matched event's own tz wins over [zone] rules. */
    private fun wallStart(epochMs: Long, event: TimelineEvent?, zone: ZoneId): Long {
        if (epochMs < 0 || epochMs > MAX_VALID_MS) return -1L // off-day or nonsense: not drawable
        val off = event?.tz ?: zone.rules.getOffset(Instant.ofEpochMilli(epochMs))
        val shifted = (epochMs + off.totalSeconds * 1000L) % DAY_MS
        return (shifted + DAY_MS) % DAY_MS
    }
}
