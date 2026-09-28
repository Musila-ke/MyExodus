package com.example.myexodus.di

import android.app.Application
import android.content.Context
import com.example.myexodus.blocking.BlockDecisionEngine
import com.example.myexodus.blocking.EmergencyExclusionManager
import com.example.myexodus.blocking.LatencyProfiler
import com.example.myexodus.blocking.RulesSnapshotPublisher
import com.example.myexodus.blocking.usage.AccountingDiagnosticsState
import com.example.myexodus.blocking.usage.PlatformUsageReader
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.blocking.usage.UsageDiagnosticsRecorder
import com.example.myexodus.blocking.usage.UsageLedgerRepository
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.telemetry.CrashReporting
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Enhanced Mode pipeline graph (plan §7 Steps 2–6); the service wires the URL scanner itself. */
@Module
@InstallIn(SingletonComponent::class)
object BlockingModule {

    @Provides @Singleton
    fun provideEmergencyExclusionManager(@ApplicationContext context: Context): EmergencyExclusionManager =
        EmergencyExclusionManager(context)

    @Provides @Singleton
    fun provideBlockDecisionEngine(publisher: RulesSnapshotPublisher): BlockDecisionEngine =
        BlockDecisionEngine(publisher::snapshot)

    @Provides @Singleton
    fun provideLatencyProfiler(@ApplicationContext context: Context): LatencyProfiler =
        LatencyProfiler(context as Application)

    /** Decision 19: every caller (both detection paths, receivers, the maintenance job) shares
     *  this one serialized dispatcher instance, so accounting cycles never interleave. Two
     *  `Dispatchers.IO.limitedParallelism(1)` views of the same pool carry independent limits,
     *  so the single instance is what makes the serialization real — see [AccountingDispatcher]. */
    @Provides @Singleton @AccountingDispatcher
    fun provideAccountingDispatcher(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The journal is the system of record for credited time; the tracker is its live
     *  projection. Same dispatcher as the engine, so ledger order matches accounting order. */
    @Provides @Singleton
    fun provideUsageLedgerRepository(
        storeFactory: AppRulesStoreFactory,
        @AccountingDispatcher dispatcher: CoroutineDispatcher,
        crashReporting: CrashReporting,
    ): UsageLedgerRepository = UsageLedgerRepository(storeFactory, dispatcher, crashReporting)

    @Provides @Singleton
    fun provideUsageAccountingEngine(
        storeFactory: AppRulesStoreFactory,
        @AccountingDispatcher dispatcher: CoroutineDispatcher,
        crashReporting: CrashReporting,
        ledger: UsageLedgerRepository,
        @ApplicationContext context: Context,
    ): UsageAccountingEngine =
        UsageAccountingEngine(storeFactory, dispatcher, crashReporting, ledger, context, System::currentTimeMillis)

    /** §8.7's prompt-eligibility status (SharedPreferences) — one instance, one source. */
    @Provides @Singleton
    fun provideAccountingDiagnosticsState(@ApplicationContext context: Context): AccountingDiagnosticsState =
        AccountingDiagnosticsState(context)

    /** §8.7 gap detector + §8.9 recovery bookkeeping; the service supplies the binder half. */
    @Provides @Singleton
    fun provideUsageDiagnosticsRecorder(
        ledger: UsageLedgerRepository,
        state: AccountingDiagnosticsState,
        crashReporting: CrashReporting,
    ): UsageDiagnosticsRecorder = UsageDiagnosticsRecorder(ledger, state, crashReporting)

    /** OPD #28(a): singleton because it owns the bounded binder thread. */
    @Provides @Singleton
    fun providePlatformUsageReader(@ApplicationContext context: Context): PlatformUsageReader =
        PlatformUsageReader(context)
    // ForegroundStateMachine + OverlayController are deliberately NOT provided here: they are
    // per-bind state, constructed by the service itself on each bind (plan §12 — a Hiber
    // disconnect would otherwise leave a stale @Singleton overlay/generation behind).
}
