package com.example.myexodus.ui.partner

import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.state.ChainEvent
import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.accountability.state.UnpairPhase
import com.example.myexodus.accountability.state.UnpairState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the dashboard read model (FR3 / §11 cache-first): local-only, decode-skipping,
 *  latest-enrollment-wins — with fakes, no Android or Firestore. */
class PartnerDashboardSourceTest {

    private val now = 1_700_000_000_000L
    private val weekMillis = 7 * 24 * 60 * 60 * 1000L

    private fun enrollment(
        pairId: String = "pair-1",
        partnerKeyId: String = "partner-key",
        createdAt: Long = 1_000L,
        watermark: Long? = null,
    ) = EnrollmentRecord(
        pairId = pairId,
        mySenderKeyId = "my-key",
        partnerSigningKeyId = partnerKeyId,
        partnerSigningPubKey = byteArrayOf(1),
        partnerAgreementPubKey = byteArrayOf(2),
        createdAtEpochMillis = createdAt,
        mailboxWatermarkTsEpochMillis = watermark,
    )

    private fun row(createdAt: Long) = ChainEvent(
        pairId = "pair-1",
        senderKeyId = "partner-key",
        epoch = 0,
        sequence = 1,
        previousEventHash = null,
        eventHash = byteArrayOf(0),
        envelope = byteArrayOf(9),
        direction = ChainEvent.DIRECTION_RECEIVED,
        createdAtEpochMillis = createdAt,
        syncState = ChainEvent.SYNC_STATE_SENT,
    )

    private fun head(
        repairState: String = ChainHead.REPAIR_NONE,
        forked: Boolean = false,
    ) = ChainHead(
        pairId = "pair-1",
        senderKeyId = "partner-key",
        lastSequence = 1,
        lastEventHash = byteArrayOf(0),
        forked = forked,
        repairState = repairState,
    )

    private class RecordingSource(
        val source: PartnerDashboardSource,
        val sinceArgs: MutableList<Long> = mutableListOf(),
    )

    private fun source(
        enrollments: List<EnrollmentRecord> = listOf(enrollment()),
        enrollmentsThrow: Boolean = false,
        head: ChainHead? = head(),
        rows: List<ChainEvent> = emptyList(),
        rowsThrow: Boolean = false,
        decode: (ByteArray) -> String? = { null },
        decoderForThrows: Boolean = false,
        journal: List<UnpairState> = emptyList(),
        /** Step 17: the agreement-key signal — true on any device whose pair was ever set up. */
        priorPairingKeys: Boolean = false,
        /** Step 18: relay rejections discovered inside the dashboard's window. */
        rejectionsSince: Int = 0,
        rejectionsThrow: Boolean = false,
    ): RecordingSource {
        val sinceArgs = mutableListOf<Long>()
        val source = PartnerDashboardSource(
            enrollments = {
                if (enrollmentsThrow) throw IllegalStateException("db unopenable") else enrollments
            },
            head = { _, _ -> head },
            receivedSince = { _, since ->
                sinceArgs += since
                if (rowsThrow) throw IllegalStateException("db unavailable") else rows
            },
            decoderFor = {
                if (decoderForThrows) throw IllegalStateException("no key material")
                val decodeRow: (ByteArray) -> String? = { envelope -> decode(envelope) }
                decodeRow
            },
            unpairRows = { journal },
            hasPriorPairingKeys = { priorPairingKeys },
            rejectionsSince = { _, _ ->
                if (rejectionsThrow) throw IllegalStateException("outbox read failed") else rejectionsSince
            },
        )
        return RecordingSource(source, sinceArgs)
    }

    private fun journalRow(pairId: String = "pair-1", requestedAt: Long = 500L) = UnpairState(
        pairId = pairId,
        role = UnpairState.ROLE_REQUESTER,
        partnerUid = "uid-2",
        messageId = "msg-1",
        senderKeyId = "my-key",
        requestEnvelope = byteArrayOf(7),
        delivered = true,
        ackSent = false,
        localShredded = true,
        requestedAtEpochMillis = requestedAt,
        confirmedAtEpochMillis = now,
    )

    @Test
    fun noEnrollment_readsAsUnpaired() = runTest {
        val ui = source(enrollments = emptyList()).source.load(now)
        assertEquals(PartnerDashboardUi.Unpaired, ui)
    }

    @Test
    fun noEnrollment_butTheKeystoreKeySurvives_readsAsWipeDetected() = runTest {
        // §8 Step 17 (AD-22): a wipe never leaves a journal row behind (the DB it lived in is
        // gone), and a key with no enrollment cannot be a legit unpair — the shred destroys
        // the key before deleting the enrollment.
        val ui = source(enrollments = emptyList(), priorPairingKeys = true).source.load(now)
        assertEquals(PartnerDashboardUi.WipeDetected, ui)
    }

    @Test
    fun wipeKeyWins_overAStaleJournalRow() = runTest {
        // Key present + no enrollment is a wipe by definition, whatever a surviving journal
        // row claims to remember — reporting it as a normal ending would misstate the loss.
        val ui = source(
            enrollments = emptyList(),
            journal = listOf(journalRow()),
            priorPairingKeys = true,
        ).source.load(now)
        assertEquals(PartnerDashboardUi.WipeDetected, ui)
    }

    @Test
    fun unopenableDatabase_readsAsWipeDetected_onlyWhenAPairingKeyExists() = runTest {
        // The plan's "fails to open/decrypt" branch: never strand the screen on Loading.
        val wiped = source(enrollmentsThrow = true, priorPairingKeys = true).source.load(now)
        assertEquals(PartnerDashboardUi.WipeDetected, wiped)

        // No key means nothing was ever paired — an empty read is honest, not a wipe.
        val neverPaired = source(enrollmentsThrow = true, priorPairingKeys = false).source.load(now)
        assertEquals(PartnerDashboardUi.Unpaired, neverPaired)
    }

    @Test
    fun endedJournal_stillReadsAsEnded_whenTheKeyWasShredded() = runTest {
        val ui = source(enrollments = emptyList(), journal = listOf(journalRow()))
            .source.load(now) as PartnerDashboardUi.Ended
        assertEquals(UnpairPhase.TERMINAL, ui.phase)
    }

    @Test
    fun latestEnrollment_wins() = runTest {
        val ui = source(
            enrollments = listOf(
                enrollment(pairId = "old-pair", partnerKeyId = "old-key", createdAt = 100L),
                enrollment(pairId = "new-pair", partnerKeyId = "new-key", createdAt = 200L),
            ),
        ).source.load(now) as PartnerDashboardUi.Paired
        assertEquals("new-key", ui.partnerKeyId)
        assertEquals(200L, ui.pairedAtEpochMillis)
    }

    @Test
    fun paired_healthy_carriesDecodedActivityAndTheWatermark() = runTest {
        val paired = source(
            enrollments = listOf(enrollment(watermark = now - 60_000L)),
            rows = listOf(row(now - 3_600_000L)),
            decode = { "blocked:com.x" },
        ).source.load(now) as PartnerDashboardUi.Paired

        assertEquals(PartnerChainStatus.HEALTHY, paired.chainStatus)
        assertEquals(SyncFreshness.FRESH, paired.freshness)
        assertEquals(now - 60_000L, paired.lastSyncedAtEpochMillis)
        assertEquals(listOf(PartnerAlert.AppActivity("com.x", false, now - 3_600_000L)), paired.alerts.recent)
        assertEquals(1, paired.alerts.todayCount)
    }

    @Test
    fun neverSynced_readsNever_andNullWatermark() = runTest {
        val paired = source().source.load(now) as PartnerDashboardUi.Paired
        assertEquals(SyncFreshness.NEVER, paired.freshness)
        assertEquals(null, paired.lastSyncedAtEpochMillis)
    }

    @Test
    fun tamperSuspectedHead_readsUnverified() = runTest {
        val paired = source(head = head(ChainHead.REPAIR_TAMPER_SUSPECTED))
            .source.load(now) as PartnerDashboardUi.Paired
        assertEquals(PartnerChainStatus.UNVERIFIED, paired.chainStatus)
    }

    @Test
    fun forkedHead_readsUnverified() = runTest {
        val paired = source(head = head(forked = true)).source.load(now) as PartnerDashboardUi.Paired
        assertEquals(PartnerChainStatus.UNVERIFIED, paired.chainStatus)
    }

    @Test
    fun repairPending_readsCatchingUp_andMissingHead_readsHealthy() = runTest {
        val repairing = source(head = head(ChainHead.REPAIR_PENDING))
            .source.load(now) as PartnerDashboardUi.Paired
        assertEquals(PartnerChainStatus.CATCHING_UP, repairing.chainStatus)

        val absent = source(head = null).source.load(now) as PartnerDashboardUi.Paired
        assertEquals(PartnerChainStatus.HEALTHY, absent.chainStatus)
    }

    @Test
    fun undecodableRows_areSkipped_andNonActivityPayloadsNeverBecomeAlerts() = runTest {
        val paired = source(
            rows = listOf(row(now), row(now), row(now)),
            decode = { null },
        ).source.load(now) as PartnerDashboardUi.Paired
        assertTrue(paired.alerts.recent.isEmpty())
        assertEquals(0, paired.alerts.todayCount)
    }

    @Test
    fun decoderKeyMaterialFailure_degradesToEmptyActivity_notAFailedLoad() = runTest {
        val paired = source(rows = listOf(row(now)), decoderForThrows = true)
            .source.load(now) as PartnerDashboardUi.Paired
        assertTrue(paired.alerts.recent.isEmpty())
    }

    @Test
    fun receivedReadFailure_stillRendersPaired() = runTest {
        val paired = source(rowsThrow = true).source.load(now) as PartnerDashboardUi.Paired
        assertTrue(paired.alerts.recent.isEmpty())
    }

    @Test
    fun activityRead_isBoundedToTheLastWeek() = runTest {
        val recording = source()
        recording.source.load(now)
        assertEquals(listOf(now - weekMillis), recording.sinceArgs)
    }

    /** §8 Step 18 / §9 row 461: a recent relay rejection is a distinct dashboard state — not
     *  the network-failure copy — and a failed read never fabricates one. */
    @Test
    fun rateLimitRejections_surfaceDistinctly_andAFailedReadDoesNot() = runTest {
        val rejected = source(rejectionsSince = 1).source.load(now) as PartnerDashboardUi.Paired
        assertTrue(rejected.rateLimitedRecently)

        val clean = source(rejectionsSince = 0).source.load(now) as PartnerDashboardUi.Paired
        assertFalse(clean.rateLimitedRecently)

        val failed = source(rejectionsThrow = true).source.load(now) as PartnerDashboardUi.Paired
        assertFalse("an unreadable outbox is not evidence of a rejection", failed.rateLimitedRecently)
    }
}
