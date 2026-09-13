package com.example.myexodus.blocking

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Staged latency measurement (Enhanced Mode spec §8 Step 6, Architecture Decision 11):
 * - detection: event timestamp → decision timestamp (measured by the service)
 * - submission: decision → `dialog.show()` return (measured by [OverlayController])
 * - presentation: `dialog.show()` → first `FrameMetrics.TOTAL_DURATION` callback on the
 *   dialog's `Window` (measured here via the listener attached at dialog creation — the
 *   reason the overlay is a Dialog subclass, not a bare added View).
 *
 * The rolling buffer holds the last 500 events; p50/p95/p99 are computed off the hot path.
 * Sampling: 100% in debuggable builds, 1% otherwise (deterministic counter, not random).
 */
class LatencyProfiler(
    appContext: Application,
    private val bufferSize: Int = 500,
    private val sampleDenominator: Int = if ((appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) 1 else 100,
) {
    private val buffer = ArrayDeque<LongArray>(bufferSize)
    private val lock = Any()
    private val pendingShowNanos = ConcurrentHashMap<Int, Long>()
    private val decisionsByGeneration = ConcurrentHashMap<Long, Pair<Long, Long>>()
    private val sampleCounter = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Attach at dialog-creation time (per display); the first frame after a show marks presentation. */
    fun attach(window: Window, displayId: Int) {
        window.addOnFrameMetricsAvailableListener(
            { _, frameMetrics, _ ->
                val showNanos = pendingShowNanos.remove(displayId) ?: return@addOnFrameMetricsAvailableListener
                val cbNanos = SystemClock.elapsedRealtimeNanos()
                val frameDuration = frameMetrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
                recordPresentation(showNanos, cbNanos, frameDuration)
            },
            mainHandler,
        )
    }

    /** Called by the §12 device suite at suite start — percentiles must not mix suites. */
    fun reset() {
        synchronized(lock) { buffer.clear() }
        decisionsByGeneration.clear()
        pendingShowNanos.clear()
    }

    /** Call at decision time; pairs with [recordSubmission] called after [show] returns. */
    fun recordDecision(generation: Long, detectionTimeNanos: Long, decisionTimeNanos: Long) {
        if (!shouldSample()) return
        decisionsByGeneration[generation] = Pair(detectionTimeNanos, decisionTimeNanos)
    }

    fun recordSubmission(generation: Long, displayId: Int, showTimeNanos: Long) {
        // Diagnostic (2026-08-28): a latency run showed one submission across many Block
        // decides — either a generation that never reached recordDecision (WSC null-window
        // drops / URL-scan paths) or a double submission (second show of the same generation
        // on another display). Log once per missing generation.
        val pair = decisionsByGeneration.remove(generation)
        if (pair == null) {
            Log.w(
                TAG,
                "recordSubmission: no decision entry for gen=$generation display=$displayId " +
                    "(was it never decided, or already submitted?)",
            )
            return
        }
        val (detection, decision) = pair
        pendingShowNanos[displayId] = showTimeNanos
        val submission = showTimeNanos - decision
        val detectionStage = decision - detection
        Log.d(TAG, "block latency gen=$generation detection=${detectionStage / 1_000_000}ms submission=${submission / 1_000_000}ms")
        append(generation, detectionStage, submission, -1L)
    }

    private fun recordPresentation(showNanos: Long, cbNanos: Long, frameDurationNanos: Long) {
        val presentation = cbNanos - showNanos
        Log.d(
            TAG,
            "block latency presentation=${presentation / 1_000_000}ms frame=" +
                if (frameDurationNanos < 0) "n/a" else "${frameDurationNanos / 1_000_000}ms",
        )
        append(-1L, -1L, -1L, presentation)
    }

    /** Presentation marker for the bare-addView overlay path (Transsion XOS, 2026-08-27):
     *  no Window, so no FrameMetrics; the view's first pre-draw after show stands in for
     *  the first presented frame. */
    fun presented(displayId: Int) {
        val showNanos = pendingShowNanos.remove(displayId) ?: return
        recordPresentation(showNanos, SystemClock.elapsedRealtimeNanos(), -1L)
    }

    /** (p50, p95, p99) in milliseconds per stage: detection, submission, presentation. */
    fun percentilesMillis(): Triple<LongArray, LongArray, LongArray> {
        synchronized(lock) {
            val detection = buffer.map { it[0] }.filter { it >= 0 }
            val submission = buffer.map { it[1] }.filter { it >= 0 }
            val presentation = buffer.map { it[3] }.filter { it >= 0 }
            return Triple(
                percentileNanos(detection).toMillisArray(),
                percentileNanos(submission).toMillisArray(),
                percentileNanos(presentation).toMillisArray(),
            )
        }
    }

    private fun append(generation: Long, detectionNanos: Long, submissionNanos: Long, presentationNanos: Long) {
        synchronized(lock) {
            if (buffer.size == bufferSize) buffer.removeFirst()
            buffer.addLast(longArrayOf(detectionNanos, submissionNanos, generation, presentationNanos))
        }
    }

    private fun shouldSample(): Boolean = sampleDenominator == 1 || sampleCounter.incrementAndGet() % sampleDenominator == 1L

    private fun percentileNanos(values: List<Long>): List<Long> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        return listOf(50, 95, 99).map { p ->
            val index = ((p / 100.0) * sorted.size).toInt().coerceAtLeast(1) - 1
            sorted[index.coerceAtMost(sorted.size - 1)]
        }
    }

    private fun List<Long>.toMillisArray(): LongArray = LongArray(3) { i ->
        getOrNull(i)?.div(1_000_000L) ?: -1L
    }

    private companion object {
        const val TAG = "MyExodusLatency"
    }
}
