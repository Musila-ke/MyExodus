package com.example.myexodus.accountability.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.blocking.EnhancedModeTestEntryPoint
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
 * via `-e partnerUid`. Everything AFTER the row is production: the same `syncOnce` pass the
 * app runs links it to the pair, signs it with the real keystore (needs the 900 s warm window
 * — tap Send in the app once, then run this), drains it to the partner's mailbox, and the
 * partner device receives it over the real FCM/mailbox rails.
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
        val context = ApplicationProvider.getApplicationContext<Context>()
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
        val enrollment = assumePresent(
            ep.enrollmentDao().getByPairId(pairId),
            "no enrollment for pair=$pairId (uidA=$uidA uidB=$uidB) — run the pairing harness first",
        )

        // v16/ADR #135: the reconcile filter joins the row to its habit's consent slice —
        // a row without habit_progress_id matches no branch and would never deliver. Seed
        // through the pair's real consented habit so everything stays production-shaped.
        val sharing = store.pairSharingDao().streakForPair(pairId)
            .firstOrNull { it.shareStreak && it.shareMilestones }
            ?: error("no habit with shared streak+milestones for pair=$pairId — enable both toggles first")
        val habit = store.habitProgressDao().getById(sharing.habitProgressId)
            ?: error("consented habit ${sharing.habitProgressId} does not exist")
        val streak = store.recoveryStreakDao().getActiveStreak(habit.progressId)
        val achieved = streak?.let { store.milestoneEventDao().getForStreak(it.streakId) }
            ?.filter { it.achievedAt != null }?.map { it.milestoneDay }?.sorted().orEmpty()

        val now = System.currentTimeMillis()
        store.partnerActivityDao().insertJournal(
            PartnerActivityOutbox(
                eventId = UUID.randomUUID().toString(),
                kind = EventPayload.TYPE_MILESTONE_REACHED,
                habitProgressId = habit.progressId,
                targetPairId = pairId,
                text = "",
                occurredAtMs = now,
                daysFree = 7,
                relapseCount = habit.totalRelapses,
                milestonesReached = encodeMilestoneDays((achieved + 7).distinct().sorted()),
                habitLabel = habit.targetIdentifier.take(EventPayload.MAX_HABIT_LABEL_LENGTH),
                milestoneDay = 7,
                coachingProgress = 0,
                protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
                schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
                createdAtMs = now,
            ),
        )
        harnessLog("journey seed: milestone row written pair=$pairId partner=${enrollment.partnerLabel}")

        val report = ep.partnerSyncCoordinator().syncOnce(null)
        harnessLog("journey seed sync: $report")
        assertTrue("seed row must link + sign: $report", report.eventsLinked >= 1)
        assertTrue("seed row must drain to the partner mailbox: $report", report.drained)
        harnessLog(
            "RESULT journeySeed=PASS pair=$pairId daysFree=7 milestones=[7] " +
                "eventsLinked=${report.eventsLinked} drained=${report.drained}",
        )
    }
}
