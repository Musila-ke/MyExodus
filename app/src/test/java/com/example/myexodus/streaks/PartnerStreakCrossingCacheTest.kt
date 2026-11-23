package com.example.myexodus.streaks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Register #17 (owner-approved 2026-11-23): the non-vault next-day-crossing hint the periodic
 * sender pass reads. Round-trips, due selection, removal, and the malformed-value guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PartnerStreakCrossingCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun cache() = PartnerStreakCrossingCache(context)

    private fun entry(habitId: String = "habit-1", nextCrossingAtMs: Long = 5_000L) =
        PartnerStreakCrossingCache.Entry(
            habitId = habitId,
            streakId = "streak-$habitId",
            daysFree = 3,
            nextCrossingAtMs = nextCrossingAtMs,
        )

    @Test
    fun record_roundTripsThroughANewInstance() {
        cache().record(entry())

        val hint = checkNotNull(cache().entry("habit-1"))
        assertEquals("streak-habit-1", hint.streakId)
        assertEquals(3, hint.daysFree)
        assertEquals(5_000L, hint.nextCrossingAtMs)
    }

    @Test
    fun dueHabits_returnsOnlyInstantsAtOrBeforeNow() {
        cache().record(entry(habitId = "past", nextCrossingAtMs = 1_000L))
        cache().record(entry(habitId = "now", nextCrossingAtMs = 2_000L))
        cache().record(entry(habitId = "future", nextCrossingAtMs = 3_000L))

        assertEquals(setOf("past", "now"), cache().dueHabits(2_000L).toSet())
        assertTrue(cache().dueHabits(999L).isEmpty())
    }

    @Test
    fun remove_dropsOnlyTheNamedHabit() {
        cache().record(entry(habitId = "habit-1"))
        cache().record(entry(habitId = "habit-2"))

        cache().remove("habit-1")

        assertNull(cache().entry("habit-1"))
        assertEquals("streak-habit-2", checkNotNull(cache().entry("habit-2")).streakId)
        cache().remove("habit-1")
    }

    @Test
    fun malformedValue_isIgnoredInsteadOfCrashing() {
        context.getSharedPreferences(PartnerStreakCrossingCache.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("crossing_bad", "not|a|hint")
            .putString("crossing_worse", "x|y|not-a-number|1")
            .commit()

        assertNull(cache().entry("bad"))
        assertTrue(cache().dueHabits(Long.MAX_VALUE).isEmpty())
    }
}
