package app.pendant.timeline

import java.time.ZoneOffset

/**
 * One capture-log event as the timeline state machine sees it (pure, JVM-testable).
 * Built from the flat field map of a `<date>.jsonl` line; [fields] latecomers are
 * simply absent (null).
 */
data class TimelineEvent(
    /** UTC epoch ms. */
    val t: Long,
    /** Zone offset id written with the line, e.g. "Z" or "+02:00"; null when unparseable. */
    val tz: ZoneOffset?,
    /** Event kind: start, stop, pause, resume, mic, silenced, unsilenced, alive, gap, clip, … */
    val kind: String,
    /** `mic` events only: whether any capture source actually opened. */
    val open: Boolean?,
    /** `alive` events: line carries `"silenced":true` while another app holds the mic. */
    val silencedFlag: Boolean?,
    /** `alive` events: analyzed audio seconds in that minute (silenced/warm-up excluded). */
    val audioS: Long?,
    /** `alive` events: speech-frame share of [audioS], 0..1. */
    val speech: Float?,
    /** `gap` events: ms of buffer overrun lost since the last report. */
    val ms: Long?,
    /** `clip` events: published file name (with duration suffix). */
    val file: String?,
    /** `clip` events: clip start epoch ms. */
    val start: Long?,
    /** `clip` events: clip duration ms. */
    val durMs: Long?,
) {
    companion object {
        /** Parses a flat line; null when `t` or `e` is missing. */
        fun from(fields: Map<String, String>): TimelineEvent? {
            val t = fields["t"]?.toLongOrNull() ?: return null
            val kind = fields["e"] ?: return null
            val tz = fields["tz"]?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
            return TimelineEvent(
                t = t,
                tz = tz,
                kind = kind,
                open = when (fields["open"]) {
                    "true" -> true
                    "false" -> false
                    else -> null
                },
                silencedFlag = if (fields["silenced"] == "true") true else null,
                audioS = fields["audio_s"]?.toLongOrNull(),
                speech = fields["speech"]?.toFloatOrNull(),
                ms = fields["ms"]?.toLongOrNull(),
                file = fields["file"],
                start = fields["start"]?.toLongOrNull(),
                durMs = fields["dur_ms"]?.toLongOrNull(),
            )
        }
    }
}
