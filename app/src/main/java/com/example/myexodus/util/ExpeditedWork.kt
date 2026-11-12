package com.example.myexodus.util

import android.os.Build

/**
 * Expedited work on API 26-30 runs as a foreground service and fails without a
 * [androidx.work.ListenableWorker.getForegroundInfo] override, so the flag is Android 12+
 * only; below that the ordinary request still runs, just without scheduler priority.
 */
fun canExpediteWork(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
