package com.example.myexodus.ui.recovery

import com.example.myexodus.data.db.dao.SessionCount
import com.example.myexodus.data.db.entity.RecoverySession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The history summary's two claims are both arithmetic: what each row counts as, and where the
 * window starts. Both are pure here so the boundary is checked without a clock — the screen
 * itself only renders what these return.
 */
class HistorySummaryTest {

    private fun count(state: String, technique: String = "urge_surfing", n: Int = 1) =
        SessionCount(state = state, technique = technique, count = n.toLong())

    @Test
    fun `started rows are excluded from every bucket`() {
        val counts = summarize(listOf(count(RecoverySession.STATE_STARTED, n = 7)))

        assertEquals("a running session has no outcome yet", 0, counts.total)
    }

    @Test
    fun `a bare log is not a completed technique`() {
        val counts = summarize(
            listOf(
                count(RecoverySession.STATE_COMPLETED, technique = RecoverySession.TECHNIQUE_NONE, n = 4),
                count(RecoverySession.STATE_COMPLETED, n = 3),
            ),
        )

        assertEquals("only the rows with a technique count as completed", 3, counts.completed)
        assertEquals(4, counts.loggedOnly)
        assertEquals(7, counts.total)
    }

    @Test
    fun `not finished covers both stopped and interrupted`() {
        val counts = summarize(
            listOf(
                count(RecoverySession.STATE_ABANDONED, n = 2),
                count(RecoverySession.STATE_INTERRUPTED, n = 5),
            ),
        )

        assertEquals(7, counts.notFinished)
        assertEquals(0, counts.completed)
    }

    @Test
    fun `buckets partition the terminal rows exactly once`() {
        val counts = summarize(
            listOf(
                count(RecoverySession.STATE_COMPLETED, n = 4),
                count(RecoverySession.STATE_COMPLETED, technique = RecoverySession.TECHNIQUE_NONE, n = 1),
                count(RecoverySession.STATE_ABANDONED, n = 4),
                count(RecoverySession.STATE_INTERRUPTED, n = 4),
                count(RecoverySession.STATE_STARTED, n = 99),
            ),
        )

        assertEquals(
            "every terminal row lands in exactly one bucket — no row counted twice, none lost",
            13,
            counts.total,
        )
    }

    @Test
    fun `window start is exclusive for all time and exact for bounded ranges`() {
        val now = 1_700_000_000_000L

        assertEquals("All must not filter at all", 0L, windowStartMs(HistoryRange.ALL, now))
        assertEquals(now - 7L * 24 * 60 * 60 * 1000, windowStartMs(HistoryRange.WEEK, now))
        assertEquals(now - 30L * 24 * 60 * 60 * 1000, windowStartMs(HistoryRange.MONTH, now))
    }

    @Test
    fun `empty history yields a zero total rather than a division`() {
        assertEquals(0, summarize(emptyList()).total)
    }
}
