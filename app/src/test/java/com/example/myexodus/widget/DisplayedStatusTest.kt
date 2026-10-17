package com.example.myexodus.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The governing rule of the widget (spec §1): PROTECTED is only renderable from a sample the app
 * can confirm is fresh, correctly clocked, and valid since the last reboot. Every case below is a
 * way that confirmation fails — the point of each is that it must not land on PROTECTED.
 *
 * Pure JVM: [displayedStatusFor] takes every clock as an argument, so skew and reboot are
 * reproducible here instead of relying on the device's real time (spec §12).
 */
class DisplayedStatusTest {

    private val nowUtc = 1_800_000_000_000L
    private val nowElapsed = 500_000L
    private val nowBootCount = 42

    /** A sample one minute old that is consistent with the clocks passed to [status]. */
    private fun snapshot(
        enforcement: EnforcementStatus = EnforcementStatus.ACTIVE,
        evalUtc: Long = nowUtc - MINUTE,
        evalElapsed: Long = nowElapsed - MINUTE,
        evalBootCount: Int = nowBootCount,
    ) = ProtectionSnapshot(
        enforcement = enforcement,
        streakDays = 12,
        habitLabel = null,
        habitId = null,
        discreet = true,
        evalUtc = evalUtc,
        evalElapsed = evalElapsed,
        evalBootCount = evalBootCount,
    )

    private fun status(snapshot: ProtectionSnapshot?) =
        displayedStatusFor(snapshot, nowUtc, nowElapsed, nowBootCount)

    // --- no usable sample at all ---

    @Test
    fun missingSnapshot_isUnverified() {
        assertEquals(DisplayedStatus.UNVERIFIED, status(null))
    }

    // --- validity since reboot ---

    @Test
    fun rebootedSinceTheSample_isUnverified() {
        assertEquals(
            DisplayedStatus.UNVERIFIED,
            status(snapshot(evalBootCount = nowBootCount - 1)),
        )
    }

    @Test
    fun unreadableBootCountOnEitherSide_isUnverified() {
        // Sentinel vs. real count: they differ, so the sample cannot be confirmed (fail closed).
        assertEquals(
            DisplayedStatus.UNVERIFIED,
            status(snapshot(evalBootCount = UNSET_BOOT_COUNT)),
        )
    }

    // --- clock integrity ---

    @Test
    fun clockSetBackAnHour_isUnverified() {
        // UTC says an hour ago, monotonic time says the sample was just taken: an NTP correction
        // this large is exactly what the skew check exists to refuse.
        assertEquals(
            DisplayedStatus.UNVERIFIED,
            status(snapshot(evalUtc = nowUtc + HOUR, evalElapsed = nowElapsed)),
        )
    }

    @Test
    fun skewBeyondTolerance_isUnverified() {
        // Both clocks advanced, but at rates 9 minutes apart — past the 5-minute NTP tolerance.
        assertEquals(
            DisplayedStatus.UNVERIFIED,
            status(snapshot(evalUtc = nowUtc - MINUTE, evalElapsed = nowElapsed - 10 * MINUTE)),
        )
    }

    @Test
    fun skewWithinTolerance_stillReportsEnforcement() {
        // 4 minutes apart: real-world NTP drift, which the tolerance is sized for (spec §5 6′).
        assertEquals(
            DisplayedStatus.PROTECTED,
            status(snapshot(evalUtc = nowUtc - MINUTE, evalElapsed = nowElapsed - 5 * MINUTE)),
        )
    }

    // --- freshness ---

    @Test
    fun olderThanTheFreshnessCeiling_isUnverified() {
        // Both clocks agree the sample is old, so only the ceiling can be what rejects it.
        assertEquals(
            DisplayedStatus.UNVERIFIED,
            status(agedSampleBy(STALE_CEILING + 1)),
        )
    }

    @Test
    fun exactlyAtTheFreshnessCeiling_isStillFresh() {
        // The ceiling is "not older than", so the boundary itself must not degrade to UNVERIFIED.
        assertEquals(DisplayedStatus.PROTECTED, status(agedSampleBy(STALE_CEILING)))
    }

    /** A sample [age] old in both clocks — the shape a live app actually writes, and a fresh one
     *  for anything under the ceiling. */
    private fun agedSampleBy(age: Long) =
        snapshot(evalUtc = nowUtc - age, evalElapsed = nowElapsed - age)

    // --- enforcement tri-state (FR3a) ---

    @Test
    fun active_isProtected() {
        assertEquals(DisplayedStatus.PROTECTED, status(snapshot()))
    }

    @Test
    fun inactive_isUnprotected() {
        assertEquals(
            DisplayedStatus.UNPROTECTED,
            status(snapshot(enforcement = EnforcementStatus.INACTIVE)),
        )
    }

    @Test
    fun unknownEnforcement_isUnverified_neverUnprotected() {
        // The whole reason enforcement is a tri-state: "I can't tell" must not read as
        // "protection is off", which the user would act on.
        assertEquals(
            DisplayedStatus.UNVERIFIED,
            status(snapshot(enforcement = EnforcementStatus.UNKNOWN)),
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val STALE_CEILING = 30 * MINUTE
    }
}
