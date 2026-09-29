package com.example.myexodus.ui.partner

import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.state.ChainEvent
import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.accountability.state.UnpairPhase
import com.example.myexodus.accountability.state.UnpairState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        /** §19.5 item 3: the partner's self-chosen name; null = the roster shows a key fragment. */
        label: String? = null,
    ) = EnrollmentRecord(
        pairId = pairId,
        mySenderKeyId = "my-key",
        partnerSigningKeyId = partnerKeyId,
        partnerSigningPubKey = byteArrayOf(1),
        partnerAgreementPubKey = byteArrayOf(2),
        createdAtEpochMillis = createdAt,
        mailboxWatermarkTsEpochMillis = watermark,
        partnerLabel = label,
    )

    private fun row(createdAt: Long, pairId: String = "pair-1") = ChainEvent(
        pairId = pairId,
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
        pairId: String = "pair-1",
    ) = ChainHead(
        pairId = pairId,
        senderKeyId = "partner-key",
        lastSequence = 1,
        lastEventHash = byteArrayOf(0),
        forked = forked,
        repairState = repairState,
    )

    private fun approval(requestEventId: String = "req-1", pairId: String = "pair-1") = ApprovalRequest(
        requestEventId = requestEventId,
        pairId = pairId,
        role = ApprovalRequest.ROLE_REQUESTER,
        status = ApprovalRequest.STATUS_REQUESTED,
        pairingEpoch = 1,
        message = "Unblock com.x",
        createdAtEpochMillis = now - 60_000L,
        expiresAtEpochMillis = now + 60_000L,
    )

    /** §19.5 item 2: every scoped read is recorded with the pairId it was given, so a test can
     *  prove the selection was threaded through rather than an implicit "the" enrollment used. */
    private class RecordingSource(
        val source: PartnerDashboardSource,
        val sinceArgs: MutableList<Long> = mutableListOf(),
        val sincePairIds: MutableList<String> = mutableListOf(),
        val headPairIds: MutableList<String> = mutableListOf(),
        val approvalPairIds: MutableList<String> = mutableListOf(),
        val rejectionPairIds: MutableList<String> = mutableListOf(),
    )

    private fun source(
        enrollments: List<EnrollmentRecord> = listOf(enrollment()),
        enrollmentsThrow: Boolean = false,
        head: ChainHead? = head(),
        /** Per-pair heads, for the multi-user cases; absent keys fall back to [head]. */
        headByPair: Map<String, ChainHead?> = emptyMap(),
        rows: List<ChainEvent> = emptyList(),
        rowsByPair: Map<String, List<ChainEvent>> = emptyMap(),
        rowsThrow: Boolean = false,
        decode: (ByteArray) -> String? = { null },
        decoderForThrows: Boolean = false,
        journal: List<UnpairState> = emptyList(),
        approvalRows: List<ApprovalRequest> = emptyList(),
        approvalsThrow: Boolean = false,
        /** Step 17: the agreement-key signal — true on any device whose pair was ever set up. */
        priorPairingKeys: Boolean = false,
        /** Step 18: relay rejections discovered inside the dashboard's window. */
        rejectionsSince: Int = 0,
        rejectionsByPair: Map<String, Int> = emptyMap(),
        rejectionsThrow: Boolean = false,
        /** OPD #30(b): the recorded first-denial timestamp for this pairing; null = reachable. */
        pairingDeniedAt: (String) -> Long? = { null },
    ): RecordingSource {
        val sinceArgs = mutableListOf<Long>()
        val sincePairIds = mutableListOf<String>()
        val headPairIds = mutableListOf<String>()
        val approvalPairIds = mutableListOf<String>()
        val rejectionPairIds = mutableListOf<String>()
        val source = PartnerDashboardSource(
            enrollments = {
                if (enrollmentsThrow) throw IllegalStateException("db unopenable") else enrollments
            },
            head = { pairId, _ ->
                headPairIds += pairId
                if (headByPair.containsKey(pairId)) headByPair[pairId] else head
            },
            receivedSince = { pairId, since ->
                sinceArgs += since
                sincePairIds += pairId
                if (rowsThrow) throw IllegalStateException("db unavailable") else rowsByPair[pairId] ?: rows
            },
            decoderFor = {
                if (decoderForThrows) throw IllegalStateException("no key material")
                val decodeRow: (ByteArray) -> String? = { envelope -> decode(envelope) }
                decodeRow
            },
            unpairRows = { journal },
            approvals = { pairId ->
                approvalPairIds += pairId
                if (approvalsThrow) throw IllegalStateException("approval read failed") else approvalRows
            },
            hasPriorPairingKeys = { priorPairingKeys },
            pairingDeniedAt = pairingDeniedAt,
            rejectionsSince = { pairId, _ ->
                rejectionPairIds += pairId
                if (rejectionsThrow) throw IllegalStateException("outbox read failed") else {
                    rejectionsByPair[pairId] ?: rejectionsSince
                }
            },
        )
        return RecordingSource(source, sinceArgs, sincePairIds, headPairIds, approvalPairIds, rejectionPairIds)
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
    fun deniedPairingDocument_readsAsUnreachable() = runTest {
        val paired = source(pairingDeniedAt = { 42L })
            .source.load(now) as PartnerDashboardUi.Paired
        assertTrue(paired.pairingUnreachable)
    }

    @Test
    fun reachablePairing_isNotFlagged_andAFailedFlagReadReadsAsReachable() = runTest {
        val paired = source().source.load(now) as PartnerDashboardUi.Paired
        assertFalse(paired.pairingUnreachable)

        // A prefs read that throws degrades to "reachable" — the card is an accusation, so it
        // only ever appears on positive evidence.
        val throwing = source(pairingDeniedAt = { throw IllegalStateException("prefs locked") })
            .source.load(now) as PartnerDashboardUi.Paired
        assertFalse(throwing.pairingUnreachable)
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

    // ---- §19.5 multi-user: one partner, several supported users ----

    /**
     * Item 2: with the pick honored, *every* stream reads the selected pair — head, envelopes,
     * approvals, rejections — never an implicit "the" enrollment.
     */
    @Test
    fun selectingAUser_scopesEveryStreamToThatPair() = runTest {
        val recording = source(
            enrollments = listOf(
                enrollment(pairId = "pair-old", partnerKeyId = "old-key", createdAt = 100L, watermark = now - 60_000L),
                enrollment(pairId = "pair-new", partnerKeyId = "new-key", createdAt = 200L, watermark = null),
            ),
            headByPair = mapOf("pair-old" to head(pairId = "pair-old", forked = true)),
            rowsByPair = mapOf("pair-old" to listOf(row(now - 3_600_000L, pairId = "pair-old"))),
            approvalRows = listOf(approval(requestEventId = "req-old", pairId = "pair-old")),
            rejectionsByPair = mapOf("pair-old" to 1),
            decode = { "blocked:com.x" },
        )

        val picked = recording.source.load(now, selectedPairId = "pair-old") as PartnerDashboardUi.Paired

        assertEquals("pair-old", picked.pairId)
        assertEquals("old-key", picked.partnerKeyId)
        assertEquals(now - 60_000L, picked.lastSyncedAtEpochMillis)
        assertEquals(PartnerChainStatus.UNVERIFIED, picked.chainStatus) // pair-old's forked head
        assertEquals(listOf(PartnerAlert.AppActivity("com.x", false, now - 3_600_000L)), picked.alerts.recent)
        assertEquals(listOf("req-old"), picked.approvals.map { it.requestEventId })
        assertTrue(picked.rateLimitedRecently)

        assertEquals(listOf("pair-old"), recording.headPairIds)
        assertEquals(listOf("pair-old"), recording.sincePairIds)
        assertEquals(listOf("pair-old"), recording.approvalPairIds)
        assertEquals(listOf("pair-old"), recording.rejectionPairIds)
    }

    /** Item 2, the other half: the streams must *change* when the pick changes — a cached or
     *  shared read would show the same watermark for both. */
    @Test
    fun switchingUsers_changesTheScopedData() = runTest {
        val recording = source(
            enrollments = listOf(
                enrollment(pairId = "pair-a", partnerKeyId = "key-a", createdAt = 100L, watermark = now - 60_000L),
                enrollment(pairId = "pair-b", partnerKeyId = "key-b", createdAt = 200L, watermark = null),
            ),
            rejectionsByPair = mapOf("pair-a" to 1),
        )

        val a = recording.source.load(now, selectedPairId = "pair-a") as PartnerDashboardUi.Paired
        assertEquals(SyncFreshness.FRESH, a.freshness)
        assertTrue(a.rateLimitedRecently)

        val b = recording.source.load(now, selectedPairId = "pair-b") as PartnerDashboardUi.Paired
        assertEquals("key-b", b.partnerKeyId)
        assertEquals(SyncFreshness.NEVER, b.freshness)
        assertNull(b.lastSyncedAtEpochMillis)
        assertFalse(b.rateLimitedRecently)
    }

    /** Item 1: default is the most recently activated; a pick that no longer exists (the user
     *  was unpaired, or the roster shrank) falls back rather than blanking the screen. */
    @Test
    fun nullOrStaleSelection_fallsBackToTheMostRecentlyActivated() = runTest {
        val recording = source(
            enrollments = listOf(
                enrollment(pairId = "pair-old", partnerKeyId = "old-key", createdAt = 100L),
                enrollment(pairId = "pair-new", partnerKeyId = "new-key", createdAt = 200L),
            ),
        )

        assertEquals("pair-new", (recording.source.load(now) as PartnerDashboardUi.Paired).pairId)
        assertEquals(
            "pair-new",
            (recording.source.load(now, selectedPairId = "pair-gone") as PartnerDashboardUi.Paired).pairId,
        )
    }

    /** Item 1/3: the roster carries every supported user, newest first, with the label the
     *  dashboard should render — and the selected user's label is the one on screen. */
    @Test
    fun roster_listsEveryUserNewestFirst_andTheSelectionCarriesItsOwnLabel() = runTest {
        val enrollments = listOf(
            enrollment(pairId = "pair-old", createdAt = 100L, label = "Ana"),
            enrollment(pairId = "pair-new", createdAt = 200L, label = null),
        )

        val byDefault = source(enrollments = enrollments).source.load(now) as PartnerDashboardUi.Paired
        assertEquals(listOf("pair-new", "pair-old"), byDefault.users.map { it.pairId })
        assertEquals(listOf(null, "Ana"), byDefault.users.map { it.label })
        assertNull("no label is never invented", byDefault.partnerLabel)

        val picked = source(enrollments = enrollments).source.load(now, selectedPairId = "pair-old")
            as PartnerDashboardUi.Paired
        assertEquals("Ana", picked.partnerLabel)
    }

    /**
     * Item 4 at the read-model level: unpairing one of two leaves the survivor on the dashboard.
     * The journal row for the gone pair must not be mistaken for "the pairing ended", and the
     * selection must not strand on the dead pair.
     */
    @Test
    fun unpairingOneOfTwo_leavesTheSurvivorVisible() = runTest {
        val recording = source(
            enrollments = listOf(enrollment(pairId = "pair-b", partnerKeyId = "key-b", createdAt = 200L)),
            journal = listOf(journalRow(pairId = "pair-a", requestedAt = 500L)),
        )

        val paired = recording.source.load(now) as PartnerDashboardUi.Paired
        assertEquals("pair-b", paired.pairId)
        assertEquals(listOf("pair-b"), paired.users.map { it.pairId })

        // The unpair flow releases the pick: a selection pointing at the dead pair degrades to
        // the survivor, not to Ended.
        val afterUnpair = recording.source.load(now, selectedPairId = "pair-a") as PartnerDashboardUi.Paired
        assertEquals("pair-b", afterUnpair.pairId)
    }

    /** Every stream failing independently, scoped to a non-default pick, still renders the
     *  selected user rather than falling back to another one. */
    @Test
    fun failedStreams_onASelectedPair_stillNameThatPair() = runTest {
        val recording = source(
            enrollments = listOf(
                enrollment(pairId = "pair-old", createdAt = 100L),
                enrollment(pairId = "pair-new", createdAt = 200L),
            ),
            rowsThrow = true,
            approvalsThrow = true,
            rejectionsThrow = true,
        )

        val paired = recording.source.load(now, selectedPairId = "pair-old") as PartnerDashboardUi.Paired
        assertEquals("pair-old", paired.pairId)
        assertEquals(listOf("pair-old"), recording.approvalPairIds)
        assertTrue(paired.approvals.isEmpty())
        assertFalse(paired.rateLimitedRecently)
    }
}
