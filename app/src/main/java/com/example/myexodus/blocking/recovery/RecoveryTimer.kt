package com.example.myexodus.blocking.recovery

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max

/**
 * Pure dual-clock recovery math (blocking-core plan §8.3). Elapsed-realtime is authoritative
 * within the boot session that created the deadline (sub-second precision, immune to user
 * clock changes); the wall-clock deadline is the reboot-safe durable value. No in-memory
 * counter is ever the source of truth — everything derives from the persisted deadlines.
 */
object RecoveryTimer {
    fun remainingSeconds(
        deadlineWallClockUtcMillis: Long,
        deadlineElapsedRealtimeThisBoot: Long,
        sameBootSession: Boolean,
        nowWallClockUtcMillis: Long,
        nowElapsedRealtimeMillis: Long,
    ): Long = when {
        sameBootSession ->
            max(0L, (deadlineElapsedRealtimeThisBoot - nowElapsedRealtimeMillis) / 1000)
        else ->
            max(0L, (deadlineWallClockUtcMillis - nowWallClockUtcMillis) / 1000)
    }

    /**
     * Same boot session iff a fresh elapsed-realtime sample is >= the value stored when the
     * session was created: elapsed-realtime resets (to ~0) at boot and never goes backward
     * within a boot, so a fresh sample below the stored value means the device rebooted.
     * The reverse error (short previous session, new sample slightly larger) misjudges a
     * reboot as same-session and fails lenient — it falls back to a wall-clock-derived
     * remaining from a stale elapsed deadline, which clamps to 0 (hatch unlocks early,
     * never late).
     */
    fun sameBootSession(storedBootSessionId: Long, nowElapsedRealtimeMillis: Long): Boolean =
        nowElapsedRealtimeMillis >= storedBootSessionId

    /**
     * A wall-clock jump between reads (manual clock change, large NTP correction) when
     * |actual remaining - expected remaining| exceeds the tolerance. Expected remaining =
     * previous remaining decremented by the real wall time that elapsed between the reads;
     * a backward jump makes remaining grow instead of shrink, a forward jump makes it drop
     * too fast. A remaining that legitimately reached 0 is never an anomaly (unlock).
     */
    fun detectClockJump(
        previousRemainingSeconds: Long,
        previousNowWallClockUtcMillis: Long,
        currentRemainingSeconds: Long,
        currentNowWallClockUtcMillis: Long,
        toleranceMillis: Long,
    ): Boolean {
        if (currentRemainingSeconds <= 0L) return false
        val realElapsedMillis = currentNowWallClockUtcMillis - previousNowWallClockUtcMillis
        val expectedRemainingMillis =
            max(0L, previousRemainingSeconds * 1000 - realElapsedMillis)
        return abs(expectedRemainingMillis - currentRemainingSeconds * 1000) > toleranceMillis
    }

}

/** Injectable clock pair so reboot and clock-change scenarios are unit-testable (§12). */
interface RecoveryClock {
    fun nowWallClockUtcMillis(): Long
    fun nowElapsedRealtimeMillis(): Long
}

object SystemRecoveryClock : RecoveryClock {
    override fun nowWallClockUtcMillis(): Long = System.currentTimeMillis()
    override fun nowElapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
