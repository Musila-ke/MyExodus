package com.example.myexodus.blocking

import android.app.Application
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fail-open semantics per plan §8 Step 3 / Decision 9 — before any overlay code exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EmergencyExclusionManagerTest {

    private var dialerRoles = mutableListOf<String>()
    private var emergencyRoles = mutableListOf<String>()
    private var dialerPackages = mutableSetOf<String>()
    private lateinit var manager: EmergencyExclusionManager

    @Before
    fun setUp() {
        manager = EmergencyExclusionManager(
            ApplicationProvider.getApplicationContext(),
            roleHolderQuery = { role ->
                when (role) {
                    "android.app.role.DIALER" -> dialerRoles
                    else -> emergencyRoles
                }
            },
            dialerQuery = { dialerPackages },
        )
    }

    @Test
    fun nullOrEmptyPackage_isProtected() {
        manager.refresh()
        assertTrue(manager.isProtected(null, null))
        assertTrue(manager.isProtected("", null))
        assertTrue(manager.isProtected("  ", null))
    }

    @Test
    fun liveDialerPackage_isProtected() {
        dialerPackages.add("com.google.android.dialer")
        manager.refresh()
        assertTrue(manager.isProtected("com.google.android.dialer", null))
        assertFalse(manager.isProtected("com.instagram.android", null))
    }

    @Test
    fun roleHolders_areProtected() {
        dialerRoles.add("com.google.android.dialer")
        emergencyRoles.add("com.emergency.app")
        manager.refresh()

        assertTrue(manager.isProtected("com.google.android.dialer", null))
        assertTrue(manager.isProtected("com.emergency.app", null))
        assertFalse(manager.isProtected("com.instagram.android", null))
    }

    @Test
    fun fallback_usedOnlyWhenLiveSourcesEmpty() {
        // Live dialer known: fallback packages are NOT protected on their own.
        dialerPackages.add("com.google.android.dialer")
        manager.refresh()
        assertFalse(manager.isProtected("com.android.dialer", null))
        assertFalse(manager.isProtected("com.transsion.incallui", null))

        // All live sources empty (no dialer configured, roles unpopulated): fallback kicks in.
        dialerPackages.clear()
        dialerRoles.clear()
        emergencyRoles.clear()
        manager.refresh()
        assertTrue(manager.isProtected("com.android.dialer", null))
        assertTrue(manager.isProtected("com.transsion.incallui", null))
        assertTrue(manager.isProtected("com.google.android.dialer", null))
        assertFalse(manager.isProtected("com.instagram.android", null))
    }

    @Test
    fun refresh_afterDialerChange_repopulatesSets() {
        manager.refresh()
        assertFalse(manager.isProtected("com.instagram.android", null))

        dialerPackages.add("com.samsung.android.dialer")
        manager.refresh()
        assertTrue(manager.isProtected("com.samsung.android.dialer", null))
    }

    @Test
    fun systemAndAccessibilityOverlayWindows_areProtectedRegardlessOfPackage() {
        manager.refresh()
        assertTrue(manager.isProtectedWindowType(AccessibilityWindowInfo.TYPE_SYSTEM, null))
        // 4 = TYPE_ACCESSIBILITY_OVERLAY. Real keyguard windows surface as TYPE_SYSTEM; the
        // overlay type stays protected because OEMs misreport window types (fail-open).
        assertTrue("Type 4 windows must stay protected",
            manager.isProtectedWindowType(4, null))
    }

    @Test
    fun callUiTitleMarkers_protectOnApi30Plus_forNonApplicationWindowsOnly() {
        // App windows fully control their own titles — trusting a call-UI marker on one
        // would let a hostile app exempt itself by faking an "Incoming call" title.
        assertFalse(manager.isProtectedWindowType(AccessibilityWindowInfo.TYPE_APPLICATION, "Incoming call — Google Phone"))
        assertFalse(manager.isProtectedWindowType(AccessibilityWindowInfo.TYPE_APPLICATION, "In-Call UI"))
        assertFalse(manager.isProtectedWindowType(AccessibilityWindowInfo.TYPE_APPLICATION, "Keyguard"))
        assertFalse(manager.isProtectedWindowType(AccessibilityWindowInfo.TYPE_APPLICATION, "Instagram"))
        assertFalse(manager.isProtectedWindowType(AccessibilityWindowInfo.TYPE_APPLICATION, null))
        // Non-application windows (OEM-misreported system/call UI) still get the markers.
        assertTrue(manager.isProtectedWindowType(AccessibilityWindowInfo.TYPE_SYSTEM, "Incoming call — Google Phone"))
    }

    @Test
    fun ordinaryAppWindow_isNotProtected() {
        manager.refresh()
        assertFalse(manager.isProtectedWindowType(AccessibilityWindowInfo.TYPE_APPLICATION, "Instagram"))
        assertFalse(manager.isProtectedWindowType(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null))
    }
}
