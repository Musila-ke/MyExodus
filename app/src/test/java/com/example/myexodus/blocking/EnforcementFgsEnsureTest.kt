package com.example.myexodus.blocking

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * ADR #152: [EnforcementForegroundService.ensureOnProcessStart] is the process-start re-arm,
 * called from MyExodusApp.onCreate on every cold start so an XOS kill ("prcp FGS";
 * START_STICKY ignored, 2026-10-15) can never leave enforcement down past the next platform
 * wake. The X6532 kill legs are the device evidence for the full chain; this pins the start
 * itself — deleting it re-breaks the interruption windows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnforcementFgsEnsureTest {

    @Test
    fun ensureOnProcessStart_startsTheFgs() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        EnforcementForegroundService.ensureOnProcessStart(ctx)
        val shadow: ShadowApplication = shadowOf(ctx)
        assertEquals(
            EnforcementForegroundService::class.java.name,
            shadow.nextStartedService?.component?.className,
        )
    }
}
