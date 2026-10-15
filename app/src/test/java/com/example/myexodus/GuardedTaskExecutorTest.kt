package com.example.myexodus

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B-10 (2026-10-15): a throwable escaping a WorkManager task must abort that task, not the
 * process — the disk-full ForceStopRunnable ISE killed every respawn before this wrapper.
 */
@RunWith(RobolectricTestRunner::class)
class GuardedTaskExecutorTest {

    private val direct = Executor { it.run() }

    @Test
    fun exceptionIsContained_andFollowingTasksStillRun() {
        val guarded = GuardedTaskExecutor(direct)
        guarded.execute { throw IllegalStateException("disk full") }
        var ran = false
        guarded.execute { ran = true }
        assertTrue(ran)
    }

    @Test
    fun everyExecuteReachesTheDelegate() {
        var seen = 0
        val guarded = GuardedTaskExecutor(Executor { seen++; it.run() })
        guarded.execute { }
        guarded.execute { }
        assertEquals(2, seen)
    }

    @Test
    fun errorsAreNotSwallowed() {
        val guarded = GuardedTaskExecutor(direct)
        var propagated = false
        try {
            guarded.execute { throw AssertionError("loud") }
        } catch (expected: AssertionError) {
            propagated = true
        }
        assertTrue(propagated)
    }
}
