package com.example.myexodus.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.AccountabilityEventCategory
import com.example.myexodus.accountability.serialization.AccountabilityEventWire
import com.example.myexodus.accountability.serialization.MonitoringState
import com.example.myexodus.accountability.serialization.OutboxAgeBucket
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.entity.PartnerEventJournal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan §8.9 / §8.7 against real Room + real SQLite (Robolectric): the journal row is the
 * minimized detector record, the reconcile work list is bounded by the pairing cut-off, the
 * expiry filter and the batch limit, and the sweep is strict about its boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerEventJournalDaoTest {

    private lateinit var context: Context
    private lateinit var db: AppRulesStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        db = Room.databaseBuilder(context, AppRulesStore::class.java, DB_NAME).build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"

    private fun block(id: String = id(1), createdAtMs: Long = NOW) = PartnerEventJournal.forEvent(
        eventId = id,
        category = AccountabilityEventCategory.BLOCKED_APP_ATTEMPT,
        occurredAtMinute = AccountabilityEventWire.minuteOf(createdAtMs),
        createdAtMs = createdAtMs,
    )

    private fun monitoring(id: String = id(2), createdAtMs: Long = NOW) = PartnerEventJournal.forEvent(
        eventId = id,
        category = AccountabilityEventCategory.MONITORING_PERMISSION_CHANGED,
        occurredAtMinute = AccountabilityEventWire.minuteOf(createdAtMs),
        createdAtMs = createdAtMs,
        monitoringState = MonitoringState.DEGRADED,
        outboxAgeBucket = OutboxAgeBucket.ONE_TO_SIX_HOURS,
    )

    @Test
    fun insert_andById_roundTripBothShapes() = runBlocking {
        val dao = db.partnerEventJournalDao()
        dao.insert(block())
        dao.insert(monitoring())

        val storedBlock = dao.byId(id(1))!!
        assertEquals(AccountabilityEventCategory.BLOCKED_APP_ATTEMPT.wire, storedBlock.category)
        assertNull(storedBlock.monitoringState)
        assertNull(storedBlock.outboxAgeBucket)
        assertEquals(AccountabilityEventWire.SCHEMA_VERSION, storedBlock.schemaVersion)
        assertEquals(NOW + PartnerEventJournal.RETENTION_MILLIS, storedBlock.expiresAtMs)

        val storedMonitoring = dao.byId(id(2))!!
        assertEquals(MonitoringState.DEGRADED.wire, storedMonitoring.monitoringState)
        assertEquals(OutboxAgeBucket.ONE_TO_SIX_HOURS.wire, storedMonitoring.outboxAgeBucket)
        assertNull(dao.byId(id(3)))
    }

    @Test
    fun dueForDelivery_filtersByPairingCutoff_expiry_order_andLimit() = runBlocking {
        val dao = db.partnerEventJournalDao()
        dao.insert(block(id(1), createdAtMs = NOW - 10_000)) // pre-enrollment
        dao.insert(block(id(2), createdAtMs = NOW))
        dao.insert(block(id(3), createdAtMs = NOW + 5_000))
        dao.insert(block(id(4), createdAtMs = NOW + 10_000))
        // Created inside the window but already expired: the retention filter wins.
        dao.insert(
            PartnerEventJournal(
                eventId = id(5),
                category = AccountabilityEventCategory.BLOCKED_APP_ATTEMPT.wire,
                occurredAtMinute = 1,
                schemaVersion = AccountabilityEventWire.SCHEMA_VERSION,
                createdAtMs = NOW + 1,
                expiresAtMs = NOW + 2,
            ),
        )

        val due = dao.dueForDelivery(sinceMs = NOW, nowMs = NOW + 2, limit = 10)

        assertEquals("cut-off, expiry and creation order", listOf(id(2), id(3), id(4)), due.map { it.eventId })
        assertEquals(listOf(id(2), id(3)), dao.dueForDelivery(sinceMs = NOW, nowMs = NOW + 2, limit = 2).map { it.eventId })
        assertEquals(
            "the cut-off is inclusive: a row created exactly at pairing time is due",
            id(2),
            dao.dueForDelivery(sinceMs = NOW, nowMs = NOW, limit = 1).single().eventId,
        )
    }

    @Test
    fun deleteExpired_sweepsAtTheBoundaryAndCountsWhatItRemoved() = runBlocking {
        val dao = db.partnerEventJournalDao()
        dao.insert(block(id(1), createdAtMs = NOW))
        dao.insert(block(id(2), createdAtMs = NOW + 1))

        assertEquals(0, dao.deleteExpired(NOW + PartnerEventJournal.RETENTION_MILLIS - 1))
        assertEquals(1, dao.deleteExpired(NOW + PartnerEventJournal.RETENTION_MILLIS))

        assertNull(dao.byId(id(1)))
        assertNotNull("the later row is still inside its window", dao.byId(id(2)))
        assertEquals(1, dao.deleteExpired(NOW + 1 + PartnerEventJournal.RETENTION_MILLIS))
        assertTrue(dao.dueForDelivery(0, NOW + PartnerEventJournal.RETENTION_MILLIS * 2, 10).isEmpty())
    }

    @Test
    fun theEntityRefusesRowsOutsideTheContract() {
        // Monitoring-shaped rows need both contract values; block rows carry neither. These
        // guards are what keep the journal body from silently drifting past the minimizer.
        try {
            PartnerEventJournal.forEvent(
                eventId = id(1),
                category = AccountabilityEventCategory.MONITORING_PERMISSION_CHANGED,
                occurredAtMinute = 1,
                createdAtMs = NOW,
            )
            fail("a monitoring row without its state/bucket must not be constructible")
        } catch (expected: IllegalArgumentException) {
        }

        try {
            PartnerEventJournal.forEvent(
                eventId = id(1),
                category = AccountabilityEventCategory.BLOCKED_APP_ATTEMPT,
                occurredAtMinute = 1,
                createdAtMs = NOW,
                monitoringState = MonitoringState.ACTIVE,
            )
            fail("a block row with monitoring fields must not be constructible")
        } catch (expected: IllegalArgumentException) {
        }

        try {
            PartnerEventJournal.forEvent(
                eventId = id(1),
                category = AccountabilityEventCategory.GAP_MARKER,
                occurredAtMinute = 1,
                createdAtMs = NOW,
                monitoringState = MonitoringState.ACTIVE,
                outboxAgeBucket = OutboxAgeBucket.UNKNOWN,
            )
            fail("gap markers are chain evidence, never journaled")
        } catch (expected: IllegalArgumentException) {
        }
    }

    companion object {
        private const val DB_NAME = "partner_event_journal_test.db"
        private const val NOW = 1_700_000_000_000L
    }
}
