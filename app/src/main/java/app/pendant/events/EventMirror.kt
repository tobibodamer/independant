package app.pendant.events

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.pendant.settings.StorageMode
import app.pendant.storage.SegmentStore
import app.pendant.storage.safDirUri
import app.pendant.storage.treeDocUri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Copies event-log day files next to the clips:
 * - [StorageMode.DOCUMENTS]: `Documents/Pendant/events/` — beside the clips.
 * - [StorageMode.MUSIC]: `Documents/Pendant/events/` (Android only admits audio
 *   under Music/, so the log goes one tree over).
 * - [StorageMode.CUSTOM]: `<picked tree>/Pendant/events/`
 * - [StorageMode.APP_PRIVATE]: nothing, the source files already sit beside the clips.
 *
 * Runs on the event-log thread only.
 */
internal class EventMirror(context: Context) {

    private val resolver = context.contentResolver
    /** Resolved target URI per "<targetKey>/<day file>". */
    private val uris = HashMap<String, Uri>()

    /** Identifies the mirror target, or null when nothing is mirrored. */
    fun targetKey(loc: SegmentStore.Location): String? = when (loc.mode) {
        StorageMode.APP_PRIVATE -> null
        // Distinct keys per mode even where the physical folder matches (MUSIC and
        // DOCUMENTS both use Documents/Pendant/events): a mode switch then restarts
        // the mirror from a full re-copy instead of trusting stale byte offsets.
        StorageMode.DOCUMENTS -> "media:documents"
        StorageMode.MUSIC -> "media:music"
        StorageMode.CUSTOM -> loc.customTreeUri.takeIf { it.isNotBlank() }?.let { "saf:$it" }
    }

    /**
     * Writes bytes [from, until) of [file] to the mirror of [name]: appended when
     * [append], otherwise replacing the mirror's content. Returns false when the
     * target is unavailable.
     */
    fun write(loc: SegmentStore.Location, name: String, file: File, from: Long, until: Long, append: Boolean): Boolean {
        val key = targetKey(loc) ?: return false
        val uri = uris["$key/$name"] ?: resolve(loc, name)?.also { uris["$key/$name"] = it } ?: return false
        val bytes = ByteArray((until - from).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            raf.readFully(bytes)
        }
        val out = try {
            resolver.openOutputStream(uri, if (append) "wa" else "wt")
        } catch (e: Exception) {
            // Deleted by the user or a sync tool: resolve afresh on the next attempt.
            uris.remove("$key/$name")
            throw e
        } ?: return false
        out.use { it.write(bytes) }
        return true
    }

    private fun resolve(loc: SegmentStore.Location, name: String): Uri? = when (loc.mode) {
        StorageMode.APP_PRIVATE -> null
        StorageMode.DOCUMENTS -> mediaRow(name)
        StorageMode.MUSIC -> mediaRow(name)
        StorageMode.CUSTOM -> safDocument(loc.customTreeUri, name)
    }

    private val filesCollection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun mediaRow(name: String): Uri? {
        resolver.query(
            filesCollection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf("$MEDIA_RELATIVE_PATH/", name),
            null,
        )?.use { c ->
            if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(filesCollection, c.getLong(0))
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            // octet-stream keeps the .jsonl name as is (a text MIME type would append ".txt").
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, MEDIA_RELATIVE_PATH)
        }
        return resolver.insert(filesCollection, values)
    }

    private fun safDocument(treeUriString: String, name: String): Uri? {
        val tree = Uri.parse(treeUriString)
        val pendant = safDirUri(resolver, tree, SegmentStore.SAF_DIR_NAME, createIfMissing = true) ?: return null
        val events = child(tree, pendant, EventLog.DIR_NAME, DocumentsContract.Document.MIME_TYPE_DIR) ?: return null
        return child(tree, events, name, MIME)
    }

    /** Finds or creates [name] directly below [parent]. */
    private fun child(tree: Uri, parent: Uri, name: String, mime: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return treeDocUri(tree, c.getString(0))
            }
        }
        return DocumentsContract.createDocument(resolver, parent, mime, name)?.let { treeDocUri(tree, it) }
            ?: throw IOException("cannot create $name")
    }

    companion object {
        const val MEDIA_RELATIVE_PATH = "Documents/Pendant/events"
        private const val MIME = "application/octet-stream"
    }
}
