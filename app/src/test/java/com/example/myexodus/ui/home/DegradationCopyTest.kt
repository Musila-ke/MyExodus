package com.example.myexodus.ui.home

import android.app.Application
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.R
import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.blocking.ProtectionDegradation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B-09 (emulator stress, 2026-10-15): the degraded notification and the Home banner shipped the
 * same copy resource, and the shared text said "Open MyExodus…" — correct from the notification,
 * nonsense on the Home banner (the user is already in the app, with the Fix button right below
 * the message). The surfaces now carry their own variants; this pins the split: an in-app banner
 * never instructs the user to open the app, and the buttonless notification always does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DegradationCopyTest {

    private val resources: Resources =
        ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun homeBanner_neverTellsAnInAppUserToOpenTheApp() {
        val card = resources.getString(R.string.protection_paused_card_text)
        assertFalse(
            "Home banner copy must not tell an in-app user to open MyExodus: \"$card\"",
            card.contains("Open MyExodus"),
        )
        assertTrue(
            "The Home banner has the Fix button below it, so its copy must name the button: \"$card\"",
            card.contains("Fix"),
        )
    }

    @Test
    fun notification_keepsTheOpenAppInstruction() {
        val notification = resources.getString(R.string.protection_paused_text)
        assertTrue(
            "The notification has no button, so it must keep the open-app instruction: \"$notification\"",
            notification.contains("Open MyExodus"),
        )
    }

    /** Owner correction 2026-10-21: the Limited chip must carry its own cause and fix on Home —
     *  the state names exactly one mechanism, so the copy names it and the action turns it on. */
    @Test
    fun limitedExplainer_namesTheCauseAndTheFix() {
        val card = resources.getString(R.string.protection_limited_card_text)
        assertTrue("must name the mechanism: \"$card\"", card.contains("accessibility"))
        assertTrue("must name the fix: \"$card\"", card.contains("Turn it back on"))
        assertFalse("in-app copy must not say open the app: \"$card\"", card.contains("Open MyExodus"))
        assertEquals("Re-enable", resources.getString(R.string.protection_stopped_action))
    }

    /** Owner correction 2026-10-21: accessibility is one essential grant, not the only one —
     *  every other blocking essential carries the same cause-and-fix grammar on Home. */
    @Test
    fun everyEssentialFix_namesTheLossAndTheAction() {
        val rows = listOf(
            R.string.protection_overlay_off_title to R.string.protection_overlay_off_text,
            R.string.protection_usage_off_title to R.string.protection_usage_off_text,
            R.string.protection_website_off_title to R.string.protection_website_off_text,
        )
        for ((titleRes, bodyRes) in rows) {
            val title = resources.getString(titleRes)
            val body = resources.getString(bodyRes)
            assertTrue("title must state a loss: \"$title\"", title.contains("off", ignoreCase = true) ||
                title.contains("can’t", ignoreCase = true))
            assertTrue("body must name the remedy: \"$body\"", body.contains("Turn", ignoreCase = true) ||
                body.contains("Allow", ignoreCase = true))
            assertFalse("in-app copy must not say open the app: \"$body\"", body.contains("Open MyExodus"))
        }
        assertEquals("Turn on", resources.getString(R.string.protection_limited_action))
    }

    @Test
    fun readinessGap_isAnyEssentialMissing() {
        val all = BlockingReadiness(
            usageAccess = true,
            overlayGranted = true,
            websiteConsentMissing = false,
            accessibilityEnabledByPlatform = true,
        )
        assertFalse(all.essentialGap)
        assertTrue(all.copy(usageAccess = false).essentialGap)
        assertTrue(all.copy(overlayGranted = false).essentialGap)
        assertTrue(all.copy(websiteConsentMissing = true).essentialGap)
    }

    @Test
    fun fullAuthority_isLimitedWhileAnyEssentialIsMissing() {
        val all = BlockingReadiness(
            usageAccess = true,
            overlayGranted = true,
            websiteConsentMissing = false,
            accessibilityEnabledByPlatform = true,
        )
        assertFalse(
            "a complete install reads Protecting",
            limitedBeyondAuthority(ProtectionAuthority.FULL, all),
        )
        assertTrue(limitedBeyondAuthority(ProtectionAuthority.FULL, all.copy(overlayGranted = false)))
        assertTrue(limitedBeyondAuthority(ProtectionAuthority.FULL, all.copy(usageAccess = false)))
        assertTrue(limitedBeyondAuthority(ProtectionAuthority.FULL, all.copy(websiteConsentMissing = true)))
        // Other authorities keep their own verdicts; a missing grant cannot downgrade them
        // past what they already report, and an unread snapshot never flips the chip.
        assertFalse(limitedBeyondAuthority(ProtectionAuthority.DEGRADED_POLLING_ONLY, all.copy(overlayGranted = false)))
        assertFalse(limitedBeyondAuthority(ProtectionAuthority.DEGRADED_NO_DETECTION, null))
        assertFalse(limitedBeyondAuthority(ProtectionAuthority.FULL, null))
    }

    @Test
    fun usageAndOverlayFixes_belongToTheFullAuthorityOnly() {
        val usageGap = BlockingReadiness(
            usageAccess = false,
            overlayGranted = true,
            websiteConsentMissing = false,
            accessibilityEnabledByPlatform = true,
        )
        val overlayGap = BlockingReadiness(
            usageAccess = true,
            overlayGranted = false,
            websiteConsentMissing = false,
            accessibilityEnabledByPlatform = true,
        )
        assertTrue(showUsageFix(ProtectionAuthority.FULL, usageGap))
        assertFalse(showUsageFix(ProtectionAuthority.FULL, overlayGap))
        assertTrue(showOverlayFix(ProtectionAuthority.FULL, overlayGap))
        assertFalse(showOverlayFix(ProtectionAuthority.FULL, usageGap))
        // Polling-only necessarily holds both grants, and no-detection has its own row; a
        // repeated fix row there would name a mechanism that is not the one down.
        assertFalse(showUsageFix(ProtectionAuthority.DEGRADED_POLLING_ONLY, usageGap))
        assertFalse(showOverlayFix(ProtectionAuthority.DEGRADED_POLLING_ONLY, overlayGap))
        assertFalse(showUsageFix(ProtectionAuthority.DEGRADED_NO_DETECTION, usageGap))
        assertFalse(showOverlayFix(ProtectionAuthority.DEGRADED_NO_DETECTION, overlayGap))
        assertFalse(showUsageFix(null, usageGap))
        assertFalse(showOverlayFix(null, overlayGap))
    }

    @Test
    fun limitedExplainer_rendersOnlyForPersistentPollingOnly() {
        // The one showing state: polling-only, no visible degradation, past the rebind window.
        assertTrue(
            explainPollingOnly(
                ProtectionAuthority.DEGRADED_POLLING_ONLY,
                degradation = null,
                persistent = true,
            ),
        )
        // Inside the post-start rebind window: the state is transient, so nothing renders.
        assertFalse(
            explainPollingOnly(
                ProtectionAuthority.DEGRADED_POLLING_ONLY,
                degradation = null,
                persistent = false,
            ),
        )
        // Every other authority reads its own chip and must not borrow this row.
        for (authority in listOf(
            ProtectionAuthority.FULL,
            ProtectionAuthority.DEGRADED_NO_DETECTION,
            null,
        )) {
            assertFalse(
                "authority=$authority",
                explainPollingOnly(authority, degradation = null, persistent = true),
            )
        }
        // A visible degradation outranks the Limited row: the chip reads OFF and that state
        // owns the cause + fix (never two rows for one outage).
        for (degradation in ProtectionDegradation.entries) {
            assertFalse(
                "degradation=$degradation",
                explainPollingOnly(
                    ProtectionAuthority.DEGRADED_POLLING_ONLY,
                    degradation,
                    persistent = true,
                ),
            )
        }
    }

    @Test
    fun pollingOnly_settlesImmediatelyOnlyWhenTheEnabledListProvesTheServiceCannotBind() {
        // Owner report 2026-10-23: an absent enabled-list entry means no rebind can complete,
        // so the cause must show without waiting out the window.
        assertTrue(
            pollingOnlySettled(
                ProtectionAuthority.DEGRADED_POLLING_ONLY,
                degradation = null,
                accessibilityEnabledByPlatform = false,
                rebindWindowElapsed = false,
            ),
        )
        // Listed but not connected: this is the flappy rebind the grace exists for.
        assertFalse(
            pollingOnlySettled(
                ProtectionAuthority.DEGRADED_POLLING_ONLY,
                degradation = null,
                accessibilityEnabledByPlatform = true,
                rebindWindowElapsed = false,
            ),
        )
        // Not read yet (first frames) is not proof either way.
        assertFalse(
            pollingOnlySettled(
                ProtectionAuthority.DEGRADED_POLLING_ONLY,
                degradation = null,
                accessibilityEnabledByPlatform = null,
                rebindWindowElapsed = false,
            ),
        )
        // Listed and the window passed without a bind: also a real gap.
        assertTrue(
            pollingOnlySettled(
                ProtectionAuthority.DEGRADED_POLLING_ONLY,
                degradation = null,
                accessibilityEnabledByPlatform = true,
                rebindWindowElapsed = true,
            ),
        )
        // Other authorities and visible degradations read their own state, never this row.
        for (authority in listOf(
            ProtectionAuthority.FULL,
            ProtectionAuthority.DEGRADED_NO_DETECTION,
            null,
        )) {
            assertFalse(
                "authority=$authority",
                pollingOnlySettled(
                    authority,
                    degradation = null,
                    accessibilityEnabledByPlatform = false,
                    rebindWindowElapsed = true,
                ),
            )
        }
        for (degradation in ProtectionDegradation.entries) {
            assertFalse(
                "degradation=$degradation",
                pollingOnlySettled(
                    ProtectionAuthority.DEGRADED_POLLING_ONLY,
                    degradation,
                    accessibilityEnabledByPlatform = false,
                    rebindWindowElapsed = true,
                ),
            )
        }
    }
}
