package com.example.myexodus.accountability.sync

import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// FirebaseFirestoreException's static init needs an Android runtime, hence Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirestoreTaskDenialTest {

    @Test
    fun onlyPermissionDeniedIsARulesRejection() {
        val denied = FirebaseFirestoreException(
            "rules",
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
        )
        val unavailable = FirebaseFirestoreException(
            "transport",
            FirebaseFirestoreException.Code.UNAVAILABLE,
        )
        assertTrue(isRulesDenial(denied))
        assertFalse(isRulesDenial(unavailable))
    }

    @Test
    fun eventNameIsPinnedForTheCrashlyticsRail() {
        assertEquals("firestore_denied", FIRESTORE_DENIED_EVENT)
    }
}
