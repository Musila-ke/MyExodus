package com.example.myexodus.widget

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's decoder, which is the widget's half of the honesty rule: anything it cannot read
 * must come out as "unknown", never as a value the app did not actually write (spec §9).
 */
class WidgetSnapshotTest {

    private val streakSample = StreakSample(
        elapsedMs = 12 * 86_400_000L + 3_600_000L,
        verifiedUtc = 1_800_000_000_000L,
        verifiedElapsed = 500_000L,
        verifiedBoot = 42,
    )

    private val sample = ProtectionSnapshot(
        enforcement = EnforcementStatus.ACTIVE,
        streakDays = 12,
        habitLabel = "No smoking",
        habitId = "habit-1",
        streakSample = streakSample,
        discreet = false,
        evalUtc = 1_800_000_000_000L,
        evalElapsed = 500_000L,
        evalBootCount = 42,
    )

    @Test
    fun roundTrip_preservesEveryField() {
        val prefs = mutablePreferencesOf()
        prefs.putSnapshot(sample)
        assertEquals(sample, snapshotFrom(prefs))
    }

    @Test
    fun unknownStreak_roundTripsAsUnknown() {
        val prefs = mutablePreferencesOf()
        prefs.putSnapshot(sample.copy(streakDays = null))
        assertNull(snapshotFrom(prefs)?.streakDays)
    }

    @Test
    fun removedHabit_clearsTheStaleLabel() {
        // Habit removed: the label must vanish with it, not keep naming a track that is gone.
        val prefs = mutablePreferencesOf()
        prefs.putSnapshot(sample.copy(streakDays = null, habitLabel = null))
        assertNull(snapshotFrom(prefs)?.habitLabel)
    }

    @Test
    fun habitId_roundTripsAndClearsWithTheStreak() {
        // The relapse control's binding: an id that outlived its streak would target a track
        // the widget no longer shows — it must clear exactly when the number and label do.
        val prefs = mutablePreferencesOf()
        prefs.putSnapshot(sample)
        assertEquals("habit-1", snapshotFrom(prefs)?.habitId)
        prefs.putSnapshot(sample.copy(streakDays = null, habitLabel = null, habitId = null))
        assertNull(snapshotFrom(prefs)?.habitId)
    }

    @Test
    fun preV2Sample_decodesWithoutAHabitId() {
        // A record written before this field existed renders unlabelled-for-writes: the relapse
        // control hides rather than binding a guessed track.
        val prefs = mutablePreferencesOf()
        prefs.putSnapshot(sample)
        prefs.remove(stringPreferencesKey("habit_id"))
        assertNull(snapshotFrom(prefs)?.habitId)
    }

    @Test
    fun verificationPair_roundTripsAndClears() {
        // The pair is what lets the day count stay cached honestly; losing it on a write would
        // silently turn every later publish into a store read.
        val prefs = mutablePreferencesOf()
        prefs.putSnapshot(sample)
        assertEquals(streakSample, snapshotFrom(prefs)?.streakSample)
        prefs.putSnapshot(sample.copy(streakDays = null, streakSample = null))
        assertNull(snapshotFrom(prefs)?.streakSample)
    }

    @Test
    fun partialVerificationRecord_decodesWithFailsafeDefaults() {
        // A record damaged mid-block (accumulator present, stamps gone) must still decode — the
        // writer re-reads on the missing smoke, and the verified boot sentinel mismatches rather
        // than accidentally matching a real boot count.
        val prefs = mutablePreferencesOf()
        prefs.putSnapshot(sample)
        prefs.remove(longPreferencesKey("streak_verified_utc"))
        prefs.remove(intPreferencesKey("streak_verified_boot"))
        val decoded = snapshotFrom(prefs)?.streakSample
        assertEquals(streakSample.elapsedMs, decoded?.elapsedMs)
        assertEquals(0L, decoded?.verifiedUtc)
        assertEquals(UNSET_BOOT_COUNT, decoded?.verifiedBoot)
    }

    @Test
    fun nothingWrittenYet_isNoSnapshot() {
        // Absent record: the caller renders UNVERIFIED rather than defaulting to a status.
        assertNull(snapshotFrom(preferencesOf()))
        assertNull(snapshotFrom(null))
    }

    @Test
    fun unparseableEnforcement_isUnknown_neverInactive() {
        // A damaged record: the sample itself is still usable, but the enforcement claim is not.
        val prefs = mutablePreferencesOf()
        prefs.putSnapshot(sample)
        prefs[stringPreferencesKey("enforcement_state")] = "garbage"
        assertEquals(EnforcementStatus.UNKNOWN, snapshotFrom(prefs)?.enforcement)
    }

    @Test
    fun absentEnforcement_isUnknown() {
        val prefs = mutablePreferencesOf()
        prefs.putSnapshot(sample)
        prefs.remove(stringPreferencesKey("enforcement_state"))
        assertEquals(EnforcementStatus.UNKNOWN, snapshotFrom(prefs)?.enforcement)
    }

    @Test
    fun absentDiscreetFlag_staysDiscreet() {
        // FR5: the disclosure-free copy is the default, so a missing flag must not disclose.
        val prefs = mutablePreferencesOf()
        prefs.putSnapshot(sample)
        prefs.remove(booleanPreferencesKey("discreet_mode"))
        assertTrue(snapshotFrom(prefs)?.discreet == true)
    }
}
