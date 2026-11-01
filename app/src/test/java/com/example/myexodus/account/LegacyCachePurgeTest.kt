package com.example.myexodus.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The legacy purge terminates the Firestore client, and the SDK rebuilds it with production
 * settings — the 2026-10-31 two-AVD rig failure (every pairing-token read denied after the
 * first launch of a fresh install). The debug re-wire hook must run after every attempted
 * purge, including one that fails, and must not run when there is nothing to purge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyCachePurgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs
        get() = context.getSharedPreferences(LegacyCachePurge.PREFS, Context.MODE_PRIVATE)

    @After
    fun clearHook() {
        LegacyCachePurge.backendReapply = null
        prefs.edit().clear().commit()
    }

    @Test
    fun reapplyHook_runsAfterAnAttemptedPurge_evenWhenThePurgeFails() {
        prefs.edit().clear().commit()
        val seen = mutableListOf<Context>()
        LegacyCachePurge.backendReapply = { seen += it }

        // Robolectric has no FirebaseApp, so the purge cannot terminate anything and reports
        // false — the path that still leaves a client (possibly a terminated one) behind.
        assertFalse(LegacyCachePurge.run(context))

        assertEquals(listOf(context), seen)
        assertFalse(
            "a failed purge must keep retrying on the next launch",
            prefs.getBoolean(LegacyCachePurge.KEY_LEGACY_CACHE_PURGED, false),
        )
    }

    @Test
    fun reapplyHook_isNotRunWhenThereIsNothingToPurge() {
        prefs.edit().putBoolean(LegacyCachePurge.KEY_LEGACY_CACHE_PURGED, true).commit()
        LegacyCachePurge.backendReapply = {
            error("nothing was terminated, so no client needs re-wiring")
        }

        assertTrue(LegacyCachePurge.run(context))
    }
}
