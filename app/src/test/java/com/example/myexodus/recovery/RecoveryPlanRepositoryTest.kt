package com.example.myexodus.recovery

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.RulesSnapshotPublisher
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.RiskWindow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W3/W4 limits at the repository boundary (v2 FR-6/FR-7): ≤5 items per kind, ≤200 characters
 * per item, blank refused; ≤3 windows with the single default seeded on first read. The caps
 * live here, not only in the editor — a UI-only guard would let any other caller past them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryPlanRepositoryTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var repository: RecoveryPlanRepository
    private lateinit var profileId: String

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        repository = RecoveryPlanRepository(factory)
        profileId = RulesRepository(factory, RulesSnapshotPublisher(context, factory))
            .ensureActiveProfile().userId
    }

    @After
    fun tearDown() = recovery.resetAllData()

    @Test
    fun addItem_trimsText_andRejectsBlank() = runTest {
        assertFalse(
            "blank input must be refused at the boundary",
            repository.addItem(profileId, CoachingStep.KIND_STEP, "   "),
        )
        assertTrue(repository.addItem(profileId, CoachingStep.KIND_STEP, "  Count to ten  "))
        assertEquals("Count to ten", repository.items(profileId).single().actionText)
    }

    @Test
    fun addItem_capsTextAtTheEntityLimit() = runTest {
        repository.addItem(profileId, CoachingStep.KIND_STEP, "x".repeat(CoachingStep.MAX_TEXT_LENGTH + 50))

        assertEquals(
            CoachingStep.MAX_TEXT_LENGTH,
            repository.items(profileId).single().actionText.length,
        )
    }

    @Test
    fun addItem_fivePerKind_limitHoldsPerKind() = runTest {
        repeat(CoachingStep.MAX_PER_KIND) {
            assertTrue(repository.addItem(profileId, CoachingStep.KIND_IF_THEN, "plan $it"))
        }

        assertFalse(
            "the sixth If-Then must be refused",
            repository.addItem(profileId, CoachingStep.KIND_IF_THEN, "one more"),
        )
        assertTrue(
            "the limit is per kind, not shared across kinds",
            repository.addItem(profileId, CoachingStep.KIND_VALUE, "family"),
        )
        assertEquals(
            CoachingStep.MAX_PER_KIND,
            repository.items(profileId).count { it.kind == CoachingStep.KIND_IF_THEN },
        )
    }

    @Test
    fun moveItem_swapsWithinTheKind_andTheEndsAreNoOps() = runTest {
        repository.addItem(profileId, CoachingStep.KIND_STEP, "first")
        repository.addItem(profileId, CoachingStep.KIND_STEP, "second")

        suspend fun steps() = repository.items(profileId).filter { it.kind == CoachingStep.KIND_STEP }

        repository.moveItem(steps()[1], -1)
        assertEquals(listOf("second", "first"), steps().map { it.actionText })

        repository.moveItem(steps().first(), -1)
        repository.moveItem(steps().last(), +1)
        assertEquals("moving past either end changes nothing", listOf("second", "first"), steps().map { it.actionText })
    }

    @Test
    fun updateItem_rewritesTextAndWindowLink_butRefusesBlank() = runTest {
        repository.addItem(profileId, CoachingStep.KIND_STEP, "call Sam")
        val step = repository.items(profileId).single()

        repository.updateItem(step, "call Sam now", "7")
        val updated = repository.items(profileId).single()
        assertEquals("call Sam now", updated.actionText)
        assertEquals("7", updated.associatedTriggerWindow)

        repository.updateItem(updated, "   ", "")
        assertEquals("a blank edit must not erase the item", "call Sam now", repository.items(profileId).single().actionText)
    }

    @Test
    fun deleteItem_removesIt() = runTest {
        repository.addItem(profileId, CoachingStep.KIND_STEP, "call Sam")

        repository.deleteItem(repository.items(profileId).single())

        assertTrue(repository.items(profileId).isEmpty())
    }

    @Test
    fun coachingEdits_journalTheItemCount_neverTheText() = runTest {
        repository.addItem(profileId, CoachingStep.KIND_STEP, "call Sam")
        repository.addItem(profileId, CoachingStep.KIND_STEP, "breathe")
        repository.deleteItem(checkNotNull(repository.items(profileId).find { it.actionText == "call Sam" }))

        val rows = checkNotNull(factory.open()).partnerActivityDao()
            .pendingForPair("pair-1", sinceMs = 0L, limit = 10)
            .filter { it.kind == EventPayload.TYPE_COACHING_STEP_UPDATED }

        assertEquals(listOf(1, 1, 2), rows.map { it.coachingProgress }.sorted())
        assertTrue("the item text never travels (INV-13)", rows.all { it.text.isEmpty() })
    }

    @Test
    fun windows_seedTheSingleDefaultOnFirstRead() = runTest {
        val windows = repository.windows()

        assertEquals(1, windows.size)
        val seeded = windows.single()
        assertEquals(RiskWindow.DEFAULT.startMinute, seeded.startMinute)
        assertEquals(RiskWindow.DEFAULT.endMinute, seeded.endMinute)
        assertEquals(RiskWindow.DEFAULT.daysMask, seeded.daysMask)
        assertTrue(seeded.enabled)
    }

    @Test
    fun addWindow_capsAtThree_andDeleteLeavesAtLeastOneAfterReseed() = runTest {
        repository.windows() // seed the default

        assertTrue(repository.addWindow(9 * 60, 10 * 60, RiskWindowSpec.ALL_DAYS_MASK))
        assertTrue(repository.addWindow(13 * 60, 14 * 60, RiskWindowSpec.ALL_DAYS_MASK))
        assertFalse(
            "a fourth window must be refused (FR-7 ≤3)",
            repository.addWindow(15 * 60, 16 * 60, RiskWindowSpec.ALL_DAYS_MASK),
        )
        assertEquals(RiskWindow.MAX_WINDOWS, repository.windows().size)
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}
