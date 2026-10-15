package com.example.myexodus.di

import com.google.firebase.firestore.MemoryCacheSettings
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-10 (2026-10-15) pin: Firestore runs memory-only — with default persistence the SDK's local
 * SQLite hit ENOSPC and its internal RuntimeException killed the process (B7 disk-fill).
 */
class FirestoreCacheSettingsTest {

    @Test
    fun firestoreCacheIsMemoryOnly() {
        val settings = AccountabilityModule.memoryOnlyFirestoreSettings()
        assertTrue(settings.cacheSettings is MemoryCacheSettings)
    }
}
