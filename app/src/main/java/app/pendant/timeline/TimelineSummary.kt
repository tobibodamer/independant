package app.pendant.timeline

import java.util.Locale

/** Text for the per-day summary line under the Recordings header (pure, JVM-testable). */
object TimelineSummary {

    /**
     * "listening 6h 10m · speech 2h 05m · paused 45m · mic-lost 2m · off 8h";
     * zero terms are omitted. Null when nothing measurable was captured (a day
     * without a log file shows no summary at all — that is the caller's call).
     */
    fun text(a: DayAnalysis): String? {
        val parts = buildList {
            if (a.listeningMs > 0) add("listening " + dur(a.listeningMs))
            if (a.speechKnown && a.speechMs > 0) add("speech " + dur(a.speechMs))
            if (a.pausedMs > 0) add("paused " + dur(a.pausedMs))
            if (a.micLostMs > 0) add("mic-lost " + dur(a.micLostMs))
            if (a.downMs > 0) add("off " + dur(a.downMs))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** "6h 10m" or "45m"; minutes zero-padded inside hours ("2h 05m"). */
    fun dur(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) {
            "${minutes / 60}h " + String.format(Locale.US, "%02dm", minutes % 60)
        } else {
            "${minutes}m"
        }
    }
}
