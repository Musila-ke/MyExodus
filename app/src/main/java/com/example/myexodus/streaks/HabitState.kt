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
    /** Whole completed days of [currentStreakElapsedMs], floored at 1 so a fresh streak never
     *  reads 0 (owner rulings ADR #128, display scale revised by ADR #176). */
    val currentDay: Int,
    val habitDurationYears: Int,
    val severity: WithdrawalSeverity,
    val withdrawalWeeks: List<WithdrawalWeek>,
    /** The active streak's 7/30/90 rows, ordered by day. The splash card is
     *  `achievedAt != null && acknowledgedAt == null` (§8 Step 4), independent of
     *  `notificationSentAt` (REQ-12: card is the guaranteed delivery path). */
    val milestones: List<MilestoneEvent>,
)
