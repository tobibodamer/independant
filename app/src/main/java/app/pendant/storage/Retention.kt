package app.pendant.storage

/**
 * Pure retention math, shared by every backend's cleanup pass (JVM-testable).
 *
 * Semantics (same as the original per-directory loops):
 * - Age: clips with `1 <= lastModifiedMs < ageCutoffMs` are deleted; unknown age
 *   (0) is never age-deleted. `ageCutoffMs <= 0` disables age deletion.
 * - Size: walking oldest-first, delete clips until the remaining bytes are at
 *   or below [capBytes]; already-selected clips don't count toward the remaining
 *   total. `capBytes <= 0` disables the cap.
 */
internal object Retention {

    data class Item(val lastModifiedMs: Long, val sizeBytes: Long)

    /** Indices into [items] (given oldest-first) that should be deleted. */
    fun plan(items: List<Item>, capBytes: Long, ageCutoffMs: Long): List<Int> {
        if (items.isEmpty()) return emptyList()
        val delete = BooleanArray(items.size)
        var deletedBytes = 0L
        if (ageCutoffMs > 0) {
            for (i in items.indices) {
                val lm = items[i].lastModifiedMs
                if (lm in 1 until ageCutoffMs) {
                    delete[i] = true
                    deletedBytes += items[i].sizeBytes
                }
            }
        }
        if (capBytes > 0) {
            var total = items.sumOf { it.sizeBytes } - deletedBytes
            for (i in items.indices) {
                if (total <= capBytes) break
                if (!delete[i]) {
                    delete[i] = true
                    total -= items[i].sizeBytes
                }
            }
        }
        return delete.indices.filter { delete[it] }
    }
}
