package com.example.myexodus.accountability.sos

import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.enrollment.targetEnrollment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * SOS plan Step 9 (§6.9/§8.16, table A2): the recipient's inbox — one listener over the active
 * pair's nudge collection, the newest-only decrypt behind the Home card, and the batch
 * acknowledgment. The §6.9 query shape and its expiry handling are pinned by rules-test R-F2q:
 * the rules engine cannot filter `request.time` on a LIST, so expired-but-not-yet-TTL'd
 * documents do arrive and are dropped here client-side (§6.9 — TTL lags cleanup by design).
 *
 * A document that cannot be opened (malformed fields or a failed AEAD tag) is reported as a
 * safe non-fatal — event + cause only, never envelope, pair, uid, or plaintext — and the card
 * is suppressed for that emission; nothing derived from it is ever displayed. It is NOT
 * auto-acknowledged: §6.9 permits silent terminal cleanup only under an existing product
 * policy, and the codebase has none (mapping doc D10).
 */
class NudgeInboxRepository(
    private val enrollments: suspend () -> List<EnrollmentRecord>,
    private val selectedPairId: () -> String?,
    private val ensureUid: suspend () -> String?,
    private val deriveCek: (EnrollmentRecord) -> ByteArray,
    private val listenNudges: (pairId: String, uid: String) -> Flow<List<NudgeDoc>>,
    private val ackBatch: suspend (pairId: String, nudgeIds: List<String>) -> Unit,
    private val cancelNotifications: () -> Unit,
    private val reportUnreadable: (cause: String) -> Unit,
    private val cipher: EventCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** One document as the listener delivers it; nulls are possible and never trusted. */
    data class NudgeDoc(
        val nudgeId: String,
        val senderUid: String?,
        val envelope: String?,
        val createdAtMs: Long?,
        val expiresAtMillis: Long?,
    )

    /** The card's whole state: the newest readable nudge's age and every fetched pending id
     *  (the ack batch covers all of them — §6.9 step 1 — not just the displayed one). */
    data class NudgeCard(
        val pairId: String,
        val createdAtMs: Long,
        val pendingNudgeIds: List<String>,
    )

    /** Emits the card state for the current pair; null whenever no card may render (no
     *  pair/uid/key yet, nothing fetched, all fetched docs expired, or the newest unreadable). */
    fun inbox(): Flow<NudgeCard?> = flow {
        val uid = runCatching { ensureUid() }.getOrNull()
        val enrollment = runCatching {
            // The shared §19.5 rule (EnrollmentTargeting) — the same pair the sender and the
            // dashboard resolve for this user.
            enrollments().targetEnrollment(selectedPairId())
        }.getOrNull()
        val cek = enrollment?.let { runCatching { deriveCek(it) }.getOrNull() }
        if (uid == null || enrollment == null || cek == null) {
            emit(null)
            return@flow
        }
        listenNudges(enrollment.pairId, uid).collect { docs ->
            emit(cardOf(enrollment.pairId, cek, docs))
        }
    }

    /**
     * §6.9 ack steps 1-4: one batch over every fetched pending id, notifications cancelled
     * only after the batch commits. False means nothing was written and nothing was cancelled;
     * the listener still holds the card, so the user can simply tap again.
     */
    suspend fun acknowledge(card: NudgeCard): Boolean {
        val committed = runCatching { ackBatch(card.pairId, card.pendingNudgeIds) }.isSuccess
        if (committed) cancelNotifications()
        return committed
    }

    private fun cardOf(pairId: String, cek: ByteArray, docs: List<NudgeDoc>): NudgeCard? {
        val newest = docs
            .filter { doc -> doc.expiresAtMillis?.let { it > now() } != false }
            .maxByOrNull { it.createdAtMs ?: Long.MIN_VALUE }
            ?: return null
        val createdAtMs = newest.createdAtMs
        val senderUid = newest.senderUid
        val envelope = newest.envelope
        if (createdAtMs == null || senderUid == null || envelope == null) {
            reportUnreadable("schema")
            return null
        }
        // Only the newest document is ever decrypted (§6.9); a failure suppresses the card —
        // no fallback to older documents, nothing displayed, no silent ack.
        if (NudgeEnvelope.open(cipher, cek, pairId, newest.nudgeId, senderUid, envelope) == null) {
            reportUnreadable("decrypt")
            return null
        }
        return NudgeCard(pairId, createdAtMs, docs.map { it.nudgeId })
    }
}
