package com.example.myexodus.account

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.myexodus.backup.BackupRestoreMarkStore
import com.example.myexodus.backup.BackupUploadSessionStore
import com.example.myexodus.data.db.LocalDatabaseMigration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plan §4.1: the persisted facts an identity switch must survive a process death with — the
 * session generation, an explicit sign-out (so a restart never silently mints a fresh guest),
 * the old UID whose push mapping still needs deleting, and whether the one-time legacy-cache
 * purge has run. Production: [PrefsSessionStore]; tests use an in-memory implementation.
 */
interface SessionStore {
    fun generation(): Long
    fun setGeneration(value: Long)
    fun signedOut(): Boolean
    fun setSignedOut(value: Boolean)
    fun pendingUnregisterUid(): String?
    fun setPendingUnregister(uid: String?)
    fun legacyCachePurged(): Boolean

    /** ADR #239: an identity change the app itself started; the only marker that lets the
     *  next process move the database scope without asking. */
    fun pendingIdentitySwitch(): Boolean
    fun setPendingIdentitySwitch(value: Boolean)
}

@Singleton
class PrefsSessionStore @Inject constructor(@ApplicationContext context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences(LegacyCachePurge.PREFS, Context.MODE_PRIVATE)

    override fun generation(): Long = prefs.getLong(KEY_GENERATION, 0)
    override fun setGeneration(value: Long) {
        prefs.edit(commit = true) { putLong(KEY_GENERATION, value) }
    }

    override fun signedOut(): Boolean = prefs.getBoolean(KEY_SIGNED_OUT, false)
    override fun setSignedOut(value: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_SIGNED_OUT, value) }
    }

    override fun pendingUnregisterUid(): String? = prefs.getString(KEY_PENDING_UNREGISTER, null)
    override fun setPendingUnregister(uid: String?) {
        prefs.edit(commit = true) { putString(KEY_PENDING_UNREGISTER, uid) }
    }

    override fun legacyCachePurged(): Boolean =
        prefs.getBoolean(LegacyCachePurge.KEY_LEGACY_CACHE_PURGED, false)

    override fun pendingIdentitySwitch(): Boolean = prefs.getBoolean(KEY_PENDING_SWITCH, false)
    override fun setPendingIdentitySwitch(value: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_PENDING_SWITCH, value) }
    }

    private companion object {
        const val KEY_GENERATION = "session_generation"
        const val KEY_SIGNED_OUT = "signed_out"
        const val KEY_PENDING_UNREGISTER = "pending_unregister_uid"
        const val KEY_PENDING_SWITCH = "pending_identity_switch"
    }
}

/** The UID-scoped database side of a switch (plan §7 step 20); production wraps [LocalDatabaseMigration]. */
interface DatabaseScopeSwitch {
    /** The scope a store opened right now would use, or null before the first migration. */
    fun activeHash(): String?

    fun switchTo(uid: String)

    /**
     * ADR #239: an identity changed outside a session transition (a pre-R1.10 build signed in,
     * or a guest signed out). The scope pointer still has to move — a retained database must
     * never open under a different UID — but the retained data is no longer silently orphaned:
     * it stays importable, and [recoveryOffer] is what the Account pane offers back.
     */
    fun recordRecoveryOffer(retainedHash: String, forUid: String?)

    /** The retained scope still offered for import to [uid] (null: any account may take it). */
    fun recoveryOffer(uid: String?): String?

    /** ADR #240: that offer only while bound to an account; an unbound copy is never automatic. */
    fun boundRecoveryOffer(uid: String?): String?

    fun clearRecoveryOffer()
}

/**
 * Plan §4.3/§8.7: deletes `/users/{uid}/push_installations/{installationId}` while that UID is
 * still the current one. Both JVM and production implementations must never throw outward.
 */
fun interface PushMapping {
    suspend fun unregister(uid: String)
}

/** Cancels every WorkManager unit that belongs to the outgoing identity (plan §7 step 10). */
fun interface SessionWork {
    fun cancelAccountScoped()
}

/** Restarts the process so no Firestore client, Room handle or listener outlives an identity change. */
fun interface Relauncher {
    fun relaunch()
}

/**
 * Account plan §4.1/§7 step 20/§8.7: owns the local session facts and the switch choreography
 * that the identity change itself cannot.
 *
 * A switch runs in two phases with the credential call in between, because the plan's §8.4
 * recovery path requires a failed sign-in to leave the old session usable:
 * 1. [beginTransition] — freeze all remote work (the fence goes null), flush queued SDK writes
 *    **while the old UID still owns the token** (refuse the switch if they cannot be
 *    acknowledged unless the user chose to discard), advance the generation, and delete the
 *    old push mapping while the rules still permit it.
 * 2. [completeTransition] — after the credential succeeded: point the database scope at the new
 *    UID and restart the process. The restart is a superset of the plan's "terminate the old
 *    Firestore instance and obtain a new memory-only one": no SDK client, cache, listener or
 *    Room handle can survive it, so the new UID can never observe the old session's state.
 *    A failed credential calls [abortTransition] instead: the fence is restored, nothing was
 *    terminated, and the §8.4 retry stays possible.
 *
 * [onAppStart] reconciles the crash window (identity changed but the process died before the
 * scope pointer moved — a different UID must never open a retained database) and retries the
 * push-mapping deletion the network may have refused. An anonymous→anonymous UID change is the
 * ADR #125 rotation: the local scope and every pairing stay exactly as they are.
 *
 * ADR #239: only a switch THIS app started (the journal marker) may move the scope without an
 * import decision. An unexplained identity change still moves the pointer — the retained
 * database may never be served under another UID — but it is recorded as a recovery offer, so
 * the signed-in account sees "data from an earlier session" and can import it explicitly
 * instead of finding an empty app.
 */
@Singleton
class LocalSessionManager @Inject constructor(
    private val operations: AuthOperations,
    private val cloud: SessionCloud,
    private val store: SessionStore,
    private val scopes: DatabaseScopeSwitch,
    private val push: PushMapping,
    private val workers: SessionWork,
    private val relauncher: Relauncher,
    // §6.9.1: resumable upload metadata dies with the session it belongs to. Lazy because the
    // secrets DataStore sits behind both and startup must not construct it pre-unlock.
    private val uploadSessions: dagger.Lazy<BackupUploadSessionStore>,
    private val restoreMarks: dagger.Lazy<BackupRestoreMarkStore>,
) : SessionFence, SessionTransitions {

    private enum class Phase { ACTIVE, TRANSITIONING, COMPLETING }

    private val _cloudAccess =
        MutableStateFlow(if (store.legacyCachePurged()) CloudAccess.READY else CloudAccess.BLOCKED)
    val cloudAccess: StateFlow<CloudAccess> = _cloudAccess.asStateFlow()

    private val lock = Any()

    @Volatile private var phase = Phase.ACTIVE
    @Volatile private var generation = store.generation()
    @Volatile private var identity: AuthIdentity? = operations.currentIdentity()
    @Volatile private var signedOut = store.signedOut()
    @Volatile private var initialized = false
    private var discardApproved = false

    /** Seeded before any consumer can ask; idempotent. [cloudReady] is the purge's own result. */
    fun onAppStart(scope: CoroutineScope) {
        synchronized(lock) {
            if (initialized) return
            val current = operations.currentIdentity()
            identity = current
            // A persisted sign-out that never reached the SDK (crash inside the transition)
            // is not a sign-out the user completed; keep the session instead.
            if (store.signedOut() && current != null) store.setSignedOut(false)
            signedOut = store.signedOut()
            // Crash window: the credential changed but the process died before the scope pointer
            // moved. A retained database must never open under a different UID.
            val switchedHere = store.pendingIdentitySwitch()
            if (switchedHere) store.setPendingIdentitySwitch(false)
            if (current != null && !current.isAnonymous) {
                val active = scopes.activeHash()
                if (active != null && active != uidHash(current.uid)) {
                    // ADR #239: an unmarked change (signed in on a pre-R1.10 build) must not
                    // strand the retained data behind an empty scope without a way back.
                    if (!switchedHere) scopes.recordRecoveryOffer(active, current.uid)
                    scopes.switchTo(current.uid)
                    Log.w(TAG, "identity changed outside a session transition; retained scope kept for recovery")
                }
            }
            initialized = true
            operations.observeIdentity(::onIdentityObserved)
        }
        scope.launch {
            val pending = store.pendingUnregisterUid() ?: return@launch
            val current = operations.currentIdentity()?.uid
            if (pending == current && !signedOut) {
                if (runCatching { push.unregister(pending) }.isSuccess) {
                    store.setPendingUnregister(null)
                }
            } else if (pending != current) {
                // The old UID is gone; its document can only be removed by that identity or the
                // server. Routing verification (plan §4.3) is what makes a stale row harmless.
                store.setPendingUnregister(null)
            }
        }
    }

    /** §9 recoverable action for a failed one-time purge: a clean restart retries it. */
    fun restart() = relauncher.relaunch()

    override fun current(): SessionFingerprint? {
        if (!initialized || phase != Phase.ACTIVE || signedOut) return null
        if (_cloudAccess.value != CloudAccess.READY) return null
        val uid = identity?.uid ?: return null
        return SessionFingerprint(uid, generation)
    }

    override fun mayMintGuest(): Boolean =
        initialized && phase == Phase.ACTIVE && !signedOut && _cloudAccess.value == CloudAccess.READY

    override fun isCloudReady(): Boolean = _cloudAccess.value == CloudAccess.READY

    /** ADR #239: the retained scope the current identity may still import, or null. */
    fun recoveryOffer(): String? = scopes.recoveryOffer(operations.currentIdentity()?.uid)

    /** ADR #240: the offered scope only when an account owns it (never a signed-out guest's). */
    fun boundRecoveryOffer(): String? = scopes.boundRecoveryOffer(operations.currentIdentity()?.uid)

    /** Called once a recovery import committed; the retained scope stays, the offer is done. */
    fun clearRecoveryOffer() = scopes.clearRecoveryOffer()

    override suspend fun beginTransition(signOut: Boolean): TransitionStart {
        val (uid, wasAnonymous) = synchronized(lock) {
            if (_cloudAccess.value != CloudAccess.READY) return TransitionStart.Refused(TransitionRefusal.MIGRATION_BLOCKED)
            if (phase != Phase.ACTIVE) return TransitionStart.Refused(TransitionRefusal.ALREADY_TRANSITIONING)
            // No identity means nothing is queued under a token; the caller may proceed.
            val current = identity ?: return TransitionStart.Started
            phase = Phase.TRANSITIONING
            current.uid to current.isAnonymous
        }
        // §8.7: run while the old UID is still the current auth subject. Nothing has been
        // terminated yet, so a refusal below is a clean, reversible state.
        val flushed = cloud.flushPendingWrites(PENDING_WRITES_TIMEOUT_MS)
        if (!flushed && !discardApproved) {
            synchronized(lock) { phase = Phase.ACTIVE }
            return TransitionStart.Refused(TransitionRefusal.PENDING_WRITES)
        }
        discardApproved = false
        if (signOut) {
            // §6.9.1: the resumable upload metadata belongs to the session being signed out.
            runCatching { uploadSessions.get().clear() }
            runCatching { restoreMarks.get().clear() }
        }
        synchronized(lock) {
            generation += 1
            store.setGeneration(generation)
            // ADR #239: written before the credential call, so a process death between a
            // successful sign-in and completeTransition can never look like a stray change.
            store.setPendingIdentitySwitch(true)
            if (signOut) {
                signedOut = true
                store.setSignedOut(true)
                // An anonymous UID can never sign back in; without this, its local data would
                // become unreachable. Retained and offered to the next persistent account.
                if (wasAnonymous) {
                    val active = scopes.activeHash()
                    if (active != null && active == uidHash(uid)) scopes.recordRecoveryOffer(active, null)
                }
            }
        }
        // §4.3: the mapping is deletable only while this UID is current. Offline, the retry
        // metadata is retained and replayed at the next start on the same identity.
        runCatching { push.unregister(uid) }
            .onFailure { store.setPendingUnregister(uid) }
        return TransitionStart.Started
    }

    override fun completeTransition(newUid: String?) {
        synchronized(lock) {
            if (phase == Phase.COMPLETING) return
            phase = Phase.COMPLETING
            store.setPendingIdentitySwitch(false)
            if (newUid != null) {
                reconcileScope(newUid)
                signedOut = false
                store.setSignedOut(false)
            }
        }
        workers.cancelAccountScoped()
        store.setPendingUnregister(null)
        relauncher.relaunch()
    }

    override suspend fun abortTransition() {
        synchronized(lock) {
            if (phase != Phase.TRANSITIONING) return
            phase = Phase.ACTIVE
            store.setPendingIdentitySwitch(false)
            if (store.signedOut()) {
                signedOut = false
                store.setSignedOut(false)
            }
        }
        // The push mapping was deleted before the credential attempt. The old UID is the current
        // one again, so the next sync pass's registration() rewrites it; until then the device
        // misses pushes but still receives events through the sync fallback (AD-05).
    }

    override fun approvePendingWriteDiscard() {
        discardApproved = true
    }

    private fun onIdentityObserved(next: AuthIdentity?) {
        val previous = identity
        if (previous?.uid == next?.uid) {
            // Link in place (UID preserved) or a token refresh: same scope, same generation.
            identity = next
            return
        }
        if (previous != null && previous.isAnonymous && next?.isAnonymous == true) {
            // ADR #125: a re-minted anonymous identity keeps the local scope and every pairing.
            identity = next
            return
        }
        identity = next
        if (previous == null && next?.isAnonymous == true) {
            // The very first guest mint is bootstrap, not a switch: restarting here would
            // restart every fresh install. Scope adoption for it stays with the next launch's
            // LocalDatabaseMigration.start (the pre-R1.10 behavior).
            return
        }
        completeTransition(next?.uid)
    }

    /** Points the scope at [uid] unless it already is; never switches an anonymous rotation. */
    private fun reconcileScope(uid: String) {
        val active = scopes.activeHash() ?: return
        if (active != uidHash(uid)) {
            scopes.switchTo(uid)
            Log.i(TAG, "database scope moved to the signed-in identity")
        }
    }

    private fun uidHash(uid: String) = LocalDatabaseMigration.scopeHashOf(uid)

    private companion object {
        const val TAG = "MyExodusSession"
        /** §8.7 needs a bounded wait; 5 s is long enough for an ordinary flush, short enough
         *  that a wedged queue surfaces the explicit-discard choice instead of hanging. */
        const val PENDING_WRITES_TIMEOUT_MS = 5_000L
    }
}
