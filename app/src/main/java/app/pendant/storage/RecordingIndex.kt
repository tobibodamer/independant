package app.pendant.storage

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.pendant.events.JsonLine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Persisted backend kinds for [IndexRow]. MediaStore rows are told apart from SAF
 * documents via MediaStore's fixed `content://media/` authority; a file-backed
 * clip carries its absolute path instead.
 */
internal const val KIND_FILE = 0
internal const val KIND_MEDIA_ROW = 1
internal const val KIND_SAF_DOC = 2

private const val TAG = "PendantIndex"

/** One persisted listing entry — enough to rebuild a playable, shareable, deletable clip. */
internal data class IndexRow(
    val kind: Int,
    /** Absolute file path (FILE) or the backend's playback/deletion URI (media row, SAF document). */
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val lastModifiedMs: Long,
) {
    /** Flat JSON object, same per-line shape as the event log. */
    fun toLine(): String = JsonLine()
        .put("k", kind)
        .put("u", uri)
        .put("n", name)
        .put("s", sizeBytes)
        .put("d", durationMs)
        .put("m", lastModifiedMs)
        .build()

    fun toSegmentInfo(resolver: android.content.ContentResolver): SegmentInfo? = try {
        when (kind) {
            KIND_FILE -> FileSegmentInfo(File(uri), name, sizeBytes, durationMs, lastModifiedMs)
            KIND_MEDIA_ROW -> MediaSegmentInfo(resolver, Uri.parse(uri), name, sizeBytes, durationMs, lastModifiedMs)
            KIND_SAF_DOC -> SafSegmentInfo(resolver, Uri.parse(uri), name, sizeBytes, durationMs, lastModifiedMs)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * Extracts the persisted form of a live listing entry. Works from backend-agnostic
         * fields only: [SegmentInfo.key] doubles as the identity URI for the URI backends,
         * so a fake or future subclass stays serializable without touching Android URIs.
         */
        fun of(seg: SegmentInfo): IndexRow {
            val backing = seg.backingFile
            return when {
                backing != null -> IndexRow(
                    KIND_FILE, backing.absolutePath, seg.name, seg.sizeBytes, seg.durationMs, seg.lastModifiedMs,
                )
                seg.key.startsWith("content://media/") -> IndexRow(
                    KIND_MEDIA_ROW, seg.key, seg.name, seg.sizeBytes, seg.durationMs, seg.lastModifiedMs,
                )
                else -> IndexRow(
                    KIND_SAF_DOC, seg.key, seg.name, seg.sizeBytes, seg.durationMs, seg.lastModifiedMs,
                )
            }
        }

        /** Parses one line written by [toLine]; null for foreign or corrupt lines. */
        fun fromLine(line: String): IndexRow? {
            val f = flatFields(line) ?: return null
            val kind = f["k"]?.toIntOrNull() ?: return null
            if (kind !in KIND_FILE..KIND_SAF_DOC) return null
            val uri = f["u"] ?: return null
            val name = f["n"] ?: return null
            val size = f["s"]?.toLongOrNull() ?: return null
            val dur = f["d"]?.toLongOrNull() ?: return null
            val mod = f["m"]?.toLongOrNull() ?: return null
            return IndexRow(kind, uri, name, size, dur, mod)
        }

        /** Maps `"key":string|number` of a flat object to string form; escapes resolved. */
        private fun flatFields(line: String): Map<String, String>? {
            if (line.length < 2 || line.first() != '{' || line.last() != '}') return null
            val out = HashMap<String, String>(8)
            var i = 1
            while (i < line.length - 1) {
                while (i < line.length - 1 && line[i] == ',') i++
                if (i >= line.length - 1) return null
                if (line[i] != '"') return null
                val key = readString(line, i) ?: return null
                i = key.second
                if (i >= line.length - 1 || line[i] != ':') return null
                val value = readValue(line, i + 1) ?: return null
                out[key.first] = value.first
                i = value.second
            }
            return out
        }

        /** Reads a quoted string at [from]; returns (unescaped text, index after the closing quote). */
        private fun readString(s: String, from: Int): Pair<String, Int>? {
            val sb = StringBuilder()
            var i = from + 1
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> return sb.toString() to (i + 1)
                    c == '\\' -> {
                        if (i + 1 >= s.length) return null
                        when (val e = s[i + 1]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 6 > s.length) return null
                                val code = s.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4 // the loop's shared advance below adds the last two
                            }
                            else -> return null
                        }
                        i += 2
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return null
        }

        /** Reads a string or number value at [from]; returns (value text, index after it). */
        private fun readValue(s: String, from: Int): Pair<String, Int>? {
            if (from >= s.length) return null
            return if (s[from] == '"') {
                readString(s, from)
            } else {
                var i = from
                while (i < s.length && s[i] != ',' && s[i] != '}') i++
                val raw = s.substring(from, i)
                if (raw.isEmpty() || raw.any { !it.isDigit() && it != '-' }) return null
                raw to i
            }
        }
    }
}

/**
 * Persisted snapshot of the clip listing: one [IndexRow] per line in an app-private
 * JSON-lines file, written atomically (tmp + rename) on a single background thread.
 * At most one write per few seconds; a request made while throttled still lands at
 * the end of the window, so the newest adoption always flushes.
 *
 * The load runs on the executor as well and delivers the cached rows without any
 * provider call, so a cold start can paint the Recordings UI before the first
 * backend query. A fresh listing replaces the snapshot wholesale — [RecordingsSource]
 * owns that cycle.
 */
class RecordingIndex(private val file: File) {

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pendant-index").apply { isDaemon = true } }

    /** Delivering the load on a fixed handler keeps the callback off arbitrary threads. */
    private val mainHandler = Handler(Looper.getMainLooper())

    // Executor-thread state only.
    private var latest: List<IndexRow>? = null
    private var lastWritten: List<IndexRow> = emptyList()
    private var lastWriteMs = 0L
    private var trailingQueued = false

    /** Loads the cached rows; [onLoaded] runs on the main thread when ready. */
    internal fun load(onLoaded: (List<IndexRow>) -> Unit) {
        submit {
            val rows = try {
                file.readLines().mapNotNull { l -> if (l.isBlank()) null else IndexRow.fromLine(l) }
            } catch (e: Exception) {
                Log.w(TAG, "index load failed", e)
                emptyList()
            }
            lastWritten = rows
            mainHandler.post { onLoaded(rows) }
        }
    }

    /** Replaces the snapshot and schedules a throttled persist. */
    internal fun adopt(rows: List<IndexRow>) {
        submit {
            latest = rows
            scheduleFlush(System.currentTimeMillis())
        }
    }

    private fun scheduleFlush(nowMs: Long) {
        if (trailingQueued) return
        val waitMs = lastWriteMs + THROTTLE_MS - nowMs
        if (waitMs <= 0) {
            flush()
            return
        }
        trailingQueued = true
        mainHandler.postDelayed({
            submit {
                trailingQueued = false
                flush()
            }
        }, waitMs)
    }

    /** Writes [latest] unless it equals what is already on disk. Executor thread. */
    private fun flush() {
        val rows = latest ?: return
        if (rows == lastWritten) {
            // Unchanged content: a repeated adoption (e.g. the steady fast listing)
            // must not rewrite identical bytes each trigger.
            return
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { outs ->
                for (r in rows) outs.write((r.toLine() + "\n").toByteArray(Charsets.UTF_8))
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) Log.w(TAG, "index rename failed")
            lastWritten = rows
            lastWriteMs = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "index write failed", e)
        }
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute {
                try {
                    task()
                } catch (e: Exception) {
                    Log.w(TAG, "index task failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Executor shut down (never in practice; this lives app-long).
        }
    }

    companion object {
        private const val THROTTLE_MS = 3000L

        /** Where the snapshot lives: beside the event log in the app's outer files dir. */
        fun snapshotFile(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings.index.jsonl")
    }
}
