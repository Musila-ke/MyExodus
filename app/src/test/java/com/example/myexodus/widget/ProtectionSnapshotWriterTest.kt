package com.example.myexodus.widget

import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.streaks.WithdrawalSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        streakSample: StreakSample? = null,
        discreet: Boolean = true,
        evalUtc: Long = nowUtc - MINUTE,
        evalBootCount: Int = 42,
    ) = ProtectionSnapshot(
        enforcement = enforcement,
        streakDays = streakDays,
        habitLabel = habitLabel,
        streakSample = streakSample,
        discreet = discreet,
        evalUtc = evalUtc,
        evalElapsed = 500_000L,
        evalBootCount = evalBootCount,
    )

    // --- the cache proof: when the stored day count can no longer be trusted ---

    @Test
    fun freshInstall_needsRefresh() {
        // Never read: the first publish owes the store a read.
        assertTrue(needsStreakRefresh(existing = null, nowElapsed = 100_000L, nowBootCount = 42))
    }

    @Test
    fun recordWithoutAVerificationPair_needsRefresh() {
        // Written before the pair existed: it cannot prove anything, so it is re-read once.
        assertTrue(
            needsStreakRefresh(
                existing = snapshot(streakSample = null),
                nowElapsed = 100_000L,
                nowBootCount = 42,
            ),
        )
    }

    @Test
    fun noStreak_needsNoRefresh() {
        // No habit set up: nothing to keep current. One arriving later comes through the state
        // flow, which only exists in-process.
        assertFalse(
            needsStreakRefresh(
                existing = snapshot(streakDays = null),
                nowElapsed = 10 * DAY,
                nowBootCount = 42,
            ),
        )
    }

    @Test
    fun sampleInsideTheSameDay_needsNoRefresh() {
        // Read 3h into day 3, asked 2h later: the accumulator cannot have crossed day 4 yet —
        // that is the whole point of caching the pair instead of re-reading every heartbeat.
        assertFalse(
            needsStreakRefresh(
                existing = snapshot(streakSample = sample(elapsedMs = 3 * DAY + 3 * HOUR)),
                nowElapsed = 100_000L + 2 * HOUR,
                nowBootCount = 42,
            ),
        )
    }

    @Test
    fun samplePastTheNextDayBoundary_needsRefresh() {
        assertTrue(
            needsStreakRefresh(
                existing = snapshot(streakSample = sample(elapsedMs = 3 * DAY + 3 * HOUR)),
                nowElapsed = 100_000L + (DAY - 3 * HOUR) + 1,
                nowBootCount = 42,
            ),
        )
    }

    @Test
    fun sampleExactlyAtTheBoundary_needsRefresh() {
        // At the boundary the day count may already be the next one, so it is re-read, not assumed.
        assertTrue(
            needsStreakRefresh(
                existing = snapshot(streakSample = sample(elapsedMs = 3 * DAY + 3 * HOUR)),
                nowElapsed = 100_000L + (DAY - 3 * HOUR),
                nowBootCount = 42,
            ),
        )
    }

    @Test
    fun rebootSinceTheReading_needsRefresh() {
        // Even a reading from a minute ago proves nothing across a reboot (spec Decision 6′).
        assertTrue(
            needsStreakRefresh(
                existing = snapshot(streakSample = sample(verifiedBoot = 42)),
                nowElapsed = 100_000L + MINUTE,
                nowBootCount = 43,
            ),
        )
    }

    @Test
    fun unreadableBootCountThatDiffers_needsRefresh() {
        // One side readable and the other not is still a mismatch: fail closed, read again.
        assertTrue(
            needsStreakRefresh(
                existing = snapshot(streakSample = sample(verifiedBoot = UNSET_BOOT_COUNT)),
                nowElapsed = 100_000L + MINUTE,
                nowBootCount = 43,
            ),
        )
    }

    @Test
    fun elapsedRegression_needsRefresh() {
        // A reboot the boot count could not see (both unreadable): elapsed going backwards is the
        // remaining signal, and it must not be read as "not due yet".
        assertTrue(
            needsStreakRefresh(
                existing = snapshot(
                    streakSample = sample(verifiedBoot = UNSET_BOOT_COUNT, verifiedElapsed = 900_000L),
                ),
                nowElapsed = 5_000L,
                nowBootCount = UNSET_BOOT_COUNT,
            ),
        )
    }

    // --- the "No streak yet" flicker: a null that is not an answer ---

    @Test
    fun unresolvedState_carriesTheStoredStreak() {
        // Process start: `state` is null until the first read lands. Publishing that null is what
        // flashed "No streak yet" over a real streak — it must carry the stored record instead.
        val stored = snapshot(streakDays = 12, streakSample = sample())
        val fields = streakFieldsFor(state = null, resolved = false, existing = stored, sample = null)
        assertEquals(12, fields?.days)
        assertEquals("No smoking", fields?.label)
        assertEquals(stored.streakSample, fields?.sample)
    }

    @Test
    fun unresolvedFirstRun_publishesNothing() {
        val fields = streakFieldsFor(state = null, resolved = false, existing = null, sample = null)
        assertNull(fields)
    }

    @Test
    fun resolvedWithoutAHabit_clearsTheStreak() {
        // A read that came back "no habit is set up" IS an answer, and must reach the widget.
        val fields = streakFieldsFor(
            state = null,
            resolved = true,
            existing = snapshot(streakDays = 12),
            sample = null,
        )
        assertNull(fields)
    }

    @Test
    fun liveState_publishesTheReadWithItsSample() {
        val read = sample(elapsedMs = 5 * DAY)
        val fields = streakFieldsFor(
            state = habitState(currentDay = 5),
            resolved = true,
            existing = snapshot(streakDays = 4),
            sample = read,
        )
        assertEquals(5, fields?.days)
        assertEquals(read, fields?.sample)
    }

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

    private fun sample(
        elapsedMs: Long = 3 * DAY,
        verifiedElapsed: Long = 100_000L,
        verifiedBoot: Int = 42,
    ) = StreakSample(
        elapsedMs = elapsedMs,
        verifiedUtc = nowUtc,
        verifiedElapsed = verifiedElapsed,
        verifiedBoot = verifiedBoot,
    )

    private fun habitState(currentDay: Int) = HabitState(
        habitId = "habit-1",
        streakId = "streak-1",
        targetLabel = "No smoking",
        recoveryStartAt = nowUtc - currentDay * DAY,
        currentStreakElapsedMs = currentDay * DAY,
        allTimeMaxElapsedMs = currentDay * DAY,
        currentDay = currentDay,
        habitDurationYears = 1,
        severity = WithdrawalSeverity.MILD,
        withdrawalWeeks = emptyList(),
        milestones = emptyList(),
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
