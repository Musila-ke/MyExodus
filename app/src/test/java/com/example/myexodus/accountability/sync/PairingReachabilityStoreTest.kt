package com.example.myexodus.accountability.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OPD #30(b): the denied-pairing flag's contract — first denial wins (the card reports "since"),
 * any successful read clears it, flags for pairings the device no longer holds are pruned — and
 * the classifier that keeps an offline pass from ever raising the re-pair card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingReachabilityStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store() = PairingReachabilityStore(context)

    @Test
    fun deniedAt_isNullUntilADenialIsRecorded() {
        assertNull(store().deniedAt("pair-1"))
    }

    @Test
    fun markDenied_recordsTheFirstTimestampAndLaterDenialsDoNotMoveIt() {
        val store = store()
        store.markDenied("pair-1", 1_000L)
        assertEquals(1_000L, store.deniedAt("pair-1"))

        store.markDenied("pair-1", 2_000L)
        assertEquals(1_000L, store.deniedAt("pair-1"))
    }

    @Test
    fun markReachable_clearsOnlyTheNamedPairing() {
        val store = store()
        store.markDenied("pair-1", 1_000L)
        store.markDenied("pair-2", 1_000L)

        store.markReachable("pair-1")

        assertNull(store.deniedAt("pair-1"))
        assertEquals(1_000L, store.deniedAt("pair-2"))
    }

    @Test
    fun prune_dropsThePairingsNoLongerEnrolled() {
        val store = store()
        store.markDenied("pair-gone", 1_000L)
        store.markDenied("pair-live", 1_000L)

        store.prune(setOf("pair-live"))

        assertNull(store.deniedAt("pair-gone"))
        assertEquals(1_000L, store.deniedAt("pair-live"))
    }

    @Test
    fun theFlagOutlivesTheProcessThatRecordedIt() {
        store().markDenied("pair-1", 1_000L)

        assertEquals(1_000L, store().deniedAt("pair-1"))
    }

    @Test
    fun onlyAServerRulesDenialQualifies() {
        assertTrue(isDefinitiveDenial(FirebaseFirestoreException.Code.PERMISSION_DENIED))

        // Everything a missing connection can produce must never raise the re-pair card —
        // the card is an accusation that the pairing is over.
        assertFalse(isDefinitiveDenial(FirebaseFirestoreException.Code.UNAVAILABLE))
        assertFalse(isDefinitiveDenial(FirebaseFirestoreException.Code.DEADLINE_EXCEEDED))
        assertFalse(isDefinitiveDenial(FirebaseFirestoreException.Code.NOT_FOUND))
        assertFalse(isDefinitiveDenial(null))
    }

    @Test
    fun aDenialNeedsTheRulesAnswer_andABuildThatCanStillAttest() {
        val denial = FirebaseFirestoreException(
            "Missing or insufficient permissions.",
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
        )
        assertTrue(shouldRecordDenial(denial, canAttest = true))

        // App Check enforcement surfaces as the same PERMISSION_DENIED: that one is about the
        // build, not the pairing, and re-pairing could not clear it.
        assertFalse(shouldRecordDenial(denial, canAttest = false))
        assertFalse(
            shouldRecordDenial(
                FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE),
                canAttest = true,
            ),
        )
        assertFalse(shouldRecordDenial(null, canAttest = true))
        assertFalse(shouldRecordDenial(IllegalStateException("not a Firestore answer"), true))
    }

    @Test
    fun onlyAServerAnswerClears_theCachedCopyIsNot() {
        assertTrue(shouldClearDenial(present = true, fromCache = false))

        // An offline pass served from cache must not blink the card off.
        assertFalse(shouldClearDenial(present = true, fromCache = true))
        assertFalse(shouldClearDenial(present = false, fromCache = false))
    }
}
