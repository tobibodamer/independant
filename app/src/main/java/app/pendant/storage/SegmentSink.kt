package app.pendant.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import app.pendant.codec.SegmentDestination
import app.pendant.codec.SegmentWriters
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException

/** Destination of one segment: hands bytes to the codec writer, then finalizes or discards. */
abstract class SegmentSink {
    /** Where the writer should send its encoded bytes, or null when storage is unavailable. */
    abstract fun openOutput(): SegmentDestination?

    /** Called after the writer finished and the segment is kept. */
    abstract fun finalize(durationMs: Long)

    /** Called when the segment was dropped (too short / writer failure). */
    abstract fun discard()
}

private const val TAG = "PendantStorage"

/** Plain file destination used by the app-private path. Pure java.io, JVM-testable. */
class FileSegmentSink(private val dir: File, baseName: String) : SegmentSink() {

    val file = File(dir, baseName)

    override fun openOutput(): SegmentDestination = SegmentDestination.AsFile(file)

    override fun finalize(durationMs: Long) {
        // The "_NNs" duration suffix rename; a no-op when the writer never opened the file.
        try {
            val target = File(dir, SegmentNames.finalizedName(file.name, durationMs))
            if (file.exists() && target != file) file.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "finalize failed", e)
        }
    }

    override fun discard() {
        try {
            file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "discard failed", e)
        }
    }
}

/**
 * MediaStore destination: inserts a row in `Music/Pendant` (IS_PENDING) up front and
 * streams into it. MediaStore rows cannot be renamed, so no duration-suffix rename
 * happens here — the list UI reads the DURATION column instead. Short segments never
 * prove their length → the row (empty file) is deleted via [discard], preserving the
 * "short segments leave nothing behind" semantics.
 */
class MediaStoreSegmentSink(
    context: Context,
    mime: String,
    baseName: String,
) : SegmentSink() {

    private val resolver = context.contentResolver
    private val uri: Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, baseName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, SegmentStore.MUSIC_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    } catch (e: Exception) {
        Log.e(TAG, "MediaStore insert failed", e)
        null
    }

    override fun openOutput(): SegmentDestination? {
        val u = uri ?: return null
        return try {
            if (SegmentWriters.format == SegmentWriters.Format.AAC) {
                // MediaMuxer needs a FileDescriptor; media rows can be opened as "rw".
                val pfd = resolver.openFileDescriptor(u, "rw") ?: return null
                SegmentDestination.AsFd(pfd)
            } else {
                val s = resolver.openOutputStream(u, "w") ?: return null
                SegmentDestination.AsStream(BufferedOutputStream(s, 32 * 1024))
            }
        } catch (e: Exception) {
            Log.e(TAG, "cannot open MediaStore output", e)
            null
        }
    }

    override fun finalize(durationMs: Long) {
        val u = uri ?: return
        try {
            // Publishing the row is the critical step; do it alone so a rejected
            // DURATION write cannot leave the file pending forever.
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            runCatching { resolver.update(u, publish, null, null) }
                .onFailure { Log.w(TAG, "MediaStore publish failed", it) }
            // Best effort; MediaProvider may treat DURATION as read-only.
            val duration = ContentValues().apply { put(MediaStore.Audio.AudioColumns.DURATION, durationMs) }
            runCatching { resolver.update(u, duration, null, null) }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore finalize failed", e)
        }
    }

    override fun discard() {
        val u = uri ?: return
        try {
                val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                resolver.delete(ContentUris.withAppendedId(collection, ContentUris.parseId(u)), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore discard failed", e)
        }
    }
}

/** Reads a single document or tree attribute; small helper around DocumentsContract queries. */
internal fun queryDocumentName(resolver: ContentResolver, documentUri: Uri): String? = try {
    resolver.query(
        documentUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
} catch (e: Exception) {
    null
}

/**
 * SAF destination inside `<picked tree>/Pendant`. Keeps the duration-suffix rename
 * (DocumentsContract.renameDocument is allowed on our own tree documents).
 */
class SafSegmentSink(
    context: Context,
    treeUriString: String,
    mime: String,
    baseName: String,
    dirName: String = SegmentStore.SAF_DIR_NAME,
) : SegmentSink() {

    private val resolver = context.contentResolver
    private val treeUri: Uri
    private val dirUri: Uri
    private val docUri: Uri
    private val baseName = baseName
    private val mime = mime
    /** The document's current URI; renameDocument returns a new one. */
    private var renamedUri: Uri? = null

    init {
        if (treeUriString.isBlank()) throw IOException("no custom folder chosen")
        treeUri = Uri.parse(treeUriString)
        DocumentsContract.getTreeDocumentId(treeUri) // throws when not a tree URI
        // ensureDir always returns tree-derived URIs that carry the persisted grant.
        dirUri = safDirUri(resolver, treeUri, dirName, createIfMissing = true) ?: throw IOException("custom folder unavailable")
        docUri = DocumentsContract.createDocument(resolver, dirUri, mime, baseName)?.let {
            treeDocUri(treeUri, it)
        } ?: throw IOException("cannot create $baseName in custom folder")
    }

    override fun openOutput(): SegmentDestination? = try {
        when (SegmentWriters.format) {
            SegmentWriters.Format.AAC -> {
                val pfd = resolver.openFileDescriptor(docUri, "rw") ?: return null
                SegmentDestination.AsFd(pfd)
            }
            else -> {
                val s = resolver.openOutputStream(docUri, "w") ?: return null
                SegmentDestination.AsStream(BufferedOutputStream(s, 32 * 1024))
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "cannot open custom-folder output", e)
        null
    }

    override fun finalize(durationMs: Long) {
        val current = renamedUri ?: docUri
        val target = SegmentNames.finalizedName(baseName, durationMs)
        Log.i(TAG, "SAF finalize name=$baseName target=$target current=$current")
        if (target == baseName) return
        val fresh = try {
            DocumentsContract.renameDocument(resolver, current, target)?.let { treeDocUri(treeUri, it) }
        } catch (e: Exception) {
            Log.w(TAG, "SAF renameDocument threw", e)
            null
        } ?: run {
            // Some providers (e.g. Samsung's externalstorage on newer Android) silently
            // refuse renames via FUSE. Fall back to copying under the final name.
            copyToNewDocument(current, target)
        }
        if (fresh != null) renamedUri = fresh
        else Log.w(TAG, "SAF rename impossible; clip keeps its base name ($current)")
    }

    /** Copy fallback: new document with the final name, contents moved, old one removed. */
    private fun copyToNewDocument(src: Uri, targetName: String): Uri? = try {
        val created = DocumentsContract.createDocument(resolver, dirUri, mime, targetName)
            ?: return null
        val target = treeDocUri(treeUri, created)
        resolver.openInputStream(src)?.use { ins ->
            resolver.openOutputStream(target, "w")?.use { outs -> ins.copyTo(outs) }
        }
        DocumentsContract.deleteDocument(resolver, src)
        target
    } catch (e: Exception) {
        Log.w(TAG, "SAF copy fallback failed", e)
        null
    }

    override fun discard() {
        try {
            val current = renamedUri ?: docUri
            runCatching { DocumentsContract.deleteDocument(resolver, current) }
                .onFailure { Log.w(TAG, "SAF discard failed", it) }
        } catch (e: Exception) {
            Log.w(TAG, "SAF discard failed", e)
        }
    }
}

/** Finds or creates the recording subfolder directly below the tree root. */
internal fun safDirUri(
    resolver: ContentResolver,
    treeUri: Uri,
    dirName: String,
    createIfMissing: Boolean,
): Uri? {
    val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootUri = treeDocUri(treeUri, rootDocId)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
    try {
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == dirName && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    // Operations (create/rename/delete) need a tree-derived URI to carry the grant.
                    return treeDocUri(treeUri, c.getString(0))
                }
            }
        }
        if (!createIfMissing) return null
        // Do not nest Pendant/Pendant when the user already picked a folder with that name.
        if (queryDocumentName(resolver, rootUri) == dirName) return rootUri
        return DocumentsContract.createDocument(resolver, rootUri, DocumentsContract.Document.MIME_TYPE_DIR, dirName)
            ?.let { treeDocUri(treeUri, it) }
    } catch (e: Exception) {
        Log.e(TAG, "cannot resolve custom dir", e)
        return null
    }
}

/** Document id URI that carries the persisted tree grant (plain document URIs do not). */
internal fun treeDocUri(treeUri: Uri, documentId: String): Uri =
    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

/** Wraps SimpleDocumentUri into a tree-based URI so provider calls honor the persisted grant. */
internal fun treeDocUri(treeUri: Uri, documentUri: Uri): Uri = try {
    treeDocUri(treeUri, DocumentsContract.getDocumentId(documentUri))
} catch (e: Exception) {
    documentUri
}
