package com.example.myexodus.accountability.sync

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the owner's send-feedback contract (2026-10-01 UX ruling): SENT means the queues
 * actually cleared, QUEUED is the safe fallback for anything short of that (offline, declined
 * signing prompt, failed pass, unreadable queue), FAILED only when nothing was written. The
 * dangerous direction — a transport or auth failure reading as delivered — must be impossible.
 */
class MessageDeliveryTest {

    @Test
    fun nothingEmittedIsFailure() = runBlocking {
        var passRan = false
        val outcome = messageSendOutcome(
            emitted = false,
            online = true,
            authorize = { true },
            runPass = { passRan = true },
            stillPending = { false },
        )
        assertEquals(MessageSendOutcome.FAILED, outcome)
        assertFalse("a failed emit must not kick the network", passRan)
    }

    @Test
    fun offlineIsSavedNotFailed() = runBlocking {
        var passRan = false
        val outcome = messageSendOutcome(
            emitted = true,
            online = false,
            authorize = { true },
            runPass = { passRan = true },
            stillPending = { true },
        )
        assertEquals(MessageSendOutcome.QUEUED, outcome)
        assertFalse("offline skips the pass — it cannot deliver", passRan)
    }

    @Test
    fun declinedSigningPromptIsSavedNotFailed() = runBlocking {
        var passRan = false
        val outcome = messageSendOutcome(
            emitted = true,
            online = true,
            authorize = { false },
            runPass = { passRan = true },
            stillPending = { true },
        )
        assertEquals(MessageSendOutcome.QUEUED, outcome)
        assertFalse("a cold window with no authorization cannot sign — no pass", passRan)
    }

    @Test
    fun authorizeFailureIsSavedNotFailed() = runBlocking {
        val outcome = messageSendOutcome(
            emitted = true,
            online = true,
            authorize = { throw IllegalStateException("prompt unavailable") },
            runPass = {},
            stillPending = { true },
        )
        assertEquals(MessageSendOutcome.QUEUED, outcome)
    }

    @Test
    fun clearedQueuesReportSent() = runBlocking {
        var passRan = false
        val outcome = messageSendOutcome(
            emitted = true,
            online = true,
            authorize = { true },
            runPass = { passRan = true },
            stillPending = { false },
        )
        assertEquals(MessageSendOutcome.SENT, outcome)
        assertTrue(passRan)
    }

    @Test
    fun passFailureWithPendingRowsStaysQueued() = runBlocking {
        val outcome = messageSendOutcome(
            emitted = true,
            online = true,
            authorize = { true },
            runPass = { throw IllegalStateException("no network after all") },
            stillPending = { true },
        )
        assertEquals(MessageSendOutcome.QUEUED, outcome)
    }

    /** Finding 2026-11-02: a prompt left up (or a wedged pass) held `sending` forever while
     *  the composer refused every dismiss. Each leg is bounded; a timed-out leg is QUEUED. */
    @Test
    fun anAuthorizationThatNeverReturnsIsSavedWithinTheTimeout() = runTest {
        val outcome = messageSendOutcome(
            emitted = true,
            online = true,
            authorize = { awaitCancellation() },
            runPass = { error("must not run") },
            stillPending = { false },
            timeoutMs = 1_000L,
        )
        assertEquals(MessageSendOutcome.QUEUED, outcome)
    }

    @Test
    fun aQueueThatNeverAnswersNeverClaimsSent() = runTest {
        val outcome = messageSendOutcome(
            emitted = true,
            online = true,
            authorize = { true },
            runPass = {},
            stillPending = { awaitCancellation() },
            timeoutMs = 1_000L,
        )
        assertEquals(MessageSendOutcome.QUEUED, outcome)
    }

    @Test
    fun unreadableQueueNeverClaimsSent() = runBlocking {
        val outcome = messageSendOutcome(
            emitted = true,
            online = true,
            authorize = { true },
            runPass = {},
            stillPending = { throw IllegalStateException("db closed") },
        )
        assertEquals(MessageSendOutcome.QUEUED, outcome)
    }
}
