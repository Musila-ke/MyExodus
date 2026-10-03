package com.example.myexodus.accountability.sos

import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.enrollment.targetEnrollment
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException

/**
 * SOS plan Step 8 (§6.1/§8.15, table A2): the sender-side transport for one nudge. Resolves
 * the target pair, re-derives the pair CEK in memory, seals the constant envelope
 * ([NudgeEnvelope]), invokes the `sendNudge` callable and maps the server's verdict onto the
 * sender states. All collaborators are function seams — the unit tests script every branch
 * without Firebase, keystore or network.
 *
 * Offline is checked first (FR-3): with no validated network nothing is encrypted and nothing
 * is queued. A transport-ambiguous outcome ([SendResult.CouldNotConfirm]) hands the exact
 * `(nudgeId, envelope)` pair back so a retry replays the identical request — the server's
 * idempotency then guarantees one logical nudge, never two. Nothing here logs payload, pair,
 * uid or envelope (plan §9.4).
 */
class NudgeRepository(
    private val enrollments: suspend () -> List<EnrollmentRecord>,
    private val selectedPairId: () -> String?,
    private val ensureUid: suspend () -> String?,
    private val deriveCek: (EnrollmentRecord) -> ByteArray,
    private val callSendNudge: suspend (wire: Map<String, Any>) -> Any?,
    private val validatedNetwork: () -> Boolean,
    private val cipher: EventCipher,
) {

    /** The retry-stable identity of one logical send (§6.1 step 8). */
    data class PendingNudge(val pairId: String, val nudgeId: String, val envelope: String)

    enum class RateLimitReason { COOLDOWN, DAILY_CAP, UNKNOWN }

    sealed interface SendResult {
        data object Accepted : SendResult
        data class RateLimited(val reason: RateLimitReason, val retryAfterSeconds: Long) : SendResult
        data object Unavailable : SendResult
        data class CouldNotConfirm(val pending: PendingNudge) : SendResult
        data object NotSentOffline : SendResult
    }

    /** A fresh attempt: [nudgeId] is minted by the ViewModel (§6.1 step 2, UUIDv4). */
    suspend fun sendNew(nudgeId: String): SendResult {
        if (!validatedNetwork()) return SendResult.NotSentOffline
        val enrollment = runCatching { resolveTargetPair() }.getOrNull()
            ?: return SendResult.Unavailable
        val uid = runCatching { ensureUid() }.getOrNull() ?: return SendResult.Unavailable
        val cek = runCatching { deriveCek(enrollment) }.getOrNull() ?: return SendResult.Unavailable
        val envelope = NudgeEnvelope.seal(cipher, cek, enrollment.pairId, nudgeId, uid)
        return call(PendingNudge(enrollment.pairId, nudgeId, envelope))
    }

    /**
     * A retry of an ambiguous attempt: the sealed envelope is replayed verbatim — never
     * re-encrypted (a fresh seal would be a different envelope under the same nudgeId, which
     * the server correctly rejects as nudge_id_reuse).
     */
    suspend fun resend(pending: PendingNudge): SendResult {
        if (!validatedNetwork()) return SendResult.NotSentOffline
        return call(pending)
    }

    private suspend fun resolveTargetPair(): EnrollmentRecord? {
        // The shared §19.5 rule (EnrollmentTargeting): pick when live, else newest. The server
        // re-verifies membership either way.
        return enrollments().targetEnrollment(selectedPairId())
    }

    private suspend fun call(pending: PendingNudge): SendResult = try {
        val data = callSendNudge(
            mapOf(
                "pairId" to pending.pairId,
                "nudgeId" to pending.nudgeId,
                "envelope" to pending.envelope,
            ),
        )
        when ((data as? Map<*, *>)?.get("status")) {
            "accepted" -> SendResult.Accepted
            // A malformed success body is not a refusal — the server may have committed.
            else -> SendResult.CouldNotConfirm(pending)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: FirebaseFunctionsException) {
        mapError(e, pending)
    } catch (e: Exception) {
        // Connection drop, JSON failure, anything transport-level: ambiguous, retry-safe.
        SendResult.CouldNotConfirm(pending)
    }

    private fun mapError(e: FirebaseFunctionsException, pending: PendingNudge): SendResult = when (e.code) {
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
            // Only the server's own rate_limited verdict is decisive; any other
            // resource-exhausted (platform quota) never ran our handler — ambiguous.
            if (e.message == "rate_limited") parseRateLimited(e.details) else SendResult.CouldNotConfirm(pending)
        // feature_disabled, unauthenticated, permission-denied, invalid-argument: the server
        // made a decision and nothing was recorded — reusing the id buys nothing.
        FirebaseFunctionsException.Code.FAILED_PRECONDITION,
        FirebaseFunctionsException.Code.UNAUTHENTICATED,
        FirebaseFunctionsException.Code.PERMISSION_DENIED,
        FirebaseFunctionsException.Code.INVALID_ARGUMENT,
        -> SendResult.Unavailable
        // unavailable / deadline-exceeded / internal and every unmapped code: the request may
        // have committed with only the response lost (§8.15) — keep the pending pair.
        else -> SendResult.CouldNotConfirm(pending)
    }

    private fun parseRateLimited(details: Any?): SendResult {
        val map = details as? Map<*, *>
        val reason = when (map?.get("reason")) {
            "cooldown" -> RateLimitReason.COOLDOWN
            "daily_cap" -> RateLimitReason.DAILY_CAP
            else -> RateLimitReason.UNKNOWN
        }
        val retryAfterSeconds = (map?.get("retryAfterSeconds") as? Number)?.toLong() ?: 0L
        return SendResult.RateLimited(reason, retryAfterSeconds)
    }
}
