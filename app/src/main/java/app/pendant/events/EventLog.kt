package app.pendant.events

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.pendant.storage.SegmentStore
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Append-only capture log, one JSON object per line, one file per local day
 * (`2026-10-30.jsonl`). Tells consumers when Pendant was actually listening, so a
 * gap between clips can be told apart from "paused", "silenced" or "not running".
 *
 * Every line carries `t` (UTC epoch ms), `tz` (offset at that instant), `up`
 * (elapsedRealtime ms, monotonic: exposes wall-clock jumps) and `e` (event type).
 *
 * The source of truth lives app-private (`Android/data/app.pendant/files/events`).
 * Unless clips are stored app-private, new bytes are mirrored next to them
 * ([EventMirror]): right away for state changes, at most every
 * [MIRROR_INTERVAL_MS] for heartbeats, so sync tools see few small appends.
 * All I/O runs on one background thread, never on the capture thread.
 */
class EventLog(context: Context, dirName: String = DIR_NAME) {

    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, dirName)
    private val mirror = EventMirror(context, dirName)
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pendant-events").apply { isDaemon = true } }

    @Volatile var location = SegmentStore.Location()

    // Executor-thread state.
    private val dirty = LinkedHashSet<String>()
    /** Bytes of each day file already mirrored to [mirrorKey]. */
    private val mirrored = HashMap<String, Long>()
    private var mirrorKey: String? = null
    private var lastMirrorMs = 0L
    private var lastMirrorErrorMs = 0L

    fun log(event: String, fill: JsonLine.() -> Unit = {}) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val instant = Instant.ofEpochMilli(now)
        val line = JsonLine()
            .put("t", now)
            .put("tz", zone.rules.getOffset(instant).id)
            .put("up", SystemClock.elapsedRealtime())
            .put("e", event)
            .apply(fill)
            .build()
        write(line, instant, zone, urgent = event != ALIVE)
    }

    /** Appends an already-built line to the day file of [instant] and mirrors it right away. */
    fun logRaw(line: String, instant: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) =
        write(line, instant, zone, urgent = true)

    private fun write(line: String, instant: Instant, zone: ZoneId, urgent: Boolean) {
        val now = instant.toEpochMilli()
        val day = DAY_FORMAT.format(instant.atZone(zone)) + EXTENSION
        submit {
            append(day, line)
            if (urgent || now - lastMirrorMs >= MIRROR_INTERVAL_MS) mirrorPending(now)
        }
    }

    /** Deletes local day files older than [days] (0 = keep forever). Mirrors are left to the user. */
    fun prune(days: Int) {
        if (days <= 0) return
        submit {
            val cutoff = LocalDate.now().minusDays(days.toLong())
            dir.listFiles()?.forEach { f ->
                val date = runCatching { LocalDate.parse(f.name.removeSuffix(EXTENSION), DAY_FORMAT) }.getOrNull()
                if (date != null && date.isBefore(cutoff)) f.delete()
            }
        }
    }

    /** Mirrors what is pending and stops the I/O thread; queued lines are still written. */
    fun close() {
        submit { mirrorPending(System.currentTimeMillis()) }
        executor.shutdown()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute {
                try {
                    task()
                } catch (e: Exception) {
                    Log.w(TAG, "event log I/O failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closed: the engine is gone, late callbacks have nothing left to record.
        }
    }

    private fun append(day: String, line: String) {
        dir.mkdirs()
        FileOutputStream(File(dir, day), true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
        dirty.add(day)
    }

    private fun mirrorPending(now: Long) {
        lastMirrorMs = now
        val loc = location
        val key = mirror.targetKey(loc)
        if (key != mirrorKey) {
            // New target: start its copies from scratch with every file touched from now on.
            mirrorKey = key
            mirrored.clear()
        }
        if (key == null) {
            dirty.clear()
            return
        }
        val it = dirty.iterator()
        while (it.hasNext()) {
            val name = it.next()
            val file = File(dir, name)
            val length = file.length()
            val from = mirrored[name]
            val ok = try {
                mirror.write(loc, name, file, from ?: 0L, length, append = from != null)
            } catch (e: Exception) {
                if (now - lastMirrorErrorMs > 15 * 60_000L) {
                    lastMirrorErrorMs = now
                    Log.w(TAG, "event log mirror failed", e)
                }
                false
            }
            if (!ok) continue
            mirrored[name] = length
            it.remove()
        }
    }

    companion object {
        const val DIR_NAME = "events"
        const val EXTENSION = ".jsonl"
        const val ALIVE = "alive"
        const val MIRROR_INTERVAL_MS = 5 * 60_000L
        private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private const val TAG = "PendantEvents"
    }
}
