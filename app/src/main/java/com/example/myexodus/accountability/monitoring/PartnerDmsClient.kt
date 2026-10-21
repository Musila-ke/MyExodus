package com.example.myexodus.accountability.monitoring

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Plan §4/§8.13 connection signal, derived server-side at read time from accepted heartbeats.
 *  Deliberately separate from [com.example.myexodus.accountability.serialization.MonitoringState]
 *  (the E2EE snapshot) and from sync freshness — the UI never blends them into one badge. */
enum class PartnerConnection(val wire: String) {
    CHECKING_IN("CHECKING_IN"),
    LATE("LATE"),
    NOT_CHECKING_IN("NOT_CHECKING_IN"),

    /** No accepted heartbeat exists yet — not evidence of lateness either way. */
    NO_DATA("NO_DATA"),
    ;

    companion object {
        fun fromWire(value: String?): PartnerConnection? =
            entries.firstOrNull { it.wire == value }
    }
}

/** Plan §4: coarse availability buckets only — the screen never renders a check-in timestamp. */
enum class PartnerConnectionBucket(val wire: String) {
    WITHIN_1H("WITHIN_1H"),
    HOURS_1_TO_6("HOURS_1_TO_6"),
    OVER_6H("OVER_6H"),
    NO_DATA("NO_DATA"),
    ;

    companion object {
        fun fromWire(value: String?): PartnerConnectionBucket? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * One `readPartnerStatus` answer, exactly the fields §8.10 allows: [consent] is the monitored
 * member's explicit `DEVICE_AVAILABILITY_STATUS` choice. False is a distinct state — nothing is
 * shared, so no connection claim can be made.
 */
data class PartnerStatus(
    val consent: Boolean,
    val connection: PartnerConnection,
    val bucket: PartnerConnectionBucket,
)

/**
 * Plan §7 Step 8 client half (plan §4/§8.10): consent convergence and the partner's coarse
 * connection reading.
 *
 * [syncConsent] writes this device's explicit availability choice onto the authoritative pairing
 * document. The server handler is idempotent, so re-sending is harmless; the in-memory ack cache
 * only stops one callable per pair per 15-minute pass. A failed call keeps the previous ack, so
 * the next pass retries — offline revocation is "pending until acknowledged", exactly as the
 * plan phrases it.
 *
 * [status] is cached for [statusTtlMillis] because resumes and pull ticks reload the dashboard
 * far more often than the 45/90-minute thresholds can change; the cache only bounds callable
 * traffic. A failed read returns a still-fresh cached reading, never a fabricated state.
 *
 * Every call requires a resolved identity first: the callables reject anonymous-less traffic,
 * and signing in from here is the same single-flight path the rest of the app uses.
 */
class PartnerDmsClient(
    private val uid: suspend () -> String?,
    private val requestConsent: suspend (pairId: String, enabled: Boolean) -> Boolean,
    private val readRemoteStatus: suspend (pairId: String) -> PartnerStatus?,
    private val now: () -> Long = System::currentTimeMillis,
    private val statusTtlMillis: Long = STATUS_TTL_MILLIS,
) {
    private data class CachedStatus(val status: PartnerStatus, val atMs: Long)

    private val mutex = Mutex()

    /** pairId -> the last value the server confirmed. */
    private val ackedConsent = HashMap<String, Boolean>()

    private val statusCache = HashMap<String, CachedStatus>()

    /** True = the server holds [enabled] for this pair (or already did). */
    suspend fun syncConsent(pairId: String, enabled: Boolean): Boolean = mutex.withLock {
        if (uid() == null) return@withLock false
        if (ackedConsent[pairId] == enabled) return@withLock true
        val acked = requestConsent(pairId, enabled)
        if (acked) ackedConsent[pairId] = enabled
        acked
    }

    suspend fun status(pairId: String, force: Boolean = false): PartnerStatus? = mutex.withLock {
        if (uid() == null) return@withLock null
        val cached = statusCache[pairId]
        if (!force && cached != null && now() - cached.atMs <= statusTtlMillis) {
            return@withLock cached.status
        }
        val fresh = readRemoteStatus(pairId)
        if (fresh != null) {
            statusCache[pairId] = CachedStatus(fresh, now())
            return@withLock fresh
        }
        cached?.takeIf { now() - it.atMs <= statusTtlMillis }?.status
    }

    companion object {
        /** One call a minute per pair — far coarser than any displayed threshold. */
        const val STATUS_TTL_MILLIS = 60_000L
    }
}

/** Parses `{consent, connection, bucket}`; null = unusable, and the screen then claims nothing. */
internal fun parsePartnerStatus(data: Any?): PartnerStatus? {
    val map = data as? Map<*, *> ?: return null
    val consent = map["consent"] as? Boolean ?: return null
    val connection = PartnerConnection.fromWire(map["connection"] as? String) ?: return null
    val bucket = PartnerConnectionBucket.fromWire(map["bucket"] as? String) ?: return null
    return PartnerStatus(consent, connection, bucket)
}
