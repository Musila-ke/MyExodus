package com.example.myexodus.blocking.recovery

import com.example.myexodus.data.db.entity.RecoveryState

/** Result of one countdown tick; the UI renders the state, it never owns the math. */
sealed interface RecoveryCountdown {
    /** Normal countdown on the authoritative clock. */
    data class Counting(val remainingSeconds: Long) : RecoveryCountdown

    /**
     * Wall-clock anomaly (plan §6): display stays frozen at the last known-good remaining
     * value and the user is surfaced a "time change detected" state; the authoritative
     * countdown ([unlockInSeconds]) keeps running on the tamper-proof clock so the escape
     * hatch remains reachable.
     */
    data class Frozen(val displayedRemainingSeconds: Long, val unlockInSeconds: Long) : RecoveryCountdown
}

private data class Observation(
    val nowWallClockUtcMillis: Long,
    val remainingSeconds: Long,
)

/**
 * Stateful tick holder (plan §8.3: freeze at last known-good on detected wall-clock jumps).
 * Stateless under the hood — every value derives from the persisted [RecoveryState], so
 * process death and recreation within the same boot reproduce the same countdown (§12).
 */
class RecoveryCountdownTracker(private val toleranceMillis: Long = 5_000) {
    private var lastGood: Observation? = null

    fun tick(
        state: RecoveryState,
        sameBootSession: Boolean,
        nowWallClockUtcMillis: Long,
        nowElapsedRealtimeMillis: Long,
    ): RecoveryCountdown {
        val remaining = RecoveryTimer.remainingSeconds(
            state.deadlineWallClockUtcMillis,
            state.deadlineElapsedRealtimeThisBoot,
            sameBootSession,
            nowWallClockUtcMillis,
            nowElapsedRealtimeMillis,
        )
        if (remaining == 0L) return RecoveryCountdown.Counting(0)
        val previous = lastGood ?: run {
            lastGood = Observation(nowWallClockUtcMillis, remaining)
            return RecoveryCountdown.Counting(remaining)
        }
        return if (RecoveryTimer.detectClockJump(
                previous.remainingSeconds,
                previous.nowWallClockUtcMillis,
                remaining,
                nowWallClockUtcMillis,
                toleranceMillis,
            )
        ) {
            RecoveryCountdown.Frozen(previous.remainingSeconds, remaining)
        } else {
            lastGood = Observation(nowWallClockUtcMillis, remaining)
            RecoveryCountdown.Counting(remaining)
        }
    }
}
