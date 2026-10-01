package com.example.myexodus.blocking.usage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #13's outage-surface precedence truth table. The contract under test: a dismissal silences
 * only the evidence it named (a newer measured interruption re-raises the report), and the
 * resolved confirmation appears only when the fired fix is actually in effect — evidence newer
 * than the fix must bring the report back, never a false "tracking can run again".
 */
class OutageStageTest {

    private val now = 1_000_000_000L
    private val freshness = AccountingDiagnosticsState.ELIGIBILITY_FRESHNESS_MS

    private fun stage(
        newestMeasuredOutageAtMs: Long = 0L,
        batteryExempt: Boolean = false,
        lastFixFiredAtMs: Long = 0L,
        dismissedAtMs: Long = 0L,
    ) = OutageStage.stage(
        newestMeasuredOutageAtMs = newestMeasuredOutageAtMs,
        batteryExempt = batteryExempt,
        lastFixFiredAtMs = lastFixFiredAtMs,
        dismissedAtMs = dismissedAtMs,
        nowWallClockMs = now,
        freshnessMs = freshness,
    )

    @Test
    fun nothingMeasuredNoFix_isSilent() {
        assertEquals(OutageStage.Stage.NONE, stage())
    }

    @Test
    fun freshEvidence_reports() {
        assertEquals(OutageStage.Stage.REPORT, stage(newestMeasuredOutageAtMs = now - 3_600_000))
    }

    @Test
    fun dismissedEvidence_isSilent() {
        assertEquals(
            OutageStage.Stage.NONE,
            stage(
                newestMeasuredOutageAtMs = now - 2 * 3_600_000,
                dismissedAtMs = now - 3_600_000,
            ),
        )
    }

    @Test
    fun evidenceNewerThanTheDismissal_reRaisesTheReport() {
        assertEquals(
            OutageStage.Stage.REPORT,
            stage(
                newestMeasuredOutageAtMs = now - 60_000,
                dismissedAtMs = now - 3_600_000,
            ),
        )
    }

    @Test
    fun exemptWithFixAfterTheNewestEvidence_confirmsOnce() {
        assertEquals(
            OutageStage.Stage.CONFIRMED,
            stage(
                newestMeasuredOutageAtMs = now - 5 * 3_600_000,
                batteryExempt = true,
                lastFixFiredAtMs = now - 3_600_000,
            ),
        )
    }

    @Test
    fun evidenceNewerThanTheFix_keepsReporting_evenWhenExempt() {
        assertEquals(
            OutageStage.Stage.REPORT,
            stage(
                newestMeasuredOutageAtMs = now - 60_000,
                batteryExempt = true,
                lastFixFiredAtMs = now - 3_600_000,
            ),
        )
    }

    @Test
    fun fixFiredButNotExempt_staysAReport() {
        assertEquals(
            OutageStage.Stage.REPORT,
            stage(
                newestMeasuredOutageAtMs = now - 3_600_000,
                batteryExempt = false,
                lastFixFiredAtMs = now - 1_800_000,
            ),
        )
    }

    @Test
    fun dismissedConfirmation_isSilent() {
        assertEquals(
            OutageStage.Stage.NONE,
            stage(
                batteryExempt = true,
                lastFixFiredAtMs = now - 2 * 3_600_000,
                dismissedAtMs = now - 3_600_000,
            ),
        )
    }

    @Test
    fun staleFix_isSilent() {
        assertEquals(
            OutageStage.Stage.NONE,
            stage(
                batteryExempt = true,
                lastFixFiredAtMs = now - freshness - 60_000,
            ),
        )
    }

    @Test
    fun noFixEverFired_neverConfirms_howeverExempt() {
        assertEquals(
            OutageStage.Stage.NONE,
            stage(batteryExempt = true),
        )
    }

    @Test
    fun recentFixWithEvidenceAgedOut_confirms() {
        assertEquals(
            OutageStage.Stage.CONFIRMED,
            stage(
                batteryExempt = true,
                lastFixFiredAtMs = now - 3_600_000,
            ),
        )
    }
}
