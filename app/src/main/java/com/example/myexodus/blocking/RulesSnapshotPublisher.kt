package com.example.myexodus.blocking

import android.content.Context
import android.util.Log
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.VaultLockedException
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Snapshot lifecycle (plan §8 Step 2): never "no rules loaded" misread as "no rules exist". */
sealed interface SnapshotState {
    data object Uninitialized : SnapshotState
    data object Loading : SnapshotState
    data class Ready(val version: Long) : SnapshotState
    data class Degraded(val reason: String) : SnapshotState
}

/**
 * Publishes the immutable [RulesSnapshot] the blocking engine reads (Enhanced Mode spec §8
 * Step 2). Paths:
 * - Fast path ([loadFastPath]): synchronously reads the last-known-good snapshot from the
 *   small on-disk cache at service connect / boot, before the authoritative load completes,
 *   closing the "enabled but uninitialized" gap.
 * - Device-protected path ([loadDeviceProtected]): plan §8.5 — restores enforcement from the
 *   Device-Protected-Storage copy of the cache before first user unlock (LOCKED_BOOT_COMPLETED),
 *   when Room (credential-protected) is unreachable. The DE copy holds only the snapshot's
 *   package IDs/domains + windows (no keys, no partner identity — §8.5), encrypted by Android's
 *   device-level DE encryption. It is a versioned cache, not a second authority: the
 *   authoritative path rewrites it on every successful publish (reconciliation).
 * - Authoritative path ([loadFromDb]): rebuilds the full snapshot from the rules store,
 *   writes it to both on-disk caches, then publishes via [AtomicReference.set]. Runs on a
 *   background thread after the fast path and after every successful write transaction.
 *
 * On authoritative failure the state moves to DEGRADED(reason) and the last published
 * snapshot stays in use — the engine always decides against whatever is published.
 */
class RulesSnapshotPublisher(
    private val appContext: Context,
    private val storeFactory: AppRulesStoreFactory,
) {
    private val ref = AtomicReference<RulesSnapshot>()
    private val _state = MutableStateFlow<SnapshotState>(SnapshotState.Uninitialized)
    private val loadMutex = Mutex()

    val state: StateFlow<SnapshotState> = _state.asStateFlow()

    /** Never null: pre-fast-path reads get [RulesSnapshot.EMPTY], never "no rules". */
    val snapshot: RulesSnapshot get() = ref.get() ?: RulesSnapshot.EMPTY

    private val cacheFile: File get() = File(appContext.filesDir, CACHE_FILE_NAME)
    // Device-protected copy (plan §8.5): same JSON format, different storage — readable
    // pre-unlock, encrypted by Android's device-level DE encryption.
    // compileSdk 37 stub trim: getDeviceProtectedStorageContext() is gone; the two-step
    // createDeviceProtectedStorageContext()/isDeviceProtectedStorage() idiom survives.
    private val deCacheFile: File
        get() {
            val de = if (appContext.isDeviceProtectedStorage) appContext
                     else appContext.createDeviceProtectedStorageContext()
            return File(de.filesDir, CACHE_FILE_NAME)
        }

    /**
     * Synchronous fast-path load; call first thing on service connect / boot receiver. A
     * no-op once anything has been published (never regresses an authoritative snapshot).
     */
    fun loadFastPath(): RulesSnapshot {
        ref.get()?.let { return it }
        val cached = RulesSnapshot.readFrom(cacheFile)
        val published = cached ?: RulesSnapshot.EMPTY
        ref.set(published)
        _state.value = SnapshotState.Loading
        Log.i(TAG, "Fast path: ${if (cached == null) "no cache, publishing empty" else "published cache v${published.version}"}")
        return published
    }

    /**
     * Device-protected restore (plan §8.5) for the [LockedBootReceiver] — publishes the DE copy
     * as Ready so enforcement data exists before first unlock, when Room is unreachable. A
     * no-op once anything has been published (never regresses an authoritative snapshot); the
     * post-unlock authoritative load supersedes it and rewrites the DE copy (reconciliation).
     */
    fun loadDeviceProtected(): RulesSnapshot? {
        ref.get()?.let { return null }
        val cached = RulesSnapshot.readFrom(deCacheFile) ?: return null
        ref.set(cached)
        _state.value = SnapshotState.Ready(cached.version)
        Log.i(TAG, "Device-protected restore: published cache v${cached.version}")
        return cached
    }

    /**
     * Authoritative rebuild from the rules store; suspend, run on a background thread.
     * Concurrent callers serialize on [loadMutex]; each runs a fresh rebuild, so a call
     * arriving mid-load publishes the current DB state rather than being dropped.
     */
    suspend fun loadFromDb() = loadMutex.withLock { buildAndPublish() }

    private suspend fun buildAndPublish() {
        try {
            val store = try {
                storeFactory.open()
            } catch (e: VaultLockedException) {
                // §8.9: the App Lock vault closing is a deferral, not a degradation — the
                // fast-path/DE snapshot keeps deciding until the next unlocked rebuild.
                Log.i(TAG, "Authoritative snapshot load deferred: vault locked")
                return
            }
            if (store == null) {
                degrade("rules store unavailable")
                return
            }
            val rules = try {
                store.restrictionRuleDao().getActiveRules()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Authoritative snapshot load failed", e)
                degrade("db read failed: ${e.javaClass.simpleName}")
                return
            }
            // Version authority is Room (plan §4), not a publisher-local counter: survives
            // restart, monotone per write transaction, consistent across all engines.
            val version = try {
                store.policyVersionDao().current()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Policy version read failed", e)
                degrade("policy version read failed: ${e.javaClass.simpleName}")
                return
            }
            val snapshot = RulesSnapshot.build(rules, version, System.currentTimeMillis())
            // Both caches are best-effort speed-ups; the publish below is the authority. A
            // cache I/O failure (disk-full class, B-10) must neither abort the publish nor
            // escape into the handler-less service scopes that cold-load this — the DE write
            // alone being wrapped let a CE-cache write failure crash-loop every boot and
            // service start (finding #10, 2026-10-16).
            try {
                snapshot.writeTo(cacheFile)
            } catch (e: Throwable) {
                Log.e(TAG, "Snapshot cache write failed", e)
            }
            try {
                snapshot.writeTo(deCacheFile)
            } catch (e: Throwable) {
                // DE copy is best-effort reconciliation; a failure must not degrade the CE path.
                Log.e(TAG, "Device-protected cache write failed", e)
            }
            ref.set(snapshot)
            _state.value = SnapshotState.Ready(snapshot.version)
            Log.i(TAG, "Authoritative snapshot v${snapshot.version}: ${snapshot.appRules.size} app, ${snapshot.urlRules.size} url rules")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Totality (finding #1's pattern): the class contract is "authoritative failure →
            // Degraded(reason), last published snapshot stays in use" — an unexpected thrower
            // (open()'s pre-open steps, a future step) must surface as Degraded, never as a
            // crash in the callers' handler-less scopes.
            degrade("snapshot publish failed: ${e.javaClass.simpleName}")
        }
    }

    private fun degrade(reason: String) {
        Log.e(TAG, "Snapshot DEGRADED: $reason")
        _state.value = SnapshotState.Degraded(reason)
    }

    private companion object {
        const val TAG = "MyExodusRules"
        const val CACHE_FILE_NAME = "rules_snapshot.json"
    }
}
