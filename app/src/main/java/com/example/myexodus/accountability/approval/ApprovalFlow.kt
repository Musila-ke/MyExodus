package com.example.myexodus.accountability.approval

import android.util.Log
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.state.ApprovalRequestDao
import java.security.Signature

/**
 * §8 Step 12 disable-approval flow (AD-07/AD-16): the request travels as a normal chain event
 * (`TYPE_APPROVAL_REQUEST`), the decision as an answering chain event (`TYPE_APPROVAL_RESPONSE`)
 * referencing the request by its canonical event id. One `approval_requests` row exists per
 * request on BOTH devices; every local transition is a single-statement CAS (see
 * [ApprovalRequestDao]) so a replayed or racing message can never move a row twice.
 *
 * Authority split (AD-16): the RESPONDER's row deadline is anchored on the mailbox document's
 * Firestore server timestamp — the security-relevant expiry decision, immune to a manipulated
 * requester clock — and its CAS carries the expiry filter. The REQUESTER's deadline is the
 * local wall clock, display-only; its apply-CAS trusts the responder's decision, and a clock
 * rolled backwards is refused outright rather than allowed to extend anything.
 *
 * The request's own event id is never self-reported: the responder derives it from the
 * envelope's (senderKeyId, sequence) — the chain position the sender cannot spoof (Step 11
 * replays preserve it, mailbox re-drains cannot forge it).
 */
class ApprovalFlow(
    private val dao: ApprovalRequestDao,
    private val enrollmentDao: EnrollmentDao,
    private val creator: EventCreator,
    private val notifyRequest: () -> Unit = {},
    private val notifyDecision: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {

    sealed interface RequestResult {
        data class Submitted(val requestEventId: String) : RequestResult
        data object NotEnrolled : RequestResult
        data class Failed(val reason: String) : RequestResult
    }

    sealed interface RespondResult {
        data class Decided(val approved: Boolean) : RespondResult

        /** The CAS found nothing to move (or the guard refused) — logged, not applied (§9 row 459). */
        data class Refused(val reason: String) : RespondResult
    }

    /**
     * Requester side: chain a `TYPE_APPROVAL_REQUEST` event and persist the REQUESTER row
     * keyed by its canonical event id. The chain append commits first — the row insert is
     * IGNORE-on-conflict, so a retried creation can never double-count.
     */
    suspend fun requestApproval(pairId: String, message: String, signature: Signature): RequestResult {
        val enrollment = enrollmentDao.getByPairId(pairId) ?: return RequestResult.NotEnrolled
        val created = creator.create(
            pairId,
            EventPayload(
                type = EventPayload.TYPE_APPROVAL_REQUEST,
                text = message,
                pairingEpoch = enrollment.pairingEpoch,
            ),
            signature,
        )
        return when (created) {
            is EventCreator.CreateResult.Created -> {
                val requestEventId = ApprovalRequest.eventIdOf(enrollment.mySenderKeyId, created.sequence)
                val at = now()
                dao.insertIgnore(
                    ApprovalRequest(
                        requestEventId = requestEventId,
                        pairId = pairId,
                        role = ApprovalRequest.ROLE_REQUESTER,
                        status = ApprovalRequest.STATUS_REQUESTED,
                        pairingEpoch = enrollment.pairingEpoch,
                        message = message,
                        createdAtEpochMillis = at,
                        // Display anchor only — a manipulated requester clock cannot mint a
                        // response; the responder's own server-anchored deadline is the bound.
                        expiresAtEpochMillis = at + ApprovalRequest.TTL_MILLIS,
                    ),
                )
                RequestResult.Submitted(requestEventId)
            }
            EventCreator.CreateResult.Duplicate -> RequestResult.Failed("request already chained")
            is EventCreator.CreateResult.Failed -> RequestResult.Failed(created.reason)
        }
    }

    /**
     * Responder side decision (the UI calls this after BiometricPrompt). CAS-decide FIRST,
     * then chain the response event; if creation fails the CAS is reverted so the request is
     * decidable again — the row and the wire can never disagree about whether a decision was
     * sent.
     */
    suspend fun respond(requestEventId: String, approve: Boolean, signature: Signature): RespondResult {
        val row = dao.byId(requestEventId) ?: return refuse(requestEventId, "unknown request")
        if (row.role != ApprovalRequest.ROLE_RESPONDER) return refuse(requestEventId, "not this device's side")
        val enrollment = enrollmentDao.getByPairId(row.pairId) ?: return refuse(requestEventId, "not enrolled")
        // AD-07: a request minted under a previous partnership is dead after a re-pair.
        if (row.pairingEpoch != enrollment.pairingEpoch) return refuse(requestEventId, "pairing epoch mismatch")
        val at = now()
        // AD-16 backward-rollback guard: a rewound clock must not resurrect an expired request.
        if (at < row.createdAtEpochMillis) return refuse(requestEventId, "clock rollback")

        val status = if (approve) ApprovalRequest.STATUS_APPROVED else ApprovalRequest.STATUS_REJECTED
        if (dao.casDecide(requestEventId, status, at, at) == 0) {
            return refuse(requestEventId, describeUnchanged(requestEventId, at))
        }
        val created = creator.create(
            row.pairId,
            EventPayload(
                type = EventPayload.TYPE_APPROVAL_RESPONSE,
                text = "",
                requestEventId = row.requestEventId,
                pairingEpoch = enrollment.pairingEpoch,
                decision = approve,
            ),
            signature,
        )
        return when (created) {
            is EventCreator.CreateResult.Created -> RespondResult.Decided(approve)
            // Identical bytes already chained — the decision is effectively delivered.
            EventCreator.CreateResult.Duplicate -> RespondResult.Decided(approve)
            is EventCreator.CreateResult.Failed -> {
                val reverted = dao.casRevert(requestEventId, status) == 1
                refuse(requestEventId, "decision not recorded (${created.reason}); reverted=$reverted")
            }
        }
    }

    /**
     * Responder side, wire path: a verified `TYPE_APPROVAL_REQUEST` was appended to the
     * partner's chain. Records the RESPONDER row with the server-timestamp-anchored deadline
     * and notifies once — a re-delivery (Step 11 replay) hits the IGNORE and stays silent.
     */
    suspend fun onRequestAppended(
        pairId: String,
        senderKeyId: String,
        sequence: Long,
        payload: EventPayload,
        serverTsEpochMillis: Long?,
    ) {
        val enrollment = enrollmentDao.getByPairId(pairId) ?: return
        val requestEpoch = payload.pairingEpoch
        if (requestEpoch == null || requestEpoch != enrollment.pairingEpoch) {
            Log.w(TAG, "approval request with wrong/missing pairing epoch dropped pairId=$pairId")
            return
        }
        val at = serverTsEpochMillis ?: now()
        val inserted = dao.insertIgnore(
            ApprovalRequest(
                requestEventId = ApprovalRequest.eventIdOf(senderKeyId, sequence),
                pairId = pairId,
                role = ApprovalRequest.ROLE_RESPONDER,
                status = ApprovalRequest.STATUS_REQUESTED,
                pairingEpoch = requestEpoch,
                message = payload.text,
                createdAtEpochMillis = at,
                expiresAtEpochMillis = at + ApprovalRequest.TTL_MILLIS,
            ),
        )
        if (inserted != -1L) notifyRequest()
    }

    /**
     * Requester side, wire path: a verified `TYPE_APPROVAL_RESPONSE` was appended. Applies the
     * decision through the CAS; every rejection path is a log, never a state change (§9 row 459,
     * §10 line 624: non-existent, already-decided, or wrong-epoch responses are rejected).
     */
    suspend fun applyResponse(pairId: String, payload: EventPayload, serverTsEpochMillis: Long?) {
        val requestEventId = payload.requestEventId
        val decision = payload.decision
        if (requestEventId == null || decision == null) {
            Log.w(TAG, "malformed approval response dropped pairId=$pairId")
            return
        }
        val enrollment = enrollmentDao.getByPairId(pairId) ?: return
        val row = dao.byId(requestEventId)
        if (row == null || row.role != ApprovalRequest.ROLE_REQUESTER) {
            Log.w(TAG, "approval response for unknown request=$requestEventId — rejected (§9)")
            return
        }
        // AD-07: row epoch, response epoch, and current partnership epoch must all agree.
        val responseEpoch = payload.pairingEpoch
        if (responseEpoch == null || row.pairingEpoch != responseEpoch ||
            row.pairingEpoch != enrollment.pairingEpoch
        ) {
            Log.w(TAG, "approval response with pairing epoch mismatch request=$requestEventId — rejected (AD-07)")
            return
        }
        val status = if (decision) ApprovalRequest.STATUS_APPROVED else ApprovalRequest.STATUS_REJECTED
        val changed = dao.casApply(requestEventId, status, serverTsEpochMillis ?: now())
        if (changed == 0) {
            Log.w(TAG, "approval response not applied (already decided/expired/consumed) request=$requestEventId — rejected (§9 row 459)")
            return
        }
        notifyDecision()
    }

    /** 72h sweep: REQUESTED rows past their deadline go EXPIRED (called from the sync pass). */
    suspend fun expireDue(pairId: String): Int = dao.casExpireDue(pairId, now())

    /**
     * APPROVED → CONSUMED: the single-use boundary a consumer (the Strict Mode gate) crosses
     * when it acts on an approval. Returns false when the row was not APPROVED.
     */
    suspend fun consume(requestEventId: String): Boolean = dao.casConsume(requestEventId) == 1

    suspend fun pendingFor(pairId: String, role: String): List<ApprovalRequest> = dao.pendingFor(pairId, role)

    suspend fun recentFor(pairId: String, limit: Int = RECENT_LIMIT): List<ApprovalRequest> =
        dao.recentFor(pairId, limit)

    /** Why a CAS moved zero rows — read back for the refusal detail (the CAS itself is the guard). */
    private suspend fun describeUnchanged(requestEventId: String, at: Long): String {
        val row = dao.byId(requestEventId)
        return when {
            row == null -> "unknown request"
            row.status != ApprovalRequest.STATUS_REQUESTED -> "already ${row.status.lowercase()}"
            row.expiresAtEpochMillis <= at -> "request expired"
            else -> "not applied"
        }
    }

    private fun refuse(requestEventId: String, reason: String): RespondResult.Refused {
        Log.w(TAG, "approval decision refused request=$requestEventId ($reason)")
        return RespondResult.Refused(reason)
    }

    private companion object {
        const val TAG = "MyExodusApproval"
        const val RECENT_LIMIT = 20
    }
}
