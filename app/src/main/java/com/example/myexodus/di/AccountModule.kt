package com.example.myexodus.di

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.work.WorkManager
import com.example.myexodus.MainActivity
import com.example.myexodus.account.AuthOperations
import com.example.myexodus.account.DatabaseScopeSwitch
import com.example.myexodus.account.FirebaseAuthOperations
import com.example.myexodus.account.FirestoreSessionCloud
import com.example.myexodus.account.GuestTransitionApi
import com.example.myexodus.account.GuestTransitionCallables
import com.example.myexodus.account.GuestTransitionJournal
import com.example.myexodus.account.KeystoreGuestTransitionJournal
import com.example.myexodus.account.LocalSessionManager
import com.example.myexodus.account.PrefsSessionStore
import com.example.myexodus.account.PushMapping
import com.example.myexodus.account.Relauncher
import com.example.myexodus.account.SessionCloud
import com.example.myexodus.account.SessionFence
import com.example.myexodus.account.SessionStore
import com.example.myexodus.account.SessionTransitions
import com.example.myexodus.account.SessionWork
import com.example.myexodus.accountability.sync.FirestoreSchema
import com.example.myexodus.accountability.sync.PARTNER_SYNC_WORK_NAME
import com.example.myexodus.accountability.sync.PushInstallationRegistrar
import com.example.myexodus.accountability.sync.awaitReportingDenial
import com.example.myexodus.data.db.LocalDatabaseMigration
import com.example.myexodus.telemetry.CrashReporting
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release 1 (account plan §4.1/§8.4/§7 step 10): the account and session seams. */
@Module
@InstallIn(SingletonComponent::class)
object AccountModule {

    @Provides
    @Singleton
    fun provideAuthOperations(): AuthOperations = FirebaseAuthOperations()

    @Provides
    @Singleton
    fun provideGuestTransitionJournal(@ApplicationContext context: Context): GuestTransitionJournal =
        KeystoreGuestTransitionJournal(context)

    @Provides
    @Singleton
    fun provideGuestTransitionApi(functions: FirebaseFunctions): GuestTransitionApi =
        GuestTransitionCallables(functions)

    @Provides
    @Singleton
    fun provideSessionStore(@ApplicationContext context: Context): SessionStore =
        PrefsSessionStore(context)

    @Provides
    @Singleton
    fun provideSessionCloud(cloud: FirestoreSessionCloud): SessionCloud = cloud

    @Provides
    @Singleton
    fun provideDatabaseScopeSwitch(@ApplicationContext context: Context): DatabaseScopeSwitch =
        object : DatabaseScopeSwitch {
            override fun activeHash(): String? = LocalDatabaseMigration.effectiveScopeHash(context)
            override fun switchTo(uid: String) = LocalDatabaseMigration.switchScopeTo(context, uid)
        }

    /**
     * Plan §4.3: the old UID's push mapping is deleted while that UID is still the auth subject
     * (the rules are owner-only). The installation id is the device FID, replaced on every sync
     * pass, so a failed delete is bounded by the next pass and by server-side routing checks.
     */
    @Provides
    @Singleton
    fun providePushMapping(
        firestore: FirebaseFirestore,
        crashReporting: CrashReporting,
    ): PushMapping = object : PushMapping {
        override suspend fun unregister(uid: String) {
            val installationId = PushInstallationRegistrar.installationIdOf() ?: return
            FirestoreSchema.pushInstallationDocument(firestore, uid, installationId)
                .delete()
                .awaitReportingDenial(crashReporting, "push_installation_delete")
        }
    }

    @Provides
    @Singleton
    fun provideSessionWork(@ApplicationContext context: Context): SessionWork = SessionWork {
        WorkManager.getInstance(context).cancelUniqueWork(PARTNER_SYNC_WORK_NAME)
    }

    /**
     * The switch completes with a process restart: it is the only teardown that provably leaves
     * no Firestore client, snapshot listener, Storage task or Room handle from the old session
     * alive, and it reopens the database under the new UID's scope (plan §7 step 20).
     */
    @Provides
    @Singleton
    fun provideRelauncher(@ApplicationContext context: Context): Relauncher = Relauncher {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        // The launch is queued in system_server already; let it dispatch, then drop this process.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 250)
    }

    @Provides
    @Singleton
    fun provideSessionFence(manager: LocalSessionManager): SessionFence = manager

    @Provides
    @Singleton
    fun provideSessionTransitions(manager: LocalSessionManager): SessionTransitions = manager
}
