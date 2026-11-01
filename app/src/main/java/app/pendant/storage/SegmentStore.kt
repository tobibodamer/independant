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
import java.io.FileOutputStream

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
 * - [StorageMode.DOCUMENTS] (default): segments are buffered app-privately and finished
 *   clips are published to MediaStore file rows under [DOCUMENTS_RELATIVE_PATH] at
 *   finalize ([PublishingSegmentSink]); same staging discipline as MUSIC. The capture
 *   log mirrors beside the clips into `Documents/Pendant/events` ([EventMirror]),
 *   keeping clips and metadata in one synced tree.
 * - [StorageMode.MUSIC]: same publish-to-MediaStore flow into `Music/Pendant`; keeps
 *   clips listed by music players, but the capture log cannot live there (Music only
 *   admits audio) and mirrors to `Documents/Pendant/events` instead. On Android 10
 *   (minSdk 29) MediaStore refuses arbitrary-file contributions to Documents/, so a
 *   DOCUMENTS-mode publish fails and its rescue path parks the clip app-privately.
 * - [StorageMode.APP_PRIVATE]: classic files in Android/data/…/files/recordings.
 * - [StorageMode.CUSTOM]: SAF tree (usually Music) picked by the user, written to
 *   `<tree>/Pendant`; those files keep the `_NNs` duration-suffix rename.
 */
class SegmentStore(private val context: Context) {

    companion object {
        /** Public Music subfolder that MediaStore-backed recordings land in. */
        const val MUSIC_RELATIVE_PATH = "Music/Pendant"
        /** Public Documents subfolder — the one-tree home for clips and capture log. */
        const val DOCUMENTS_RELATIVE_PATH = "Documents/Pendant"
        /** Subfolder inside a user-picked SAF tree. */
        const val SAF_DIR_NAME = "Pendant"
        /** Rows never finalized (app died mid-segment) are swept after this age. */
        private const val PENDING_SWEEP_MS = 60L * 60 * 1000
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val GB_BYTES = 1024L * 1024 * 1024

        /** The app-private recordings directory (created on demand). */
        internal fun appPrivateDir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").also { it.mkdirs() }

        /** Staging dir for segments under construction — outside every clip listing. */
        internal fun stagingDir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "staging").also { it.mkdirs() }

        /** Moves a finished clip file into the app-private recordings dir (rename, else byte copy). */
        internal fun moveIntoRecordings(recordingsDir: File, file: File): Boolean {
            recordingsDir.mkdirs()
            val target = File(recordingsDir, file.name)
            if (file.renameTo(target)) return true
            // Same-volume renames practically never fail; the copy is belt and braces.
            // Timestamp names make collisions all but impossible.
            return try {
                file.inputStream().use { ins -> FileOutputStream(target).use { outs -> ins.copyTo(outs) } }
                file.delete()
            } catch (e: Exception) {
                Log.w(TAG, "cannot move ${file.name}", e)
                false
            }
        }
    }

    data class Location(
        val mode: StorageMode = StorageMode.DOCUMENTS,
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
        StorageMode.DOCUMENTS -> "/storage/emulated/0/$DOCUMENTS_RELATIVE_PATH (MediaStore)"
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
            StorageMode.DOCUMENTS -> PublishingSegmentSink(context, containerMime(), base, DOCUMENTS_RELATIVE_PATH)
            StorageMode.MUSIC -> PublishingSegmentSink(context, containerMime(), base, MUSIC_RELATIVE_PATH)
            StorageMode.CUSTOM -> SafSegmentSink(context, location.customTreeUri, containerMime(), base)
        }
    }

    /** The app-private recordings dir (created on demand). */
    private fun appPrivateDir(): File = appPrivateDir(context)

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
        recoverStagedClips()
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
        /** Fully built row URI (built on the same collection the row came from). */
        val rowUri: Uri,
        val name: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val lastModifiedMs: Long,
    )

    private val mediaCollection: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val filesCollection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val mediaProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DURATION,
    )

    /**
     * Own ready audio rows under [MUSIC_RELATIVE_PATH] and [DOCUMENTS_RELATIVE_PATH]
     * (the latter via the Files collection: Android only admits audio under Music/,
     * so DOCUMENTS clips are file rows that music players never list). Without READ
     * permissions the provider returns only our app's contributions on modern
     * Android, exactly what we want.
     */
    private fun mediaStoreQuery(): List<MediaRow> = try {
        queryMediaRows(
            mediaCollection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$MUSIC_RELATIVE_PATH/%"),
        ) + queryMediaRows(
            filesCollection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} LIKE ?",
            // audio/… kept clips only: the events log (.jsonl, octet-stream) beside them
            // is not a clip.
            arrayOf("$DOCUMENTS_RELATIVE_PATH/%", "audio/%"),
        )
    } catch (e: Exception) {
        Log.w(TAG, "MediaStore scan failed", e)
        emptyList()
    }

    private fun queryMediaRows(collection: Uri, selection: String, args: Array<String>): List<MediaRow> =
        resolver.query(collection, mediaProjection, selection, args, null)?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val durI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val rows = ArrayList<MediaRow>()
            while (c.moveToNext()) {
                rows.add(
                    MediaRow(
                        rowUri = ContentUris.withAppendedId(collection, c.getLong(idI)),
                        name = c.getString(nameI) ?: "",
                        sizeBytes = c.getLong(sizeI),
                        durationMs = c.getLong(durI),
                        lastModifiedMs = c.getLong(dateI) * 1000,
                    ),
                )
            }
            rows
        } ?: emptyList()

    private fun mediaStoreList(): List<SegmentInfo> = mediaStoreQuery()
        .filter { it.sizeBytes > 0L || it.durationMs > 0L }
        .map { r ->
        MediaSegmentInfo(
            mediaResolver = resolver,
            rowUri = r.rowUri,
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
        recoverStagedClips()
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
        resolver.delete(r.rowUri, null, null) != 0
    } catch (e: Exception) {
        Log.w(TAG, "media row delete failed", e)
        false
    }

    /**
     * Rescues finished clips that never reached their publish step (process death
     * while queued) into the app-private listing, and drops stale never-finalized
     * staging leftovers. Must run on the publish thread: an in-flight publish owns
     * its staging file, so recovery may not touch staging concurrently.
     */
    private fun recoverStagedClips() {
        onPublishThread {
            val staged = stagingDir(context).listFiles { f -> f.isFile } ?: return@onPublishThread
            val finished = ArrayList<File>()
            val stale = ArrayList<File>()
            val deadCutoff = System.currentTimeMillis() - DAY_MS
            for (f in staged) {
                if (f.length() == 0L && SegmentNames.parseDurationMs(f.name) == 0L) {
                    f.delete() // empty artifact, never contained audio
                } else if (SegmentNames.parseDurationMs(f.name) > 0L) {
                    finished.add(f)
                } else if (f.lastModified() < deadCutoff) {
                    // Open segment torn off mid-write: no configured segment length
                    // approaches a day, so this one can never finalize again.
                    stale.add(f)
                }
            }
            if (finished.isEmpty() && stale.isEmpty()) return@onPublishThread
            val mediaNames = try {
                mediaStoreQuery().mapTo(HashSet()) { it.name }
            } catch (e: Exception) {
                Log.w(TAG, "staging recovery: media scan failed", e)
                HashSet<String>()
            }
            for (f in finished) {
                if (f.name in mediaNames) {
                    f.delete() // twin of an already-published clip
                } else if (!moveIntoRecordings(appPrivateDir(), f)) {
                    Log.w(TAG, "cannot rescue staged clip ${f.name}")
                }
            }
            for (f in stale) {
                f.delete()
            }
        }
    }

    /** Deletes MediaStore rows of segments the app died in the middle of. */
    private fun mediaStorePendingSweep(): Int = try {
        sweepPending(mediaCollection, MUSIC_RELATIVE_PATH) + sweepPending(filesCollection, DOCUMENTS_RELATIVE_PATH)
    } catch (e: Exception) {
        Log.w(TAG, "pending sweep failed", e)
        0
    }

    private fun sweepPending(collection: Uri, relativePath: String): Int {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" +
            " AND ${MediaStore.MediaColumns.IS_PENDING} = 1" +
            " AND ${MediaStore.MediaColumns.DATE_MODIFIED} < ?"
        val cutoffSec = (System.currentTimeMillis() - PENDING_SWEEP_MS) / 1000
        return resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf("$relativePath/%", cutoffSec.toString()),
            null,
        )?.use { c ->
            var n = 0
            while (c.moveToNext()) {
                if (resolver.delete(ContentUris.withAppendedId(collection, c.getLong(0)), null, null) != 0) n++
            }
            n
        } ?: 0
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
