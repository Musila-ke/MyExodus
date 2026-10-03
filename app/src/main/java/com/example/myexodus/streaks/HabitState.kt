package com.example.myexodus.streaks

import com.example.myexodus.data.db.entity.MilestoneEvent

/**
 * Read-only snapshot of one habit's live streak, emitted by [HabitRepository.state] (plan §7
 * "StateFlow exposure", §8 Step 5). Everything here is persisted state or derived purely
 * from it — a collector renders without re-reading the database. Single-habit V1: there is
 * one state, not a map.
 */
data class HabitState(
    val habitId: String,
    val streakId: String,
    /** User-entered label for the habit ('Instagram', 'Betting Sites'). */
    val targetLabel: String,
    /** The active streak's start, wall-clock. Day counts derive from [currentStreakElapsedMs],
     *  not from this (Decision 17: no calendar math). */
    val recoveryStartAt: Long,
    /** Live accumulator of the active streak (§8 Step 2). */
    val currentStreakElapsedMs: Long,
    /** Habit-level record across all streaks; never rendered as the live streak (Decision 13). */
    val allTimeMaxElapsedMs: Long,
    /** Ordinal display day of [currentStreakElapsedMs] — the day of the run the user is in,
     *  so a fresh streak is Day 1, never 0 (owner ruling 2026-10-03, ADR #128). Completed
     *  days stay in [currentStreakElapsedMs]; this is the display boundary's +1. */
    val currentDay: Int,
    val habitDurationYears: Int,
    val severity: WithdrawalSeverity,
    val withdrawalWeeks: List<WithdrawalWeek>,
    /** The active streak's 7/30/90 rows, ordered by day. The splash card is
     *  `achievedAt != null && acknowledgedAt == null` (§8 Step 4), independent of
     *  `notificationSentAt` (REQ-12: card is the guaranteed delivery path). */
    val milestones: List<MilestoneEvent>,
)
