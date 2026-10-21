package com.example.myexodus.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR #189: browser-UID classification — role holders, known secondaries, and the fail-safe. */
class BrowserPackagesTest {

    @Test
    fun theDefaultBrowserRoleHolderCountsEvenWhenUnlisted() {
        assertTrue(
            BrowserPackages.isBrowser(
                arrayOf("com.example.custom.browser"),
                listOf("com.example.custom.browser"),
            ),
        )
    }

    @Test
    fun aKnownSecondaryBrowserCountsWithoutHoldingTheRole() {
        assertTrue(BrowserPackages.isBrowser(arrayOf("com.brave.browser"), emptyList()))
    }

    @Test
    fun aUidSharingPackagesCountsWhenAnyOfThemIsABrowser() {
        assertTrue(
            BrowserPackages.isBrowser(
                arrayOf("com.example.shared", "com.android.chrome"),
                emptyList(),
            ),
        )
    }

    @Test
    fun theSitesOwnAppIsNotABrowser() {
        assertFalse(BrowserPackages.isBrowser(arrayOf("com.instagram.android"), emptyList()))
    }

    @Test
    fun anUnresolvableUidIsNeverABrowser() {
        assertFalse(BrowserPackages.isBrowser(null, listOf("com.android.chrome")))
    }
}
