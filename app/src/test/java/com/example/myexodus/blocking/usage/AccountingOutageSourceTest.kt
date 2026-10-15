package com.example.myexodus.blocking.usage

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.telemetry.CrashReporting
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ADR #149's read-model semantics against the real (Robolectric) ledger: only windows the
 * platform has NOT accounted for are reportable, day-split siblings of one detection count as
 * one interruption, and the exemption offer keeps its surfaces through the eligible gap's own
 * evidence. The exempt-device pins are the owner's device shape: a resolved backlog must
 * produce no banner at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountingOutageSourceTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var db: AppRulesStore
    private lateinit var ledger: UsageLedgerRepository
    private lateinit var state: AccountingDiagnosticsState
    private lateinit var source: AccountingOutageSource

    private val now = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
        .plusHours(12).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        val factory = AppRulesStoreFactory(context, recovery, healthStore)
        db = factory.open()!!
        ledger = UsageLedgerRepository(factory, Dispatchers.IO.limitedParallelism(1), CrashReporting(context))
        state = AccountingDiagnosticsState(context)
        source = AccountingOutageSource(context, ledger, state)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    private fun setBatteryExempt(exempt: Boolean) {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, exempt)
    }

    @Test
    fun resolvedGaps_areNotReported() = runBlocking {
        appendGap(wallClockMs = now - 60_000L, fromElapsedMs = 1_000L, toElapsedMs = 301_000L)
        state.markGapsResolvedThrough(now)

        assertNull(source.current(now))
    }

    @Test
    fun unansweredGaps_areReported_withDaySplitSiblingsMerged() = runBlocking {
        // One detection written across midnight: two rows sharing its wall clock.
        appendGap(wallClockMs = now - 60_000L, fromElapsedMs = 1_000L, toElapsedMs = 61_000L)
        appendGap(wallClockMs = now - 60_000L, fromElapsedMs = 61_000L, toElapsedMs = 121_000L)

        val outage = source.current(now)!!

        assertEquals(1, outage.summary.count)
        assertEquals(120_000L, outage.summary.largestWindowMs)
        assertNull(outage.promptRoute)
        assertFalse(outage.trackingResumed)
    }

    @Test
    fun unansweredGaps_showEvenOnAnExemptDevice_withoutAnOffer() = runBlocking {
        // The claim "some usage may not be counted" is true for these (no platform answer), so
        // the report stays; there is just no offer to attach.
        setBatteryExempt(true)
        appendGap(wallClockMs = now - 60_000L, fromElapsedMs = 1_000L, toElapsedMs = 301_000L)

        val outage = source.current(now)!!

        assertEquals(1, outage.summary.count)
        assertNull(outage.promptRoute)
    }

    @Test
    fun dismissal_silencesUntilANewerUnansweredGap() = runBlocking {
        appendGap(wallClockMs = now - 120_000L, fromElapsedMs = 1_000L, toElapsedMs = 301_000L)
        assertNotNull(source.current(now))

        source.dismiss(now)
        assertNull(source.current(now))

        appendGap(wallClockMs = now + 10_000L, fromElapsedMs = 500_000L, toElapsedMs = 800_000L)
        val outage = source.current(now + 30_000L)!!
        // The dismissed window is still unanswered, so it is still honestly part of the day's
        // count; the dismissal silenced the surface, it never resolved the gap.
        assertEquals(2, outage.summary.count)
    }

    @Test
    fun eligibleOffer_survivesItsWindowBeingResolved() = runBlocking {
        // The eligible gap is resolved (nothing recoverable in it), but the offer it feeds
        // must still reach the user — its evidence is the gap's own span.
        appendGap(wallClockMs = now - 60_000L, fromElapsedMs = 1_000L, toElapsedMs = 301_000L)
        state.markGapsResolvedThrough(now)
        state.markPromptEligible(now, 300_000L)

        val outage = source.current(now)!!

        assertEquals(1, outage.summary.count)
        assertEquals(300_000L, outage.summary.largestWindowMs)
        assertEquals(BatteryPromptRoute.DIRECT_REQUEST, outage.promptRoute)
    }

    @Test
    fun eligibleOffer_onAnExemptDevice_isInvisible() = runBlocking {
        // The owner's device shape: exemption granted, nightly sleep gaps all resolved —
        // nothing to offer, nothing to report, no banner.
        setBatteryExempt(true)
        appendGap(wallClockMs = now - 60_000L, fromElapsedMs = 1_000L, toElapsedMs = 301_000L)
        state.markGapsResolvedThrough(now)
        state.markPromptEligible(now, 300_000L)

        assertNull(source.current(now))
    }

    @Test
    fun dismissedEligibleOffer_staysSilent() = runBlocking {
        state.markGapsResolvedThrough(now)
        state.markPromptEligible(now, 300_000L)
        source.dismiss(now + 1_000L)

        assertNull(source.current(now + 2_000L))
    }

    @Test
    fun trackingResumedConfirmation_stillShowsWithNoUnresolvedRows() = runBlocking {
        // #13's one-time confirmation: the fix was fired, the exemption stands, and there is
        // no unresolved evidence after it.
        setBatteryExempt(true)
        state.markPromptShown(now - 60_000L)

        val outage = source.current(now)!!

        assertTrue(outage.trackingResumed)
        assertNull(outage.promptRoute)
    }

    private suspend fun appendGap(
        wallClockMs: Long,
        fromElapsedMs: Long,
        toElapsedMs: Long,
        bootCount: Int = BOOT,
    ) {
        ledger.appendStandalone(
            AccountingDiagnostics.gapDraft(
                UsageLedgerSource.PATH_A, bootCount, wallClockMs, fromElapsedMs, toElapsedMs,
            ),
        )
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val BOOT = 7
    }
}
