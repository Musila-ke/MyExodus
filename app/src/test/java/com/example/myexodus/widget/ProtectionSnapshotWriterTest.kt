package com.example.myexodus.widget

import com.example.myexodus.blocking.ProtectionAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writer's two pure decisions: when a sample is worth re-writing, and what the app's authority
 * arbiter means for the widget.
 *
 * The restamp gate is what keeps a 30-second arbiter cycle from re-rendering the widget when
 * nothing moved, while still letting a stale-but-unchanged sample refresh (spec §5 Decision 7′).
 */
class ProtectionSnapshotWriterTest {

    private val nowUtc = 1_800_000_000_000L

    private fun snapshot(
        enforcement: EnforcementStatus = EnforcementStatus.ACTIVE,
        streakDays: Int? = 12,
        habitLabel: String? = "No smoking",
        discreet: Boolean = true,
        evalUtc: Long = nowUtc - MINUTE,
        evalBootCount: Int = 42,
    ) = ProtectionSnapshot(
        enforcement = enforcement,
        streakDays = streakDays,
        habitLabel = habitLabel,
        discreet = discreet,
        evalUtc = evalUtc,
        evalElapsed = 500_000L,
        evalBootCount = evalBootCount,
    )

    // --- churn gate ---

    @Test
    fun firstSample_isAlwaysWritten() {
        assertTrue(shouldRestamp(existing = null, next = snapshot(), nowUtc = nowUtc))
    }

    @Test
    fun unchangedYoungSample_isNotRewritten() {
        // The heartbeat re-samples every 5 minutes; a write here would re-render for nothing.
        assertFalse(shouldRestamp(existing = snapshot(), next = snapshot(), nowUtc = nowUtc))
    }

    @Test
    fun unchangedSamplePastTheRestampAge_isRewritten() {
        // Backstop: even with no change, the stored age must never approach the 30-minute ceiling
        // the freshness gate enforces.
        assertTrue(
            shouldRestamp(
                existing = snapshot(evalUtc = nowUtc - 10 * MINUTE),
                next = snapshot(),
                nowUtc = nowUtc,
            ),
        )
    }

    @Test
    fun unchangedJustUnderTheRestampAge_isNotRewritten() {
        assertFalse(
            shouldRestamp(
                existing = snapshot(evalUtc = nowUtc - (10 * MINUTE - 1)),
                next = snapshot(),
                nowUtc = nowUtc,
            ),
        )
    }

    // --- everything the widget renders must survive a change ---

    @Test
    fun enforcementChange_isWritten() {
        assertTrue(
            shouldRestamp(
                existing = snapshot(),
                next = snapshot(enforcement = EnforcementStatus.INACTIVE),
                nowUtc = nowUtc,
            ),
        )
    }

    @Test
    fun streakChange_isWritten() {
        assertTrue(
            shouldRestamp(existing = snapshot(), next = snapshot(streakDays = 13), nowUtc = nowUtc),
        )
    }

    @Test
    fun streakBecomingUnknown_isWritten() {
        // Losing the streak (habit removed) has to reach the widget, not be swallowed as "no
        // change" by a null-vs-value comparison.
        assertTrue(
            shouldRestamp(existing = snapshot(), next = snapshot(streakDays = null), nowUtc = nowUtc),
        )
    }

    @Test
    fun habitLabelChange_isWritten() {
        // Switching the tracked habit moves the label under the unchanged day count; the widget
        // must not keep naming the old track (finding #12).
        assertTrue(
            shouldRestamp(
                existing = snapshot(),
                next = snapshot(habitLabel = "No alcohol"),
                nowUtc = nowUtc,
            ),
        )
    }

    @Test
    fun discreetToggle_isWritten() {
        assertTrue(
            shouldRestamp(existing = snapshot(), next = snapshot(discreet = false), nowUtc = nowUtc),
        )
    }

    @Test
    fun reboot_isWrittenEvenWhenNothingElseMoved() {
        // Without this the freshness gate would reject the new sample as reboot-stale for as long
        // as enforcement happens to match the pre-reboot value.
        assertTrue(
            shouldRestamp(existing = snapshot(), next = snapshot(evalBootCount = 43), nowUtc = nowUtc),
        )
    }

    // --- the arbiter mapping (§16 Q2, user ruling 2026-09-16) ---

    @Test
    fun fullAuthority_isActive() {
        assertEquals(EnforcementStatus.ACTIVE, ProtectionAuthority.FULL.toEnforcementStatus())
    }

    @Test
    fun pollingOnlyAuthority_isActive() {
        // Blocks genuinely run under the polling path; UNVERIFIED here would tell a user with
        // working blocks that something is broken.
        assertEquals(
            EnforcementStatus.ACTIVE,
            ProtectionAuthority.DEGRADED_POLLING_ONLY.toEnforcementStatus(),
        )
    }

    @Test
    fun noDetectionAuthority_isInactive() {
        // Includes the master switch being off, which is what the spec's policy=INACTIVE row
        // asks to render as UNPROTECTED.
        assertEquals(
            EnforcementStatus.INACTIVE,
            ProtectionAuthority.DEGRADED_NO_DETECTION.toEnforcementStatus(),
        )
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
