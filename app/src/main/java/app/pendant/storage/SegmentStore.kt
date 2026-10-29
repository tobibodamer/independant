package app.pendant.storage

import android.content.Context
import app.pendant.codec.SegmentWriters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns the recordings folder, file naming, listing and retention. */
class SegmentStore(private val context: Context) {

    data class SegmentInfo(
        val file: File,
        val name: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val lastModifiedMs: Long,
    )

    val dir: File
        get() = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "recordings",
        ).also { it.mkdirs() }

    /** File for a new segment; duration suffix is added when it is finalized. */
    fun newSegmentFile(startWallClockMs: Long): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startWallClockMs))
        return File(dir, "$ts.${SegmentWriters.format.extension}")
    }

    fun finalizeName(file: File, durationMs: Long) {
        val suffix = "_${durationMs / 1000}s"
        val target = File(file.parentFile, "${file.nameWithoutExtension}$suffix.${file.extension}")
        if (!file.renameTo(target)) {
            // ignore; file keeps base name
        }
    }

    fun discard(file: File) {
        file.delete()
    }

    fun list(): List<SegmentInfo> =
        dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }
            ?.map { f ->
                SegmentInfo(
                    file = f,
                    name = f.name,
                    sizeBytes = f.length(),
                    durationMs = parseDurationMs(f.name),
                    lastModifiedMs = f.lastModified(),
                )
            }
            ?.sortedByDescending { it.lastModifiedMs }
            ?: emptyList()

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(info: SegmentInfo) {
        info.file.delete()
    }

    /**
     * Retention pass. Deletes oldest recordings when the folder grows beyond
     * [maxStorageGb] (0 = unlimited) or files get older than [autoDeleteDays]
     * (0 = never). Returns number of deleted files.
     */
    fun cleanup(maxStorageGb: Int, autoDeleteDays: Int): Int {
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0
        var deleted = 0
        val now = System.currentTimeMillis()
        if (autoDeleteDays > 0) {
            val cutoff = now - autoDeleteDays.toLong() * 24 * 60 * 60 * 1000
            for (f in files) {
                if (f.lastModified() < cutoff && f.delete()) deleted++
            }
        }
        if (maxStorageGb > 0) {
            val remaining = (dir.listFiles()?.filter { it.isFile } ?: emptyList()).sortedBy { it.lastModified() }
            var total = remaining.sumOf { it.length() }
            val limit = maxStorageGb.toLong() * 1024 * 1024 * 1024
            for (f in remaining) {
                if (total <= limit) break
                if (f.delete()) {
                    total -= f.length()
                    deleted++
                }
            }
        }
        return deleted
    }

    companion object {
        /** Parses "..._42s.opus" duration suffixes. */
        fun parseDurationMs(name: String): Long {
            val m = Regex("""_(\d+)s\.[^.]+$""").find(name) ?: return 0L
            return m.groupValues[1].toLong() * 1000L
        }
    }
}
