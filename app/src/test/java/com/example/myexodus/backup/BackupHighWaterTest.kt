package com.example.myexodus.backup

import com.example.myexodus.backup.BackupHighWater.Advance
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Account plan §6.4 item 13 / §6.6 item 3: the client-side rollback/equivocation guard. A
 * server that replays an older snapshot, or answers the same revision with different bytes,
 * must be rejected by this device's own high-water anchor — including during replacement.
 */
class BackupHighWaterTest {

    private val first = BackupPair(3, "sha-3")
    private val second = BackupPair(3, "sha-other")

    @Test
    fun firstSightingIsAcceptedAndBecomesTheHighWater() {
        assertEquals(Advance.Accepted(first), BackupHighWater.advance(null, first))
    }

    @Test
    fun higherRevisionAdvancesTheHighWater() {
        assertEquals(
            Advance.Accepted(BackupPair(4, "sha-4")),
            BackupHighWater.advance(first, BackupPair(4, "sha-4")),
        )
    }

    @Test
    fun lowerRevisionIsARollbackEvenWhenEverythingElseIsValid() {
        assertEquals(Advance.Rollback, BackupHighWater.advance(first, BackupPair(2, "sha-2")))
    }

    @Test
    fun sameRevisionWithDifferentBytesIsEquivocation() {
        assertEquals(Advance.Equivocation, BackupHighWater.advance(first, second))
    }

    @Test
    fun identicalPairKeepsTheExistingHighWater() {
        // Equal revisions with equal hashes are the same snapshot, not a new sighting.
        assertEquals(Advance.Accepted(first), BackupHighWater.advance(first, BackupPair(3, "sha-3")))
    }
}
