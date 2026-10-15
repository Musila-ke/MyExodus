package com.example.myexodus.ui.home

import android.app.Application
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.R
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
}
