package com.example.myexodus.accountability.sync

/**
 * Plan §8.5 retry policy for the sender outbox: exponential backoff with jitter and a bounded
 * maximum delay, computed deterministically from `(messageId, attemptCount)` so a row's retry
 * time is reproducible and every message spreads across a different offset. No new row state
 * beyond the persisted `attempt_count` / `next_attempt_at` this feeds.
 */
object OutboxBackoff {

    /** First retry waits 30–60 s. */
    const val BASE_DELAY_MILLIS = 60_000L

    /** Bounded maximum: a transient outage never starves a row longer than six hours. */
    const val MAX_DELAY_MILLIS = 6L * 60 * 60 * 1000

    /**
     * The next due time after attempt number [attemptCount] has just failed at
     * [failedAtEpochMillis]. Jitter is the [0.5, 1.0) fraction of the exponential step, derived
     * from the message id so tests can pin it and retries never thundering-herd in lockstep.
     */
    fun nextAttemptAt(attemptCount: Int, failedAtEpochMillis: Long, messageId: String): Long {
        require(attemptCount >= 1) { "attempt count must be positive" }
        val exponent = (attemptCount - 1).coerceAtMost(20)
        val step = (BASE_DELAY_MILLIS shl exponent).coerceAtMost(MAX_DELAY_MILLIS)
        val jittered = step / 2 + Math.floorMod(seed(messageId, attemptCount).toLong(), step / 2 + 1)
        return failedAtEpochMillis + jittered
    }

    private fun seed(messageId: String, attemptCount: Int): Int =
        (messageId.hashCode() * 31 + attemptCount) xor (messageId.length * 0x9E3779B1.toInt())
}
