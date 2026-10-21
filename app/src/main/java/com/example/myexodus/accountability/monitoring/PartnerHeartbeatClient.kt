package com.example.myexodus.accountability.monitoring

import android.app.usage.UsageStatsManager
import android.os.Build
import android.util.Log
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.dao.PartnerLivenessDao
import com.example.myexodus.data.db.entity.DeviceLiveness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** The server's answer to one heartbeat. [accepted] false is a normal response, not an error:
 *  [serverCounter] then tells a client with a lost/reset counter where to resume. */
data class HeartbeatReply(
    val accepted: Boolean,
    val serverCounter: Long,
    val generation: Long,
)

/** Plan §4.7 App Standby groups, recorded as a low-sensitivity diagnostic only. */
enum class StandbyBucket(val wire: String) {
    EXEMPTED("EXEMPTED"),
    ACTIVE("ACTIVE"),
    WORKING_SET("WORKING_SET"),
    FREQUENT("FREQUENT"),
    RARE("RARE"),
    RESTRICTED("RESTRICTED"),
    NEVER("NEVER"),
    UNKNOWN("UNKNOWN"),
    ;

    val isConstrained: Boolean get() = this == RARE || this == RESTRICTED

    companion object {
        // The compileSdk 37 stubs trim these two public constants (javap-verified 2026-10-21;
        // ADR #17's adaptation pattern). Values per AOSP frameworks/base UsageStatsManager.
        private const val BUCKET_EXEMPTED = 5
        private const val BUCKET_NEVER = 50

        /** API 28+ bucket integers; called only behind [platformSupported]. */
        fun fromPlatform(bucket: Int): StandbyBucket = when (bucket) {
            BUCKET_EXEMPTED -> EXEMPTED
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> ACTIVE
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> WORKING_SET
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> FREQUENT
            UsageStatsManager.STANDBY_BUCKET_RARE -> RARE
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> RESTRICTED
            BUCKET_NEVER -> NEVER
            else -> UNKNOWN
        }

        val platformSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }
}

/**
 * §7 Step 7 liveness client (plan §4.7). Beats are authenticated callables carrying only
 * `{installId, counter}` — the monitoring fields never travel here; they ride the E2EE
 * snapshot body. The counter is persisted BEFORE the network call, so a crash mid-send can
 * only burn a number, never reuse one. The namespace is `(uid, installId)`: an identity
 * rotation re-enrolls and restarts at 1, and the server treats it as a new document — a lost
 * counter can never lock an install out (§8.10).
 *
 * Cadence is enforced here ([MIN_INTERVAL_MILLIS]) so every caller — the FGS piggyback and
 * the WorkManager fallback — shares one clock; [force] is reserved for check-ins the plan
 * names (service start, material state change). Failures are silent by design: liveness is
 * best-effort, and the next eligible check-in is the retry.
 */
class PartnerHeartbeatClient(
    private val storeFactory: AppRulesStoreFactory,
    private val uid: suspend () -> String?,
    private val installId: suspend () -> String?,
    private val send: suspend (installId: String, counter: Long) -> HeartbeatReply?,
    /** API 28+ Standby bucket read; null = unsupported or the platform refused. */
    private val standbyBucket: () -> StandbyBucket?,
    private val signals: PartnerSyncSignals = PartnerSyncSignals,
    /** Plan §7 Step 7's jittered interval: the periodic beat lands 15 minutes plus a random
     *  [0, JITTER_MAX_MILLIS) after the last send, so a fleet never shares one boundary.
     *  Never shortens the 15-minute floor. Injectable for deterministic tests. */
    private val jitterMillis: () -> Long = { Random.nextLong(JITTER_MAX_MILLIS) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome {
        /** Inside the 15-minute window (or the namespace could not be resolved). */
        SKIPPED,
        ACCEPTED,
        /** The server holds a different counter — this client resynced onto it. */
        RESYNCED,
        FAILED,
    }

    private val mutex = Mutex()

    /** Last bucket this process explained; a transition re-arms it, repetition does not. */
    @Volatile private var explainedBucket: StandbyBucket? = null

    /** The plan names startup as a standby read point. The 6-hour interval alone would skip
     *  it on a cold process whose first check-in is the unforced WorkManager pass — exactly
     *  the constrained device the diagnostic exists for. */
    @Volatile private var standbyReadThisProcess = false

    suspend fun checkIn(force: Boolean = false): Outcome = mutex.withLock {
        val store = storeFactory.open() ?: return@withLock Outcome.FAILED
        val dao = store.partnerLivenessDao()
        val uidValue = uid() ?: return@withLock Outcome.SKIPPED
        val install = installId() ?: return@withLock Outcome.SKIPPED
        // A uid change is a new namespace: its counter starts at 1 and the accepted fields
        // belong to the old server document, so they are not carried over.
        val previous = dao.liveness(install)?.takeIf { it.uid == uidValue }
        val nowMs = now()
        if (!force && previous != null &&
            nowMs - previous.lastSentAtMs < MIN_INTERVAL_MILLIS + jitterMillis()
        ) {
            return@withLock Outcome.SKIPPED
        }

        val startupRead = !standbyReadThisProcess
        standbyReadThisProcess = true
        checkStandby(dao, force || startupRead)

        val counter = (previous?.counter ?: 0L) + 1
        // Persist first: a crash after this line but before the response burns one number;
        // it can never reuse one. The row also carries the pre-send accepted fields forward.
        dao.upsertLiveness(
            DeviceLiveness(
                installId = install,
                uid = uidValue,
                counter = counter,
                lastSentAtMs = nowMs,
                lastAcceptedCounter = previous?.lastAcceptedCounter ?: 0,
                lastAcceptedAtMs = previous?.lastAcceptedAtMs ?: 0,
            ),
        )
        val reply = try {
            send(install, counter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "heartbeat send failed counter=$counter", e)
            null
        } ?: return@withLock Outcome.FAILED

        if (!reply.accepted) {
            // A normal answer: this client's namespace is behind (restore, reinstall onto the
            // same install id) or it beat inside the server floor. Resume from the server's
            // counter — never reject such a device forever because its old counter was lost.
            // The prior acceptance time survives: a refused beat never unproves the last one.
            Log.i(TAG, "heartbeat refused counter=$counter server=${reply.serverCounter}")
            dao.upsertLiveness(
                DeviceLiveness(
                    installId = install,
                    uid = uidValue,
                    counter = maxOf(reply.serverCounter, counter),
                    lastSentAtMs = nowMs,
                    lastAcceptedCounter = reply.serverCounter,
                    lastAcceptedAtMs = previous?.lastAcceptedAtMs ?: 0,
                ),
            )
            return@withLock Outcome.RESYNCED
        }
        dao.upsertLiveness(
            DeviceLiveness(
                installId = install,
                uid = uidValue,
                counter = counter,
                lastSentAtMs = nowMs,
                lastAcceptedCounter = counter,
                lastAcceptedAtMs = nowMs,
            ),
        )
        Outcome.ACCEPTED
    }

    /** Reader of record for the server-side liveness row (Step 8's deadlines build on it). */
    suspend fun lastAcceptedAt(): Long? {
        val store = storeFactory.open() ?: return null
        val install = installId() ?: return null
        return store.partnerLivenessDao().liveness(install)?.lastAcceptedAtMs?.takeIf { it > 0 }
    }

    /**
     * §4.7 App Standby diagnostic: read at a check-in (never a tight loop), persisted as the
     * bucket name only, and explained at most once per transition in this process. The
     * explanation never claims a setting is required or that Unrestricted guarantees delivery,
     * and no battery-exemption permission is ever requested.
     */
    private suspend fun checkStandby(dao: PartnerLivenessDao, force: Boolean) {
        if (!StandbyBucket.platformSupported) return
        val nowMs = now()
        val previous = runCatching { dao.checkin() }.getOrNull()
        if (!force && previous != null &&
            nowMs - previous.standbyCheckedAtMs < STANDBY_INTERVAL_MILLIS
        ) {
            return
        }
        val bucket = runCatching { standbyBucket() }.getOrNull() ?: return
        Log.i(TAG, "standby bucket=${bucket.wire}")
        runCatching {
            dao.insertCheckinIfAbsent()
            dao.updateStandbyBucket(bucket.wire, nowMs)
        }.onFailure { Log.w(TAG, "standby diagnostic write failed", it) }
        if (bucket.isConstrained) {
            if (explainedBucket != bucket) {
                explainedBucket = bucket
                signals.standbyNotice.tryEmit(bucket)
            }
        } else {
            // A return to an unrestricted group re-arms the explanation for the next decline.
            explainedBucket = null
        }
    }

    companion object {
        private const val TAG = "MyExodusHeartbeat"

        /** Plan §4.7: a heartbeat is never sent more often than every 15 minutes. */
        const val MIN_INTERVAL_MILLIS = 15L * 60 * 1000

        /** Plan §7 Step 7's jitter ceiling — never shortens [MIN_INTERVAL_MILLIS]. */
        const val JITTER_MAX_MILLIS = 2L * 60 * 1000

        /** Standby is re-read once per 6 hours (plus at forced check-ins) — never polled. */
        const val STANDBY_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
    }
}

/** Parses the callable's `{accepted, serverCounter, generation}` data; null = unusable. */
internal fun parseHeartbeatReply(data: Any?): HeartbeatReply? {
    val map = data as? Map<*, *> ?: return null
    val accepted = map["accepted"] as? Boolean ?: return null
    val counter = (map["serverCounter"] as? Number)?.toLong() ?: return null
    val generation = (map["generation"] as? Number)?.toLong() ?: return null
    if (counter < 0 || generation < 0) return null
    return HeartbeatReply(accepted, counter, generation)
}
