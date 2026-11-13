package com.example.myexodus.accountability.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §14 metric sample placement: only enrolled passes report, buckets stay coarse, and a
 *  telemetry failure can never take the sync pass down with it. */
class PartnerSyncTelemetryTest {

    private class Fake(
        private val canPost: Boolean = true,
        private val oldestPending: (() -> Long?)? = null,
        private val oldestWaiting: (() -> Long?)? = null,
        private var now: Long = 1_000_000_000L,
    ) {
        val events = mutableListOf<String>()
        val telemetry = PartnerSyncTelemetry(
            canPost = { canPost },
            oldestPendingCreatedAt = { oldestPending?.invoke() },
            report = { events += it },
            oldestWaitingCreatedAt = { oldestWaiting?.invoke() },
            now = { now },
        )
    }

    private val phaseStaged = "${PartnerSyncTelemetry.EVENT_DELIVERY_PHASE} rule_producer=staged"

    @Test
    fun unpairedPass_reportsNothing() = runTest {
        val fake = Fake(canPost = false)
        fake.telemetry.afterPass(PartnerSyncCoordinator.SyncReport(enrolled = false))
        assertTrue(fake.events.isEmpty())
    }

    @Test
    fun enrolledPass_reportsThePhaseFlag_andDeniedOnlyWhenNotificationsCannotPost() = runTest {
        Fake(canPost = false).let {
            it.telemetry.afterPass(PartnerSyncCoordinator.SyncReport(enrolled = true))
            assertEquals(
                listOf(phaseStaged, PartnerSyncTelemetry.EVENT_NOTIFICATIONS_DENIED),
                it.events,
            )
        }
        Fake(canPost = true).let {
            it.telemetry.afterPass(PartnerSyncCoordinator.SyncReport(enrolled = true))
            assertEquals(listOf(phaseStaged), it.events)
        }
    }

    @Test
    fun worstPass_reportsPhaseDeniedAndBothAgeBuckets() = runTest {
        // The fully-degraded pass: notifications denied AND rows stuck PENDING/WAITING — every
        // rail fires, in the documented order.
        val now = 1_000_000_000L
        val fake = Fake(
            canPost = false,
            oldestPending = { now },
            oldestWaiting = { now - 60 * 60_000L },
            now = now,
        )
        fake.telemetry.afterPass(PartnerSyncCoordinator.SyncReport(enrolled = true))
        assertEquals(
            listOf(
                phaseStaged,
                PartnerSyncTelemetry.EVENT_NOTIFICATIONS_DENIED,
                "${PartnerSyncTelemetry.EVENT_WAITING_RECIPIENT_KEY} age=under_24h",
                "${PartnerSyncTelemetry.EVENT_OUTBOX_PENDING} age=under_15m",
            ),
            fake.events,
        )
    }

    @Test
    fun oldestPending_agesIntoTheRightBucket_withBoundariesAtTheBucketEdges() = runTest {
        val now = 1_000_000_000L
        val edges = listOf(
            0L to "under_15m",
            15 * 60_000L to "under_1h", // exactly 15m is no longer "under 15m"
            60 * 60_000L to "under_24h",
            24 * 60 * 60_000L to "over_24h",
        )
        for ((age, bucket) in edges) {
            val fake = Fake(oldestPending = { now - age }, now = now)
            fake.telemetry.afterPass(PartnerSyncCoordinator.SyncReport(enrolled = true))
            assertEquals(
                "${age}ms old → $bucket",
                listOf(phaseStaged, "${PartnerSyncTelemetry.EVENT_OUTBOX_PENDING} age=$bucket"),
                fake.events,
            )
        }
    }

    @Test
    fun oldestWaiting_isBucketed_andAbsentWhenNothingWaits() = runTest {
        val now = 1_000_000_000L
        Fake(oldestWaiting = { now - 24 * 60 * 60_000L }, now = now).let {
            it.telemetry.afterPass(PartnerSyncCoordinator.SyncReport(enrolled = true))
            assertEquals(
                listOf(
                    phaseStaged,
                    "${PartnerSyncTelemetry.EVENT_WAITING_RECIPIENT_KEY} age=over_24h",
                ),
                it.events,
            )
        }
        Fake(oldestWaiting = { null }).let {
            it.telemetry.afterPass(PartnerSyncCoordinator.SyncReport(enrolled = true))
            assertEquals(listOf(phaseStaged), it.events)
        }
    }

    @Test
    fun nothingPending_reportsNoOutboxEvent() = runTest {
        val fake = Fake(oldestPending = { null })
        fake.telemetry.afterPass(PartnerSyncCoordinator.SyncReport(enrolled = true))
        assertEquals(listOf(phaseStaged), fake.events)
    }

    @Test
    fun failingReads_neverThrow_andNeverTakeThePassDown() = runTest {
        val fake = Fake(
            oldestPending = { throw IllegalStateException("db down") },
            oldestWaiting = { throw IllegalStateException("db down") },
        )
        fake.telemetry.afterPass(PartnerSyncCoordinator.SyncReport(enrolled = true))
        assertEquals(listOf(phaseStaged), fake.events)
    }

    @Test
    fun latencyBucket_edgesMatchTheT1Targets() {
        assertEquals("under_1s", PartnerSyncTelemetry.latencyBucket(999))
        assertEquals("under_3s", PartnerSyncTelemetry.latencyBucket(1_000))
        assertEquals("under_3s", PartnerSyncTelemetry.latencyBucket(2_999))
        assertEquals("over_3s", PartnerSyncTelemetry.latencyBucket(3_000))
    }
}
