package com.example.myexodus.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/**
 * The widget-facing store (widget spec §5 Decision 2/3): one plain Preferences DataStore, not
 * Room — the widget renders from a different lifecycle than enforcement and must not need the
 * SQLCipher/Keystore stack to draw. Global, not per-widget-instance (Decision 3).
 *
 * Application-private state transport only: app-private storage limits ordinary access by other
 * apps, but nothing here is tamper-resistant or integrity-checked (spec §10).
 */
val Context.widgetSnapshotDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "widget_protection_snapshot",
)

private val KEY_ENFORCEMENT = stringPreferencesKey("enforcement_state")
private val KEY_STREAK_DAYS = intPreferencesKey("streak_days")
private val KEY_HABIT_LABEL = stringPreferencesKey("habit_label")
private val KEY_HABIT_ID = stringPreferencesKey("habit_id")
private val KEY_DISCREET = booleanPreferencesKey("discreet_mode")
private val KEY_EVAL_UTC = longPreferencesKey("eval_utc")
private val KEY_EVAL_ELAPSED = longPreferencesKey("eval_elapsed")
private val KEY_EVAL_BOOT_COUNT = intPreferencesKey("eval_boot_count")
private val KEY_STREAK_ELAPSED_MS = longPreferencesKey("streak_elapsed_ms")
private val KEY_STREAK_VERIFIED_UTC = longPreferencesKey("streak_verified_utc")
private val KEY_STREAK_VERIFIED_ELAPSED = longPreferencesKey("streak_verified_elapsed")
private val KEY_STREAK_VERIFIED_BOOT = intPreferencesKey("streak_verified_boot")

/** Recorded when the boot count was unreadable. If both the write and the read get this sentinel
 *  they compare equal and can no longer discriminate, leaving [displayedStatusFor]'s elapsed-time
 *  regression as the only reboot signal; if only one side gets it, they differ and the sample
 *  fails closed to UNVERIFIED rather than reporting a status it cannot back. */
internal const val UNSET_BOOT_COUNT = -1

/**
 * One materialized sample of how protection stands (spec §4/§5 Decision 1). The widget never
 * evaluates protection itself — it renders this, bounded by the freshness/clock/reboot gate in
 * [displayedStatusFor].
 *
 * There is no separate `policy` field: the app's existing arbiter ([com.example.myexodus.blocking
 * .ProtectionStatusMonitor]) already folds "the user turned protection off" into
 * DEGRADED_NO_DETECTION, which renders UNPROTECTED — the outcome the spec's
 * `policy=INACTIVE -> UNPROTECTED` row asks for. One tri-state instead of two booleans plus a
 * combination matrix (spec deviation, recorded 2026-09-16).
 *
 * [streakDays] is nullable on purpose: no habit onboarded, or a read that has not succeeded yet,
 * must render as unknown rather than a 0-day streak (spec deviation, recorded 2026-09-16 — the
 * plan's non-null Int defaulted to 0, which claims something the app does not know).
 */
data class ProtectionSnapshot(
    val enforcement: EnforcementStatus,
    val streakDays: Int?,
    /** Which habit the day count belongs to (finding #12: with 2+ habits an unnamed number
     *  is the wrong track as far as the user can tell). Null = no habit / not known yet;
     *  absent in samples written before this field existed, which renders unlabelled. */
    val habitLabel: String?,
    /** The identity behind [streakDays]/[habitLabel] (owner ruling 2026-10-17): the widget's
     *  "Log a relapse" control must end exactly the streak track the widget displays, and the
     *  label alone cannot bind a write (two habits may share a label, and a selection flipped in
     *  the app would silently re-target the wrong track). The writer stamps the id at the same
     *  read that produced the number, so the control can never disagree with the count above it.
     *  Null = a sample from before this field existed, or no habit — the relapse control stays
     *  hidden rather than binding a guessed track. */
    val habitId: String?,
    /** When the streak fields above were last actually read from the store, and what the
     *  accumulator stood at then. Absent = never verified (or a sample from before this field
     *  existed), which the writer treats as "refresh now". The pair is what lets the day count
     *  stay cached without lying: the accumulator advances 1:1 with elapsedRealtime
     *  (Decision 17), so `elapsedMs + (now - verifiedElapsed)` proves whether the next whole
     *  day has been crossed since — no calendar math, no re-read. */
    val streakSample: StreakSample? = null,
    val discreet: Boolean,
    val evalUtc: Long,
    val evalElapsed: Long,
    val evalBootCount: Int,
)

/** One observed reading of the streak accumulator, with the clocks it was taken under. */
data class StreakSample(
    val elapsedMs: Long,
    val verifiedUtc: Long,
    val verifiedElapsed: Long,
    val verifiedBoot: Int,
)

/** Decodes a persisted sample; null when nothing was ever written or the record is unusable. */
fun snapshotFrom(prefs: Preferences?): ProtectionSnapshot? {
    val evalUtc = prefs?.get(KEY_EVAL_UTC) ?: return null
    return ProtectionSnapshot(
        enforcement = when (prefs[KEY_ENFORCEMENT]) {
            EnforcementStatus.ACTIVE.name -> EnforcementStatus.ACTIVE
            EnforcementStatus.INACTIVE.name -> EnforcementStatus.INACTIVE
            // Absent or unparseable is UNKNOWN, never coerced to INACTIVE (spec §9).
            else -> EnforcementStatus.UNKNOWN
        },
        streakDays = prefs[KEY_STREAK_DAYS],
        habitLabel = prefs[KEY_HABIT_LABEL],
        // Absent (pre-v2 sample or no habit) = null: the relapse control hides until a real
        // read stamps the id — never a fabricated binding.
        habitId = prefs[KEY_HABIT_ID],
        // Absent accumulator = the sample predates the field (or nothing was verified):
        // the writer reads that as "verify now", so a partial record fails toward refresh.
        streakSample = prefs[KEY_STREAK_ELAPSED_MS]?.let { elapsedMs ->
            StreakSample(
                elapsedMs = elapsedMs,
                verifiedUtc = prefs[KEY_STREAK_VERIFIED_UTC] ?: 0L,
                verifiedElapsed = prefs[KEY_STREAK_VERIFIED_ELAPSED] ?: 0L,
                verifiedBoot = prefs[KEY_STREAK_VERIFIED_BOOT] ?: UNSET_BOOT_COUNT,
            )
        },
        // Discreet is the default (FR5): an absent flag must never disclose.
        discreet = prefs[KEY_DISCREET] ?: true,
        evalUtc = evalUtc,
        evalElapsed = prefs[KEY_EVAL_ELAPSED] ?: 0L,
        evalBootCount = prefs[KEY_EVAL_BOOT_COUNT] ?: UNSET_BOOT_COUNT,
    )
}

fun MutablePreferences.putSnapshot(snapshot: ProtectionSnapshot) {
    this[KEY_ENFORCEMENT] = snapshot.enforcement.name
    val streakDays = snapshot.streakDays
    if (streakDays == null) remove(KEY_STREAK_DAYS) else this[KEY_STREAK_DAYS] = streakDays
    val habitLabel = snapshot.habitLabel
    if (habitLabel == null) remove(KEY_HABIT_LABEL) else this[KEY_HABIT_LABEL] = habitLabel
    val habitId = snapshot.habitId
    if (habitId == null) remove(KEY_HABIT_ID) else this[KEY_HABIT_ID] = habitId
    val streakSample = snapshot.streakSample
    if (streakSample == null) {
        remove(KEY_STREAK_ELAPSED_MS)
        remove(KEY_STREAK_VERIFIED_UTC)
        remove(KEY_STREAK_VERIFIED_ELAPSED)
        remove(KEY_STREAK_VERIFIED_BOOT)
    } else {
        this[KEY_STREAK_ELAPSED_MS] = streakSample.elapsedMs
        this[KEY_STREAK_VERIFIED_UTC] = streakSample.verifiedUtc
        this[KEY_STREAK_VERIFIED_ELAPSED] = streakSample.verifiedElapsed
        this[KEY_STREAK_VERIFIED_BOOT] = streakSample.verifiedBoot
    }
    this[KEY_DISCREET] = snapshot.discreet
    this[KEY_EVAL_UTC] = snapshot.evalUtc
    this[KEY_EVAL_ELAPSED] = snapshot.evalElapsed
    this[KEY_EVAL_BOOT_COUNT] = snapshot.evalBootCount
}
