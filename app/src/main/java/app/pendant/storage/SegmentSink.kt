package app.pendant.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import app.pendant.codec.SegmentDestination
import app.pendant.codec.SegmentWriters
import app.pendant.settings.StorageMode
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/** Destination of one segment: hands bytes to the codec writer, then finalizes or discards. */
abstract class SegmentSink {
    /** File name the segment was created under, before the duration suffix. */
    abstract val baseName: String

    /** Where the writer should send its encoded bytes, or null when storage is unavailable. */
    abstract fun openOutput(): SegmentDestination?

    /** Called after the writer finished and the segment is kept. */
    abstract fun finalize(durationMs: Long)

    /** Called when the segment was dropped (too short / writer failure). */
    abstract fun discard()
}

private const val TAG = "PendantStorage"

/** Plain file destination used by the app-private path. Pure java.io, JVM-testable. */
class FileSegmentSink(private val dir: File, override val baseName: String) : SegmentSink() {

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
 * MediaStore destination that buffers the segment in an app-private staging
 * folder and publishes the *finished* clip into a public MediaStore folder at
 * finalize: `Music/Pendant` ([StorageMode.MUSIC], Audio rows) or
 * `Documents/Pendant` ([StorageMode.DOCUMENTS], Files rows — Android only
 * admits audio under Music/, and Documents contributions go through the Files
 * collection).
 *
 * Sync-friendly: the public folder only ever gains complete files under their
 * final `_NNs` name and nothing there is modified after creation — sync engines
 * never see partial audio or renames. Publishing runs on a single background
 * thread, in segment order, off the capture loop: the finished staging file is
 * inserted as a fresh MediaStore row that stays IS_PENDING while the bytes land,
 * so a crash mid-copy cannot expose a partial file (the store's pending-row
 * sweep removes dead rows). A crash before the publish leaves the suffixed file
 * in staging; [SegmentStore]'s staging recovery rescues it into the app-private
 * listing so no audio is lost.
 */
class PublishingSegmentSink(
    context: Context,
    mime: String,
    override val baseName: String,
    /** RELATIVE_PATH to publish under; Audio rows under Music/, Files rows elsewhere. */
    private val relativePath: String = SegmentStore.MUSIC_RELATIVE_PATH,
) : SegmentSink() {

    private val resolver = context.contentResolver
    private val mime = mime
    /** Segment under construction; hidden from every clip listing until published. */
    private val staged = File(SegmentStore.stagingDir(context), baseName)
    /** Where the rescue path puts an unpublished finished clip. */
    private val recordingsDir = SegmentStore.appPrivateDir(context)

    override fun openOutput(): SegmentDestination = SegmentDestination.AsFile(staged)

    override fun finalize(durationMs: Long) {
        if (!staged.exists()) return
        val target = File(staged.parentFile!!, SegmentNames.finalizedName(baseName, durationMs))
        // Rename staging → final name first; after this the publish thread owns
        // the file exclusively (name/path never change again).
        if (!staged.renameTo(target)) {
            Log.w(TAG, "staged rename failed; $baseName stays in staging")
            return
        }
        publishExecutor.execute {
            try {
                publish(target, durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "publish crashed", e)
                rescue(target)
            }
        }
    }

    /**
     * Insert, copy, publish. Any failure downgrades the clip to the app-private
     * recordings listing (fully playable/shareable) instead of dropping audio.
     */
    private fun publish(finished: File, durationMs: Long) {
        val row = try {
            val values = ContentValues().apply {
                // Insert under the final, duration-suffixed name — no rename later.
                put(MediaStore.MediaColumns.DISPLAY_NAME, finished.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            // Music/ lives in the Audio collection; Documents/ contributions use the
            // generic Files collection.
            val collection = if (relativePath == SegmentStore.MUSIC_RELATIVE_PATH) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.w(TAG, "publish insert failed", e)
            null
        }
        if (row == null) {
            rescue(finished)
            return
        }
        val copied = try {
            resolver.openOutputStream(row, "w")?.use { outs ->
                finished.inputStream().use { ins -> ins.copyTo(outs) }
            } != null
        } catch (e: Exception) {
            Log.w(TAG, "publish copy failed", e)
            false
        }
        if (!copied) {
            try {
                resolver.delete(row, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "publish row cleanup failed", e)
            }
            rescue(finished)
            return
        }
        // Publishing the row is the critical step; the DURATION column is best
        // effort (MediaProvider may treat it as read-only).
        try {
            resolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore publish failed", e)
        }
        try {
            val duration = ContentValues().apply { put(MediaStore.MediaColumns.DURATION, durationMs) }
            resolver.update(row, duration, null, null)
        } catch (_: Exception) {
        }
        finished.delete()
    }

    /** Moves a finished-but-unpublished clip into the app-private recordings listing. */
    private fun rescue(finished: File) {
        if (SegmentStore.moveIntoRecordings(recordingsDir, finished)) {
            Log.i(TAG, "publish failed; clip rescued to app-private storage: ${finished.name}")
        } else {
            Log.w(TAG, "publish failed and rescue failed for ${finished.name}")
        }
    }

    override fun discard() {
        try {
            staged.delete()
        } catch (e: Exception) {
            Log.w(TAG, "discard failed", e)
        }
    }
}

/** Serializes publishes and staging recovery; ordered, one at a time, never on the capture thread. */
internal val publishExecutor by lazy {
    Executors.newSingleThreadExecutor { r -> Thread(r, "pendant-publish").apply { isDaemon = true } }
}

/** Runs [action] on the publish thread, in order with any pending publish. */
internal fun onPublishThread(action: () -> Unit) {
    publishExecutor.execute(action)
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
    override val baseName: String,
    dirName: String = SegmentStore.SAF_DIR_NAME,
) : SegmentSink() {

    private val resolver = context.contentResolver
    private val treeUri: Uri
    private val dirUri: Uri
    private val docUri: Uri
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
