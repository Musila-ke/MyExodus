package com.example.myexodus.telemetry

import android.os.SystemClock
import android.util.Log

/**
 * Cold-start stage timing (2026-10-15 startup investigation, ADR #153): one Log.i per lap with
 * the delta since the previous lap and the absolute uptime, so a device cold-start logcat
 * carries the full breakdown the measurement recipe reads. t0 is construction of the tracer —
 * Application/MainActivity/Service construction respectively — so cross-process lines correlate
 * by the absolute uptime value. Ungated on purpose: a dozen short log lines cost nothing, and
 * release cold starts need the same visibility (this is the perf regression check).
 */
class StartupTrace(component: String) {
    private val tag = "MyExodusStartup:$component"
    private val t0 = SystemClock.uptimeMillis()
    private var last = t0

    fun lap(stage: String) {
        val now = SystemClock.uptimeMillis()
        Log.i(tag, "$stage +${now - last}ms t+${now - t0}ms uptime=$now")
        last = now
    }
}
