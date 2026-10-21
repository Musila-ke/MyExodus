package com.example.myexodus.accountability.plan

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.PartnerPlan
import com.example.myexodus.data.db.entity.PartnerPlanTask
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR #193 authoring contract against a real Room store: input bounds are applied here (blank
 * lines dropped, caps enforced), a shared plan raises the publish flag while a self plan never
 * leaves the phone, only the recipient side may check lines, and edits keep task ids so checks
 * survive a rewrite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerPlanRepositoryTest {

    private lateinit var recovery: RecoveryManager
    private lateinit var store: AppRulesStore
    private lateinit var repository: PartnerPlanRepository

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
    }

    @After
    fun tearDown() {
        store.close()
        recovery.resetAllData()
    }

    @Test
    fun createPlan_trimsDropsBlanksAndCaps() = runBlocking {
        val planId = checkNotNull(
            repository.createPlan(
                pairId = null,
                title = "  Week 1  ",
                taskTexts = listOf("", "  Read  ", "x".repeat(PartnerPlanTask.MAX_TEXT_LENGTH + 80)),
            ),
        )
        assertEquals("Week 1", store.partnerPlanDao().get(planId)?.title)
        val tasks = store.partnerPlanDao().tasks(planId)
        assertEquals(2, tasks.size)
        assertEquals("Read", tasks[0].text)
        assertEquals(PartnerPlanTask.MAX_TEXT_LENGTH, tasks[1].text.length)
    }

    @Test
    fun createPlan_capsTaskCount() = runBlocking {
        val planId = checkNotNull(
            repository.createPlan(null, "Many", (1..PartnerPlanTask.MAX_TASKS + 5).map { "line $it" }),
        )
        assertEquals(PartnerPlanTask.MAX_TASKS, store.partnerPlanDao().tasks(planId).size)
    }

    @Test
    fun createPlan_blankTitle_isRefused() = runBlocking {
        assertNull(repository.createPlan(null, "   ", listOf("a")))
        assertTrue(store.partnerPlanDao().allPlans().isEmpty())
    }

    @Test
    fun sharedPlan_isPendingAndDisplayOnlyForTheAuthor() = runBlocking {
        val planId = checkNotNull(repository.createPlan(PAIR, "Shared", listOf("one")))
        val plan = checkNotNull(store.partnerPlanDao().get(planId))
        assertTrue(plan.authored)
        assertTrue("a shared plan must publish", plan.pendingPublish)
        val taskId = store.partnerPlanDao().tasks(planId).single().taskId
        assertFalse(
            "an author's copy never checks its own lines",
            repository.setTaskDone(planId, taskId, true),
        )
        assertFalse(store.partnerPlanDao().tasks(planId).single().done)
    }

    @Test
    fun selfPlan_isLocalAndCheckable() = runBlocking {
        val planId = checkNotNull(repository.createPlan(null, "Mine", listOf("one")))
        val plan = checkNotNull(store.partnerPlanDao().get(planId))
        assertFalse("a self plan never publishes", plan.pendingPublish)
        val taskId = store.partnerPlanDao().tasks(planId).single().taskId
        assertTrue(repository.setTaskDone(planId, taskId, true))
        val task = store.partnerPlanDao().tasks(planId).single()
        assertTrue(task.done)
        assertNotNull(task.doneAtMs)
        assertFalse(
            "a self plan raises no progress event",
            checkNotNull(store.partnerPlanDao().get(planId)).pendingProgress,
        )
    }

    @Test
    fun updatePlan_bumpsRevision_keepsTaskIds_dropsRemovedLines() = runBlocking {
        val planId = checkNotNull(repository.createPlan(PAIR, "Week", listOf("keep", "drop")))
        val before = store.partnerPlanDao().tasks(planId)
        val keepId = before.first { it.text == "keep" }.taskId
        val dropId = before.first { it.text == "drop" }.taskId

        assertTrue(
            repository.updatePlan(
                planId,
                "Week 2",
                listOf(PlanTaskDraft(keepId, "keep edited"), PlanTaskDraft(null, "new line")),
            ),
        )

        val plan = checkNotNull(store.partnerPlanDao().get(planId))
        assertEquals(2, plan.revision)
        assertEquals("Week 2", plan.title)
        assertTrue(plan.pendingPublish)
        val after = store.partnerPlanDao().tasks(planId)
        assertEquals(listOf("keep edited", "new line"), after.map { it.text })
        assertEquals(keepId, after[0].taskId)
        assertTrue(after.none { it.taskId == dropId })
    }

    @Test
    fun updatePlan_refusesACopyThisDeviceDidNotAuthor() = runBlocking {
        val planId = "foreign-plan"
        store.partnerPlanDao().insertPlan(
            PartnerPlan(
                planId = planId,
                pairId = PAIR,
                authored = false,
                title = "Theirs",
                revision = 1,
                updatedAtMs = 1_800_000_000_000L,
            ),
        )
        assertFalse(repository.updatePlan(planId, "Mine now", listOf(PlanTaskDraft(null, "x"))))
        assertEquals("Theirs", store.partnerPlanDao().get(planId)?.title)
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PAIR = "pair-1"
    }
}
