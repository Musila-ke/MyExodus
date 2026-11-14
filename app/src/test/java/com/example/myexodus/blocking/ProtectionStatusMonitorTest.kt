package com.example.myexodus.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §8 Step 6's pure decision logic: the Decision 2 authority hierarchy and the one
 * user-visible degraded reason. Pure JVM — the platform probes, the transition notification,
 * and the telemetry line are device-verified in the §12 suite.
 */
class ProtectionStatusMonitorTest {

    // --- authority hierarchy (Decision 2) ---

    @Test
    fun a11yAlive_isFull_regardlessOfPollingPrerequisites() {
        // §9: Path A needs neither usage access nor SYSTEM_ALERT_WINDOW (TYPE_ACCESSIBILITY_OVERLAY).
        assertEquals(
            ProtectionAuthority.FULL,
            ProtectionStatusMonitor.authorityFor(a11yAlive = true, usageAccess = false, overlayGranted = false),
        )
        assertEquals(
            ProtectionAuthority.FULL,
            ProtectionStatusMonitor.authorityFor(a11yAlive = true, usageAccess = true, overlayGranted = true),
        )
    }

    @Test
    fun a11yUnavailable_withBothPrerequisites_isPollingOnly() {
        assertEquals(
            ProtectionAuthority.DEGRADED_POLLING_ONLY,
            ProtectionStatusMonitor.authorityFor(a11yAlive = false, usageAccess = true, overlayGranted = true),
        )
    }

    @Test
    fun a11yUnavailable_missingEitherPrerequisite_isNoDetection() {
        // Path B can't see apps without usage access, and can't render its wall without SAW —
        // either one down means nothing is enforced (§9).
        assertEquals(
            ProtectionAuthority.DEGRADED_NO_DETECTION,
            ProtectionStatusMonitor.authorityFor(a11yAlive = false, usageAccess = false, overlayGranted = true),
        )
        assertEquals(
            ProtectionAuthority.DEGRADED_NO_DETECTION,
            ProtectionStatusMonitor.authorityFor(a11yAlive = false, usageAccess = true, overlayGranted = false),
        )
    }

    // --- the single visible degraded reason ---

    @Test
    fun reenableRequirement_outranksEveryOtherReason() {
        assertEquals(
            ProtectionDegradation.APP_STOPPED,
            ProtectionStatusMonitor.visibleDegradation(
                requiresExplicitReEnable = true,
                authority = ProtectionAuthority.DEGRADED_NO_DETECTION,
                notificationsDenied = true,
            ),
        )
    }

    @Test
    fun noDetection_isVisible() {
        assertEquals(
            ProtectionDegradation.DEGRADED_NO_DETECTION,
            ProtectionStatusMonitor.visibleDegradation(
                requiresExplicitReEnable = false,
                authority = ProtectionAuthority.DEGRADED_NO_DETECTION,
                notificationsDenied = false,
            ),
        )
    }

    @Test
    fun pollingOnlyAndFull_areSilent() {
        // Blocking still runs under a polling-only authority (and Path A is the healthy case):
        // a "protection is paused" alert would be false in both.
        assertNull(
            ProtectionStatusMonitor.visibleDegradation(false, ProtectionAuthority.DEGRADED_POLLING_ONLY, false),
        )
        assertNull(ProtectionStatusMonitor.visibleDegradation(false, ProtectionAuthority.FULL, false))
    }

    @Test
    fun notificationsOff_isSoft() {
        assertEquals(
            ProtectionDegradation.NOTIFICATIONS_OFF,
            ProtectionStatusMonitor.visibleDegradation(false, ProtectionAuthority.FULL, notificationsDenied = true),
        )
    }
}
