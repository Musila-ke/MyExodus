package com.example.myexodus.streaks

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-vault next-day-crossing hints for shared streaks (owner-approved partner-freshness
 * design, owner-issues register #17, 2026-11-23).
 *
 * A shared streak's day count advances only when the sender's phone evaluates, and under App
 * Lock the vault cannot open in the background at all — so a crossing could sit unshared until
 * the user next opened the app. Each foreground evaluation records here, per streak some pair
 * shares, the day count it just rendered and the wall instant the next whole day ticks over.
 * The existing periodic sender pass reads these hints and asks [HabitRepository] to evaluate
 * what is due; the ordinary day-crossing guard then emits STREAK_UPDATED through the ordinary
 * journal and consent gates. Only counts partners already receive are cached, and a hint is
 * only a hint — evaluation's own anchors decide whether a day actually crossed.
 *
 * Prefs, not Room, for the same reason as
 * [com.example.myexodus.accountability.sync.PairingReachabilityStore]: it must be readable
 * while the vault is closed. The value holds the habit's opaque id and the shared count, never
 * its label.
 */
@Singleton
class PartnerStreakCrossingCache @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    data class Entry(
        val habitId: String,
        val streakId: String,
        val daysFree: Int,
        val nextCrossingAtMs: Long,
    )

    // Lazy: the Hilt graph is built at process start, which can be pre-unlock
    // (LOCKED_BOOT_COMPLETED) — CE SharedPreferences throw until first unlock.
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun record(entry: Entry) {
        prefs.edit { putString(key(entry.habitId), encode(entry)) }
    }

    fun remove(habitId: String) {
        if (prefs.contains(key(habitId))) prefs.edit { remove(key(habitId)) }
    }

    fun entry(habitId: String): Entry? = decode(prefs.getString(key(habitId), null))

    /** Habit ids whose next crossing instant has passed at [nowMs]. */
    fun dueHabits(nowMs: Long): List<String> =
        prefs.all.values.mapNotNull { decode(it as? String) }
            .filter { it.nextCrossingAtMs <= nowMs }
            .map { it.habitId }

    private fun key(habitId: String) = "$KEY_PREFIX$habitId"

    internal companion object {
        const val PREFS_NAME = "partner_streak_crossings"
        private const val KEY_PREFIX = "crossing_"
        private const val SEPARATOR = '|'

        fun encode(entry: Entry): String =
            "${entry.habitId}$SEPARATOR${entry.streakId}$SEPARATOR${entry.daysFree}" +
                "$SEPARATOR${entry.nextCrossingAtMs}"

        /** Null for a missing or malformed value — a hint is never worth a crash. */
        fun decode(raw: String?): Entry? {
            val parts = raw?.split(SEPARATOR) ?: return null
            if (parts.size != 4 || parts[0].isEmpty() || parts[1].isEmpty()) return null
            val days = parts[2].toIntOrNull() ?: return null
            val next = parts[3].toLongOrNull() ?: return null
            if (days < 0) return null
            return Entry(habitId = parts[0], streakId = parts[1], daysFree = days, nextCrossingAtMs = next)
        }
    }
}
