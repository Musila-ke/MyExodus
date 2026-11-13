package com.example.myexodus.di

import android.content.Context
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.streaks.MilestoneAlerter
import com.example.myexodus.streaks.MilestoneScheduler
import com.example.myexodus.streaks.StreakAnomalyLog
import com.example.myexodus.streaks.SystemStreakClock
import com.example.myexodus.telemetry.CrashReporting
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Habit-streaks wiring (plan §8 Steps 3/4): single repository instance (its [HabitRepository.state]
 *  flow is the UI's read model) and the alarm/notification collaborators receivers inject. */
@Module
@InstallIn(SingletonComponent::class)
object StreaksModule {

    @Provides @Singleton
    fun provideStreakAnomalyLog(@ApplicationContext context: Context): StreakAnomalyLog =
        StreakAnomalyLog(File(context.filesDir, ANOMALY_LOG_FILE_NAME))

    @Provides @Singleton
    fun provideMilestoneScheduler(
        @ApplicationContext context: Context,
        storeFactory: AppRulesStoreFactory,
    ): MilestoneScheduler =
        MilestoneScheduler(context, storeFactory, readings = { SystemStreakClock.read(context) })

    @Provides @Singleton
    fun provideHabitRepository(
        storeFactory: AppRulesStoreFactory,
        anomalyLog: StreakAnomalyLog,
        scheduler: MilestoneScheduler,
        @ApplicationContext context: Context,
        crashReporting: CrashReporting,
    ): HabitRepository = HabitRepository(
        storeFactory = storeFactory,
        anomalyLog = anomalyLog,
        readings = { SystemStreakClock.read(context) },
        scheduler = scheduler,
        featureTelemetry = { event, outcome, reason ->
            crashReporting.featureOutcome(event, outcome, reason)
        },
    )

    @Provides @Singleton
    fun provideMilestoneAlerter(
        @ApplicationContext context: Context,
        storeFactory: AppRulesStoreFactory,
        repository: HabitRepository,
        anomalyLog: StreakAnomalyLog,
    ): MilestoneAlerter = MilestoneAlerter(
        context = context,
        storeFactory = storeFactory,
        repository = repository,
        anomalyLog = anomalyLog,
        readings = { SystemStreakClock.read(context) },
    )

    private const val ANOMALY_LOG_FILE_NAME = "streak-anomaly.log"
}
