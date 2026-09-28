package com.example.myexodus.blocking.usage

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.os.Build

/**
 * The event's package name; pre-API 29 the field is read reflectively (`getPackageName` is API
 * 29+; the value of ACTIVITY_RESUMED equals the deprecated MOVE_TO_FOREGROUND on older
 * runtimes, so one eventType comparison covers both — ADR #24). Shared by the poll ticker and
 * [PlatformUsageReader].
 */
@SuppressLint("DiscouragedPrivateApi")
internal fun usageEventPackage(event: UsageEvents.Event): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        event.packageName
    } else {
        runCatching {
            UsageEvents.Event::class.java.getDeclaredField("mPackage")
                .apply { isAccessible = true }
                .get(event) as? String
        }.getOrNull()
    }

/**
 * The package of the last ACTIVITY_RESUMED in [events], or null when the stream has none.
 * One eventType comparison covers pre-API 29 runtimes (ACTIVITY_RESUMED equals the
 * deprecated MOVE_TO_FOREGROUND's value — ADR #24). Shared by the Path B poll and
 * [PlatformUsageReader].
 */
internal fun latestResumedPackage(events: UsageEvents?): String? {
    if (events == null) return null
    var latest: String? = null
    while (events.hasNextEvent()) {
        val event = UsageEvents.Event()
        if (!events.getNextEvent(event)) continue
        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
            latest = usageEventPackage(event)
        }
    }
    return latest
}
