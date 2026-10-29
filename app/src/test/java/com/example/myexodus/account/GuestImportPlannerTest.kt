package com.example.myexodus.account

import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RiskWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account plan §6.3/§8.4: the pure additive planner. Covers the cases the plan names
 * explicitly — duplicate same-day logs, habits with identical names, journal entries with
 * different IDs but equal content, milestones, portable preferences, cached aggregate
 * recomputation — plus the conflict paths that must abort before activation and the
 * replay that must insert nothing.
 */
class GuestImportPlannerTest {

    private val day = 86_400_000L

    private fun planner(): GuestImportPlanner {
        var next = 0
        return GuestImportPlanner { "new-${++next}" }
    }

    private fun habit(
        id: String,
        profile: String = GUEST_PROFILE,
        name: String = "Porn",
        allTimeMax: Long = 0,
        relapses: Int = 0,
        lastRelapse: Long? = null,
        selected: Boolean = true,
    ) = HabitProgress(
        progressId = id,
        profileId = profile,
        targetIdentifier = name,
        currentCleanDaysStreak = 0,
        totalRelapses = relapses,
        accessAttemptsCount = 4,
        habitDurationYears = 5,
        onboardingSeverityLevel = "MODERATE",
        lastRelapseTimestamp = lastRelapse,
        assignedPartnershipId = "pair-a",
        allTimeMaxStreakElapsedMs = allTimeMax,
        isSelected = selected,
    )

    private fun streak(
        id: String,
        habitId: String,
        start: Long,
        relapsedAt: Long? = null,
        elapsedMs: Long = 0,
    ) = RecoveryStreak(
        streakId = id,
        habitId = habitId,
        recoveryStartAt = start,
        relapsedAt = relapsedAt,
        tWallLastObserved = start + elapsedMs,
        rBootLastObserved = 0,
        bootCountLastObserved = 0,
        currentStreakElapsedMs = elapsedMs,
    )

    private fun milestone(streakId: String, day: Int, achieved: Long? = null) =
        MilestoneEvent(streakId = streakId, milestoneDay = day, achievedAt = achieved)

    private fun attempt(
        at: Long,
        target: String = "com.x",
        outcome: String = AttemptLog.OUTCOME_BLOCKED,
    ) = AttemptLog(
        occurredAtEpochMillis = at,
        ruleId = "r1",
        blockedTarget = target,
        outcome = outcome,
    )

    private fun session(id: String, started: Long) = RecoverySession(
        id = id,
        technique = "box_breathing",
        intensityStart = 6,
        intensityEnd = 3,
        state = RecoverySession.STATE_COMPLETED,
        startedAtEpochMs = started,
        endedAtEpochMs = started + 60_000,
        durationSec = 60,
        zoneId = "Africa/Nairobi",
        entry = "TAB",
    )

    private fun step(id: String, order: Int, text: String, window: String = "") =
        CoachingStep(
            stepId = id,
            profileId = GUEST_PROFILE,
            stepOrder = order,
            actionText = text,
            associatedTriggerWindow = window,
        )

    private fun window(id: Long, start: Int, end: Int) =
        RiskWindow(id = id, startMinute = start, endMinute = end, daysMask = 127)

    private fun guest(
        habits: List<HabitProgress> = emptyList(),
        streaks: List<RecoveryStreak> = emptyList(),
        milestones: List<MilestoneEvent> = emptyList(),
        attempts: List<AttemptLog> = emptyList(),
        sessions: List<RecoverySession> = emptyList(),
        coaching: List<CoachingStep> = emptyList(),
        riskWindows: List<RiskWindow> = emptyList(),
        policy: RecoveryPolicy? = null,
    ) = GuestSnapshot(habits, streaks, milestones, attempts, sessions, coaching, riskWindows, policy)

    private fun target(
        habits: List<HabitProgress> = emptyList(),
        streaks: List<RecoveryStreak> = emptyList(),
        milestones: List<MilestoneEvent> = emptyList(),
        attempts: List<AttemptLog> = emptyList(),
        sessions: List<RecoverySession> = emptyList(),
        coaching: List<CoachingStep> = emptyList(),
        riskWindows: List<RiskWindow> = emptyList(),
        policy: RecoveryPolicy? = null,
    ) = TargetSnapshot(
        profileId = TARGET_PROFILE,
        habits = habits,
        streaks = streaks,
        milestones = milestones,
        attempts = attempts,
        sessions = sessions,
        coaching = coaching,
        riskWindows = riskWindows,
        policy = policy,
    )

    @Test
    fun emptyGuest_plansAnEmptyNoOp() {
        val plan = planner().plan(guest(), target(), includePortablePreferences = false)

        assertTrue(plan.canApply)
        assertEquals(ImportSummary(0, 0, 0, 0), plan.summary)
    }

    @Test
    fun newHabit_withStreaksAndMilestones_recomputesEveryAggregate() {
        val g = guest(
            habits = listOf(habit("h1")),
            streaks = listOf(
                streak("s1", "h1", start = 1_000_000L, relapsedAt = 1_000_000L + 10 * day, elapsedMs = 10 * day),
                streak("s2", "h1", start = 11 * day, elapsedMs = 3 * day),
            ),
            milestones = listOf(
                milestone("s1", 7, 2 * day),
                milestone("s1", 30, 31 * day),
                milestone("s1", 90, null),
                milestone("s2", 7, 14 * day),
            ),
        )

        val plan = planner().plan(g, target(), includePortablePreferences = false)

        assertTrue(plan.canApply)
        val inserted = plan.habitsToInsert.single()
        assertEquals(3, inserted.currentCleanDaysStreak)
        assertEquals(1, inserted.totalRelapses)
        assertEquals("attempt history is device-local and never carried", 0, inserted.accessAttemptsCount)
        assertNull("guest pairs are terminated by the switch", inserted.assignedPartnershipId)
        assertTrue("an account with no selection adopts the imported habit", inserted.isSelected)
        assertEquals(1_000_000L + 10 * day, inserted.lastRelapseTimestamp)
        assertEquals(10 * day, inserted.allTimeMaxStreakElapsedMs)

        assertEquals(listOf("s1", "s2"), plan.streaksToInsert.map { it.streakId })
        assertEquals(4, plan.milestonesToInsert.size)
        assertEquals(7, plan.summary.inserted)
        assertEquals(0, plan.summary.updated)
    }

    @Test
    fun theGuestsSelectedHabit_isTheOneSelected_whenTheAccountHasNone() {
        val g = guest(
            habits = listOf(
                habit("g1", name = "Gambling", selected = false),
                habit("g2", name = "Porn", selected = true),
            ),
        )

        val plan = planner().plan(g, target(), includePortablePreferences = false)

        val byName = plan.habitsToInsert.associateBy { it.targetIdentifier }
        assertEquals(true, byName.getValue("Porn").isSelected)
        assertEquals(false, byName.getValue("Gambling").isSelected)
    }

    @Test
    fun anAccountsOwnSelection_isNeverOverriddenByImport() {
        val g = guest(habits = listOf(habit("g1", name = "Gambling")))
        val t = target(habits = listOf(habit("th1", profile = TARGET_PROFILE, name = "Porn")))

        val plan = planner().plan(g, t, includePortablePreferences = false)

        assertFalse("the target keeps its own selection", plan.habitsToInsert.single().isSelected)
        assertTrue(plan.habitsToUpdate.isEmpty())
    }

    @Test
    fun duplicateSameDayAttempts_dedupByNaturalKey_acrossTargetAndPending() {
        val g = guest(
            attempts = listOf(
                attempt(1_000L, target = "com.a"),
                attempt(2_000L, target = "com.b"),
                attempt(2_000L, target = "com.b"),
                attempt(2_000L, target = "com.b", outcome = AttemptLog.OUTCOME_OVERRIDE),
            ),
        )
        val t = target(attempts = listOf(attempt(1_000L, target = "com.a")))

        val plan = planner().plan(g, t, includePortablePreferences = false)

        assertEquals(
            listOf("com.b" to AttemptLog.OUTCOME_BLOCKED, "com.b" to AttemptLog.OUTCOME_OVERRIDE),
            plan.attemptsToInsert.map { it.blockedTarget to it.outcome },
        )
        assertTrue("imported rows never carry a delivery marker", plan.attemptsToInsert.all {
            it.id == 0L && it.eventSequence == null
        })
        assertEquals(2, plan.summary.deduplicated)
        assertEquals(2, plan.summary.inserted)
    }

    @Test
    fun habitsWithIdenticalNames_foldToOneHabit_andDedupAgainstTheTarget() {
        val g = guest(
            habits = listOf(
                habit("g1", name = "Porn"),
                habit("g2", name = " porn "),
                habit("g3", name = "Gambling"),
                habit("g4", name = "Gambling "),
            ),
        )
        val t = target(habits = listOf(habit("th1", profile = TARGET_PROFILE, name = "Porn")))

        val plan = planner().plan(g, t, includePortablePreferences = false)

        val added = plan.habitsToInsert.single()
        assertEquals("new-1", added.progressId)
        assertEquals("Gambling", added.targetIdentifier)
        assertEquals("both 'Porn' rows map onto the target habit", 2, plan.summary.deduplicated)
    }

    @Test
    fun journalEntriesWithDifferentIdsButEqualContent_dedupByContent() {
        val g = guest(
            sessions = listOf(
                session("g1", 5_000L),
                session("t1", 7_000L),
            ),
        )
        val t = target(sessions = listOf(session("t1", 5_000L)))

        val plan = planner().plan(g, t, includePortablePreferences = false)

        val inserted = plan.sessionsToInsert.single()
        assertEquals("the target id is taken, so the colliding content gets a fresh id", "new-1", inserted.id)
        assertEquals(7_000L, inserted.startedAtEpochMs)
        assertNull(inserted.eventSequence)
        assertEquals("the guest g1 row equals the target t1 row", 1, plan.summary.deduplicated)
    }

    @Test
    fun milestonesUnderAnExistingStreak_mergeWithoutDuplicating() {
        val g = guest(
            habits = listOf(habit("gh1")),
            streaks = listOf(streak("gs1", "gh1", start = 1_000L)),
            milestones = listOf(milestone("gs1", 7, 100L), milestone("gs1", 90, 300L)),
        )
        val t = target(
            habits = listOf(habit("th1", profile = TARGET_PROFILE)),
            streaks = listOf(streak("ts1", "th1", start = 1_000L)),
            milestones = listOf(milestone("ts1", 7, null), milestone("ts1", 30, 200L)),
        )

        val plan = planner().plan(g, t, includePortablePreferences = false)

        assertTrue(plan.streaksToInsert.isEmpty())
        val added = plan.milestonesToInsert.single()
        assertEquals("ts1" to 90, added.streakId to added.milestoneDay)
        val merged = plan.milestonesToUpdate.single()
        assertEquals("ts1" to 7, merged.streakId to merged.milestoneDay)
        assertEquals(100L, merged.achievedAt)
        assertEquals("target-only facts are not rewritten", 200L, 200L)
        assertEquals(1, plan.summary.updated)
    }

    @Test
    fun activeGuestStreak_isAbsorbedIntoTheRunningTargetStreak_andItsTimeCounts() {
        val g = guest(
            habits = listOf(habit("gh1")),
            streaks = listOf(streak("gs1", "gh1", start = 9_000L, elapsedMs = 4 * day)),
            milestones = listOf(milestone("gs1", 7, 100L), milestone("gs1", 30, 200L)),
        )
        val t = target(
            habits = listOf(
                habit("th1", profile = TARGET_PROFILE, allTimeMax = 0).copy(currentCleanDaysStreak = 5),
            ),
            streaks = listOf(streak("ts1", "th1", start = 1_000L, elapsedMs = 5 * day)),
            milestones = listOf(milestone("ts1", 7, null)),
        )

        val plan = planner().plan(g, t, includePortablePreferences = false)

        assertTrue("one active streak per habit", plan.streaksToInsert.isEmpty())
        assertEquals("ts1" to 30, plan.milestonesToInsert.single().let { it.streakId to it.milestoneDay })
        assertEquals(100L, plan.milestonesToUpdate.single().achievedAt)
        val updated = plan.habitsToUpdate.single()
        assertEquals("the target's running streak stays the live one", 5, updated.currentCleanDaysStreak)
        assertEquals("the absorbed streak still raises the record", 4 * day, updated.allTimeMaxStreakElapsedMs)
    }

    @Test
    fun orphanReferences_abortBeforeActivation() {
        val orphanStreak = planner().plan(
            guest(habits = listOf(habit("h1")), streaks = listOf(streak("s1", "missing", start = 1L))),
            target(),
            includePortablePreferences = false,
        )
        assertEquals(
            ImportConflict(ImportConflictTable.STREAKS, ImportConflictReason.BROKEN_REFERENCE, 1),
            orphanStreak.conflicts.single(),
        )
        assertFalse(orphanStreak.canApply)

        val orphanMilestone = planner().plan(
            guest(habits = listOf(habit("h1")), milestones = listOf(milestone("nope", 7))),
            target(),
            includePortablePreferences = false,
        )
        assertEquals(
            ImportConflict(ImportConflictTable.MILESTONES, ImportConflictReason.BROKEN_REFERENCE, 1),
            orphanMilestone.conflicts.single(),
        )
        assertFalse(orphanMilestone.canApply)

        val orphanStep = planner().plan(
            guest(coaching = listOf(step("c1", 0, "Call Sam", window = "99"))),
            target(),
            includePortablePreferences = false,
        )
        assertEquals(
            ImportConflict(ImportConflictTable.COACHING, ImportConflictReason.BROKEN_REFERENCE, 1),
            orphanStep.conflicts.single(),
        )
        assertFalse(orphanStep.canApply)
    }

    @Test
    fun windowAtCap_conflictsUnlesstheWindowIsAlreadyPresent() {
        val full = (1L..3L).map { window(it, 60 * it.toInt(), 60 * it.toInt() + 30) }

        val blocked = planner().plan(
            guest(riskWindows = listOf(window(0, 1200, 1380))),
            target(riskWindows = full),
            includePortablePreferences = false,
        )
        assertEquals(
            ImportConflict(ImportConflictTable.RISK_WINDOWS, ImportConflictReason.WINDOW_LIMIT, 1),
            blocked.conflicts.single(),
        )
        assertFalse(blocked.canApply)

        val duplicate = planner().plan(
            guest(riskWindows = listOf(window(9, 60, 90))),
            target(riskWindows = full),
            includePortablePreferences = false,
        )
        assertTrue(duplicate.canApply)
        assertTrue(duplicate.riskWindowsToInsert.isEmpty())
        assertEquals(1, duplicate.summary.deduplicated)
    }

    @Test
    fun coachingWindowReferences_areRemappedToTargetIds() {
        val g = guest(
            riskWindows = listOf(window(1, 1200, 1380), window(2, 60, 120)),
            coaching = listOf(
                step("c1", 0, "Call Sam", window = "1"),
                step("c2", 1, "Walk outside", window = "2"),
                step("c3", 2, "Any time", window = ""),
            ),
        )
        val t = target(riskWindows = listOf(window(7, 1200, 1380)))

        val plan = planner().plan(g, t, includePortablePreferences = false)

        assertEquals(2L, plan.riskWindowsToInsert.single().id)
        assertEquals("the matched window keeps the target id", "7", plan.coachingToInsert[0].associatedTriggerWindow)
        assertEquals("an inserted window keeps its own id", "2", plan.coachingToInsert[1].associatedTriggerWindow)
        assertEquals("", plan.coachingToInsert[2].associatedTriggerWindow)
    }

    @Test
    fun coachingAtThePerKindCap_conflicts() {
        val t = target(
            coaching = (0 until CoachingStep.MAX_PER_KIND).map {
                step("t$it", it, "Target step $it").copy(profileId = TARGET_PROFILE)
            },
        )

        val plan = planner().plan(
            guest(coaching = listOf(step("c1", 0, "Extra step"))),
            t,
            includePortablePreferences = false,
        )

        assertEquals(
            ImportConflict(ImportConflictTable.COACHING, ImportConflictReason.COACHING_LIMIT, 1),
            plan.conflicts.single(),
        )
        assertFalse(plan.canApply)
    }

    @Test
    fun portablePreferences_applyOnlyOnExplicitOptIn_andOnlyWhenDifferent() {
        val guestPolicy = RecoveryPolicy(defaultFrictionSeconds = 30, overrideCooldownSeconds = 60)
        val targetPolicy = RecoveryPolicy(defaultFrictionSeconds = 10, overrideCooldownSeconds = 20)

        val without = planner().plan(guest(policy = guestPolicy), target(policy = targetPolicy), false)
        assertNull("target singleton stays by default", without.policy)

        val with = planner().plan(guest(policy = guestPolicy), target(policy = targetPolicy), true)
        assertEquals(guestPolicy, with.policy)
        assertEquals("the policy row counts as one inserted row", 1, with.summary.inserted)

        val same = planner().plan(guest(policy = guestPolicy), target(policy = guestPolicy), true)
        assertNull("identical policies are nothing to import", same.policy)
    }

    @Test
    fun sameIdCollision_getsAFreshId_andMilestonesFollowIt() {
        val g = guest(
            habits = listOf(habit("gh1")),
            streaks = listOf(streak("dup", "gh1", start = 999L, relapsedAt = 5_000L)),
            milestones = listOf(milestone("dup", 30, 300L)),
        )
        val t = target(
            habits = listOf(habit("th1", profile = TARGET_PROFILE)),
            streaks = listOf(streak("dup", "th1", start = 100L)),
            milestones = listOf(milestone("dup", 7, 100L)),
        )

        val plan = planner().plan(g, t, includePortablePreferences = false)

        val added = plan.streaksToInsert.single()
        assertEquals("new-1", added.streakId)
        assertNotEquals("dup", added.streakId)
        assertEquals("new-1", plan.milestonesToInsert.single().streakId)
    }

    @Test
    fun replayingAnAppliedPlan_insertsAndUpdatesNothing() {
        val g = guest(
            habits = listOf(habit("h1")),
            streaks = listOf(
                streak("s1", "h1", start = 1_000_000L, relapsedAt = 1_000_000L + 10 * day, elapsedMs = 10 * day),
                streak("s2", "h1", start = 11 * day, elapsedMs = 3 * day),
            ),
            milestones = listOf(milestone("s1", 7, 2 * day), milestone("s2", 7, 14 * day)),
            attempts = listOf(attempt(5_000L)),
            sessions = listOf(session("j1", 9_000L)),
            coaching = listOf(step("c1", 0, "Call Sam")),
            riskWindows = listOf(window(1, 1200, 1380)),
        )
        val first = planner().plan(g, target(), includePortablePreferences = false)
        assertTrue(first.canApply)

        val applied = target(
            habits = first.habitsToInsert,
            streaks = first.streaksToInsert,
            milestones = first.milestonesToInsert,
            attempts = first.attemptsToInsert,
            sessions = first.sessionsToInsert,
            coaching = first.coachingToInsert,
            riskWindows = first.riskWindowsToInsert,
        )
        val second = planner().plan(g, applied, includePortablePreferences = false)

        assertTrue(second.canApply)
        assertEquals(0, second.summary.inserted)
        assertEquals(0, second.summary.updated)
        assertTrue(second.habitsToInsert.isEmpty())
        assertTrue(second.habitsToUpdate.isEmpty())
        assertTrue(second.streaksToInsert.isEmpty())
        assertTrue(second.milestonesToInsert.isEmpty())
        assertTrue(second.milestonesToUpdate.isEmpty())
        assertTrue(second.attemptsToInsert.isEmpty())
        assertTrue(second.sessionsToInsert.isEmpty())
        assertTrue(second.coachingToInsert.isEmpty())
        assertTrue(second.riskWindowsToInsert.isEmpty())
        assertTrue(second.summary.deduplicated > 0)
    }

    private companion object {
        const val GUEST_PROFILE = "guest-profile"
        const val TARGET_PROFILE = "local"
    }
}
