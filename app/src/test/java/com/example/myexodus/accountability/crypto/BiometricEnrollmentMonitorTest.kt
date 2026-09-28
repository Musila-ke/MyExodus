package com.example.myexodus.accountability.crypto

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §8.2 commit discipline (task #68): the first check is a baseline, never a change; a detected
 * change stays pending — and therefore retryable — until [BiometricEnrollmentMonitor
 * .commitEnrollmentState] is called after a successful migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BiometricEnrollmentMonitorTest {

    private lateinit var context: Context
    private lateinit var monitor: BiometricEnrollmentMonitor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        monitor = BiometricEnrollmentMonitor(context)
    }

    /** Writing the stored value directly is the point: it stands in for a previous commit. */
    private fun store(value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, value).commit()
    }

    @Test
    fun firstRun_isBaselineNeverAChange() {
        // Nothing committed yet — an upgrade must not rotate an existing identity.
        assertFalse(monitor.enrollmentStateChanged())
    }

    @Test
    fun commitThenCheck_sameState_isNotAChange() {
        monitor.commitEnrollmentState()
        assertFalse(monitor.enrollmentStateChanged())
    }

    @Test
    fun storedStateDiffersFromLive_isAChange_untilCommitted() {
        store("BIOMETRIC_ERROR_NONE_ENROLLED|false")
        assertTrue(monitor.enrollmentStateChanged())
        // Detection must not commit: a failed migration stays pending and retries.
        assertTrue(monitor.enrollmentStateChanged())
        monitor.commitEnrollmentState()
        assertFalse(monitor.enrollmentStateChanged())
    }

    private companion object {
        const val PREFS = "biometric_enrollment_state"
        const val KEY = "enrollment_state"
    }
}
