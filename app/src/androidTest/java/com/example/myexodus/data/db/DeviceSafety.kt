package com.example.myexodus.data.db

import android.os.Build
import org.junit.Assume.assumeTrue

/**
 * The suites in this package call `RecoveryManager.resetAllData()`, which deletes the rules
 * store, the legacy/activity databases and the wrapped key material at their *production*
 * paths — the app's own data directory, which the instrumentation process shares. On a handset
 * that is the user's live data: a run on the X6532 destroyed the owner's store, keys and
 * pairing identity on 2026-09-28.
 *
 * They are emulator-only fixtures. Device-side verification is served by
 * [AppRulesStoreMigrationInstrumentedTest] (its own throwaway database file, safe anywhere) and
 * by the debug seams on the real install.
 */
internal fun assumeEmulatorOnly() = assumeTrue(
    "destructive rules-store fixture: emulator only — it deletes the production store, keys " +
        "and legacy files of whatever device it runs on (DeviceSafety.kt)",
    IS_EMULATOR,
)

private val IS_EMULATOR =
    Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
        Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
        Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true) ||
        Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
        Build.HARDWARE.contains("goldfish", ignoreCase = true)
