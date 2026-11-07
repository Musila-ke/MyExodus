package com.example.myexodus.account

import android.content.Context
import androidx.core.content.edit
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.backup.BackupRestoreMarkStore
import com.example.myexodus.backup.BackupStaging
import com.example.myexodus.backup.BackupUploadSessionStore
import com.example.myexodus.backup.RemoteBackupAnchorStore
import com.example.myexodus.data.db.LocalDatabaseMigration
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Account plan §6.8/§6.9.3: deletes one identity's local data. The request is journalled in
 * plain prefs before the sign-out relaunch, so the purge still happens when the process dies
 * between acceptance and restart; the file phase must run before [LocalDatabaseMigration]
 * decides which store names this process opens.
 *
 * A marker only executes once the credential is gone: a crash mid-sign-out keeps the session,
 * and a live session must never lose its database. The purge is idempotent — an interrupted
 * one simply runs again on the next launch.
 *
 * Every dependency is a [Provider] because this object is field-injected at
 * `Application.onCreate`, before the user unlocks and before the migration binds the store
 * names; constructing the accountability database there would capture the legacy name.
 */
@Singleton
class DeletionLocalData @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadSessions: Provider<BackupUploadSessionStore>,
    private val restoreMarks: Provider<BackupRestoreMarkStore>,
    private val quarantine: Provider<GuestQuarantine>,
    private val staging: Provider<BackupStaging>,
    private val accountabilityDb: Provider<AccountabilityDatabase>,
    private val backupAnchors: Provider<RemoteBackupAnchorStore>,
) {
    private val lock = Any()

    /** §6.8.4: written before the sign-out relaunch, executed at the next launch. */
    fun markPending(uid: String) = markPendingHash(LocalDatabaseMigration.scopeHashOf(uid))

    fun markPendingHash(hash: String) {
        synchronized(lock) {
            val current = prefs().getStringSet(KEY_PENDING, null).orEmpty()
            prefs().edit(commit = true) { putStringSet(KEY_PENDING, current + hash) }
        }
    }

    /**
     * §6.9.3 "also delete this device": every scope the install can name, not just the
     * signed-in account's. The journal scope survives an account switch (ADR #125), so marking
     * only the account hash would let the previous identity's stores resurface at the next
     * launch (X6532 2026-11-07).
     */
    fun markAllPending() {
        val hashes = LocalDatabaseMigration.scopedHashesOnDisk(context) +
            listOfNotNull(LocalDatabaseMigration.effectiveScopeHash(context))
        if (hashes.isEmpty()) return
        synchronized(lock) {
            val current = prefs().getStringSet(KEY_PENDING, null).orEmpty()
            prefs().edit(commit = true) { putStringSet(KEY_PENDING, current + hashes) }
        }
    }

    /** True when a launch right now would adopt a local store (switch pointer or journal scope). */
    fun hasLocalData(): Boolean =
        LocalDatabaseMigration.effectiveScopeHash(context) != null ||
            LocalDatabaseMigration.scopedHashesOnDisk(context).isNotEmpty()

    fun clearPending(uid: String) = clearPendingHash(LocalDatabaseMigration.scopeHashOf(uid))

    fun clearPendingHash(hash: String) {
        synchronized(lock) {
            val current = prefs().getStringSet(KEY_PENDING, null).orEmpty()
            if (hash !in current) return
            prefs().edit(commit = true) { putStringSet(KEY_PENDING, current - hash) }
        }
    }

    fun hasPending(): Boolean =
        synchronized(lock) { !prefs().getStringSet(KEY_PENDING, null).isNullOrEmpty() }

    /**
     * Runs synchronously before [LocalDatabaseMigration.start]: deletes the identity's scoped
     * stores, its retained pre-scope copies and its UID-exclusive keys. Returns true when at
     * least one scope was fully forgotten.
     */
    fun purgePendingFiles(): Boolean {
        val hashes = pendingHashes()
        if (hashes.isEmpty() || FirebaseAuth.getInstance().currentUser != null) return false
        return hashes.map { LocalDatabaseMigration.forgetScope(context, it) }.any { it }
    }

    /** Second phase: operation state, staged copies and the quarantine, then the marker. */
    suspend fun finishPendingPurge() = withContext(Dispatchers.IO) {
        val hashes = pendingHashes()
        if (hashes.isEmpty() || FirebaseAuth.getInstance().currentUser != null) return@withContext
        val cleared = runCatching { uploadSessions.get().clear() }.isSuccess &&
            runCatching { restoreMarks.get().clear() }.isSuccess &&
            runCatching { quarantine.get().deleteAll() }.isSuccess &&
            runCatching { staging.get().wipe() }.isSuccess
        if (!cleared) return@withContext
        hashes.forEach { hash ->
            if (LocalDatabaseMigration.forgetScope(context, hash)) clearPendingHash(hash)
        }
    }

    /**
     * §6.9.3: what removal would discard. A null [pendingEvents] means the outbox could not be
     * read; `backupChecked == false` means the anchor could not be read either.
     */
    suspend fun removalInfo(): LocalRemovalInfo = withContext(Dispatchers.IO) {
        val pendingEvents: Boolean? = try {
            accountabilityDb.get().outboxDao().oldestPendingCreatedAt() != null
        } catch (_: Exception) {
            null
        }
        val anchors = runCatching { backupAnchors.get().read() }.getOrNull()
        LocalRemovalInfo(
            // §6.9.3: a signed-out launch adopts the switch pointer, else the journal scope.
            // Both belong in the disclosure; the pointer is cleared by the deletion purge itself.
            retainedScope = hasLocalData(),
            pendingEvents = pendingEvents,
            backupChecked = anchors != null,
            backupRevision = anchors?.highWater?.revision,
        )
    }

    private fun pendingHashes(): Set<String> =
        synchronized(lock) { prefs().getStringSet(KEY_PENDING, null).orEmpty().toSet() }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS = "local_data_purge"
        const val KEY_PENDING = "pending_scopes"
    }
}

/**
 * §6.9.3's pre-removal disclosure. [backupRevision] is the highest backup this installation has
 * seen, not "the newest local change" — no local commit stamp exists, so the gap is stated
 * rather than inferred.
 */
data class LocalRemovalInfo(
    val retainedScope: Boolean,
    val pendingEvents: Boolean?,
    val backupChecked: Boolean,
    val backupRevision: Long?,
)
