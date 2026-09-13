package com.example.myexodus.di

import android.app.Application
import android.content.Context
import com.example.myexodus.blocking.BlockDecisionEngine
import com.example.myexodus.blocking.EmergencyExclusionManager
import com.example.myexodus.blocking.LatencyProfiler
import com.example.myexodus.blocking.RulesSnapshotPublisher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
    // ForegroundStateMachine + OverlayController are deliberately NOT provided here: they are
    // per-bind state, constructed by the service itself on each bind (plan §12 — a Hiber
    // disconnect would otherwise leave a stale @Singleton overlay/generation behind).
}
