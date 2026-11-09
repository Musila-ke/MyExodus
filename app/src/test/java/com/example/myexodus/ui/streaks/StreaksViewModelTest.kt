package com.example.myexodus.ui.streaks

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.RulesSnapshotPublisher
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.streaks.MilestoneAlerter
import com.example.myexodus.streaks.MilestoneScheduler
import com.example.myexodus.streaks.StreakAnomalyLog
import com.example.myexodus.streaks.StreakTimer
import com.example.myexodus.streaks.TimeReadings
import com.example.myexodus.streaks.WithdrawalSeverity
import com.example.myexodus.ui.home.MainDispatcherRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finding #61: the setup form's Start control had no in-flight guard, and `onboard` inserts
 * unconditionally — a double tap during the IO write created the habit twice, permanently
 * (there is no delete path). The ViewModel now drops the second call and the button disables,
 * the same §9 first-line defense the relapse control uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreaksViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var repository: HabitRepository
    private lateinit var viewModel: StreaksViewModel

    private var now = TimeReadings(tWallMillis = T0, rBootMillis = 1_000, bootCount = 7)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        // Warm the store before the timed window (the sibling HabitRepositoryTest fixture does
        // the same): the sandbox's first SQLite open pays Robolectric's one-time native-runtime
        // load, which can exceed the assertion timeout and is not what this test measures.
        checkNotNull(factory.open())
        val anomalyLog = StreakAnomalyLog(tmp.newFile("streak-anomalies.log"))
        repository = HabitRepository(
            storeFactory = factory,
            anomalyLog = anomalyLog,
            readings = { now },
            scheduler = MilestoneScheduler(context, factory, readings = { now }),
        )
        viewModel = StreaksViewModel(
            repository = repository,
            rulesRepository = RulesRepository(factory, RulesSnapshotPublisher(context, factory)),
            milestoneAlerter = MilestoneAlerter(context, factory, repository, anomalyLog, { now }),
        )
    }

    @After
    fun tearDown() {
        factory.open()?.close()
        recovery.resetAllData()
    }

    @Test
    fun doubleTapOnStart_createsOneHabit() = runBlocking {
        viewModel.onboard("Instagram", 2, WithdrawalSeverity.MODERATE, T0)
        // The second tap lands before the first write returns — the exact double-tap window.
        viewModel.onboard("Instagram", 2, WithdrawalSeverity.MODERATE, T0)

        val habits = withTimeout(15_000) {
            while (viewModel.onboardingInFlight.value || !viewModel.resolved.value) delay(20)
            repository.habits()
        }

        assertEquals(
            "a double tap on Start must create one habit, not two (finding #61)",
            1,
            habits.size,
        )
    }

    /** Owner ask 2026-11-09 ("I didn't even know it took effect"): a share change confirms
     *  itself inline and asks for the delivery pass at the moment of the act. */
    @Test
    fun sharingAToggle_confirmsInlineAndRequestsDelivery() = runBlocking {
        viewModel.onboard("Instagram", 2, WithdrawalSeverity.MODERATE, T0)
        withTimeout(15_000) {
            while (viewModel.onboardingInFlight.value || !viewModel.resolved.value) delay(20)
        }
        val requested = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(15_000) { viewModel.sharingDeliveryRequested.first() }
        }

        viewModel.setHabitSharing("pair-1", "John", true)
        withTimeout(15_000) {
            while (viewModel.manageInFlight.value || viewModel.manageFeedback.value == null) delay(20)
        }
        requested.await()

        val feedback = viewModel.manageFeedback.value
        assertTrue("the Manage page confirms the share", feedback is StreaksViewModel.ManageFeedback.Shared)
        feedback as StreaksViewModel.ManageFeedback.Shared
        assertEquals("John", feedback.partnerName)
        assertTrue(feedback.enabled)
        assertEquals(true, viewModel.pairSharing.value["pair-1"])
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        /** Inside the app's plausibility window: [StreakTimer.firstAnchor] rejects a wall clock
         *  older than the release epoch, so a pre-release literal would make onboarding return
         *  Rejected and the test would measure nothing. */
        const val T0 = StreakTimer.APP_RELEASE_EPOCH_MS + 5 * StreakTimer.DAY_MS
    }
}
