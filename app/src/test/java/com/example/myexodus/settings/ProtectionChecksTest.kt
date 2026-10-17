package com.example.myexodus.settings

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Finding #59: the setup wizard asked whether notifications "work" and read only the API 33+
 * runtime grant. A user can keep POST_NOTIFICATIONS granted and switch the app's notifications
 * off, so the step claimed a heads-up that never arrives. The read must agree with the gate every
 * poster actually uses ([com.example.myexodus.notifications.NotificationAccess.canPost]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProtectionChecksTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    @Test
    fun permissionGrantedButAppToggleOff_readsNotGranted() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)

        assertFalse(
            "a granted permission with the app-level toggle off cannot deliver a warning",
            notificationsGranted(context),
        )
    }

    @Test
    fun grantedAndEnabled_readsGranted() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(notificationsGranted(context))
    }
}
