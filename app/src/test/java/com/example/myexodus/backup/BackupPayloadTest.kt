package com.example.myexodus.backup

import android.app.Application
import com.example.myexodus.account.GuestSnapshot
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RiskWindow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account plan §6.4 items 6-7 / §6.6 item 6: the payload keeps the whole logical allowlist
 * byte-exact, stays bounded under the 100 MiB / 20 MiB / 100:1 limits (ADR #247), and rejects
 * anything that is not a complete, well-formed snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupPayloadTest {

    private class Collector : SnapshotSink {
        val habits = mutableListOf<HabitProgress>()
        val streaks = mutableListOf<RecoveryStreak>()
        val milestones = mutableListOf<MilestoneEvent>()
        val attempts = mutableListOf<AttemptLog>()
        val sessions = mutableListOf<RecoverySession>()
        val coaching = mutableListOf<CoachingStep>()
        val riskWindows = mutableListOf<RiskWindow>()
        var policy: RecoveryPolicy? = null

        fun snapshot() = GuestSnapshot(
            habits = habits,
            streaks = streaks,
            milestones = milestones,
            attempts = attempts,
            sessions = sessions,
            coaching = coaching,
            riskWindows = riskWindows,
            policy = policy,
        )

        override fun habit(value: HabitProgress) { habits += value }
        override fun streak(value: RecoveryStreak) { streaks += value }
        override fun milestone(value: MilestoneEvent) { milestones += value }
        override fun attempt(value: AttemptLog) { attempts += value }
        override fun session(value: RecoverySession) { sessions += value }
        override fun coaching(value: CoachingStep) { coaching += value }
        override fun riskWindow(value: RiskWindow) { riskWindows += value }
        override fun policy(value: RecoveryPolicy) { policy = value }
    }

    private val fullSnapshot = GuestSnapshot(
        habits = listOf(
            HabitProgress(
                progressId = "h1",
                profileId = "profile-1",
                targetIdentifier = "Porn",
                currentCleanDaysStreak = 2,
                totalRelapses = 3,
                accessAttemptsCount = 4,
                habitDurationYears = 5,
                onboardingSeverityLevel = "MODERATE",
                lastRelapseTimestamp = 900L,
                assignedPartnershipId = "pair-1",
                allTimeMaxStreakElapsedMs = 86_400_000L,
                isSelected = true,
            ),
            HabitProgress(
                progressId = "h2",
                profileId = "profile-1",
                targetIdentifier = "Gambling — ünïcode",
                currentCleanDaysStreak = 0,
                totalRelapses = 0,
                accessAttemptsCount = 0,
                habitDurationYears = 0,
                onboardingSeverityLevel = "LOW",
                lastRelapseTimestamp = null,
                assignedPartnershipId = null,
                allTimeMaxStreakElapsedMs = 0,
                isSelected = false,
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
            RecoveryStreak(
                streakId = "s2",
                habitId = "h1",
                recoveryStartAt = 1_000L,
                relapsedAt = null,
                relapseBootCount = null,
                relapseElapsedRealtimeMs = null,
                tWallLastObserved = 1_001L,
                rBootLastObserved = 1_002L,
                bootCountLastObserved = 1_003L,
                currentStreakElapsedMs = 1_004L,
                rowVersion = 1_005L,
            ),
        ),
        milestones = listOf(
            MilestoneEvent("s1", 7, achievedAt = 10L, notificationSentAt = 11L, acknowledgedAt = 12L),
            MilestoneEvent("s2", 30, achievedAt = null, notificationSentAt = null, acknowledgedAt = null),
        ),
        attempts = listOf(
            AttemptLog(occurredAtEpochMillis = 13L, ruleId = "r1", blockedTarget = "com.x", outcome = "BLOCKED"),
            AttemptLog(occurredAtEpochMillis = 14L, ruleId = "r2", blockedTarget = "com.y", outcome = "ALLOWED"),
        ),
        sessions = listOf(
            RecoverySession(
                id = "j1",
                technique = "box_breathing",
                intensityStart = 6,
                intensityEnd = 2,
                state = RecoverySession.STATE_COMPLETED,
                startedAtEpochMs = 15L,
                endedAtEpochMs = 16L,
                durationSec = 60,
                zoneId = "Africa/Nairobi",
                entry = "Line one\nLine \"two\" — ünïcode",
                falseAlarm = true,
            ),
            RecoverySession(
                id = "j2",
                technique = "none",
                intensityStart = null,
                intensityEnd = null,
                state = RecoverySession.STATE_ABANDONED,
                startedAtEpochMs = 17L,
                endedAtEpochMs = null,
                durationSec = null,
                zoneId = "UTC",
                entry = "TAB",
                falseAlarm = false,
            ),
        ),
        coaching = listOf(
            CoachingStep("c1", "profile-1", 0, "Call Sam", "1", CoachingStep.KIND_IF_THEN),
        ),
        riskWindows = listOf(
            RiskWindow(id = 1, startMinute = 1200, endMinute = 1380, daysMask = 127, enabled = false),
        ),
        policy = RecoveryPolicy(defaultFrictionSeconds = 30, overrideCooldownSeconds = 60),
    )

    private fun write(snapshot: GuestSnapshot): ByteArray {
        val out = ByteArrayOutputStream()
        BackupPayload.write(snapshot, out)
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray, declaredLength: Long = bytes.size.toLong()): Pair<Collector, BackupPayload.Sizes> {
        val collector = Collector()
        val sizes = BackupPayload.read(ByteArrayInputStream(bytes), declaredLength, collector)
        return collector to sizes
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private val headerLine = "{\"format\":\"MYEXODUS-SNAPSHOT\",\"version\":1}\n"

    @Test
    fun fullSnapshot_roundTripsExactlyInOrder() {
        val bytes = write(fullSnapshot)
        val (collector, sizes) = read(bytes)

        assertEquals(fullSnapshot, collector.snapshot())
        assertEquals(bytes.size.toLong(), sizes.compressedBytes)
        assertEquals(collector.snapshot(), fullSnapshot)
    }

    @Test
    fun emptySnapshot_roundTrips() {
        val empty = GuestSnapshot(
            habits = emptyList(),
            streaks = emptyList(),
            milestones = emptyList(),
            attempts = emptyList(),
            sessions = emptyList(),
            coaching = emptyList(),
            riskWindows = emptyList(),
            policy = null,
        )

        val (collector, _) = read(write(empty))

        assertEquals(empty, collector.snapshot())
        assertNull(collector.policy)
    }

    @Test
    fun deliveryBookkeeping_isNeverCarried() {
        val snapshot = GuestSnapshot(
            habits = emptyList(),
            streaks = emptyList(),
            milestones = emptyList(),
            attempts = listOf(
                AttemptLog(
                    occurredAtEpochMillis = 1L,
                    ruleId = "r",
                    blockedTarget = "b",
                    outcome = "BLOCKED",
                    eventSequence = 42L,
                ),
            ),
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
        )

        val (collector, _) = read(write(snapshot))

        assertNull(collector.attempts.single().eventSequence)
        assertNull(collector.sessions.single().eventSequence)
    }

    @Test
    fun aSnapshotThatWouldBlowUpOnRestoreIsNeverProduced() {
        val repetitive = fullSnapshot.copy(
            sessions = listOf(
                fullSnapshot.sessions.first().copy(entry = "a".repeat(200_000)),
            ),
        )

        assertThrows(BackupPayloadException.TooLarge::class.java) { write(repetitive) }
    }

    @Test
    fun expansionBomb_isStoppedBeforeItCanFillTheHeap() {
        val bomb = deflate((headerLine + "a".repeat(60_000) + "\n").toByteArray())

        assertThrows(BackupPayloadException.TooLarge::class.java) { read(bomb) }
    }

    @Test
    fun onlyTheExplicitAllowlistRecordsAreAccepted() {
        val unknownType = deflate(
            (headerLine + "{\"type\":\"appRule\",\"data\":{}}\n").toByteArray(),
        )

        assertThrows(BackupPayloadException.Corrupt::class.java) { read(unknownType) }
    }

    @Test
    fun aPayloadWithoutItsHeaderIsRejected() {
        val missing = deflate(
            ("{\"type\":\"habit\",\"data\":{\"progressId\":\"h\"}}\n").toByteArray(),
        )

        assertThrows(BackupPayloadException.Corrupt::class.java) { read(missing) }
    }

    @Test
    fun anUnsupportedPayloadVersionIsRejected() {
        val version2 = deflate(
            ("{\"format\":\"MYEXODUS-SNAPSHOT\",\"version\":2}\n").toByteArray(),
        )

        assertThrows(BackupPayloadException.Corrupt::class.java) { read(version2) }
    }

    @Test
    fun malformedJsonAndInvalidUtf8AreRejected() {
        assertThrows(BackupPayloadException.Corrupt::class.java) {
            read(deflate((headerLine + "{not json\n").toByteArray()))
        }
        assertThrows(BackupPayloadException.Corrupt::class.java) {
            read(deflate(headerLine.toByteArray() + byteArrayOf(0xFF.toByte(), '\n'.code.toByte())))
        }
    }

    @Test
    fun aRecordWithoutDataIsRejected() {
        val noData = deflate((headerLine + "{\"type\":\"habit\"}\n").toByteArray())

        assertThrows(BackupPayloadException.Corrupt::class.java) { read(noData) }
    }

    @Test
    fun aTruncatedDeflateStreamIsRejected() {
        val bytes = write(fullSnapshot)

        assertThrows(BackupPayloadException.Corrupt::class.java) {
            read(bytes.copyOf(bytes.size - 1), bytes.size.toLong() - 1)
        }
    }

    @Test
    fun trailingCompressedBytesAreRejected() {
        val bytes = write(fullSnapshot)

        assertThrows(BackupPayloadException.Corrupt::class.java) {
            read(bytes + byteArrayOf(0), bytes.size.toLong() + 1)
        }
    }

    @Test
    fun anEmptyOrOversizedCompressedPayloadIsRejectedBeforeInflating() {
        assertThrows(BackupPayloadException.Corrupt::class.java) {
            read(ByteArray(0), 0)
        }
        assertThrows(BackupPayloadException.TooLarge::class.java) {
            read(ByteArray(1), BackupPayload.MAX_COMPRESSED_BYTES + 1)
        }
    }

    @Test
    fun sizesReportTheCompressedLengthActuallyConsumed() {
        val bytes = write(fullSnapshot)
        val inflated = java.util.zip.InflaterInputStream(ByteArrayInputStream(bytes))
            .readBytes()

        val (_, sizes) = read(bytes)

        assertEquals(bytes.size.toLong(), sizes.compressedBytes)
        assertEquals(inflated.size.toLong(), sizes.uncompressedBytes)
    }

    // Account plan §16 item 10: measured high-history fixture, not an estimate.
    @Test
    fun aHighHistoryTextOnlySnapshotStaysFarBelowTheCompressedCap() {
        val attempts = (0 until 150_000).map {
            AttemptLog(
                occurredAtEpochMillis = 1_700_000_000_000L + it * 60_000L,
                ruleId = "rule-${it % 50}",
                blockedTarget = "com.example.target${it % 200}",
                outcome = if (it % 3 == 0) "ALLOWED" else "BLOCKED",
            )
        }
        val sessions = (0 until 2_000).map {
            RecoverySession(
                id = "j$it",
                technique = "box_breathing",
                intensityStart = 6,
                intensityEnd = 2,
                state = RecoverySession.STATE_COMPLETED,
                startedAtEpochMs = 1_700_000_000_000L + it * 1_000L,
                endedAtEpochMs = 1_700_000_060_000L + it * 1_000L,
                durationSec = 60,
                zoneId = "Africa/Nairobi",
                entry = "A representative journal entry for session $it, long enough to model real text.",
                falseAlarm = false,
            )
        }
        val snapshot = GuestSnapshot(
            habits = fullSnapshot.habits,
            streaks = fullSnapshot.streaks,
            milestones = (0 until 60).map {
                MilestoneEvent("s1", (it + 1) * 7, achievedAt = 10L, notificationSentAt = 11L, acknowledgedAt = null)
            },
            attempts = attempts,
            sessions = sessions,
            coaching = emptyList(),
            riskWindows = fullSnapshot.riskWindows,
            policy = fullSnapshot.policy,
        )

        val bytes = write(snapshot)
        val (collector, sizes) = read(bytes)

        assertEquals(150_000, collector.attempts.size)
        assertEquals(2_000, collector.sessions.size)
        assertTrue("compressed=${sizes.compressedBytes}", sizes.compressedBytes < BackupPayload.MAX_COMPRESSED_BYTES)
        assertTrue("uncompressed=${sizes.uncompressedBytes}", sizes.uncompressedBytes < BackupPayload.MAX_UNCOMPRESSED_BYTES)
    }
}
