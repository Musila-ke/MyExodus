package com.example.myexodus.accountability.plan

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.PlanBody
import com.example.myexodus.accountability.serialization.PlanTaskBody
import com.example.myexodus.accountability.serialization.PlanWire
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
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
 * ADR #193 receive contract: a newer published revision applies and keeps the recipient's
 * checks; an older/equal one is ignored; the author's copy is never overwritten; progress
 * applies only at the revision the author currently holds. A wrong pair or a mismatched wire
 * version is declined without touching local rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerPlanReceiverTest {

    private lateinit var recovery: RecoveryManager
    private lateinit var store: AppRulesStore
    private lateinit var repository: PartnerPlanRepository
    private lateinit var receiver: PartnerPlanReceiver

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        val factory = AppRulesStoreFactory(context, recovery, healthStore)
        store = checkNotNull(factory.open())
        repository = PartnerPlanRepository(factory)
        receiver = PartnerPlanReceiver(factory)
    }

    @After
    fun tearDown() {
        store.close()
        recovery.resetAllData()
    }

    @Test
    fun published_createsTheRecipientCopy() = runBlocking {
        receiver.applyAppended(PAIR, published(snapshot(revision = 1, title = "Week 1")))

        val plan = checkNotNull(store.partnerPlanDao().get(PLAN))
        assertFalse(plan.authored)
        assertEquals(PAIR, plan.pairId)
        assertEquals("Week 1", plan.title)
        assertEquals(listOf("one", "two"), store.partnerPlanDao().tasks(PLAN).map { it.text })
    }

    @Test
    fun published_olderOrEqualRevision_isIgnored() = runBlocking {
        receiver.applyAppended(PAIR, published(snapshot(revision = 2, title = "Week 2")))
        receiver.applyAppended(PAIR, published(snapshot(revision = 2, title = "Restated")))
        receiver.applyAppended(PAIR, published(snapshot(revision = 1, title = "Stale")))

        assertEquals("Week 2", store.partnerPlanDao().get(PLAN)?.title)
    }

    @Test
    fun published_newerRevision_keepsChecksAndReRaisesProgress() = runBlocking {
        receiver.applyAppended(PAIR, published(snapshot(revision = 1, title = "Week 1")))
        assertTrue(repository.setTaskDone(PLAN, T1, true))
        // Simulate a completed progress emission: the flag is clear before the next publish,
        // so the assertion below proves the publish re-raises it.
        store.partnerPlanDao().clearProgressPending(PLAN, revision = 1)
        assertFalse(checkNotNull(store.partnerPlanDao().get(PLAN)).pendingProgress)

        // The author edits the wording but keeps both task ids.
        receiver.applyAppended(
            PAIR,
            published(
                snapshot(revision = 2, title = "Week 1", texts = listOf("one, edited", "two")),
            ),
        )

        val tasks = store.partnerPlanDao().tasks(PLAN)
        assertTrue("the check survives a reworded line", tasks.first { it.taskId == T1 }.done)
        assertFalse(tasks.first { it.taskId == T2 }.done)
        assertEquals("one, edited", tasks.first { it.taskId == T1 }.text)
        assertTrue(
            "a newer revision re-raises the recipient's progress",
            checkNotNull(store.partnerPlanDao().get(PLAN)).pendingProgress,
        )
    }

    @Test
    fun published_neverOverwritesAnAuthoredCopy() = runBlocking {
        val planId = checkNotNull(repository.createPlan(PAIR, "Mine", listOf("keep")))
        receiver.applyAppended(
            PAIR,
            published(
                PlanBody(
                    planId = planId,
                    title = "Overwritten?",
                    revision = 99,
                    updatedAtMs = NOW,
                    tasks = listOf(PlanTaskBody("x", 0, "x")),
                ),
            ),
        )

        val plan = checkNotNull(store.partnerPlanDao().get(planId))
        assertTrue(plan.authored)
        assertEquals("Mine", plan.title)
        assertEquals(listOf("keep"), store.partnerPlanDao().tasks(planId).map { it.text })
    }

    @Test
    fun published_removesLinesTheAuthorDropped() = runBlocking {
        receiver.applyAppended(PAIR, published(snapshot(revision = 1)))
        receiver.applyAppended(
            PAIR,
            published(
                snapshot(revision = 2, texts = listOf("one"), ids = listOf(T1)),
            ),
        )

        val tasks = store.partnerPlanDao().tasks(PLAN)
        assertEquals(listOf(T1), tasks.map { it.taskId })
    }

    @Test
    fun progress_appliesOnlyAtTheRevisionTheAuthorHolds() = runBlocking {
        val planId = checkNotNull(repository.createPlan(PAIR, "Week 1", listOf("one", "two")))
        val tasks = store.partnerPlanDao().tasks(planId)
        assertTrue(
            repository.updatePlan(
                planId,
                "Week 2",
                tasks.map { PlanTaskDraft(it.taskId, it.text) },
            ),
        )
        assertEquals(2, checkNotNull(store.partnerPlanDao().get(planId)).revision)

        val progressAtOne = progress(planId, revision = 1, done = listOf(tasks[0].taskId))
        receiver.applyAppended(PAIR, progressAtOne)
        assertFalse(
            "a stale progress event must not touch a newer revision",
            store.partnerPlanDao().tasks(planId).first { it.taskId == tasks[0].taskId }.done,
        )

        receiver.applyAppended(PAIR, progress(planId, revision = 2, done = listOf(tasks[0].taskId)))
        assertTrue(
            store.partnerPlanDao().tasks(planId).first { it.taskId == tasks[0].taskId }.done,
        )
        assertFalse(
            store.partnerPlanDao().tasks(planId).first { it.taskId == tasks[1].taskId }.done,
        )
    }

    @Test
    fun progress_onTheRecipientCopy_isIgnored() = runBlocking {
        receiver.applyAppended(PAIR, published(snapshot(revision = 1)))
        receiver.applyAppended(PAIR, progress(PLAN, revision = 1, done = listOf(T1)))

        assertFalse(store.partnerPlanDao().tasks(PLAN).first { it.taskId == T1 }.done)
    }

    @Test
    fun published_fromAnotherPair_isIgnored() = runBlocking {
        receiver.applyAppended(PAIR, published(snapshot(revision = 1, title = "Original")))
        receiver.applyAppended(
            "pair-2",
            published(snapshot(revision = 9, title = "Other pair")),
        )

        assertNull(store.partnerPlanDao().get(PLAN)?.pairId?.takeIf { it == "pair-2" })
        assertEquals("Original", store.partnerPlanDao().get(PLAN)?.title)
    }

    @Test
    fun wireVersionMismatch_isIgnored() = runBlocking {
        receiver.applyAppended(
            PAIR,
            payload(
                PlanWire.TYPE_PLAN_PUBLISHED,
                snapshot(revision = 1),
                protocolVersion = PlanWire.PLAN_PROTOCOL_VERSION + 1,
            ),
        )
        assertNull(store.partnerPlanDao().get(PLAN))
    }

    private fun snapshot(
        revision: Int,
        title: String = "Week 1",
        texts: List<String> = listOf("one", "two"),
        ids: List<String> = listOf(T1, T2),
    ) = PlanBody(
        planId = PLAN,
        title = title,
        revision = revision,
        updatedAtMs = NOW + revision,
        tasks = ids.zip(texts).mapIndexed { index, (id, text) ->
            PlanTaskBody(id, index, text)
        },
    )

    private fun progress(planId: String, revision: Int, done: List<String>) = payload(
        PlanWire.TYPE_PLAN_PROGRESS,
        PlanBody(
            planId = planId,
            title = "Week 1",
            revision = revision,
            updatedAtMs = NOW + revision,
            doneTaskIds = done,
        ),
    )

    private fun published(body: PlanBody) = payload(PlanWire.TYPE_PLAN_PUBLISHED, body)

    private fun payload(
        type: String,
        body: PlanBody,
        protocolVersion: Int = PlanWire.PLAN_PROTOCOL_VERSION,
    ) = EventPayload(
        type = type,
        text = "",
        protocolVersion = protocolVersion,
        schemaVersion = PlanWire.PLAN_SCHEMA_VERSION,
        plan = body,
    )

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PAIR = "pair-1"
        const val PLAN = "plan-1"
        const val T1 = "task-1"
        const val T2 = "task-2"
        const val NOW = 1_800_000_000_000L
    }
}
