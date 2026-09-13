package com.example.myexodus.blocking

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks the last known foreground transition (Enhanced Mode spec §8 Step 4): package,
 * window id, detection time, and a monotonically increasing generation number. The
 * generation is the single source of truth for stale-event protection (§9) across both the
 * overlay path and the URL-scan path — any result carrying an older generation than the
 * current one is a no-op everywhere.
 */
class ForegroundStateMachine {

    private val generation = AtomicLong(0)
    private var lastKnownPackage: String? = null
    private var lastKnownWindowId: Int = -1
    private var lastDetectionTimeNanos: Long = 0L

    val currentGeneration: Long get() = generation.get()

    fun lastKnownPackage(): String? = lastKnownPackage

    fun lastKnownWindowId(): Int = lastKnownWindowId

    fun lastDetectionTimeNanos(): Long = lastDetectionTimeNanos

    /**
     * Records the latest event and returns the current generation. The generation advances
     * only on a real transition (package or window change): same-window events — e.g.
     * Chrome's page-load WINDOW_STATE_CHANGED, which fires ~3ms after the content-changed
     * events (observed on-device 2026-08-27) — are not transitions, and advancing on them
     * would discard the debounced URL scan before it fires (caught on-device 2026-08-27).
     */
    fun advance(packageName: String?, windowId: Int, detectionTimeNanos: Long = SystemClock.elapsedRealtimeNanos()): Long {
        val isTransition = packageName != lastKnownPackage || windowId != lastKnownWindowId
        lastKnownPackage = packageName
        lastKnownWindowId = windowId
        lastDetectionTimeNanos = detectionTimeNanos
        return if (isTransition) generation.incrementAndGet() else generation.get()
    }

    fun isCurrent(candidate: Long): Boolean = candidate == generation.get()
}
