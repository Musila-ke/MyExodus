package com.example.myexodus.domainusage

/**
 * The Android-side effects [DomainUsageEngine.start]/[DomainUsageEngine.stop] need
 * (§6.8/§6.9, S7.4/S7.5). Injected rather than reached for, so the Engine core stays free of
 * Android types and stays JVM-testable with a fake. The production implementation is
 * `AndroidDomainUsageHost`.
 */
interface DomainUsageHost {

    /** S1.3: the debug-only DataStore override; null when unset (release ignores it). */
    suspend fun modeOverride(): EngineMode?

    /** §4.8 `persistedLastDayKey`; null when never written. */
    suspend fun lastDayKey(): String?

    /** §4.8: written on every effective-day-key change. */
    suspend fun setLastDayKey(dayKey: String)

    /**
     * §8 S7.4: post "Website limits paused" once. The Engine applies the "≥ 1 rule and mode ≠
     * `OFF`" test and the once-only guard; the host applies the notification-permission test.
     */
    fun postVpnStoppedNotification()

    /**
     * §8 S7.5/D-34: start strict-Private-DNS tracking, reporting every change of the recomputed
     * value. Returns the unregister action [DomainUsageEngine.stop] runs; a no-op below API 28.
     */
    fun registerPrivateDnsMonitor(onStrictChanged: (Boolean) -> Unit): () -> Unit
}
