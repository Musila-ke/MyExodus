package com.example.myexodus.accountability.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.blocking.EnhancedModeTestEntryPoint
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.LocalDatabaseMigration
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.PartnerActivityOutbox
import com.example.myexodus.data.db.entity.encodeMilestoneDays
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Journey seed on the PHYSICAL device (owner-approved, 2026-10-01: "in some u can seed just
 * for testing"): a milestone cannot be produced by real elapsed time inside a session (7 real
 * days), so this harness writes ONE journal row through the production DAO with the snapshot
 * the real transition would have produced (day 7 reached), addressed to the owner's live pair
 * via `-e partnerUid`. Everything AFTER the row is production and MUST run in the app process:
 * foreground the app so its own `deliverQueuedPartnerEvents()` pass links the row, signs it
 * with the real keystore, and submits it; then verify from logcat + the backend ingest log.
 *
 * Seed-only by design: an earlier version ran `syncOnce` inside the test process, which links
 * the journal row in the RULES store to an outbox row in the LEGACY accountability file the
 * instrumented process binds (MyExodusApp skips LocalDatabaseMigration.start under
 * instrumentation, MyExodusApp.kt:286, before the Hilt DB singleton is built). The link made
 * the production pass skip the row forever while nothing reached the scoped outbox.
 *
 * Deliberate bypass, documented: the consent gate (`PartnerActivityJournal.isShared`) lives
 * above the DAO; a seed row doesn't pass through it. The row is tagged with the real version
 * pair so the receiving projection treats it exactly like a production emission.
 *
 * Run (A device only, never connectedAndroidTest):
 *   adb -s <A> shell am instrument -w \
 *     -e class com.example.myexodus.accountability.sync.PartnerJourneySeedInstrumentedTest \
 *     -e partnerUid <B's uid> com.example.myexodus.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PartnerJourneySeedInstrumentedTest {

    @Test
    fun seedMilestone_deliversToOwnerPair_overProductionRails(): Unit = runBlocking {
        val seed = journeySeed(requireMilestones = true)
        val now = System.currentTimeMillis()
        val eventId = UUID.randomUUID().toString()
        seed.store.partnerActivityDao().insertJournal(
            PartnerActivityOutbox(
                eventId = eventId,
                kind = EventPayload.TYPE_MILESTONE_REACHED,
                habitProgressId = seed.habit.progressId,
                targetPairId = seed.pairId,
                text = "",
                occurredAtMs = now,
                daysFree = 7,
                relapseCount = seed.habit.totalRelapses,
                milestonesReached = encodeMilestoneDays((seed.achieved + 7).distinct().sorted()),
                habitLabel = seed.habit.targetIdentifier.take(EventPayload.MAX_HABIT_LABEL_LENGTH),
                milestoneDay = 7,
                coachingProgress = 0,
                protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
                schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
                createdAtMs = now,
            ),
        )
        harnessLog("journey seed: milestone row written pair=${seed.pairId}")
        assertQueuedForProduction(seed.store, seed.pairId, eventId, "milestone", daysFree = 7)
    }

    /** S15's day crossing cannot occur inside a session either; the row carries the snapshot
     *  the real STREAK_UPDATED emission would have produced on the first clean day. */
    @Test
    fun seedStreakUpdated_deliversGenericActivity_overProductionRails(): Unit = runBlocking {
        val seed = journeySeed(requireMilestones = false)
        val now = System.currentTimeMillis()
        val eventId = UUID.randomUUID().toString()
        seed.store.partnerActivityDao().insertJournal(
            PartnerActivityOutbox(
                eventId = eventId,
                kind = EventPayload.TYPE_STREAK_UPDATED,
                habitProgressId = seed.habit.progressId,
                targetPairId = seed.pairId,
                text = "",
                occurredAtMs = now,
                daysFree = 2,
                relapseCount = seed.habit.totalRelapses,
                milestonesReached = encodeMilestoneDays(seed.achieved),
                habitLabel = seed.habit.targetIdentifier.take(EventPayload.MAX_HABIT_LABEL_LENGTH),
                coachingProgress = 0,
                protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
                schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
                createdAtMs = now,
            ),
        )
        harnessLog("journey seed: streak-updated row written pair=${seed.pairId}")
        assertQueuedForProduction(seed.store, seed.pairId, eventId, "streak-updated", daysFree = 2)
    }

    private class Seed(
        val store: AppRulesStore,
        val pairId: String,
        val habit: HabitProgress,
        val achieved: List<Int>,
    )

    private suspend fun journeySeed(requireMilestones: Boolean): Seed {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Under instrumentation MyExodusApp skips the scope decision (MyExodusApp.kt:286); the
        // live app then writes a scoped DB this process would never see. Activate the scope so
        // the seed lands in the store the running app reads.
        LocalDatabaseMigration.start(context)
        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)
        assumePartnerHarness(ep)

        val uidA = FirebaseAuth.getInstance().currentUser?.uid
            ?: error("no signed-in identity — launch the app once (anonymous auth) and re-run")
        val uidB = assumePresent(
            InstrumentationRegistry.getArguments().getString("partnerUid"),
            "pass -e partnerUid <partner uid>",
        )
        val pairId = EnrollmentTranscript.durablePairIdOf(uidA, uidB)

        val store = EntryPointAccessors
            .fromApplication(context, EnhancedModeTestEntryPoint::class.java)
            .appRulesStoreFactory()
            .open() ?: error("rules store unavailable")

        // v16/ADR #135: the reconcile filter joins the row to its habit's consent slice —
        // a row without habit_progress_id matches no branch and would never deliver. Seed
        // through the pair's real consented habit so everything stays production-shaped.
        val sharing = store.pairSharingDao().streakForPair(pairId)
            .firstOrNull { it.shareStreak && (!requireMilestones || it.shareMilestones) }
            ?: error("no habit with shared streak${if (requireMilestones) "+milestones" else ""} for pair=$pairId — enable the toggles first")
        val habit = store.habitProgressDao().getById(sharing.habitProgressId)
            ?: error("consented habit ${sharing.habitProgressId} does not exist")
        val streak = store.recoveryStreakDao().getActiveStreak(habit.progressId)
        val achieved = streak?.let { store.milestoneEventDao().getForStreak(it.streakId) }
            ?.filter { it.achievedAt != null }?.map { it.milestoneDay }?.sorted().orEmpty()

        harnessLog("journey seed: pair=$pairId uidA=$uidA uidB=$uidB")
        return Seed(store, pairId, habit, achieved)
    }

    /** The row must still be UNLINKED (visible to the production reconcile filter); a link
     *  here would mean a test-process pass already claimed it against the legacy outbox. */
    private suspend fun assertQueuedForProduction(
        store: AppRulesStore,
        pairId: String,
        eventId: String,
        label: String,
        daysFree: Int,
    ) {
        val pending = store.partnerActivityDao().pendingForPair(pairId, 0, 100).map { it.eventId }
        assertTrue("seed row $eventId must be queued for the app's own pass: $pending", eventId in pending)
        harnessLog(
            "RESULT journeySeed=QUEUED kind=$label daysFree=$daysFree eventId=$eventId " +
                "— foreground the app now (warm 900 s window) and verify the drain from logcat",
        )
    }
}
