package com.example.myexodus.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.myexodus.backup.BackupKeyApi
import com.example.myexodus.backup.BackupKeyManager
import com.example.myexodus.backup.BackupRevisionStore
import com.example.myexodus.backup.BackupWordlists
import com.example.myexodus.backup.DataStoreBackupRevisionStore
import com.example.myexodus.backup.FirestoreBackupKeyApi
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release 2 (account plan §4.1/§8.5/§7 step 12): the ABK key-bundle seams. */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    /** The reviewed word lists are parsed once per process and fail fast when corrupt. */
    @Provides
    @Singleton
    fun provideBackupWordlists(@ApplicationContext context: Context): BackupWordlists =
        BackupWordlists(context)

    @Provides
    @Singleton
    fun provideBackupKeyApi(
        firestore: FirebaseFirestore,
        functions: FirebaseFunctions,
    ): BackupKeyApi = FirestoreBackupKeyApi(firestore, functions)

    /** §8.5: the high-water anchor lives in the Tink-wrapped DataStore, per UID. */
    @Provides
    @Singleton
    fun provideBackupRevisionStore(
        dataStore: DataStore<Preferences>,
    ): BackupRevisionStore = DataStoreBackupRevisionStore(dataStore)

    @Provides
    @Singleton
    fun provideBackupKeyManager(
        api: BackupKeyApi,
        revisions: BackupRevisionStore,
        wordlists: BackupWordlists,
    ): BackupKeyManager = BackupKeyManager(api, revisions, wordlists)
}
