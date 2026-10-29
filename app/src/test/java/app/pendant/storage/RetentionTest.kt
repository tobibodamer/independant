package app.pendant.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionTest {

    private fun items(vararg lmSize: Pair<Long, Long>) = lmSize.map { Retention.Item(it.first, it.second) }

    @Test
    fun `no limits keeps everything`() {
        val items = items(100L to 10L, 200L to 10L)
        assertEquals(0, Retention.plan(items, capBytes = 0, ageCutoffMs = 0).size)
    }

    @Test
    fun `age cutoff deletes old clips only`() {
        // now = 10_000; cutoff = 10_000 - 7days-like -> items older than cutoff go
        val items = items(1L to 10L, 5L to 10L, 9_999L to 10L)
        val plan = Retention.plan(items, capBytes = 0, ageCutoffMs = 5_000)
        assertEquals(listOf(0, 1), plan)
    }

    @Test
    fun `unknown age is never age-deleted`() {
        val items = items(0L to 10L)
        assertEquals(emptyList<Int>(), Retention.plan(items, capBytes = 0, ageCutoffMs = 1_000))
    }

    @Test
    fun `size cap walks oldest first`() {
        // 3 items a 10 B, cap 15 B -> oldest two must go.
        val items = items(1L to 10L, 2L to 10L, 3L to 10L)
        val plan = Retention.plan(items, capBytes = 15, ageCutoffMs = 0)
        assertEquals(listOf(0, 1), plan)
    }

    @Test
    fun `cap satisfied by one big delete`() {
        val items = items(1L to 40L, 2L to 5L, 3L to 5L)
        val plan = Retention.plan(items, capBytes = 10, ageCutoffMs = 0)
        assertEquals(listOf(0), plan)
    }

    @Test
    fun `aged items do not count toward the cap`() {
        // items 0 and 1 are aged out (10 B each); remaining fresh total 10 B <= cap 15.
        val items = items(1L to 10L, 2L to 10L, 9_000L to 10L)
        val plan = Retention.plan(items, capBytes = 15, ageCutoffMs = 5_000)
        assertEquals(listOf(0, 1), plan)
    }

    @Test
    fun `age and cap combine oldest-majority semantics`() {
        // aged: 0,1 (20 B removed). Fresh: 2..5 (4x10 B = 40 B) > cap 25 -> delete next oldest (2, 3).
        val items = items(1L to 10L, 2L to 10L, 3L to 10L, 4L to 10L, 9_000L to 10L, 9_100L to 10L)
        val plan = Retention.plan(items, capBytes = 25, ageCutoffMs = 5_000)
        assertEquals(listOf(0, 1, 2, 3), plan)
    }

    @Test
    fun `empty input yields empty plan`() {
        assertEquals(emptyList<Int>(), Retention.plan(emptyList(), 100, 100))
    }
}
