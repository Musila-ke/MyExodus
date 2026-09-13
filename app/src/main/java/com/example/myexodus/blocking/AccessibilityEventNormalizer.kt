package com.example.myexodus.blocking

import android.view.accessibility.AccessibilityEvent

/**
 * Extracts the fields the pipeline needs (Enhanced Mode spec §8 Step 4). Browser gating for
 * URL scanning moved to runtime discovery ([BrowserUrlScanner.viewIdFor]) — a package
 * allowlist here could never cover the browsers real devices carry (2026-09-13).
 */
class AccessibilityEventNormalizer {

    fun normalize(event: AccessibilityEvent): NormalizedEvent = normalize(
        eventType = event.eventType,
        packageName = event.packageName?.toString(),
        windowId = event.windowId,
    )

    fun normalize(eventType: Int, packageName: String?, windowId: Int): NormalizedEvent = NormalizedEvent(
        packageName = packageName,
        windowId = windowId,
        eventType = eventType,
    )
}

data class NormalizedEvent(
    val packageName: String?,
    val windowId: Int,
    val eventType: Int,
)
