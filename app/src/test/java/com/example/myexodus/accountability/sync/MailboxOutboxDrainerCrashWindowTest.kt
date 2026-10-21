package com.example.myexodus.accountability.sync

import android.app.Application
import com.example.myexodus.accountability.state.PendingOutboxRow
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stress slice A4 (backlog `project_stress_test_backlog`): the outbox crash window —
 * process death (or a local-DB hiccup) between the Firestore commit and the local SENT
 * mark. `MailboxOutboxDrainerTest` pins the single-pass contract; this file drives a
 * **stateful** outbox across multiple passes, because the property under test is
 * multi-pass: every message id must be written to the mailbox exactly once and every row
 * must eventually reach SENT, no matter where the kill landed.
 *
 * Finding fixed alongside (see ADR #147): `markSent`/`recordAttempt` were called outside
 * every try in `drain()`, so a Room/SQLCipher throw escaped `drain()` entirely — past the
 * unwrapped call site in `PartnerSyncCoordinator.syncPair`, skipping the pull and every
 * remaining pair, contradicting both "a drain failure never skips the pull" and "one
 * pair's failure never skips another's". The drain is now total (always returns a
 * [MailboxOutboxDrainer.DrainResult]); tests 4–6 pin that, and the DELIVERY semantics of a
 * lost mark (self-heal via ALREADY_EXISTS) stay exactly as the class KDoc documents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MailboxOutboxDrainerCrashWindowTest {

    /** The state a real device holds across processes: a local PENDING list and the partner's
     *  mailbox, keyed by doc id = message id — so a re-drain of an already-written row must
     *  surface as ALREADY_EXISTS, exactly like Firestore's create-only rules gate. */
    private class Outbox(rows: List<PendingOutboxRow>) {
        val pending = rows.toMutableList()
        val mailbox = mutableSetOf<String>()
        val writes = mutableListOf<String>()
        val sent = mutableListOf<String>()
        val attempts = mutableListOf<Pair<String, Long>>()
        var latencies = 0
        /** Process death sim: the write lands, the SENT mark silently never happens. */
        val skipMarkFor = mutableSetOf<String>()
        var markSentError: Exception? = null
        var attemptError: Exception? = null
        var writeError: ((String) -> Exception?)? = null

        val drainer = MailboxOutboxDrainer(
            pendingRows = { _, _, limit -> pending.take(limit) },
            markSent = { id ->
                markSentError?.let { throw it }
                if (!skipMarkFor.contains(id)) {
                    sent += id
                    pending.removeAll { it.messageId == id }
                }
            },
            recordAttempt = { id, at, _, _ ->
                attemptError?.let { throw it }
                attempts += id to at
            },
            expireDue = { 0 },
            write = { _, id, _ ->
                writeError?.invoke(id)?.let { throw it }
                if (!mailbox.add(id)) {
                    throw FirebaseFirestoreException("doc exists", FirebaseFirestoreException.Code.ALREADY_EXISTS)
                }
                writes += id
            },
            now = { 42L },
            onSendLatency = { latencies++ },
        )

        suspend fun drain() = drainer.drain("pair-1", "uid-me", "uid-partner")
    }

    private fun row(id: String) =
        PendingOutboxRow(messageId = id, envelopeBytes = byteArrayOf(1), senderKeyId = "sender-1", epoch = 3)

    private fun rows(vararg ids: String) = ids.map(::row)

    // ---- crash-window replays: kill between commit and mark ----

    @Test
    fun killAfterWriteBeforeMark_fullReplay_marksEveryRow_deliversNothingTwice() = runBlocking {
        val outbox = Outbox(rows("m1", "m2", "m3"))
        outbox.skipMarkFor += listOf("m1", "m2", "m3") // kill: docs land, no marks

        val first = outbox.drain()
        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(3), first)
        assertEquals(listOf("m1", "m2", "m3"), outbox.writes)
        assertTrue(outbox.sent.isEmpty())
        assertEquals(3, outbox.pending.size)

        outbox.skipMarkFor.clear()
        val second = outbox.drain()
        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(3), second)
        assertEquals("replay must not re-write (doc id = message id)", listOf("m1", "m2", "m3"), outbox.writes)
        assertEquals(listOf("m1", "m2", "m3"), outbox.sent)
        assertTrue(outbox.pending.isEmpty())
        assertTrue("an ALREADY_EXISTS replay records no attempt", outbox.attempts.isEmpty())
        assertEquals("latency sampled per real write only", 3, outbox.latencies)
    }

    @Test
    fun killMidChain_partialReplay_onlyUnmarkedRowsAreReplayed_inOrder() = runBlocking {
        // Kill landed after m2's mark: m1+m2 fully settled, m3..m5 written but unmarked.
        val outbox = Outbox(rows("m1", "m2", "m3", "m4", "m5"))
        outbox.skipMarkFor += listOf("m3", "m4", "m5")

        val first = outbox.drain()
        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(5), first)
        assertEquals(listOf("m3", "m4", "m5"), outbox.pending.map { it.messageId })

        outbox.skipMarkFor.clear()
        val second = outbox.drain()
        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(3), second)
        assertEquals(
            "each message id written exactly once across both passes, chain order preserved",
            listOf("m1", "m2", "m3", "m4", "m5"),
            outbox.writes,
        )
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), outbox.sent)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun resumeFromCrashedState_mixedReplayAndFreshRows_inOneBatch() = runBlocking {
        // Direct post-crash state: m1..m3 committed, only m1 marked; m4 never written.
        val outbox = Outbox(rows("m1", "m2", "m3", "m4"))
        outbox.mailbox += listOf("m1", "m2", "m3")
        outbox.pending.removeAll { it.messageId == "m1" }

        val result = outbox.drain()
        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(3), result)
        assertEquals("only the fresh row is a real write", listOf("m4"), outbox.writes)
        assertEquals(listOf("m2", "m3", "m4"), outbox.sent)
        assertTrue(outbox.pending.isEmpty())
        assertEquals(1, outbox.latencies)
    }

    // ---- local-DB hiccups: the drain must stay total (ADR #147) ----

    @Test
    fun markSentHiccup_afterFreshWrite_doesNotEscape_leavesRowPending_thenHeals() = runBlocking {
        val outbox = Outbox(rows("m1", "m2", "m3"))
        outbox.markSentError = IllegalStateException("disk full")

        val hiccup = outbox.drain()
        assertEquals("drain returned, never threw", MailboxOutboxDrainer.DrainResult.Sent(3), hiccup)
        assertEquals(3, outbox.writes.size)
        assertTrue(outbox.sent.isEmpty())
        assertEquals("rows stay PENDING when the mark is lost", 3, outbox.pending.size)

        outbox.markSentError = null
        val healed = outbox.drain()
        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(3), healed)
        assertEquals("all healed marks are replays", 3, outbox.writes.size)
        assertEquals(listOf("m1", "m2", "m3"), outbox.sent)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun markSentHiccup_onTheAlreadyExistsPath_doesNotEscape_thenHeals() = runBlocking {
        val outbox = Outbox(rows("m1", "m2"))
        outbox.skipMarkFor += listOf("m1", "m2")
        outbox.drain() // crash sim: both committed, none marked

        outbox.skipMarkFor.clear()
        outbox.markSentError = IllegalStateException("db locked")
        val hiccup = outbox.drain()
        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(2), hiccup)
        assertTrue(outbox.sent.isEmpty())
        assertEquals(2, outbox.pending.size)

        outbox.markSentError = null
        val healed = outbox.drain()
        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(2), healed)
        assertEquals(listOf("m1", "m2"), outbox.sent)
        assertEquals("still no duplicate writes", 2, outbox.writes.size)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun attemptLogHiccup_doesNotEscape_theDecidedResultStillReturns() = runBlocking {
        val unavailable = Outbox(listOf(row("m1")))
        unavailable.writeError = {
            FirebaseFirestoreException("unavailable", FirebaseFirestoreException.Code.UNAVAILABLE)
        }
        unavailable.attemptError = IllegalStateException("disk full")
        val failed = unavailable.drain()
        assertEquals("unavailable", (failed as MailboxOutboxDrainer.DrainResult.Failed).reason)
        assertTrue("the attempt log is best-effort too", unavailable.attempts.isEmpty())

        val denied = Outbox(listOf(row("m1")))
        denied.writeError = {
            FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        }
        denied.attemptError = IllegalStateException("disk full")
        assertEquals(MailboxOutboxDrainer.DrainResult.PermissionDenied, denied.drain())

        val generic = Outbox(listOf(row("m1")))
        generic.writeError = { IllegalStateException("socket closed") }
        generic.attemptError = IllegalStateException("disk full")
        assertEquals("socket closed", (generic.drain() as MailboxOutboxDrainer.DrainResult.Failed).reason)
    }

    // ---- batch continuation across passes ----

    @Test
    fun transientFailureMidBatch_stopsThePass_andTheReplayDeliversExactlyOnce_inOrder() = runBlocking {
        val outbox = Outbox(rows("m1", "m2", "m3", "m4", "m5"))
        outbox.writeError = {
            if (it == "m3") FirebaseFirestoreException("unavailable", FirebaseFirestoreException.Code.UNAVAILABLE) else null
        }

        val first = outbox.drain()
        assertEquals("unavailable", (first as MailboxOutboxDrainer.DrainResult.Failed).reason)
        assertEquals(listOf("m1", "m2"), outbox.writes)
        assertEquals(listOf("m3", "m4", "m5"), outbox.pending.map { it.messageId })
        assertEquals(listOf("m3" to 42L), outbox.attempts)

        outbox.writeError = null
        val second = outbox.drain()
        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(3), second)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), outbox.writes)
        assertEquals(listOf("m3" to 42L), outbox.attempts)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun permissionDeniedMidBatch_leavesTheRestPending_untilARecoveryPass() = runBlocking {
        // §9: a denied write at position k stops the batch (k..n pending); once the cause
        // clears (pairing active, replay distinguishable), one more pass finishes the chain.
        val outbox = Outbox(rows("m1", "m2", "m3", "m4"))
        outbox.writeError = {
            if (it == "m2") FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED) else null
        }

        assertEquals(MailboxOutboxDrainer.DrainResult.PermissionDenied, outbox.drain())
        assertEquals(listOf("m1"), outbox.writes)
        assertEquals(listOf("m2", "m3", "m4"), outbox.pending.map { it.messageId })
        assertEquals(listOf("m2" to 42L), outbox.attempts)

        outbox.writeError = null
        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(3), outbox.drain())
        assertEquals(listOf("m1", "m2", "m3", "m4"), outbox.writes)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun volumeDrainsInBatchChunks_ofOneHundred_withoutLosingOrDuplicatingARow() = runBlocking {
        val ids = (1..250).map { "m$it" }
        val outbox = Outbox(ids.map(::row))

        val chunks = mutableListOf<Int>()
        var guard = 0
        while (outbox.pending.isNotEmpty()) {
            check(guard++ < 10) { "drain stalled with ${outbox.pending.size} rows pending" }
            chunks += (outbox.drain() as MailboxOutboxDrainer.DrainResult.Sent).eventsDelivered
        }
        assertEquals("BATCH=100 boundary", listOf(100, 100, 50), chunks)
        assertEquals(ids, outbox.writes)
        assertEquals(ids, outbox.sent)
        assertEquals(250, outbox.latencies)
        assertTrue(outbox.attempts.isEmpty())
    }
}
