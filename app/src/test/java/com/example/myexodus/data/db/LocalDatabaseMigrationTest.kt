package com.example.myexodus.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §8.1: filenames come only from a stable hash of the UID, never from input. */
class LocalDatabaseMigrationTest {

    @Test
    fun uidHashIsStableOpaqueAndFilenameSafe() {
        val first = LocalDatabaseMigration.uidHash("guest-uid-1")
        assertEquals(
            "the hash must survive process restarts or the scoped files move",
            first,
            LocalDatabaseMigration.uidHash("guest-uid-1"),
        )
        assertEquals(32, first.length)
        assertTrue("filename-safe lowercase hex only: $first", first.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(first, LocalDatabaseMigration.uidHash("guest-uid-2"))
    }

    @Test
    fun scopedNamesAndKeySuffixAreDerivedFromTheHash() {
        val hash = "0123456789abcdef0123456789abcdef"
        val scope = LocalDatabaseMigration.Scope(hash)
        assertEquals("myexodus_rules_$hash.db", scope.rulesName)
        assertEquals("myexodus_activity_$hash.db", scope.activityName)
        assertEquals("myexodus_accountability_$hash.db", scope.accountabilityName)
        assertEquals("_$hash", scope.keySuffix)
    }
}
