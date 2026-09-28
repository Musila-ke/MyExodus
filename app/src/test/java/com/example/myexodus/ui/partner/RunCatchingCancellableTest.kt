package com.example.myexodus.ui.partner

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the runCatching-vs-cancellation trap behind the ceremony screen's cancel defect
 * (X6532, 2026-09-28: tapping "Cancel invitation" rendered "The invitation ran out").
 */
class RunCatchingCancellableTest {

    @Test
    fun success_wrapsValue() {
        assertEquals(3, runCatchingCancellable { 3 }.getOrNull())
    }

    @Test
    fun ordinaryFailure_isCapturedLikeRunCatching() {
        assertTrue(runCatchingCancellable { error("boom") }.isFailure)
    }

    @Test
    fun cancellation_propagates_unlikeRunCatching() {
        // The control, pinning the platform behaviour the defect exploited.
        assertTrue(runCatching { throw CancellationException("cancelled") }.isFailure)

        try {
            runCatchingCancellable { throw CancellationException("cancelled") }
            fail("cancellation must not be captured")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }
}
