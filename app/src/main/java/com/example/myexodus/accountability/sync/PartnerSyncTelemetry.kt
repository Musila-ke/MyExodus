package com.example.myexodus.accountability.sync

/**
 * §14 client-side metric wiring, sampled once per **periodic** pass ([PartnerSyncWorker]) —
 * deliberately not on the dashboard's manual "Check now", which would let a user's taps
 * inflate the counts. Metadata only (Decision 12's data contract): an event name plus a
 * coarse bucket, never package names, durations in the clear, or content.
 *
 * The metrics and their rails:
 * - `partner_notifications_denied` — the §14 "denied rate among active pairs" numerator; only
 *   enrolled devices report, so the Crashlytics event count over the `pairings` collection's
 *   active-pair count approximates the rate on the console side.
 * - `partner_outbox_pending age=<bucket>` — the §14 outbox age distribution. Fired whenever
 *   any row is still PENDING at pass end; an absent event is a healthy pass.
 * - `partner_t1_ms bucket=<bucket>` (fired by [MailboxOutboxDrainer], same rail) — the §11 T1
 *   stage (write attempt → Firestore confirmed), bucketed against its <1s/<3s targets.
 */
class PartnerSyncTelemetry(
    private val canPost: () -> Boolean,
    private val oldestPendingCreatedAt: suspend () -> Long?,
    private val report: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun afterPass(pass: PartnerSyncCoordinator.SyncReport) {
        if (!pass.enrolled) return
        if (!canPost()) report(EVENT_NOTIFICATIONS_DENIED)
        val oldest = runCatching { oldestPendingCreatedAt() }.getOrNull() ?: return
        report("$EVENT_OUTBOX_PENDING age=${outboxAgeBucket(now() - oldest)}")
    }

    companion object {
        const val EVENT_NOTIFICATIONS_DENIED = "partner_notifications_denied"
        const val EVENT_OUTBOX_PENDING = "partner_outbox_pending"

        /** §11 T1 stage sample, emitted by the drainer through the same Crashlytics rail. */
        const val EVENT_T1_MS = "partner_t1_ms"

        /** §11 T1 targets: p50 < 1s, p95 < 3s — the bucket edges are the targets themselves. */
        fun latencyBucket(millis: Long): String = when {
            millis < 1_000 -> "under_1s"
            millis < 3_000 -> "under_3s"
            else -> "over_3s"
        }

        fun outboxAgeBucket(ageMillis: Long): String = when {
            ageMillis < 15 * 60_000L -> "under_15m"
            ageMillis < 60 * 60_000L -> "under_1h"
            ageMillis < 24 * 60 * 60_000L -> "under_24h"
            else -> "over_24h"
        }
    }
}
