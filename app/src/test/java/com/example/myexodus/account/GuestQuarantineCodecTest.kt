package com.example.myexodus.account

import android.app.Application
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RiskWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account plan §8.4/§6.3.6: the quarantine payload is versioned and strict — an unknown or
 * malformed payload must fail loudly so the store can classify it unreadable instead of
 * importing a partial snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GuestQuarantineCodecTest {

    @Test
    fun fullRecord_roundTripsExactly() {
        val record = GuestQuarantineRecord(
            transitionId = "t".repeat(22),
            guestUid = "guest-1",
            importOperationId = "op-1",
            intent = TransitionIntent.IMPORT,
            stagedAtMs = 1_000L,
            targetUid = "member-1",
            importedAtMs = 2_000L,
            summary = ImportSummary(inserted = 7, updated = 2, deduplicated = 3, conflicts = 1),
            snapshot = GuestSnapshot(
                habits = listOf(
                    HabitProgress(
                        progressId = "h1",
                        profileId = "guest-profile",
                        targetIdentifier = "Porn",
                        currentCleanDaysStreak = 2,
                        totalRelapses = 3,
                        accessAttemptsCount = 4,
                        habitDurationYears = 5,
                        onboardingSeverityLevel = "MODERATE",
                        lastRelapseTimestamp = 900L,
                        assignedPartnershipId = null,
                        allTimeMaxStreakElapsedMs = 86_400_000L,
                        isSelected = true,
                    ),
                ),
                streaks = listOf(
                    RecoveryStreak(
                        streakId = "s1",
                        habitId = "h1",
                        recoveryStartAt = 100L,
                        relapsedAt = 200L,
                        relapseBootCount = 3,
                        relapseElapsedRealtimeMs = 4L,
                        tWallLastObserved = 5L,
                        rBootLastObserved = 6L,
                        bootCountLastObserved = 7L,
                        currentStreakElapsedMs = 8L,
                        rowVersion = 9L,
                    ),
                ),
                milestones = listOf(
                    MilestoneEvent("s1", 7, achievedAt = 10L, notificationSentAt = 11L, acknowledgedAt = 12L),
                ),
                attempts = listOf(AttemptLog(occurredAtEpochMillis = 13L, ruleId = "r1", blockedTarget = "com.x", outcome = "BLOCKED")),
                sessions = listOf(
                    RecoverySession(
                        id = "j1",
                        technique = "box_breathing",
                        intensityStart = 6,
                        intensityEnd = 2,
                        state = RecoverySession.STATE_COMPLETED,
                        startedAtEpochMs = 14L,
                        endedAtEpochMs = 15L,
                        durationSec = 60,
                        zoneId = "Africa/Nairobi",
                        entry = "TAB",
                        falseAlarm = true,
                    ),
                ),
                coaching = listOf(CoachingStep("c1", "guest-profile", 0, "Call Sam", "1", CoachingStep.KIND_IF_THEN)),
                riskWindows = listOf(RiskWindow(id = 1, startMinute = 1200, endMinute = 1380, daysMask = 127, enabled = false)),
                policy = RecoveryPolicy(defaultFrictionSeconds = 30, overrideCooldownSeconds = 60),
            ),
        )

        assertEquals(record, GuestQuarantineCodec.decode(GuestQuarantineCodec.encode(record)))
    }

    @Test
    fun emptySnapshot_withAllOptionalsAbsent_roundTrips() {
        val record = GuestQuarantineRecord(
            transitionId = "t".repeat(22),
            guestUid = "guest-1",
            importOperationId = "op-1",
            intent = TransitionIntent.DISCARD,
            stagedAtMs = 5L,
            snapshot = GuestSnapshot(
                habits = emptyList(),
                streaks = emptyList(),
                milestones = emptyList(),
                attempts = emptyList(),
                sessions = emptyList(),
                coaching = emptyList(),
                riskWindows = emptyList(),
                policy = null,
            ),
        )

        val decoded = GuestQuarantineCodec.decode(GuestQuarantineCodec.encode(record))

        assertEquals(record, decoded)
        assertNull(decoded.targetUid)
        assertNull(decoded.importedAtMs)
        assertNull(decoded.summary)
    }

    @Test
    fun deliveryBookkeeping_isNeverCarried() {
        val record = GuestQuarantineRecord(
            transitionId = "t".repeat(22),
            guestUid = "guest-1",
            importOperationId = "op-1",
            intent = TransitionIntent.IMPORT,
            stagedAtMs = 5L,
            snapshot = GuestSnapshot(
                habits = emptyList(),
                streaks = emptyList(),
                milestones = emptyList(),
                attempts = listOf(AttemptLog(occurredAtEpochMillis = 1L, ruleId = "r", blockedTarget = "b", outcome = "BLOCKED", eventSequence = 42L)),
                sessions = listOf(
                    RecoverySession(
                        id = "j1",
                        technique = "none",
                        state = RecoverySession.STATE_COMPLETED,
                        startedAtEpochMs = 1L,
                        zoneId = "UTC",
                        entry = "TAB",
                        eventSequence = 43L,
                    ),
                ),
                coaching = emptyList(),
                riskWindows = emptyList(),
                policy = null,
            ),
        )

        val decoded = GuestQuarantineCodec.decode(GuestQuarantineCodec.encode(record))

        assertNull("terminated guest pairs can never be re-delivered", decoded.snapshot.attempts.single().eventSequence)
        assertNull(decoded.snapshot.sessions.single().eventSequence)
    }

    @Test
    fun unknownVersion_isRejected() {
        val record = GuestQuarantineRecord(
            transitionId = "t".repeat(22),
            guestUid = "guest-1",
            importOperationId = "op-1",
            intent = TransitionIntent.IMPORT,
            stagedAtMs = 5L,
            snapshot = GuestSnapshot(
                emptyList(), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), null,
            ),
        )
        val text = GuestQuarantineCodec.encode(record)
            .replace("\"version\":${GuestQuarantineCodec.VERSION}", "\"version\":${GuestQuarantineCodec.VERSION + 1}")

        assertThrows(IllegalArgumentException::class.java) { GuestQuarantineCodec.decode(text) }
    }

    @Test
    fun malformedPayload_isRejected() {
        assertThrows(Exception::class.java) { GuestQuarantineCodec.decode("not json") }
        assertThrows(Exception::class.java) { GuestQuarantineCodec.decode("{\"version\":1}") }
    }
}
