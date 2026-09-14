package com.example.myexodus.blocking

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.AttemptLog
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Attempt-log semantics (blocking-core plan §6): every enforced block and forced override is
 * recorded durably (the local source of truth, §14); pendingEventCreation surfaces only
 * attempts not yet linked to a chain event, oldest first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AttemptLogTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var recorder: AttemptRecorder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        recorder = AttemptRecorder(factory)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun blockAndOverride_areRecordedDurably() = runTest {
        recorder.recordBlocked("r-1", "com.instagram.android")
        recorder.recordOverride("r-1", "com.instagram.android")

        val dao = factory.open()!!.attemptLogDao()
        val rows = dao.recent(10)
        assertEquals(2, rows.size)
        assertEquals(AttemptLog.OUTCOME_OVERRIDE, rows[0].outcome) // newest first
        assertEquals("com.instagram.android", rows[0].blockedTarget)
        assertEquals(AttemptLog.OUTCOME_BLOCKED, rows[1].outcome)
        assertNull("fresh attempts have no linked event", rows[1].eventSequence)
    }

    @Test
    fun pendingEventCreation_returnsUnlinkedOldestFirst() = runTest {
        recorder.recordBlocked("r-1", "com.a")
        recorder.recordBlocked("r-2", "com.b")

        val dao = factory.open()!!.attemptLogDao()
        dao.linkEvent(dao.recent(10)[1].id, sequence = 7L)

        val pending = dao.pendingEventCreation(10)
        assertEquals(1, pending.size)
        assertEquals("com.b", pending[0].blockedTarget)
    }

    @Test
    fun linkEvent_writesTheChainSequence() = runTest {
        recorder.recordBlocked("r-1", "com.a")
        val dao = factory.open()!!.attemptLogDao()
        val row = dao.recent(10).single()
        dao.linkEvent(row.id, sequence = 3L)
        assertEquals(3L, dao.recent(10).single().eventSequence)
        assertTrue(dao.pendingEventCreation(10).isEmpty())
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}
