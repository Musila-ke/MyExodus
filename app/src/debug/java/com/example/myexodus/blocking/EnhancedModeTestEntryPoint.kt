package com.example.myexodus.blocking

import com.example.myexodus.accountability.sync.PartnerSyncCoordinator
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.data.db.AppRulesStoreFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Test access to the Enhanced Mode singletons (all @Singleton via BlockingModule /
 * DatabaseModule), so the device suite observes the exact instances the bound
 * accessibility service uses — not copies.
 *
 * OverlayController is deliberately absent: it is per-bind state on the service instance,
 * reachable via [BlockerAccessibilityService.instance] (2026-08-28 review — the DI graph no
 * longer holds a @Singleton copy).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface EnhancedModeTestEntryPoint {
    fun rulesRepository(): RulesRepository
    fun latencyProfiler(): LatencyProfiler
    fun appRulesStoreFactory(): AppRulesStoreFactory
    fun partnerSyncCoordinator(): PartnerSyncCoordinator
    fun usageEngine(): UsageAccountingEngine
}
