package com.example.myexodus.vpn

/**
 * Browser-UID classification for website metering (ADR #189). The device's default browser is
 * whatever holds [android.app.role.RoleManager.ROLE_BROWSER] (API 29+); the static set covers
 * secondary browsers, which the role cannot name — the owner's X6532 has both Chrome and Brave
 * installed, and only one of them can be the default. Deliberately package-scoped: apps that
 * merely handle http(s) links or open in-app WebViews are not browsers for metering, which is
 * the owner-selected semantic (the site's own app must not count).
 */
internal object BrowserPackages {

    private val KNOWN: Set<String> = hashSetOf(
        // Chromium family
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        // Firefox family
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix.nightly",
        "org.mozilla.focus",
        "org.mozilla.klar",
        // Opera family
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.gx",
        "com.opera.mini.native",
        "com.opera.mini.native.beta",
        // Other standalone browsers
        "com.duckduckgo.mobile.android",
        "com.ecosia.android",
        "com.yandex.browser",
        "com.UCMobile.intl",
        // AOSP and OEM browsers (the app targets every Android skin)
        "com.android.browser",
        "com.mi.globalbrowser",
        "com.miui.browser",
        "com.huawei.browser",
        "com.oppo.browser",
        "com.coloros.browser",
        "com.vivo.browser",
        "com.transsion.phoenix",
    )

    /**
     * True when any package sharing [uid] is a browser. A null package list (an unknown or
     * isolated UID) is not a browser: unresolvable owners are never counted.
     */
    fun isBrowser(packages: Array<String>?, roleHolders: Collection<String>): Boolean {
        if (packages == null) return false
        for (pkg in packages) {
            if (pkg in KNOWN || pkg in roleHolders) return true
        }
        return false
    }
}
