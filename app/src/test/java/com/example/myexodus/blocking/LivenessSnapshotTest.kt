package com.example.myexodus.blocking

import com.example.myexodus.accountability.monitoring.MonitoringLiveness
import com.example.myexodus.accountability.monitoring.monitoringState
import com.example.myexodus.accountability.serialization.MonitoringState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §7 Step 7 / §8.13 line 470 — what a device may truthfully claim about its own monitoring.
 * The two rules that matter: alive is not observing (a stale stamp is UNKNOWN), and no branch
 * may claim ACTIVE without a confirmed FULL authority.
 */
class LivenessSnapshotTest {

    private val now = 1_000_000L

    @Test
    fun noServiceInstance_isUnavailable() {
        assertEquals(
            MonitoringLiveness.ServiceAbsent,
            resolveLiveness(running = false, lastConfirmedElapsedMs = now, authority = ProtectionAuthority.FULL, nowElapsedMs = now),
        )
    }

    @Test
    fun freshStamp_isConfirmedAtItsAuthority() {
        assertEquals(
            MonitoringLiveness.Confirmed(ProtectionAuthority.FULL),
            resolveLiveness(true, now - 89_999, ProtectionAuthority.FULL, now),
        )
        assertEquals(
            "a stamp exactly one window old is no longer observing",
            MonitoringLiveness.Unverifiable,
            resolveLiveness(true, now - EnforcementForegroundService.LIVENESS_FRESH_MS, ProtectionAuthority.FULL, now),
        )
    }

    @Test
    fun aliveButNeverConfirmed_isUnverifiable() {
        // onCreate marks the service alive with a zero stamp; the first status refresh has not
        // happened yet, so the only honest answer is UNKNOWN.
        assertEquals(
            MonitoringLiveness.Unverifiable,
            resolveLiveness(true, lastConfirmedElapsedMs = 0L, ProtectionAuthority.DEGRADED_NO_DETECTION, now),
        )
    }

    @Test
    fun wireMapping_isTruthfulForEveryBranch() {
        assertEquals(
            MonitoringState.ACTIVE,
            MonitoringLiveness.Confirmed(ProtectionAuthority.FULL).monitoringState(),
        )
        assertEquals(
            MonitoringState.DEGRADED,
            MonitoringLiveness.Confirmed(ProtectionAuthority.DEGRADED_POLLING_ONLY).monitoringState(),
        )
        assertEquals(
            MonitoringState.UNAVAILABLE,
            MonitoringLiveness.Confirmed(ProtectionAuthority.DEGRADED_NO_DETECTION).monitoringState(),
        )
        assertEquals(MonitoringState.UNAVAILABLE, MonitoringLiveness.ServiceAbsent.monitoringState())
        assertEquals(MonitoringState.UNKNOWN, MonitoringLiveness.Unverifiable.monitoringState())
    }
}
