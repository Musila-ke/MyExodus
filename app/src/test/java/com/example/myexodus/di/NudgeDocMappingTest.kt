package com.example.myexodus.di

import com.google.firebase.Timestamp
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Finding #64: the nudge document mapping advertised "a wrong-typed field degrades to null",
 * but used `DocumentSnapshot.getTimestamp`, whose SDK implementation throws
 * `RuntimeException("Field '...' is not a Timestamp")` on a wrong-typed value (verified against
 * firebase-firestore 26.6.0 bytecode). The mapping runs inside a snapshot listener, so that
 * throw would reach the main-thread executor uncaught and kill the process instead of the
 * repository's documented unreadable-document path.
 */
class NudgeDocMappingTest {

    @Test
    fun wrongTypedExpiry_degradesToNull() {
        assertNull(nudgeExpiryMillisOf("2026-10-17T00:00:00Z"))
        assertNull(nudgeExpiryMillisOf(1_700_000_000_000L))
        assertNull(nudgeExpiryMillisOf(mapOf("seconds" to 1)))
    }

    @Test
    fun missingExpiry_isNull() {
        assertNull(nudgeExpiryMillisOf(null))
    }

    @Test
    fun timestampExpiry_readsMillis() {
        assertEquals(1_700_000_000_000L, nudgeExpiryMillisOf(Timestamp(Date(1_700_000_000_000L))))
    }
}
