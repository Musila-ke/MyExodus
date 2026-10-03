package com.example.myexodus.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
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
 * SOS §12.3 bullet 10's system-read half: `NotificationAccess.canPost` is the wire between the
 * OS notification state and every post gate / the check-ins-off banner. Skins differ in whether
 * that OS state is drivable from a test harness at all — the X6532/XOS build silently resets an
 * `appops set … POST_NOTIFICATION deny` to default-allow (rc=0, no override stored), so the
 * mapping is pinned here on the platform shadow, and the device leg pins the render path with
 * the same boolean.
 *
 * The pre-33 branch is load-bearing: POST_NOTIFICATIONS does not exist below 33, so a raw
 * checkSelfPermission there reports DENIED for every app — trusting it would make canPost
 * permanently false on Android 12 and below (no notification ever posts, banner always on).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationAccessTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    @Test
    fun permissionDenied_isFalse() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(NotificationAccess.canPost(context))
    }

    @Test
    fun appLevelToggleOff_isFalse_evenWithPermissionGranted() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(NotificationAccess.canPost(context))
    }

    @Test
    fun grantedAndEnabled_isTrue() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(NotificationAccess.canPost(context))
    }

    @Test
    @Config(sdk = [32])
    fun pre33_ignoresTheNonexistentPermission_andFollowsTheToggle() {
        // On a real pre-33 device the permission is unknown, so checkSelfPermission reports
        // DENIED even though the app can post — the read must be areNotificationsEnabled alone.
        assertFalse(
            "precondition: the permission must read as not-granted on pre-33",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
        assertTrue(NotificationAccess.canPost(context))

        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(NotificationAccess.canPost(context))
    }
}
