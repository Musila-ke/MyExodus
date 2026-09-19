package com.example.myexodus.accountability.state

/**
 * AD-12 / plan §8 Step 15: session-key rotation policy over [ChainStateStore]'s chain heads.
 *
 * Send side: [rotateIfDue] advances the pair's `keyEpoch` when the current epoch has carried
 * [ROTATE_AFTER_EVENTS] events or [ROTATE_AFTER_MILLIS] days — the cap on how much traffic a
 * single derived session key covers (not forward secrecy; the CEK re-derives any epoch, AD-12).
 *
 * Receive side: [acceptsIncoming] is the enforceable half of "prior epoch's key is retained
 * for 7 days". Keys are deterministic functions of the CEK, so nothing can be "zeroed" —
 * what is real is the receiver refusing to apply an envelope under an epoch older than the
 * one before the current, or older than the 7-day grace window. Refused envelopes surface as
 * `UNKNOWN_KEY_EPOCH` (plan §8: "logSecurityEvent; do not apply"), never as tamper.
 *
 * Both receive rules are pure over a head snapshot: read the head, decide, and let the chain
 * append stamp the anchor (see [ChainStateStore]); no extra state is persisted here.
 */
class SessionKeyManager(
    private val store: ChainStateStore,
) {

    /**
     * Rotates the pair's own chain when due and returns the new epoch, or null when the
     * current epoch still has runway. Safe to call from any event-creation or sync path:
     * a head that is not due is left untouched, and a pair with no chain yet cannot rotate.
     */
    suspend fun rotateIfDue(pairId: String, senderKeyId: String, atEpochMillis: Long): Int? {
        val head = store.localHead(pairId, senderKeyId) ?: return null
        val startedAt = head.keyEpochStartedAtEpochMillis
        val eventsSinceRotation = head.lastSequence - head.keyEpochStartSequence
        val eventsDue = eventsSinceRotation >= ROTATE_AFTER_EVENTS
        // A head with no anchor (pre-MIGRATION_8_9, or a chain that never had an event since
        // the upgrade) has no measurable age — the next event stamps one, then days count.
        val ageDue = startedAt != null && atEpochMillis - startedAt >= ROTATE_AFTER_MILLIS
        if (!eventsDue && !ageDue) return null
        return store.advanceKeyEpoch(pairId, senderKeyId, atEpochMillis)
    }

    /**
     * May an incoming envelope under [envelopeKeyEpoch] be decrypted and applied?
     * - no head yet: the chain starts here, whatever epoch the partner is on;
     * - the current epoch, or any later one (a receiver that missed epochs catches up);
     * - the immediately prior epoch, while the current epoch is younger than the grace window
     *   (in-flight repair replays reference it, §8 Step 15);
     * - anything older: refused — the partner should have stopped using it (their own
     *   retention window matches this one).
     */
    fun acceptsIncoming(head: ChainHead?, envelopeKeyEpoch: Int, atEpochMillis: Long): Boolean = when {
        head == null -> true
        envelopeKeyEpoch >= head.keyEpoch -> true
        envelopeKeyEpoch == head.keyEpoch - 1 -> {
            val startedAt = head.keyEpochStartedAtEpochMillis
            startedAt == null || atEpochMillis - startedAt <= PRIOR_EPOCH_GRACE_MILLIS
        }
        else -> false
    }

    companion object {
        /** AD-12 threshold 1: events carried by one session key. */
        const val ROTATE_AFTER_EVENTS = 500L

        /** AD-12 threshold 2: age of the current epoch. */
        const val ROTATE_AFTER_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** §8 Step 15: the prior epoch stays usable for in-flight repair replays this long. */
        const val PRIOR_EPOCH_GRACE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
