package com.example.myexodus.blocking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Finding #9 (2026-10-16): the protection-surface notifications share one NotificationManager,
 * so any id collision makes the later post evict the earlier alert — and an evicted one-shot
 * (the health monitor only notifies on a fresh enabled→disabled turn) never re-posts. This
 * pins the four ids against re-colliding: FGS 1001, degraded 1002, outage 1003, health 1004.
 * Scope: the protection surfaces only; partner (2xxx) and the domain-usage host are separate
 * families, distinct from these by construction.
 */
class ProtectionNotificationIdsTest {

    @Test
    fun protectionSurfaces_neverShareANotificationId() {
        val ids = mapOf(
            "FGS" to ProtectionNotifications.NOTIF_FGS,
            "degraded" to ProtectionNotifications.NOTIF_DEGRADED,
            "outage" to ProtectionNotifications.NOTIF_OUTAGE,
            "health" to EnhancedModeHealthMonitor.NOTIFICATION_ID,
        )
        assertEquals("notification ids must be pairwise distinct: $ids", 4, ids.values.toSet().size)
    }
}
