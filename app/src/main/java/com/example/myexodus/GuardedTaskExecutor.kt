package com.example.myexodus

import android.util.Log
import java.util.concurrent.Executor

/**
 * B-10 (2026-10-15): WorkManager runs its internal tasks on the executor supplied via
 * [androidx.work.Configuration]. An exception escaping one of those tasks reaches the
 * thread's default uncaught handler and kills the process — with the disk full, WM 2.11.2's
 * startup ForceStopRunnable throws IllegalStateException (wrapping SQLiteFullException) and
 * every respawn died 7 s later (B7 disk-fill). Catching here converts a task abort into a
 * logged no-op: availability over WM self-checks while the device is wedged; the next
 * process start with space re-runs the same startup work. Errors (OOM etc.) still propagate.
 */
class GuardedTaskExecutor(private val delegate: Executor) : Executor {

    override fun execute(command: Runnable) {
        delegate.execute {
            try {
                command.run()
            } catch (e: Exception) {
                Log.w(TAG, "WorkManager task aborted (contained)", e)
            }
        }
    }

    private companion object {
        const val TAG = "GuardedTaskExecutor"
    }
}
