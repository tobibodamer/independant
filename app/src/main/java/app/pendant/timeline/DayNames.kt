package app.pendant.timeline

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Day-header titles for the timeline grouping (pure, JVM-testable). */
object DayNames {

    /** "Today", "Yesterday", or "Sat, Oct 31"-style for older days ([locale] formats). */
    fun title(date: LocalDate, today: LocalDate, locale: Locale): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
    }
}
