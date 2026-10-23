package com.example.myexodus.settings

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.blocking.BlockerAccessibilityService
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

    @Test
    fun enabledAccessibilityList_matchesShortAndFullyQualifiedForms() {
        val pkg = context.packageName
        val cls = BlockerAccessibilityService::class.java.name

        assertTrue(enabledAccessibilityServicesContain("$pkg/.blocking.BlockerAccessibilityService", pkg, cls))
        assertTrue(enabledAccessibilityServicesContain("$pkg/$cls", pkg, cls))
        assertTrue(
            "a service in the middle of the list still counts",
            enabledAccessibilityServicesContain("other.pkg/OtherService:$pkg/$cls:third.pkg/Third", pkg, cls),
        )
        assertFalse("absent", enabledAccessibilityServicesContain(null, pkg, cls))
        assertFalse("empty", enabledAccessibilityServicesContain("", pkg, cls))
        assertFalse(
            "a different service in our package is not ours",
            enabledAccessibilityServicesContain("$pkg/.blocking.OtherService", pkg, cls),
        )
        assertFalse(
            "the same class name in another package is not ours",
            enabledAccessibilityServicesContain("other.pkg/$cls", pkg, cls),
        )
    }

    @Test
    fun isBlockerAccessibilityEnabled_readsThePlatformList() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            null,
        )
        assertFalse("no list entry means the service cannot bind", isBlockerAccessibilityEnabled(context))

        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/${BlockerAccessibilityService::class.java.name}",
        )
        assertTrue(isBlockerAccessibilityEnabled(context))
    }

    @Test
    fun isBlockerAccessibilityEnabled_masterSwitchOffWinsOverAListedService() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/${BlockerAccessibilityService::class.java.name}",
        )
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            "0",
        )
        assertFalse(
            "XOS clears the master switch after a crash while the list keeps the entry — the platform says nothing can bind",
            isBlockerAccessibilityEnabled(context),
        )

        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            "1",
        )
        assertTrue(isBlockerAccessibilityEnabled(context))
    }
}
