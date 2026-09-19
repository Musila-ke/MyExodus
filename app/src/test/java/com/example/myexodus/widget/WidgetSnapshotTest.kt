package com.example.myexodus.widget

import androidx.datastore.preferences.core.booleanPreferencesKey
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

    private val sample = ProtectionSnapshot(
        enforcement = EnforcementStatus.ACTIVE,
        streakDays = 12,
        habitLabel = "No smoking",
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
