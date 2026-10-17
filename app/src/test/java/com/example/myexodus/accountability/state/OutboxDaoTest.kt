package com.example.myexodus.accountability.state

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 18 due-selection pins (ADR #53): `relayCheckDue` returns sent rows while never checked
 * or checked within the settle window, least-recently-checked first. The ordering is
 * load-bearing under `LIMIT`: never-checked rows (SQLite sorts NULLs first) always get their
 * first look even when the due set exceeds the batch, and re-stamping pushes checked rows to
 * the tail so no due row starves behind the batch boundary — without it, a fresh row could
 * wait arbitrarily long for the 50 rows ahead of it, its relay rejection invisible meanwhile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OutboxDaoTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(AccountabilityDatabase.DB_NAME)
    }

    @Test
    fun relayCheckDue_freshRowsLead_thenLeastRecentlyChecked_andTheWindowBoundaryIsStrict() =
        runBlocking {
            val db = Room.databaseBuilder(
                context,
                AccountabilityDatabase::class.java,
                AccountabilityDatabase.DB_NAME,
            ).build()
            try {
                sentRow(db, "m-fresh", seq = 1, checkedAt = null)
                sentRow(db, "m-old", seq = 2, checkedAt = 1_000)
                sentRow(db, "m-mid", seq = 3, checkedAt = 2_000)
                sentRow(db, "m-before-window", seq = 4, checkedAt = 100)
                // Checked exactly AT the window edge: due = checked_at > settleBefore, strict.
                sentRow(db, "m-at-edge", seq = 5, checkedAt = 500)

                val first = db.outboxDao().relayCheckDue("pair-a", settleBeforeEpochMillis = 500, limit = 2)

                // Never-checked first, then oldest-checked; the at-edge row is NOT due; the
                // before-window row (100) and edge row (500) are out. With limit=2 this also
                // pins that m-mid (third in order) is legitimately starved this pass.
                assertEquals(listOf("m-fresh", "m-old"), first.map { it.messageId })

                // The checker's loop stamps whatever it read; the next pass must advance the
                // tail (m-mid) instead of re-reading the same two.
                db.outboxDao().stampRelayCheck("m-fresh", null, 3_000)
                db.outboxDao().stampRelayCheck("m-old", null, 3_100)

                val second = db.outboxDao().relayCheckDue("pair-a", settleBeforeEpochMillis = 500, limit = 2)

                assertEquals(listOf("m-mid", "m-fresh"), second.map { it.messageId })
            } finally {
                db.close()
            }
        }

    private suspend fun sentRow(
        db: AccountabilityDatabase,
        messageId: String,
        seq: Long,
        checkedAt: Long?,
    ) {
        val eventId = db.chainEventDao().insert(
            ChainEvent(
                pairId = "pair-a",
                senderKeyId = "key-1",
                epoch = 0,
                sequence = seq,
                previousEventHash = null,
                eventHash = byteArrayOf(seq.toByte()),
                envelope = byteArrayOf(0),
                direction = ChainEvent.DIRECTION_SENT,
                createdAtEpochMillis = 10,
                syncState = ChainEvent.SYNC_STATE_SENT,
            ),
        )
        db.outboxDao().insert(
            Outbox(
                messageId = messageId,
                eventId = eventId,
                envelopeBytes = byteArrayOf(1),
                status = Outbox.STATUS_SENT,
                createdAtEpochMillis = 10,
                relayCheckedAtEpochMillis = checkedAt,
            ),
        )
    }
}
