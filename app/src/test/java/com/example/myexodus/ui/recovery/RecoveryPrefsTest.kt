package com.example.myexodus.ui.recovery

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan §E2.7 / §H2: the three assist preferences are local, optional, and resettable. The one
 * safety-critical reading is mobility: an unanswered question must stay unanswered, because the
 * engine only filters movement actions on an explicit "no" (unknown is never read as cannot move).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryPrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun mobility_roundTrips_andNotSureClearsBackToUnanswered() {
        val prefs = RecoveryPrefs(context)
        assertNull("nothing answered is not a no", prefs.canMove)

        prefs.canMove = false
        assertEquals(false, RecoveryPrefs(context).canMove)

        prefs.canMove = true
        assertEquals(true, RecoveryPrefs(context).canMove)

        prefs.canMove = null
        assertNull("Not sure must read the same as unanswered", RecoveryPrefs(context).canMove)
    }

    @Test
    fun dislikedActions_addAndClear_surviveANewInstance() {
        val prefs = RecoveryPrefs(context)
        prefs.markDisliked("breathing")
        prefs.markDisliked("walkthrough")

        assertEquals(setOf("breathing", "walkthrough"), RecoveryPrefs(context).dislikedActions)

        prefs.clearDisliked("breathing")
        assertEquals(setOf("walkthrough"), RecoveryPrefs(context).dislikedActions)
    }

    @Test
    fun quietPreference_defaultsOff_andPersists() {
        assertFalse(RecoveryPrefs(context).quietPreferred)

        RecoveryPrefs(context).quietPreferred = true
        assertTrue(RecoveryPrefs(context).quietPreferred)
    }
}
