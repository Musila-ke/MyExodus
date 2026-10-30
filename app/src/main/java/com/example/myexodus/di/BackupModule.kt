package com.example.myexodus.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room3.withReadTransaction
import com.example.myexodus.account.Relauncher
import com.example.myexodus.account.SessionFence
import com.example.myexodus.account.readGuestSnapshot
import com.example.myexodus.backup.BackupKeyApi
import com.example.myexodus.backup.BackupKeyManager
import com.example.myexodus.backup.BackupManifestApi
import com.example.myexodus.backup.BackupObjectDownloader
import com.example.myexodus.backup.BackupObjectUploader
import com.example.myexodus.backup.BackupRestoreMarkStore
import com.example.myexodus.backup.BackupRestores
import com.example.myexodus.backup.BackupRestorer
import com.example.myexodus.backup.BackupRevisionStore
import com.example.myexodus.backup.BackupSnapshotReader
import com.example.myexodus.backup.BackupStaging
import com.example.myexodus.backup.BackupUploadException
import com.example.myexodus.backup.BackupUploadFailure
import com.example.myexodus.backup.BackupUploadSessionStore
import com.example.myexodus.backup.BackupUploader
import com.example.myexodus.backup.BackupUploads
import com.example.myexodus.backup.BackupWordlists
import com.example.myexodus.backup.DataStoreBackupRestoreMarkStore
import com.example.myexodus.backup.DataStoreBackupRevisionStore
import com.example.myexodus.backup.DataStoreBackupUploadSessionStore
import com.example.myexodus.backup.FirebaseObjectDownloader
import com.example.myexodus.backup.FirebaseObjectUploader
import com.example.myexodus.backup.FirestoreBackupKeyApi
import com.example.myexodus.backup.FirestoreBackupManifestApi
import com.example.myexodus.backup.RemoteBackupAnchorStore
import com.example.myexodus.backup.RoomBackupAnchorStore
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

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

    @Provides
    @Singleton
    fun provideFirebaseStorage(): FirebaseStorage = FirebaseStorage.getInstance()

    @Provides
    @Singleton
    fun provideBackupObjectUploader(storage: FirebaseStorage): BackupObjectUploader =
        FirebaseObjectUploader(storage)

    @Provides
    @Singleton
    fun provideBackupManifestApi(
        firestore: FirebaseFirestore,
        functions: FirebaseFunctions,
    ): BackupManifestApi = FirestoreBackupManifestApi(firestore, functions)

    /** §6.4 item 11: anchors live in the active UID-scoped SQLCipher database. */
    @Provides
    @Singleton
    fun provideRemoteBackupAnchorStore(storeFactory: AppRulesStoreFactory): RemoteBackupAnchorStore =
        RoomBackupAnchorStore(storeFactory)

    /** §6.4 item 11: the resumable session URI stays in the Tink-wrapped DataStore. */
    @Provides
    @Singleton
    fun provideBackupUploadSessionStore(
        dataStore: DataStore<Preferences>,
    ): BackupUploadSessionStore = DataStoreBackupUploadSessionStore(dataStore)

    /** §6.4 item 14: `noBackupFilesDir` is outside Auto Backup and device transfer. */
    @Provides
    @Singleton
    fun provideBackupStaging(@ApplicationContext context: Context): BackupStaging =
        BackupStaging(File(context.noBackupFilesDir, "backup-staging"))

    /**
     * §6.4 item 6: one consistent freeze-read. A single read transaction keeps the habits,
     * streaks, milestones, attempts, sessions, coaching, windows and policy rows from
     * observing different moments.
     */
    @Provides
    @Singleton
    fun provideBackupSnapshotReader(storeFactory: AppRulesStoreFactory): BackupSnapshotReader =
        BackupSnapshotReader {
            val db = storeFactory.open()
                ?: throw BackupUploadException(BackupUploadFailure.LOCAL_STATE_UNAVAILABLE)
            db.withReadTransaction { readGuestSnapshot(db) }
        }

    @Provides @Singleton @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @Singleton @WorkDispatcher
    fun provideWorkDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /** §6.6 item 4: the restore's Storage read seam. */
    @Provides
    @Singleton
    fun provideBackupObjectDownloader(storage: FirebaseStorage): BackupObjectDownloader =
        FirebaseObjectDownloader(storage)

    /** §6.6 item 9: the completion the relaunched process shows the user. */
    @Provides
    @Singleton
    fun provideBackupRestoreMarkStore(
        dataStore: DataStore<Preferences>,
    ): BackupRestoreMarkStore = DataStoreBackupRestoreMarkStore(dataStore)

    @Provides
    @Singleton
    fun provideBackupRestorer(
        keys: BackupKeyManager,
        manifest: BackupManifestApi,
        anchors: RemoteBackupAnchorStore,
        staging: BackupStaging,
        downloads: BackupObjectDownloader,
        storeFactory: AppRulesStoreFactory,
        fence: SessionFence,
        rules: RulesRepository,
        mark: BackupRestoreMarkStore,
        relauncher: Relauncher,
    ): BackupRestores = BackupRestorer(
        keys = keys,
        manifest = manifest,
        anchors = anchors,
        staging = staging,
        downloads = downloads,
        storeFactory = storeFactory,
        fence = fence,
        rules = rules,
        mark = mark,
        relauncher = relauncher,
    )

    @Provides
    @Singleton
    fun provideBackupUploader(
        keys: BackupKeyManager,
        manifest: BackupManifestApi,
        anchors: RemoteBackupAnchorStore,
        sessions: BackupUploadSessionStore,
        staging: BackupStaging,
        uploads: BackupObjectUploader,
        fence: SessionFence,
        snapshot: BackupSnapshotReader,
    ): BackupUploads = BackupUploader(
        keys = keys,
        manifest = manifest,
        anchors = anchors,
        sessions = sessions,
        staging = staging,
        uploads = uploads,
        fence = fence,
        snapshot = snapshot,
    )
}
