package app.pendant.timeline

import android.content.Context
import android.util.Log
import app.pendant.events.EventLog
import app.pendant.events.FlatJson
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val TAG = "PendantDayEvents"

/**
 * Async reader for the capture log's `<date>.jsonl` day files, feeding the
 * per-day timeline in the Recordings tab (nothing about it blocks first paint:
 * results land per day in [days] and callers subscribe).
 *
 * All I/O runs on one background single-thread executor (named daemon thread),
 * never on the caller's thread. Parsed days live in a small LRU; a request is a
 * cheap stat against the file — the day is re-read only when its length changed
 * (clip finalizes append), so repeated requests (every recomposition-visible
 * day, refresh hook) cost one stat call. Missing or empty files are "no data"
 * (an empty event list, e.g. after log pruning/mirror-only days).
 */
class DayEvents(
    private val dir: File,
    private val today: () -> LocalDate = { LocalDate.now() },
) {

    /** Loaded day events; present (possibly empty) once its file was read, null/loading otherwise. */
    val days = MutableStateFlow<Map<LocalDate, List<TimelineEvent>>>(emptyMap())

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pendant-days").apply { isDaemon = true } }

    // Executor-thread state.
    private data class Cached(val events: List<TimelineEvent>, val fileLength: Long)
    private val cache = object : LinkedHashMap<LocalDate, Cached>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, Cached>): Boolean = size > MAX_ENTRIES
    }

    /** Requests [date]'s events; no-op when nothing changed on disk. Any thread. */
    fun request(date: LocalDate) = submit { load(date) }

    /** Light stat/refresh of today's file; the hook for clip finalize/delete refreshes. */
    fun refreshToday() = request(today())

    private fun load(date: LocalDate) {
        val file = File(dir, fileName(date))
        val len = file.length()
        // Length unchanged: cached and published already; a changed or unknown
        // length re-reads the whole small file.
        cache[date]?.let { if (it.fileLength == len) return }
        val events = if (len <= 0L) {
            emptyList()
        } else {
            try {
                file.readLines().mapNotNull { line ->
                    if (line.isBlank()) null else TimelineEvent.from(FlatJson.parse(line) ?: return@mapNotNull null)
                }.sortedBy { it.t }
            } catch (e: Exception) {
                Log.w(TAG, "day file read failed: $date", e)
                emptyList()
            }
        }
        cache[date] = Cached(events, len)
        days.value = days.value + (date to events)
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute {
                try {
                    task()
                } catch (e: Exception) {
                    Log.w(TAG, "day load task failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // App-long reader; executor is never shut down in practice.
        }
    }

    companion object {
        private const val MAX_ENTRIES = 48

        /** Where the day files live, matching [EventLog]'s source-of-truth dir. */
        fun dir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, EventLog.DIR_NAME)

        fun fileName(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".jsonl"
    }
}
