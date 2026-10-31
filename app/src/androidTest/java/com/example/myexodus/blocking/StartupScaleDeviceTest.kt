package com.example.myexodus.blocking

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.data.db.IS_EMULATOR
import com.example.myexodus.data.db.entity.RestrictionRule
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup scale leg (task #12, user ask: "what if we have a lot of data"): seeds the product's
 * own designed maximum — RulesSnapshot.MAX_APP_RULES + MAX_URL_RULES = 2000 + 2000 (plan §8
 * Step 2) — directly through the DAO, bypassing the repository's cap check on purpose: the cap
 * IS the worst case the product permits, so this state is the legitimate ceiling. Live
 * enforcement state is untouched during seeding (no repo write → no snapshot republish).
 *
 * Deliberately NOT an [EnhancedModeDeviceSuite] subclass: its @Before requires a bound
 * accessibility service, and a process started by `am instrument` NEVER completes the a11y
 * bind on XOS (script note 2026-08-27; re-confirmed 2026-10-15 — 60 s of rebind cycling never
 * connected). This leg is pure DB work and needs no service, so it runs under plain
 * instrumentation. It also never resets data — insert is prefixed, cleanup deletes exactly
 * that prefix, and the physical-device data-loss scar tissue (2026-09-28/29) stays respected.
 *
 * Methods run individually (never together — the host capture needs a cold start with the
 * seeded DB, a state that cannot exist inside one instrumented run):
 *   am instrument -e class com.example.myexodus.blocking.StartupScaleDeviceTest#seedStressRules
 *   <host: cold-start capture>
 *   am instrument -e class com.example.myexodus.blocking.StartupScaleDeviceTest#cleanupStressRules
 */
@RunWith(AndroidJUnit4::class)
class StartupScaleDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val entryPoint: EnhancedModeTestEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            EnhancedModeTestEntryPoint::class.java,
        )
    }

    @Before
    fun physicalDeviceOnly() {
        // The legs are host-choreographed (seed -> cold-start capture -> cleanup, one method
        // per run) and need a persisted profile with baseline rules. A full-suite AVD run has
        // neither, so it must skip — never seed 4,000 rows into a disposable store and fail
        // the baseline assert.
        Assume.assumeFalse(
            "Startup-scale legs need the X6532 host capture; an AVD has no seeded baseline",
            IS_EMULATOR,
        )
    }

    @Test
    fun seedStressRules() = runTest {
        val dao = entryPoint.appRulesStoreFactory().open()!!.restrictionRuleDao()
        val profileId = checkNotNull(dao.getActiveRules().firstOrNull()?.profileId) {
            "no active profile with rules on device — seed a profile first"
        }
        val t0 = SystemClock.uptimeMillis()
        for (i in 0 until RulesSnapshot.MAX_APP_RULES) {
            dao.insert(rule("stress-a-$i", profileId, "com.stress.victim.$i", "APP"))
        }
        for (i in 0 until RulesSnapshot.MAX_URL_RULES) {
            dao.insert(rule("stress-u-$i", profileId, "stress-$i.example.com", "WEBSITE"))
        }
        val elapsed = SystemClock.uptimeMillis() - t0
        val total = dao.getActiveRules().size
        Log.i(TAG, "STRESS SEEDED ${RulesSnapshot.MAX_APP_RULES}+${RulesSnapshot.MAX_URL_RULES} " +
            "in ${elapsed}ms; active rules now $total")
    }

    @Test
    fun cleanupStressRules() = runTest {
        val dao = entryPoint.appRulesStoreFactory().open()!!.restrictionRuleDao()
        val t0 = SystemClock.uptimeMillis()
        var removed = 0
        for (r in dao.getActiveRules()) {
            if (r.ruleId.startsWith("stress-")) {
                dao.delete(r)
                removed++
            }
        }
        val remaining = dao.getActiveRules()
        Log.i(TAG, "STRESS CLEANUP removed=$removed in ${SystemClock.uptimeMillis() - t0}ms; " +
            "remaining=${remaining.size} ids=${remaining.map { it.ruleId }}")
        assertEquals(
            "no stress row may survive cleanup",
            0,
            remaining.count { it.ruleId.startsWith("stress-") },
        )
        assertTrue("baseline rules must be untouched", remaining.isNotEmpty())
    }

    private fun rule(id: String, profileId: String, blocked: String, type: String) =
        RestrictionRule(
            ruleId = id,
            profileId = profileId,
            blockedPackage = blocked,
            targetType = type,
            isAlwaysOn = true,
            dailyLimitMs = 0L,
            timeRangeStart = null,
            timeRangeEnd = null,
            activeDaysOfWeek = "0,1,2,3,4,5,6",
            blockExpiresAt = Long.MAX_VALUE,
            severityLevel = "HIGH",
        )

    private companion object {
        const val TAG = "MyExodusStartupStress"
    }
}
