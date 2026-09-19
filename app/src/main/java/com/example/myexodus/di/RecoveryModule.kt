package com.example.myexodus.di

import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.recovery.RecoveryPlanRepository
import com.example.myexodus.recovery.RecoverySessionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Recovery-resource feature (v3 W7): the session log and the user-authored plan. */
@Module
@InstallIn(SingletonComponent::class)
object RecoveryModule {

    @Provides @Singleton
    fun provideRecoverySessionRepository(
        storeFactory: AppRulesStoreFactory,
    ): RecoverySessionRepository = RecoverySessionRepository(storeFactory)

    @Provides @Singleton
    fun provideRecoveryPlanRepository(
        storeFactory: AppRulesStoreFactory,
    ): RecoveryPlanRepository = RecoveryPlanRepository(storeFactory)
}
