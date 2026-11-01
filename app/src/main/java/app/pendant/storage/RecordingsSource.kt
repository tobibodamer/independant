package app.pendant.storage

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PendantRecordings"

/**
 * Single source of truth for the Recordings UI: a [kotlinx.coroutines.flow.StateFlow]
 * of the clip listing. Tab opens only collect; no visit triggers a rescan anymore.
 *
 * The flow is fed by
 *  - the persisted [RecordingIndex] snapshot for cold starts (zero provider calls),
 *  - fresh fast re-lists: one at app start, after each finalized or deleted clip
 *    (engine/UI callers call [refresh]), and debounced on MediaStore churn,
 *  - the SAF-append snapshot when a custom tree is configured (adopted as well,
 *    so the index paints that tree exactly like the fast backends).
 *
 * A fast re-list is deliberately cheap (two MediaStore queries plus a small dir
 * list), so bursts are coalesced on one single-threaded executor rather than
 * skipped: requests made while a pass runs fold into one immediate follow-up.
 */
class RecordingsSource(context: Context, private val store: SegmentStore) {

    /** The current listing, newest first; empty until the first adoption. */
    val clips = MutableStateFlow<List<SegmentInfo>>(emptyList())

    private val index = RecordingIndex(RecordingIndex.snapshotFile(context))
    private val resolver = context.contentResolver
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pendant-recordings").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)
    /** A fresh listing was adopted; a slow index load must not overwrite it. */
    @Volatile private var freshSeen = false

    private val mediaRefresh = Runnable { refresh() }

    init {
        // Cold start: the cache feeds the flow before any provider round trip.
        index.load { rows -> adoptCached(rows) }
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                // Finalized clips publish MediaStore rows; external churn (sync tools)
                // lands here too. Collapse it all into one debounced re-list.
                mainHandler.removeCallbacks(mediaRefresh)
                mainHandler.postDelayed(mediaRefresh, MEDIA_DEBOUNCE_MS)
            }
        }
        try {
            // Audio covers MUSIC-mode rows, Files the DOCUMENTS-mode ones; the
            // app-private mode has no provider, the engine hook covers it.
            resolver.registerContentObserver(store.mediaCollection, true, observer)
            resolver.registerContentObserver(store.filesCollection, true, observer)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore observers failed", e)
        }
        // App-start re-list: keeps the cached snapshot from drifting stale even
        // before the engine's first finalize (also rescues staged clips).
        refresh()
    }

    /** Requests one background fast re-list; overlapping requests coalesce. */
    fun refresh() {
        pending.set(true)
        try {
            executor.execute { drain() }
        } catch (_: RejectedExecutionException) {
            // Executor shut down (never in practice; this lives app-long).
        }
    }

    private fun drain() {
        if (!pending.compareAndSet(true, false)) return
        try {
            relist()
        } catch (e: Exception) {
            Log.w(TAG, "listing refresh failed", e)
        }
        // A request made during the pass folds into this one directly.
        if (pending.get()) drain()
    }

    /** One fast listing; a custom SAF tree appends its slow crawl snapshot later. */
    private fun relist() {
        val fast = store.list(onSafClips = { merged -> adopt(merged) })
        adopt(fast)
    }

    /** Replaces the shared listing; safe from both the refresh thread and main. */
    private fun adopt(list: List<SegmentInfo>) {
        freshSeen = true
        clips.value = list
        index.adopt(list.map(IndexRow::of))
    }

    private fun adoptCached(rows: List<IndexRow>) {
        if (freshSeen) return
        val rebuilt = rows.mapNotNull { it.toSegmentInfo(resolver) }
        // Re-check: a refresh may have won the race while the (first-IO) load ran.
        if (freshSeen) return
        clips.value = rebuilt
    }

    companion object {
        /** Small enough to feel instant, large enough to swallow publish bursts. */
        private const val MEDIA_DEBOUNCE_MS = 1500L
    }
}
