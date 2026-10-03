package com.example.myexodus.ui.partner

import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.enrollment.targetEnrollment
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.sync.displayNameOf
import com.example.myexodus.accountability.state.ChainEvent
import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.accountability.state.UnpairPhase
import com.example.myexodus.accountability.state.UnpairState
import java.time.ZoneId

/**
 * The dashboard's read model (plan §7 items 12-13, FR3/§11): built entirely from local state —
 * the enrollment, the partner chain head, stored received envelopes, and approval rows — with
 * ZERO network round-trip on load. Freshness is a property of the mailbox watermark, not of
 * this call.
 *
 * Re-reading a stored envelope goes through the production verify-then-decrypt pipeline
 * (wired in DI as [decoderFor]), so a row that no longer verifies is skipped, never shown.
 * Function seams keep this JVM-testable without Android or Firestore.
 */
class PartnerDashboardSource(
    private val enrollments: suspend () -> List<EnrollmentRecord>,
    private val head: suspend (pairId: String, senderKeyId: String) -> ChainHead?,
    private val receivedSince: suspend (pairId: String, sinceEpochMillis: Long) -> List<ChainEvent>,
    private val decoderFor: (EnrollmentRecord) -> (envelope: ByteArray) -> String?,
    /** Step 13: recent approval rows (both roles, newest first) — local Room read only. */
    private val approvals: suspend (pairId: String) -> List<ApprovalRequest> = { emptyList() },
    /** Step 14: the unpair journal — once the enrollment is gone, this survives the wipe and
     *  is what the screen reports the ending from (§8 Step 13 copy). */
    private val unpairRows: suspend () -> List<UnpairState> = { emptyList() },
    /** Step 17 (AD-22): the agreement-key presence check — the one prior-pairing signal that
     *  lives outside the database, so it survives whatever destroyed it. */
    private val hasPriorPairingKeys: () -> Boolean = { false },
    /** Step 18 (AD-23): relay rejections the client discovered within the window — the
     *  distinct-from-network-failure state the §9 error table promises the dashboard. */
    private val rejectionsSince: suspend (pairId: String, sinceEpochMillis: Long) -> Int = { _, _ -> 0 },
    /** §7 Step 11 delta: the received partner-visible aggregate, read from Room (never from
     *  Firestore on paint) — the dashboard's "how is my partner doing" half. */
    private val partnerActivity: suspend (pairId: String) -> PartnerActivityUi? = { null },
    /** OPD #30: when this pairing's document first denied this device — the anonymous-identity
     *  rotation (or a partner-side eviction) that freezes sync silently. Null = no such evidence.
     *  Only a definitive server denial is ever written here (see `isDefinitiveDenial`), so an
     *  offline device never raises the re-pair card. */
    private val pairingDeniedAt: (pairId: String) -> Long? = { null },
    /** Owner ask 2026-10-01: the writer's own last composed message to this pair, read back
     *  from the local journal — the Activity subpage shows the sender what they sent. */
    private val latestSentMessage: suspend (pairId: String) -> String? = { null },
) {

    /**
     * [selectedPairId] (§19.5) selects which supported user's dashboard to build: null, or an
     * id no longer in the roster, falls back to the most recently activated pairing. Every
     * stream below is scoped to the resolved enrollment's pairId — never an implicit "the"
     * enrollment.
     */
    suspend fun load(
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        selectedPairId: String? = null,
    ): PartnerDashboardUi {
        // A failed read is the plan's "fails to open/decrypt" branch, not a plain no-pairing:
        // it folds into the same key check below rather than stranding the screen on Loading.
        val all = runCatching { enrollments() }.getOrNull().orEmpty()
        val enrollment = all.targetEnrollment(selectedPairId)
        if (enrollment == null) {
            // Step 17 (AD-22): the agreement key is destroyed before the enrollment on every
            // legitimate unpair, so key-present + enrollment-absent cannot be a normal ending —
            // it is a wipe, and a wipe is what the screen must say.
            if (runCatching { hasPriorPairingKeys() }.getOrDefault(false)) {
                return PartnerDashboardUi.WipeDetected
            }
            // No enrollment + a journal row = the pair ended (or is ending); with neither it
            // never existed. A live enrollment always wins: it may be a fresh pair with the
            // same partner, where a leftover row is superseded (resumePending clears it).
            val ended = runCatching { unpairRows() }.getOrElse { emptyList() }
                .maxByOrNull { it.requestedAtEpochMillis }
            return if (ended == null) {
                PartnerDashboardUi.Unpaired
            } else {
                PartnerDashboardUi.Ended(
                    pairId = ended.pairId,
                    role = ended.role,
                    phase = ended.phaseAt(nowMillis),
                    canDismiss = ended.isDismissible(nowMillis),
                )
            }
        }

        val rows = runCatching { receivedSince(enrollment.pairId, nowMillis - WEEK_MILLIS) }
            .getOrElse { emptyList() }
        // A key-material failure (unreadable keystore entry, corrupt DER) must not strand
        // the screen on Loading — it degrades to "no decodable rows", the honest reading.
        val decode = runCatching { decoderFor(enrollment) }.getOrElse { { _: ByteArray -> null } }
        val alerts = rows.mapNotNull { row ->
            runCatching { decode(row.envelope) }
                .getOrNull()
                ?.let { parseActivityText(it, row.createdAtEpochMillis) }
        }
        val chainHead = head(enrollment.pairId, enrollment.partnerSigningKeyId)
        val approvalRows = runCatching { approvals(enrollment.pairId) }.getOrElse { emptyList() }
            .map {
                ApprovalUi(
                    requestEventId = it.requestEventId,
                    role = it.role,
                    status = it.status,
                    message = it.message,
                    createdAtEpochMillis = it.createdAtEpochMillis,
                    expiresAtEpochMillis = it.expiresAtEpochMillis,
                )
            }
        return PartnerDashboardUi.Paired(
            pairId = enrollment.pairId,
            partnerKeyId = enrollment.partnerSigningKeyId,
            partnerLabel = enrollment.partnerLabel,
            users = all.sortedByDescending { it.createdAtEpochMillis }
                .map {
                    PartnerUserUi(
                        pairId = it.pairId,
                        label = it.partnerLabel,
                        keyId = it.partnerSigningKeyId,
                        partneredAtEpochMillis = it.createdAtEpochMillis,
                    )
                },
            pairedAtEpochMillis = enrollment.createdAtEpochMillis,
            lastSyncedAtEpochMillis = enrollment.mailboxWatermarkTsEpochMillis,
            freshness = syncFreshness(enrollment.mailboxWatermarkTsEpochMillis, nowMillis),
            chainStatus = chainStatusFor(
                repairState = chainHead?.repairState ?: ChainHead.REPAIR_NONE,
                forked = chainHead?.forked ?: false,
            ),
            alerts = summarizeAlerts(alerts, startOfLocalDay(nowMillis, zone)),
            sentMessageText = runCatching { latestSentMessage(enrollment.pairId) }.getOrNull(),
            approvals = approvalRows,
            activity = runCatching { partnerActivity(enrollment.pairId) }.getOrNull(),
            rateLimitedRecently = runCatching {
                rejectionsSince(enrollment.pairId, nowMillis - REJECTION_WINDOW_MILLIS) > 0
            }.getOrDefault(false),
            pairingUnreachable = runCatching { pairingDeniedAt(enrollment.pairId) }
                .getOrNull() != null,
        )
    }

    /**
     * #12: pairId -> shown name for the Home pinned-message card, which pages across senders.
     * The same enrollment read [load] folds in, kept minimal (no chain work); a failed read
     * degrades to no names — the card falls back to its generic title, never a wrong name.
     */
    suspend fun partnerDisplayNames(): Map<String, String> =
        runCatching { enrollments() }.getOrNull().orEmpty()
            .associate { it.pairId to displayNameOf(it) }

    private companion object {
        const val WEEK_MILLIS = 7 * 24 * 60 * 60 * 1000L
        const val REJECTION_WINDOW_MILLIS = 24 * 60 * 60 * 1000L
    }
}

/** Screen state for the partner dashboard. [Loading] is a first-paint placeholder only —
 *  the load itself is a local Room read, never a network wait. */
sealed interface PartnerDashboardUi {
    data object Loading : PartnerDashboardUi

    /** No enrollment and no unpair journal on this device: nothing is (or can be) shared yet. */
    data object Unpaired : PartnerDashboardUi

    /**
     * §8 Step 17 (AD-22): local pairing data was lost — the agreement key survives, its
     * enrollment does not. No recovery is attempted and none is offered here: the only route
     * forward is SAS/QR re-enrollment, which this state names. No CTA exists because no
     * production pairing UI does (Open Product Decision #16 — harness-only pairing).
     */
    data object WipeDetected : PartnerDashboardUi

    data class Paired(
        /** The pairing's id — the unpair flow's address (journal key, pair-doc). */
        val pairId: String,
        val partnerKeyId: String,
        /** §19.5: the partner's self-chosen name; null = never exchange one — the surface
         *  falls back to the key-id fragment rather than inventing a name. */
        val partnerLabel: String? = null,
        /** §19.5 requirement 1: every active enrollment, newest first — the switcher's roster.
         *  Always contains this pairing; [users].size > 1 is when the switcher renders. */
        val users: List<PartnerUserUi> = emptyList(),
        val pairedAtEpochMillis: Long,
        val lastSyncedAtEpochMillis: Long?,
        val freshness: SyncFreshness,
        val chainStatus: PartnerChainStatus,
        val alerts: AlertSummary,
        /** Owner ask 2026-10-01: the last message this user wrote to this partner (journal
         *  read-back); null = nothing sent yet. */
        val sentMessageText: String? = null,
        /** Step 13 approval rows: RESPONDER rows are actionable, REQUESTER rows status-only. */
        val approvals: List<ApprovalUi> = emptyList(),
        /** §7 Step 11 delta: the partner's shared aggregate; null = nothing received yet. */
        val activity: PartnerActivityUi? = null,
        /** Step 18: the server rejected a recent send (AD-23 rate limit) — the mailbox write
         *  itself succeeded, so this is explicitly not the network-failure copy. */
        val rateLimitedRecently: Boolean = false,
        /** OPD #30: the pairing's document denied this device on a read. The enrollment is live,
         *  so this is not an ending — it is a connection that cannot be restored locally, and
         *  the screen names the cause (an identity change, an eviction) instead of the network. */
        val pairingUnreachable: Boolean = false,
    ) : PartnerDashboardUi

    /**
     * §8 Step 13: the pairing is over — local shred already ran (AD-13b, unconditional).
     * [phase] distinguishes the remote claim honestly: TERMINAL = the partner's own device
     * confirmed its erase; LOCAL_ONLY_TERMINATED = the 7-day window lapsed without that
     * confirmation. Delivery is never presented as erasure (§17 line 655).
     */
    data class Ended(
        /** The journal row's key — what a dismissal addresses. */
        val pairId: String,
        /** [UnpairState.ROLE_REQUESTER] or [UnpairState.ROLE_RESPONDER]. */
        val role: String,
        val phase: UnpairPhase,
        /** Mirrors the journal's dismissal guard — false while still waiting (and for a
         *  responder whose owed ack has not gone out). */
        val canDismiss: Boolean,
    ) : PartnerDashboardUi
}

/** One row of the §19.5 switcher: an active pairing the owner can bring to the dashboard. */
data class PartnerUserUi(
    val pairId: String,
    /** The partner's self-chosen name; null = the row shows the key-id fragment instead. */
    val label: String?,
    val keyId: String,
    val partneredAtEpochMillis: Long,
)
