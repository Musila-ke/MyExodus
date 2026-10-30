package com.example.myexodus.account

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.myexodus.accountability.sync.await
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** How far the one-time legacy-cache purge got on this install (plan §9). */
enum class CloudAccess { READY, BLOCKED }

/** The cloud half of [LocalSessionManager]; a seam so the session rules are JVM-testable. */
interface SessionCloud {
    /**
     * Account plan §8.7: resolves true only when every write queued under the current identity
     * has been acknowledged. False means the caller must block the switch (or need an explicit
     * discard) — never that the writes were somehow settled.
     */
    suspend fun flushPendingWrites(timeoutMillis: Long): Boolean
}

class FirestoreSessionCloud @Inject constructor() : SessionCloud {
    override suspend fun flushPendingWrites(timeoutMillis: Long): Boolean = try {
        withTimeoutOrNull(timeoutMillis) {
            FirebaseFirestore.getInstance().waitForPendingWrites().await()
            true
        } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A terminated client or a failed queue is just as blocking as a timeout.
        Log.w(TAG, "pending-write flush failed", e)
        false
    }

    private companion object {
        const val TAG = "MyExodusSessionCloud"
    }
}

/**
 * Account plan §7 step 20 last sentence / §9: the one-time purge of any Firestore disk cache
 * left by a pre-memory-only release. Runs **before** the Hilt graph exists ([MyExodusApp]
 * calls it before `super.onCreate()`), so there is no concurrent Firestore consumer that could
 * open the legacy cache; the first Firestore operation of the process happens strictly after
 * this returns. Routine account switches never call this — `clearPersistence` is reserved for
 * the upgrade.
 *
 * `terminate()` first is both the documented ordering (clearPersistence is only valid on a
 * terminated client) and what makes the later `getInstance()` throw the old client away.
 * Failure leaves the flag unset: the session manager reports [CloudAccess.BLOCKED] for this
 * launch and the user's recoverable action is a restart, never a fallback to disk persistence.
 */
object LegacyCachePurge {
    /** Shared by [PrefsSessionStore]; one journal for the process-level session facts. */
    internal const val PREFS = "local_session"
    internal const val KEY_LEGACY_CACHE_PURGED = "legacy_cache_purged"

    private const val TAG = "MyExodusLegacyCache"
    private const val TIMEOUT_SECONDS = 3L
    private const val WORKER_DEADLINE_MS = 6_500L

    /**
     * True when this install is already purged or the purge just succeeded now. Blocking on
     * purpose (the caller is the Application's bootstrap); the blocking body runs on a
     * short-lived worker because `Tasks.await` is main-thread-forbidden. A worker still
     * running at the deadline leaves the flag unset — this launch reads BLOCKED and the
     * next one retries, exactly like a failure.
     */
    fun run(context: Context, timeoutMillis: Long = WORKER_DEADLINE_MS): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LEGACY_CACHE_PURGED, false)) return true
        val result = AtomicBoolean(false)
        val worker = Thread({ result.set(purge(prefs)) }, "myexodus-cache-purge")
        worker.start()
        worker.join(timeoutMillis)
        return result.get()
    }

    private fun purge(prefs: android.content.SharedPreferences): Boolean = try {
        val instance = FirebaseFirestore.getInstance()
        Tasks.await(instance.terminate(), TIMEOUT_SECONDS, TimeUnit.SECONDS)
        Tasks.await(instance.clearPersistence(), TIMEOUT_SECONDS, TimeUnit.SECONDS)
        prefs.edit(commit = true) { putBoolean(KEY_LEGACY_CACHE_PURGED, true) }
        Log.i(TAG, "legacy Firestore disk cache purged")
        true
    } catch (e: Exception) {
        Log.w(TAG, "legacy Firestore cache purge failed — cloud access blocked this launch", e)
        false
    }
}
