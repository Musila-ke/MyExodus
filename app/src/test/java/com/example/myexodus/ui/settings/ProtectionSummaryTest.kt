package com.example.myexodus.ui.settings

import com.example.myexodus.settings.IntegrityStatus
import com.example.myexodus.settings.ProtectionChecks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Settings index says "N settings need attention" above a page of six status rows. That
 * sentence is a claim about what the user will find when they open it, so the count has to match
 * the rows that render an ATTENTION tone — too high is a false alarm, too low hides a broken
 * mechanism behind a reassuring number.
 */
class ProtectionSummaryTest {

    private fun checks(
        usageAccess: Boolean = true,
        batteryExempt: Boolean = true,
        overlayGranted: Boolean = true,
        integrity: IntegrityStatus = IntegrityStatus.VERIFIED,
    ) = ProtectionChecks(usageAccess, batteryExempt, overlayGranted, integrity)

    @Test
    fun `all mechanisms on counts zero`() {
        assertEquals(0, attentionCount(checks(), serviceEnabled = true))
    }

    @Test
    fun `each of the five mechanisms can be the one that fails`() {
        assertEquals(1, attentionCount(checks(), serviceEnabled = false))
        assertEquals(1, attentionCount(checks(usageAccess = false), serviceEnabled = true))
        assertEquals(1, attentionCount(checks(batteryExempt = false), serviceEnabled = true))
        assertEquals(1, attentionCount(checks(overlayGranted = false), serviceEnabled = true))
        assertEquals(1, attentionCount(checks(integrity = IntegrityStatus.FAILED), serviceEnabled = true))
    }

    @Test
    fun `unverified and unavailable integrity are informational, not attention`() {
        assertEquals(0, attentionCount(checks(integrity = IntegrityStatus.UNVERIFIED), serviceEnabled = true))
        assertEquals(0, attentionCount(checks(integrity = IntegrityStatus.UNAVAILABLE), serviceEnabled = true))
    }

    @Test
    fun `every failing mechanism is counted`() {
        val count = attentionCount(
            checks(
                usageAccess = false,
                batteryExempt = false,
                overlayGranted = false,
                integrity = IntegrityStatus.FAILED,
            ),
            serviceEnabled = false,
        )
        assertEquals(5, count)
    }
}
