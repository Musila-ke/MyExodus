package com.example.myexodus.blocking

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rolling buffer, percentile, and sampling behavior (plan §8 Step 6). The Window
 *  FrameMetrics listener itself is exercised on-device (§12 test suite). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LatencyProfilerTest {

    @Test
    fun buffer_keepsLast500Entries() {
        val profiler = LatencyProfiler(Application(), sampleDenominator = 1)
        repeat(600) { i ->
            // Detection stage = i ms; submission stage = 1 ms.
            profiler.recordDecision(
                generation = i.toLong(),
                detectionTimeNanos = i * 1_000_000L,
                decisionTimeNanos = 2 * i * 1_000_000L,
            )
            profiler.recordSubmission(
                generation = i.toLong(),
                displayId = 0,
                showTimeNanos = (2 * i + 1) * 1_000_000L,
            )
        }
        val (detection, submission, presentation) = profiler.percentilesMillis()
        // Entries 100..599 remain; detection stages are 100..599 ms.
        assertEquals(349L, detection[0]) // p50: index 249
        assertEquals(574L, detection[1]) // p95: index 474
        assertEquals(594L, detection[2]) // p99: index 494
        // Every submission stage is exactly 1 ms.
        assertEquals(1L, submission[0])
        assertEquals(1L, submission[1])
        assertEquals(1L, submission[2])
        // No presentation frames recorded in this pure-unit flow (-1 sentinel).
        assertEquals(-1L, presentation[0])
    }

    @Test
    fun sampling_tracksEveryHundredthPair_andDoesNotLeak() {
        val profiler = LatencyProfiler(Application(), sampleDenominator = 100)
        repeat(250) { i ->
            profiler.recordDecision(
                generation = i.toLong(),
                detectionTimeNanos = i * 1_000_000L,
                decisionTimeNanos = 2 * i * 1_000_000L,
            )
            profiler.recordSubmission(
                generation = i.toLong(),
                displayId = 0,
                showTimeNanos = (2 * i + 1) * 1_000_000L,
            )
        }
        // Pairs at counter 1, 101, 201 → detection values 0, 100, 200 ms. If a
        // submission ever failed to consume its tracked pair, only 0 would remain.
        val (detection, _, _) = profiler.percentilesMillis()
        assertEquals(0L, detection[0]) // p50 of [0,100,200]
        assertEquals(100L, detection[1]) // p95
        assertEquals(100L, detection[2]) // p99
    }

    @Test
    fun reset_clearsBufferAndPendingPairs() {
        val profiler = LatencyProfiler(Application(), sampleDenominator = 1)
        profiler.recordDecision(generation = 1L, detectionTimeNanos = 0L, decisionTimeNanos = 1_000_000L)
        profiler.recordSubmission(generation = 1L, displayId = 0, showTimeNanos = 2_000_000L)
        profiler.reset()
        val (detection, submission, presentation) = profiler.percentilesMillis()
        assertEquals(-1L, detection[0])
        assertEquals(-1L, submission[0])
        assertEquals(-1L, presentation[0])
    }

    @Test
    fun everyPendingDecision_isConsumedByItsSubmission() {
        // Finding #45: recordDecision stores an entry until recordSubmission removes it. The
        // service must therefore record only decisions that will actually submit — this pins
        // the consume half of that contract, and that stray submissions add nothing.
        val profiler = LatencyProfiler(Application(), sampleDenominator = 1)
        assertEquals(0, profiler.pendingDecisionCount())
        profiler.recordDecision(generation = 3L, detectionTimeNanos = 0L, decisionTimeNanos = 1_000_000L)
        assertEquals(1, profiler.pendingDecisionCount())
        profiler.recordSubmission(generation = 3L, displayId = 0, showTimeNanos = 2_000_000L)
        assertEquals(0, profiler.pendingDecisionCount())
        profiler.recordSubmission(generation = 4L, displayId = 0, showTimeNanos = 3_000_000L)
        assertEquals(0, profiler.pendingDecisionCount())
    }

    @Test
    fun untrackedSubmission_isANoOp() {
        val profiler = LatencyProfiler(Application(), sampleDenominator = 100)
        profiler.recordDecision(
            generation = 5L,
            detectionTimeNanos = 5_000_000L,
            decisionTimeNanos = 10_000_000L,
        )
        // Same-generation submission (counter 1 → tracked); then a second submission
        // for a generation that was never decided — must not crash or buffer.
        profiler.recordSubmission(generation = 5L, displayId = 0, showTimeNanos = 11_000_000L)
        profiler.recordSubmission(generation = 6L, displayId = 0, showTimeNanos = 13_000_000L)
        val (detection, submission, _) = profiler.percentilesMillis()
        assertEquals(5L, detection[0]) // tracked detection: 5 ms
        assertEquals(1L, submission[0]) // tracked submission: 1 ms
    }
}
