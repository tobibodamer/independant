package app.pendant.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Segment file naming, shared by every backend (pure, JVM-testable).
 *
 * Segments are created as `yyyyMMdd_HHmmss.<ext>` and carry a duration suffix
 * `_<seconds>s` once finalized (`..._42s.opus`). MediaStore-backed segments keep
 * their base name because rows cannot be renamed after insertion; their duration
 * comes from the MediaStore DURATION column instead.
 */
object SegmentNames {

    private val tsFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun baseName(ext: String, startWallClockMs: Long): String =
        "${tsFmt.format(Date(startWallClockMs))}.$ext"

    fun durationSuffix(durationMs: Long): String = "_${durationMs / 1000}s"

    /** `20261029_101530.opus` → `20261029_101530_42s.opus`. */
    fun finalizedName(name: String, durationMs: Long): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name + durationSuffix(durationMs)
        else name.substring(0, dot) + durationSuffix(durationMs) + name.substring(dot)
    }

    /** Parses `..._42s.opus` style durations; returns 0 when the name carries no suffix. */
    fun parseDurationMs(name: String): Long {
        val m = Regex("""_(\d+)s\.[^.]+$""").find(name) ?: return 0L
        return m.groupValues[1].toLong() * 1000L
    }
}
