package app.pendant.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import app.pendant.codec.SegmentWriters
import app.pendant.settings.StorageMode
import java.io.File

/** One listing entry, backend-agnostic. */
abstract class SegmentInfo(
    /** File name or MediaStore display name. */
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val lastModifiedMs: Long,
    /** The plain file for file-backed clips, null otherwise. */
    val backingFile: File? = null,
) {
    /** Stable identity for UI keys. */
    abstract val key: String

    /** URI for MediaPlayer.setDataSource(context, …). */
    abstract fun playbackUri(): Uri

    /** Content URI usable as EXTRA_STREAM (no FileProvider), or null for file-backed clips. */
    abstract fun shareContentUri(): Uri?

    /**
     * A second backend entry for the same clip: MediaProvider scans clips written
     * via SAF, so one file may exist as both a tree document and a media row.
     * Deleted together with this one.
     */
    internal var shadowBackend: SegmentInfo? = null

    /** Removes the clip from its backend; returns false when it did not exist. */
    internal abstract fun deleteFromBackend(): Boolean
}

/** Clip stored as a plain file (app-private path). */
internal class FileSegmentInfo(
    val path: File,
    name: String,
    sizeBytes: Long,
    durationMs: Long,
    lastModifiedMs: Long,
) : SegmentInfo(name, sizeBytes, durationMs, lastModifiedMs, path) {
    override val key: String = path.absolutePath
    override fun playbackUri(): Uri = Uri.fromFile(path)
    override fun shareContentUri(): Uri? = null
    override fun deleteFromBackend(): Boolean = path.delete()
}

/** Clip stored as a MediaStore audio row under Music/Pendant. */
internal class MediaSegmentInfo(
    val mediaResolver: ContentResolver,
    val rowUri: Uri,
    name: String,
    sizeBytes: Long,
    durationMs: Long,
    lastModifiedMs: Long,
) : SegmentInfo(name, sizeBytes, durationMs, lastModifiedMs) {
    override val key: String = rowUri.toString()
    override fun playbackUri(): Uri = rowUri
    override fun shareContentUri(): Uri? = rowUri
    override fun deleteFromBackend(): Boolean = try {
        mediaResolver.delete(rowUri, null, null) != 0
    } catch (e: Exception) {
        Log.w(TAG, "media delete failed", e)
        false
    }
}

/** Clip stored as a document inside a user-picked SAF tree. */
internal class SafSegmentInfo(
    val safResolver: ContentResolver,
    val docUri: Uri,
    name: String,
    sizeBytes: Long,
    durationMs: Long,
    lastModifiedMs: Long,
) : SegmentInfo(name, sizeBytes, durationMs, lastModifiedMs) {
    override val key: String = docUri.toString()
    override fun playbackUri(): Uri = docUri
    override fun shareContentUri(): Uri? = docUri
    override fun deleteFromBackend(): Boolean = try {
        DocumentsContract.deleteDocument(safResolver, docUri)
    } catch (e: Exception) {
        Log.w(TAG, "document delete failed", e)
        false
    }
}

private const val TAG = "PendantStorage"

/**
 * Owns segment storage: file naming, per-segment sinks, listing and retention.
 *
 * Writes always go to the currently configured [Location] (applied live: changing the
 * storage location takes effect for NEW segments; the open recording keeps writing
 * where it started). Listing, size reporting, delete and retention scans cover ALL
 * locations, so clips stay playable and managed after a mode switch.
 *
 * Modes:
 * - [StorageMode.MUSIC] (default): MediaStore rows under [MUSIC_RELATIVE_PATH]
 *   (RELATIVE_PATH) — no permissions needed on API 29+. MediaStore rows cannot be
 *   renamed, so no `_NNs` duration suffix; duration comes from the DURATION column.
 * - [StorageMode.APP_PRIVATE]: classic files in Android/data/…/files/recordings.
 * - [StorageMode.CUSTOM]: SAF tree (usually Music) picked by the user, written to
 *   `<tree>/Pendant`; those files keep the `_NNs` duration-suffix rename.
 */
class SegmentStore(private val context: Context) {

    companion object {
        /** Public Music subfolder that MediaStore-backed recordings land in. */
        const val MUSIC_RELATIVE_PATH = "Music/Pendant"
        /** Subfolder inside a user-picked SAF tree. */
        const val SAF_DIR_NAME = "Pendant"
        /** Rows never finalized (app died mid-segment) are swept after this age. */
        private const val PENDING_SWEEP_MS = 60L * 60 * 1000
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val GB_BYTES = 1024L * 1024 * 1024
    }

    data class Location(
        val mode: StorageMode = StorageMode.MUSIC,
        val customTreeUri: String = "",
    )

    @Volatile private var location = Location()

    private val resolver: ContentResolver = context.contentResolver

    /** Human description of `<tree>/Pendant`, empty when not set or not queryable. */
    @Volatile var customDirSummary: String? = null
        private set

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Called by the recording engine when settings change; affects new segments only. */
    fun setLocation(location: Location) {
        this.location = location
    }

    /** Refreshes [customDirSummary] for the given tree; touches ContentProvider, run off the main thread. */
    fun updateCustomDirSummary(customTreeUri: String) {
        customDirSummary = try {
            if (customTreeUri.isBlank()) {
                null
            } else {
                val tree = Uri.parse(customTreeUri).also { DocumentsContract.getTreeDocumentId(it) }
                val rootDocUri = treeDocUri(tree, DocumentsContract.getTreeDocumentId(tree))
                val root = queryDocumentName(resolver, rootDocUri)
                    ?: Uri.decode(Uri.parse(customTreeUri).lastPathSegment.orEmpty())
                        .removePrefix("tree:")
                        .substringAfter(':')
                val dir = safDirUri(resolver, tree, SAF_DIR_NAME, createIfMissing = false)
                    ?.let { queryDocumentName(resolver, it) } ?: SAF_DIR_NAME
                (root?.let { "$it/" } ?: "") + dir
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot describe custom dir", e)
            null
        }
    }

    /** One-line description of the effective path of the configured location. */
    fun describeLocation(): String = when (location.mode) {
        StorageMode.MUSIC -> "/storage/emulated/0/$MUSIC_RELATIVE_PATH (MediaStore)"
        StorageMode.APP_PRIVATE -> appPrivateDir().absolutePath
        StorageMode.CUSTOM -> customDirSummary ?: "Custom folder: not chosen"
    }

    private fun parseCustomTree(): Uri? = try {
        val s = location.customTreeUri
        if (s.isBlank()) {
            null
        } else {
            Uri.parse(s).also { DocumentsContract.getTreeDocumentId(it) }
        }
    } catch (e: Exception) {
        null
    }

    /** True when the stored tree still carries a persisted write grant. */
    private fun hasCustomWritePermission(): Boolean {
        val s = location.customTreeUri
        return s.isNotBlank() && context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == s && it.isWritePermission
        }
    }

    // ------------------------------------------------------------------
    // Writing (capture path only)
    // ------------------------------------------------------------------

    /** Sink for a new segment, in the currently configured location. */
    fun newSegmentSink(startWallClockMs: Long): SegmentSink {
        val base = SegmentNames.baseName(ext(), startWallClockMs)
        return when (location.mode) {
            StorageMode.APP_PRIVATE -> FileSegmentSink(appPrivateDir(), base)
            StorageMode.MUSIC -> MediaStoreSegmentSink(context, containerMime(), base)
            StorageMode.CUSTOM -> SafSegmentSink(context, location.customTreeUri, containerMime(), base)
        }
    }

    /** The app-private recordings dir (created on demand). */
    private fun appPrivateDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").also { it.mkdirs() }

    /** App-private recordings path for the Settings UI. */
    fun appPrivatePath(): String = appPrivateDir().absolutePath

    private fun ext(): String = SegmentWriters.format.extension

    private fun containerMime(): String = SegmentWriters.containerMimeType()

    // ------------------------------------------------------------------
    // Listing / totals / delete
    // ------------------------------------------------------------------

    /**
     * Clips from every supported location, newest first. Touches ContentProviders,
     * so run it off the main thread.
     */
    fun list(): List<SegmentInfo> {
        val files = appPrivateList()
        val media = mediaStoreList()
        val saf = safList()
        // MediaProvider scans clips written via SAF, which surfaces the same file as
        // both a tree document and a media row. Prefer the media row (it carries the
        // DURATION column) and keep the document for deletion.
        val safByName = saf.associateBy { it.name }
        val out = ArrayList<SegmentInfo>(files.size + media.size + saf.size)
        out.addAll(files)
        for (m in media) m.shadowBackend = safByName[m.name]
        for (s in saf) if (media.none { it.name == s.name }) out.add(s)
        out.addAll(media)
        return out.sortedByDescending { it.lastModifiedMs }
    }

    fun totalBytes(): Long =
        appPrivateList().sumOf { it.sizeBytes } +
            mediaStoreQuery().sumOf { it.sizeBytes } +
            safDocs().sumOf { it.sizeBytes }

    fun delete(info: SegmentInfo) {
        try {
            info.deleteFromBackend()
        } catch (e: Exception) {
            Log.w(TAG, "delete failed", e)
        }
        // The scanned twin (MediaStore row for a SAF-created clip or vice versa).
        try {
            info.shadowBackend?.deleteFromBackend()
        } catch (e: Exception) {
            Log.w(TAG, "shadow delete failed", e)
        }
    }

    // ------------------------------------------------------------------
    // Backend scanners
    // ------------------------------------------------------------------

    private fun appPrivateList(): List<SegmentInfo> =
        appPrivateDir().listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }
            ?.filter { it.length() > 0L || SegmentNames.parseDurationMs(it.name) > 0L }
            ?.map { f ->
                FileSegmentInfo(
                    path = f,
                    name = f.name,
                    sizeBytes = f.length(),
                    durationMs = SegmentNames.parseDurationMs(f.name),
                    lastModifiedMs = f.lastModified(),
                )
            }
            ?: emptyList()

    private class MediaRow(
        val id: Long,
        val name: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val lastModifiedMs: Long,
    )

    private val mediaCollection: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val mediaProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.Audio.AudioColumns.DURATION,
    )

    /**
     * Own ready audio rows under Music/Pendant. Without READ permissions the provider
     * returns only our app's contributions on modern Android, exactly what we want.
     */
    private fun mediaStoreQuery(): List<MediaRow> = try {
        resolver.query(
            mediaCollection,
            mediaProjection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$MUSIC_RELATIVE_PATH/%"),
            null,
        )?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val durI = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION)
            val rows = ArrayList<MediaRow>()
            while (c.moveToNext()) {
                rows.add(
                    MediaRow(
                        id = c.getLong(idI),
                        name = c.getString(nameI) ?: "",
                        sizeBytes = c.getLong(sizeI),
                        durationMs = c.getLong(durI),
                        lastModifiedMs = c.getLong(dateI) * 1000,
                    ),
                )
            }
            rows
        } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "MediaStore scan failed", e)
        emptyList()
    }

    private fun mediaStoreList(): List<SegmentInfo> = mediaStoreQuery()
        .filter { it.sizeBytes > 0L || it.durationMs > 0L }
        .map { r ->
        MediaSegmentInfo(
            mediaResolver = resolver,
            rowUri = ContentUris.withAppendedId(mediaCollection, r.id),
            // No duration suffix: the row cannot be renamed; duration comes from DURATION.
            name = r.name,
            sizeBytes = r.sizeBytes,
            durationMs = r.durationMs.takeIf { it > 0 } ?: SegmentNames.parseDurationMs(r.name),
            lastModifiedMs = r.lastModifiedMs,
        )
    }

    private class SafDoc(
        val uri: Uri,
        val name: String?,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
    )

    private fun safDir(createIfMissing: Boolean): Uri? {
        val tree = parseCustomTree() ?: return null
        if (createIfMissing && !hasCustomWritePermission()) return null
        return safDirUri(resolver, tree, SAF_DIR_NAME, createIfMissing)
    }

    private fun safChildren(tree: Uri, dir: Uri): List<SafDoc> = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getDocumentId(dir),
        )
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { c ->
            val out = ArrayList<SafDoc>()
            while (c.moveToNext()) {
                out.add(
                    SafDoc(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                        name = c.getString(1),
                        sizeBytes = c.getLong(2),
                        lastModifiedMs = c.getLong(3),
                    ),
                )
            }
            out
        } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "SAF scan failed", e)
        emptyList()
    }

    private fun safDocs(): List<SafDoc> = try {
        val dir = safDir(createIfMissing = false) ?: return emptyList()
        val tree = parseCustomTree() ?: return emptyList()
        safChildren(tree, dir).filter { d -> d.name?.endsWith(".tmp") == false }
    } catch (e: Exception) {
        Log.w(TAG, "SAF list failed", e)
        emptyList()
    }

    private fun safList(): List<SegmentInfo> = safDocs().mapNotNull { d ->
        val name = d.name ?: return@mapNotNull null
        if (d.sizeBytes == 0L && SegmentNames.parseDurationMs(name) == 0L) return@mapNotNull null
        SafSegmentInfo(
            safResolver = resolver,
            docUri = d.uri,
            name = name,
            sizeBytes = d.sizeBytes,
            durationMs = SegmentNames.parseDurationMs(name),
            lastModifiedMs = d.lastModifiedMs,
        )
    }

    // ------------------------------------------------------------------
    // Retention
    // ------------------------------------------------------------------

    /**
     * Retention pass across every location. Deletes oldest clips when a location
     * grows beyond [maxStorageGb] (0 = unlimited) or clips get older than
     * [autoDeleteDays] (0 = never). Returns number of deleted clips.
     */
    fun cleanup(maxStorageGb: Int, autoDeleteDays: Int): Int {
        val cap = if (maxStorageGb > 0) maxStorageGb.toLong() * GB_BYTES else 0L
        val ageCutoff = if (autoDeleteDays > 0) {
            System.currentTimeMillis() - autoDeleteDays.toLong() * DAY_MS
        } else {
            0L
        }
        var deleted = 0
        deleted += cleanupAppPrivate(cap, ageCutoff)
        deleted += cleanupMediaStore(cap, ageCutoff)
        deleted += cleanupSaf(cap, ageCutoff)
        deleted += mediaStorePendingSweep()
        return deleted
    }

    private fun cleanupAppPrivate(cap: Long, ageCutoff: Long): Int {
        val dir = appPrivateDir()
        val files = (dir.listFiles()?.filter { it.isFile }).orEmpty()
        val plan = Retention.plan(files.map { Retention.Item(it.lastModified(), it.length()) }, cap, ageCutoff)
        var deleted = 0
        for (i in plan) {
            if (files[i].delete()) deleted++
        }
        return deleted
    }

    private fun cleanupMediaStore(cap: Long, ageCutoff: Long): Int {
        val rows = mediaStoreQuery().sortedBy { it.lastModifiedMs }
        val plan = Retention.plan(rows.map { Retention.Item(it.lastModifiedMs, it.sizeBytes) }, cap, ageCutoff)
        var deleted = 0
        for (i in plan) {
            if (deleteMediaRow(rows[i])) deleted++
        }
        return deleted
    }

    private fun deleteMediaRow(r: MediaRow): Boolean = try {
        resolver.delete(ContentUris.withAppendedId(mediaCollection, r.id), null, null) != 0
    } catch (e: Exception) {
        Log.w(TAG, "media row delete failed", e)
        false
    }

    /** Deletes MediaStore rows of segments the app died in the middle of. */
    private fun mediaStorePendingSweep(): Int = try {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" +
            " AND ${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.SIZE} = 0" +
            " AND ${MediaStore.MediaColumns.DATE_MODIFIED} < ?"
        val cutoffSec = (System.currentTimeMillis() - PENDING_SWEEP_MS) / 1000
        resolver.query(
            mediaCollection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf("$MUSIC_RELATIVE_PATH/%", cutoffSec.toString()),
            null,
        )?.use { c ->
            var n = 0
            while (c.moveToNext()) {
                if (resolver.delete(ContentUris.withAppendedId(mediaCollection, c.getLong(0)), null, null) != 0) n++
            }
            n
        } ?: 0
    } catch (e: Exception) {
        Log.w(TAG, "pending sweep failed", e)
        0
    }

    private fun cleanupSaf(cap: Long, ageCutoff: Long): Int {
        val dir = safDir(createIfMissing = false) ?: return 0
        val tree = parseCustomTree() ?: return 0
        // Skip 0-byte artifacts (segment torn off mid-write) and clips that are also
        // covered by a MediaStore row: deleting that row already removes the file.
        val covered = mediaStoreQuery().associateBy { it.name }
        val docs = safChildren(tree, dir)
            .filter { it.name != null && (it.sizeBytes > 0L || SegmentNames.parseDurationMs(it.name) > 0L) }
            .filter { covered[it.name] == null }
            .sortedBy { it.lastModifiedMs }
        val plan = Retention.plan(docs.map { Retention.Item(it.lastModifiedMs, it.sizeBytes) }, cap, ageCutoff)
        var deleted = 0
        for (i in plan) {
            if (deleteSafDoc(docs[i])) deleted++
        }
        return deleted
    }

    private fun deleteSafDoc(d: SafDoc): Boolean = try {
        DocumentsContract.deleteDocument(resolver, d.uri)
    } catch (e: Exception) {
        Log.w(TAG, "document delete failed", e)
        false
    }
}
