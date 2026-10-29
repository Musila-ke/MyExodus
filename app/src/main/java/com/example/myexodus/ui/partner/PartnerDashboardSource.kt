package com.example.myexodus.ui.partner

import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.enrollment.targetEnrollment
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.MonitoringState
import com.example.myexodus.accountability.serialization.OutboxAgeBucket
import com.example.myexodus.data.db.entity.PartnerMonitoringSnapshot
import com.example.myexodus.data.db.entity.PartnerRuleChange
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.sync.displayNameOf
import com.example.myexodus.accountability.state.ChainEvent
import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.accountability.state.UnpairPhase
import com.example.myexodus.accountability.state.UnpairState
import java.time.ZoneId

/**
 * One selectable reach-out recipient (owner ruling 2026-10-23): the SOS dialog names exactly
 * one partner per send — never a fan-out, and never a hidden stored "SOS partner".
 */
data class ReachOutTarget(val pairId: String, val name: String)

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
    private val decoderFor: (EnrollmentRecord) -> (envelope: ByteArray) -> EventPayload?,
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
    /** §7 Step 7: the partner's most recent recipient-local monitoring snapshot (E2EE, read
     *  from SQLCipher). Null = nothing received yet — no claim is made. */
    private val monitoring: suspend (pairId: String) -> PartnerMonitoringSnapshot? = { null },
    /** §8.4 RULE_CONTROL: received rule-lifecycle rows, newest first — the feed's rule half.
     *  Contract bodies never reach [decoderFor], so the receiver's rows are the only source. */
    private val ruleChanges: suspend (pairId: String) -> List<PartnerRuleChange> = { emptyList() },
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
                ?.let { alertOf(it, row.createdAtEpochMillis) }
        } + runCatching { ruleChanges(enrollment.pairId) }.getOrElse { emptyList() }
            .filter { it.occurredAtMinute * 60_000L >= nowMillis - WEEK_MILLIS }
            .mapNotNull(::ruleChangeAlert)
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
            monitoring = runCatching { monitoring(enrollment.pairId) }.getOrNull()?.let { row ->
                val stale = nowMillis - row.receivedAtMs > MONITORING_STALE_MILLIS
                PartnerMonitoringUi(
                    // §8.13: a stale report answers nothing — it reads UNKNOWN, never a
                    // confident ACTIVE carried forward from before the silence.
                    state = if (stale) {
                        MonitoringState.UNKNOWN
                    } else {
                        MonitoringState.fromWire(row.monitoringState) ?: MonitoringState.UNKNOWN
                    },
                    stale = stale,
                    lastReportAtEpochMillis = row.receivedAtMs,
                    updatesDelayed = when (OutboxAgeBucket.fromWire(row.outboxAgeBucket)) {
                        OutboxAgeBucket.SIX_TO_TWENTY_FOUR_HOURS,
                        OutboxAgeBucket.OVER_TWENTY_FOUR_HOURS,
                        -> true
                        else -> false
                    },
                )
            },
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

    /**
     * Owner mandate 2026-10-04 (Q4): who the Reach-out confirmation will actually go to —
     * the same §19.5 target rule the sender resolves at tap time, so the dialog can name the
     * partner. Null = no live pair (the dialog then never opens — its FAB gate needs one).
     */
    suspend fun targetPartnerName(selectedPairId: String? = null): String? =
        runCatching { enrollments() }.getOrNull().orEmpty()
            .targetEnrollment(selectedPairId)?.let { displayNameOf(it) }

    /**
     * Every live pair as (pairId, shown name), newest first — the rule editor's assignee list.
     * The same enrollment read as [reachOutTargets]; a failed read degrades to an empty list,
     * which the editor renders as "no partners yet", never a wrong name.
     */
    suspend fun pairRoster(): List<ReachOutTarget> =
        runCatching { enrollments() }.getOrNull().orEmpty()
            .sortedByDescending { it.createdAtEpochMillis }
            .map { ReachOutTarget(it.pairId, displayNameOf(it)) }

    /**
     * The reach-out dialog's recipient list (owner ruling 2026-10-23): the §19.5 target first,
     * so the default choice is exactly the partner the single-partner flow always reached;
     * the rest newest-first. A failed read degrades to an empty list, which the dialog renders
     * as the generic unnamed flow — never a wrong name.
     */
    suspend fun reachOutTargets(selectedPairId: String? = null): List<ReachOutTarget> {
        val all = runCatching { enrollments() }.getOrNull().orEmpty()
            .sortedByDescending { it.createdAtEpochMillis }
        val target = all.targetEnrollment(selectedPairId) ?: return emptyList()
        return (listOf(target) + all.filter { it.pairId != target.pairId })
            .map { ReachOutTarget(it.pairId, displayNameOf(it)) }
    }

    internal companion object {
        const val WEEK_MILLIS = 7 * 24 * 60 * 60 * 1000L
        const val REJECTION_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

        /** Two recorder cycles (the sender journals hourly): inside this the report speaks for
         *  the present; past it the state is UNKNOWN (§8.13's stale-snapshot rule). */
        const val MONITORING_STALE_MILLIS = 2 * 60 * 60 * 1000L
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
        /** §7 Step 7: the partner's device-monitoring report; null = nothing received yet. */
        val monitoring: PartnerMonitoringUi? = null,
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

/**
 * §7 Step 7 / §8.13: the partner's device-monitoring half of the dashboard, decrypted from the
 * last E2EE snapshot. This is deliberately separate from sync freshness and from the chain
 * verdict — a live mailbox is not proof their monitoring runs, and a healthy chain is not
 * either. [stale] means the report is too old to answer for the present; the state then
 * reads UNKNOWN regardless of what the old row said.
 */
data class PartnerMonitoringUi(
    val state: MonitoringState,
    val stale: Boolean,
    val lastReportAtEpochMillis: Long,
    /** Their outbox has aged past six hours — their own updates may be arriving late. */
    val updatesDelayed: Boolean,
)

/** One row of the §19.5 switcher: an active pairing the owner can bring to the dashboard. */
data class PartnerUserUi(
    val pairId: String,
    /** The partner's self-chosen name; null = the row shows the key-id fragment instead. */
    val label: String?,
    val keyId: String,
    val partneredAtEpochMillis: Long,
)
