package com.example.myexodus.data.db

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.entity.PartnerMonitoringSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Step 7 received-snapshot replay guard (plan §8.9): wholesale state, monotonic by
 * (install generation, event sequence), so a replayed older snapshot can never roll the
 * partner card backwards while a re-pair's fresh generation still lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerMonitoringSnapshotDaoTest {

    private lateinit var context: Context
    private lateinit var db: AppRulesStore
    private val name = "monitoring-snapshot-dao-test.db"

    private val base = PartnerMonitoringSnapshot(
        pairId = "pair-1",
        monitoringState = "ACTIVE",
        outboxAgeBucket = "UNDER_1H",
        snapshotInstallGeneration = "gen-a",
        latestRelationshipSeq = 9,
        snapshotEventSequence = 5,
        occurredAtMinute = 42,
        receivedAtMs = 1_000,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
        db = Room.databaseBuilder(context, AppRulesStore::class.java, name).build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun recordIfNewer_admitsFirstSnapshot_thenRejectsOlderAndEqualReplays() = runBlocking {
        val dao = db.partnerMonitoringSnapshotDao()
        assertNull(dao.byPair("pair-1"))

        assertTrue("the first snapshot is stored", dao.recordIfNewer(base))

        assertFalse(
            "an older replayed sequence is rejected",
            dao.recordIfNewer(base.copy(snapshotEventSequence = 4, monitoringState = "UNKNOWN")),
        )
        assertFalse(
            "an equal sequence is rejected",
            dao.recordIfNewer(base.copy(monitoringState = "UNAVAILABLE")),
        )
        val held = dao.byPair("pair-1")!!
        assertEquals("the stored state was not overwritten", "ACTIVE", held.monitoringState)
        assertEquals(5L, held.snapshotEventSequence)
    }

    @Test
    fun recordIfNewer_admitsNewerSequence_andADifferentInstallGeneration() = runBlocking {
        val dao = db.partnerMonitoringSnapshotDao()
        dao.recordIfNewer(base)

        assertTrue(
            "a newer sequence is admitted",
            dao.recordIfNewer(base.copy(snapshotEventSequence = 6, monitoringState = "DEGRADED")),
        )
        assertEquals("DEGRADED", dao.byPair("pair-1")!!.monitoringState)

        assertTrue(
            "a re-pair's generation is admitted even at sequence 1 — its chain restarted",
            dao.recordIfNewer(
                base.copy(
                    snapshotInstallGeneration = "gen-b",
                    snapshotEventSequence = 1,
                    monitoringState = "UNAVAILABLE",
                ),
            ),
        )
        val rep = dao.byPair("pair-1")!!
        assertEquals("UNAVAILABLE", rep.monitoringState)
        assertEquals("gen-b", rep.snapshotInstallGeneration)
    }

    @Test
    fun deleteFor_removesOnlyTheUnpairedRows() = runBlocking {
        val dao = db.partnerMonitoringSnapshotDao()
        dao.recordIfNewer(base)
        dao.recordIfNewer(base.copy(pairId = "pair-2"))

        dao.deleteFor(listOf("pair-1"))

        assertNull(dao.byPair("pair-1"))
        assertEquals("pair-2", dao.byPair("pair-2")!!.pairId)
        assertEquals(1, dao.all().size)
    }
}
