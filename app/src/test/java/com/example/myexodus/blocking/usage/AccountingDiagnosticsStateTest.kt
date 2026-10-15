package com.example.myexodus.blocking.usage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR #149's state keys: the resolved-through watermark is monotonic and persistent (the #146
 * watermark doctrine), and the offer's gap span travels with its eligible-since timestamp
 * through every transition that clears eligibility.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountingDiagnosticsStateTest {

    private lateinit var context: Context
    private lateinit var state: AccountingDiagnosticsState

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        state = AccountingDiagnosticsState(context)
    }

    @Test
    fun defaults_areZero_andNothingIsEligible() {
        assertEquals(0L, state.gapsResolvedThroughMs)
        assertEquals(0L, state.promptEligibleGapMs)
        assertEquals(0L, state.promptEligibleSinceMs)
        assertFalse(state.promptEligible(1_000L))
    }

    @Test
    fun resolvedWatermark_isMonotonicAndPersists() {
        state.markGapsResolvedThrough(5_000L)
        assertEquals(5_000L, state.gapsResolvedThroughMs)

        // Never backward, equal writes are no-ops.
        state.markGapsResolvedThrough(4_000L)
        assertEquals(5_000L, state.gapsResolvedThroughMs)
        state.markGapsResolvedThrough(5_000L)
        assertEquals(5_000L, state.gapsResolvedThroughMs)
        state.markGapsResolvedThrough(6_000L)
        assertEquals(6_000L, state.gapsResolvedThroughMs)

        // A fresh instance (the next process) reads the same value.
        assertEquals(6_000L, AccountingDiagnosticsState(context).gapsResolvedThroughMs)
    }

    @Test
    fun offerGapSpan_travelsWithEligibility_throughEveryExit() {
        state.markPromptEligible(1_000L, 300_000L)
        assertEquals(1_000L, state.promptEligibleSinceMs)
        assertEquals(300_000L, state.promptEligibleGapMs)

        state.markPromptShown(2_000L)
        assertEquals(0L, state.promptEligibleSinceMs)
        assertEquals(0L, state.promptEligibleGapMs)

        state.markPromptEligible(3_000L, 400_000L)
        state.markDeclined(4_000L)
        assertEquals(0L, state.promptEligibleGapMs)

        state.markPromptEligible(5_000L, 500_000L)
        state.clearEligibility()
        assertEquals(0L, state.promptEligibleGapMs)
    }
}
