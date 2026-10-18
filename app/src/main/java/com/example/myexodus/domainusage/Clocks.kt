package com.example.myexodus.domainusage

import android.os.SystemClock
import java.time.LocalDateTime
import java.time.ZoneId

// SystemWallClock lives in its own file on purpose: the S1.4 CI check allows wall-clock calls
// in SystemWallClock.kt only, so the file name is load-bearing.

/** INV-2: durations are computed from this clock only. */
interface MonotonicClock {
    fun elapsedMs(): Long
}

class SystemMonotonicClock : MonotonicClock {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * INV-2: wall time is read only to derive the day key and the §6.5 midnight-split boundary —
 * never to measure a duration. The zone is the Engine's `anchoredZone`, captured once in
 * `start()` — not [ZoneId.systemDefault] read here, so a mid-session timezone change cannot
 * move the effective day key (§4.8, D-07).
 */
interface WallClock {
    fun now(zone: ZoneId): LocalDateTime

    /**
     * Epoch milliseconds — the form rule expiry is stored in (`blockExpiresAt`). Used for
     * expiry decisions only; durations still come from [MonotonicClock] (INV-2).
     */
    fun nowMillis(): Long
}
