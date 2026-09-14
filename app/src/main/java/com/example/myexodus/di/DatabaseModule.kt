package com.example.myexodus.di

import android.content.Context
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.RulesSnapshotPublisher
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.ActivityLogStore
import com.example.myexodus.data.db.AndroidDatabaseKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.DatabaseMigration
import com.example.myexodus.data.db.InstallationIdProvider
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreEncryption
import com.example.myexodus.data.db.RulesStoreKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** Key source for the pre-split combined database; consumed by [DatabaseMigration]. */
    @Provides @Singleton
    fun provideLegacyKeyManager(@ApplicationContext context: Context): DatabaseKeyManager =
        AndroidDatabaseKeyManager(context)

    @Provides @Singleton
    fun provideActivityLogKeyManager(@ApplicationContext context: Context): ActivityLogKeyManager =
        ActivityLogKeyManager(context)

    @Provides @Singleton
    fun provideInstallationIdProvider(@ApplicationContext context: Context): InstallationIdProvider =
        InstallationIdProvider(context)

    @Provides @Singleton
    fun provideHealthStore(): DatabaseHealthStore = DatabaseHealthStore()

    /** Key source for the rules store, encrypted at rest since the habit-streaks plan (§8 Step 1). */
    @Provides @Singleton
    fun provideRulesStoreKeyManager(@ApplicationContext context: Context): RulesStoreKeyManager =
        RulesStoreKeyManager(context)

    @Provides @Singleton
    fun provideRecoveryManager(
        @ApplicationContext context: Context,
        legacyKeyManager: DatabaseKeyManager,
        activityLogKeyManager: ActivityLogKeyManager,
        rulesStoreKeyManager: RulesStoreKeyManager,
        healthStore: DatabaseHealthStore,
    ): RecoveryManager =
        RecoveryManager(context, legacyKeyManager, activityLogKeyManager, rulesStoreKeyManager, healthStore)

    @Provides @Singleton
    fun provideRulesStoreEncryption(
        rulesStoreKeyManager: RulesStoreKeyManager,
        recoveryManager: RecoveryManager,
    ): RulesStoreEncryption = RulesStoreEncryption(rulesStoreKeyManager, recoveryManager)

    @Provides @Singleton
    fun provideAppRulesStoreFactory(
        @ApplicationContext context: Context,
        recoveryManager: RecoveryManager,
        healthStore: DatabaseHealthStore,
        encryption: RulesStoreEncryption,
    ): AppRulesStoreFactory = AppRulesStoreFactory(context, recoveryManager, healthStore, encryption)

    @Provides @Singleton
    fun provideActivityLogStore(
        @ApplicationContext context: Context,
        keyManager: ActivityLogKeyManager,
    ): ActivityLogStore = ActivityLogStore(context, keyManager)

    @Provides @Singleton
    fun provideDatabaseMigration(
        @ApplicationContext context: Context,
        legacyKeyManager: DatabaseKeyManager,
        recoveryManager: RecoveryManager,
        activityLogKeyManager: ActivityLogKeyManager,
        storeFactory: AppRulesStoreFactory,
    ): DatabaseMigration =
        DatabaseMigration(context, legacyKeyManager, recoveryManager, activityLogKeyManager, storeFactory)

    @Provides @Singleton
    fun provideRulesSnapshotPublisher(
        @ApplicationContext context: Context,
        storeFactory: AppRulesStoreFactory,
    ): RulesSnapshotPublisher = RulesSnapshotPublisher(context, storeFactory)

    @Provides @Singleton
    fun provideRulesRepository(
        storeFactory: AppRulesStoreFactory,
        publisher: RulesSnapshotPublisher,
    ): RulesRepository = RulesRepository(storeFactory, publisher)
}
