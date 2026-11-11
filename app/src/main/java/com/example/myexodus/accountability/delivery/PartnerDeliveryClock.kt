package com.example.myexodus.accountability.delivery

import android.os.SystemClock

/**
 * Plan §7 Phase 1 fake-clock seam. Tests substitute a fake; production uses the system.
 * The two clocks are never compared with each other: wall time stamps events, monotonic
 * time bounds leases and backoff (the wall clock can jump).
 */
interface PartnerDeliveryClock {
    /** Unix epoch milliseconds UTC — the value written to `deviceOccurredAtMs`. */
    fun wallClockMs(): Long

    /** Monotonic milliseconds since boot, including deep sleep. */
    fun elapsedRealtimeMs(): Long
}

object SystemPartnerDeliveryClock : PartnerDeliveryClock {
    override fun wallClockMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
