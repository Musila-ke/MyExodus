package com.example.myexodus.accountability.sync

import android.app.Application
import com.example.myexodus.accountability.state.Outbox
import com.example.myexodus.accountability.state.UncheckedSentRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan §8 Step 18 / §9 row 461 (AD-23): a relay rejection is a distinct client-visible state —
 * recorded from the marker the relay merged onto the sender's own message document — and a
 * failed read is NOT a rejection (the row stays due, exactly like a network failure leaves the
 * drainer's row PENDING).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MailboxRejectionCheckerTest {

    private class Fake(rows: List<String>) {
        val due = mutableListOf<String>()
        val stamps = mutableListOf<Triple<String, String?, Long>>()
        var markers: MutableMap<String, String?> = rows.associateWith { null }.toMutableMap()
        var settleBefore: Long? = null
        var readError: Exception? = null
        var checkError: Exception? = null

        val checker = MailboxRejectionChecker(
            dueRows = { _, settle, _ ->
                settleBefore = settle
                checkError?.let { throw it }
                rows.map { UncheckedSentRow(it) }
            },
            relayStatusOf = { _, messageId ->
                readError?.let { throw it }
                markers[messageId]
            },
            stampCheck = { messageId, rejection, at -> stamps += Triple(messageId, rejection, at) },
            now = { NOW },
        )
    }

    @Test
    fun aRateLimitedMarker_isRecordedAsTheRejection() = runBlocking {
        val fake = Fake(listOf("m1", "m2"))
        fake.markers["m2"] = Outbox.REJECTION_RATE_LIMITED

        val rejected = fake.checker.check("uid-partner", "pair-1")

        assertEquals(1, rejected)
        assertEquals(
            listOf(
                Triple("m1", null, NOW),
                Triple("m2", Outbox.REJECTION_RATE_LIMITED, NOW),
            ),
            fake.stamps,
        )
    }

    @Test
    fun aReadFailure_leavesNothingStamped_soTheRowStaysDue() = runBlocking {
        val fake = Fake(listOf("m1"))
        fake.markers["m1"] = Outbox.REJECTION_RATE_LIMITED
        fake.readError = RuntimeException("offline")

        val rejected = fake.checker.check("uid-partner", "pair-1")

        assertEquals(0, rejected) // a network failure is not a rejection
        assertEquals(0, fake.stamps.size) // still due next pass
    }

    @Test
    fun aDueRowQueryFailure_neverFailsTheCallingSyncPass() = runBlocking {
        val fake = Fake(listOf("m1"))
        fake.checkError = RuntimeException("db gone")

        assertEquals(0, fake.checker.check("uid-partner", "pair-1"))
    }

    @Test
    fun theDueWindowIsMeasuredFromNowMinusSettle() = runBlocking {
        val fake = Fake(emptyList())
        fake.checker.check("uid-partner", "pair-1")

        // 1h settle: anything last checked inside the window stays due — the relay runs
        // asynchronously and a wake can start a pass seconds after a drain.
        assertEquals(NOW - 60 * 60 * 1000L, fake.settleBefore)
    }

    private companion object {
        const val NOW = 1_000_000_000L
    }
}
