package com.example.myexodus.di

import android.content.Context
import com.example.myexodus.BuildConfig
import com.example.myexodus.blocking.RulesSnapshotPublisher
import com.example.myexodus.domainusage.AndroidDomainUsageHost
import com.example.myexodus.domainusage.DomainRuleProvider
import com.example.myexodus.domainusage.DomainUsageEngine
import com.example.myexodus.domainusage.DomainUsageHost
import com.example.myexodus.domainusage.EngineCounters
import com.example.myexodus.domainusage.EngineMode
import com.example.myexodus.domainusage.PublicSuffixList
import com.example.myexodus.domainusage.SystemMonotonicClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Domain-usage metering (§4.1): the Engine the VPN service drives from its relay and DNS paths.
 * The mode is the build-configured one here — the debug DataStore override can only be read
 * asynchronously, so `start()` (S7) applies it before any rule is loaded.
 */
@Module
@InstallIn(SingletonComponent::class)
object DomainUsageModule {

    /** One instance app-wide: the Engine, the rule provider and the §14 readers share it. */
    @Provides @Singleton
    fun provideEngineCounters(): EngineCounters = EngineCounters()

    /**
     * §16 item 28: loaded synchronously at first injection (the VPN service's `onCreate`), not
     * lazily on first RuleEditor save, whose validation needs a synchronous answer.
     */
    @Provides @Singleton
    fun providePublicSuffixList(@ApplicationContext context: Context): PublicSuffixList =
        PublicSuffixList.fromAsset(context)

    @Provides @Singleton
    fun provideDomainRuleProvider(
        publisher: RulesSnapshotPublisher,
        psl: PublicSuffixList,
        counters: EngineCounters,
    ): DomainRuleProvider = DomainRuleProvider(publisher, psl, counters)

    /**
     * Application-scoped: every API it touches (preferences DataStore, notifications,
     * ConnectivityManager) is application-scoped too.
     */
    @Provides @Singleton
    fun provideDomainUsageHost(@ApplicationContext context: Context): DomainUsageHost =
        AndroidDomainUsageHost(context)

    @Provides @Singleton
    fun provideDomainUsageEngine(counters: EngineCounters): DomainUsageEngine = DomainUsageEngine(
        mode = EngineMode.effectiveMode(BuildConfig.DOMAIN_USAGE_MODE, BuildConfig.DEBUG, null),
        clock = SystemMonotonicClock(),
        counters = counters,
        debugBuild = BuildConfig.DEBUG,
    )
}
