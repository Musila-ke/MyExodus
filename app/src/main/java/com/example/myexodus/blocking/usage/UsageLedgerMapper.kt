package com.example.myexodus.blocking.usage

import com.example.myexodus.data.db.entity.UsageLedger
import com.example.myexodus.data.db.entity.UsageLedgerKind
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** One journal write, before identity and day attribution are derived from it. */
data class LedgerEventDraft(
    val kind: Int,
    val packageName: String,
    val creditedMs: Long,
    val elapsedStartMs: Long,
    val elapsedEndMs: Long,
    val bootCount: Int,
    val source: String,
    /** Wall-clock instant the observation closed at; also the split's reference point. */
    val wallClockTsMs: Long,
    val referencesEventId: String? = null,
    /** §8.10's deterministic seed id; every other kind derives its own via [UsageLedgerMapper.eventIdFor]. */
    val eventIdOverride: String? = null,
)

/** One row's worth of a draft: a single local day's share of the credited interval. */
data class LedgerSlice(
    val dayKey: String,
    val creditedMs: Long,
    val elapsedStartMs: Long,
    val elapsedEndMs: Long,
)

/**
 * The accounting mapper (usage-ledger plan §8.2/§8.5/§8.6): validates a draft, derives its
 * deterministic identity, and attributes it to one or two local days. Pure — every function
 * here is arithmetic over its arguments, which is what makes the ledger's rules testable
 * without a database.
 */
object UsageLedgerMapper {

    /** §8.2's bound. Android caps package names at 255 bytes; anything longer is not a package. */
    const val MAX_PACKAGE_LENGTH = 255

    /**
     * §8.6 deterministic identity: truncated SHA-256 over the observation's own stable
     * properties. A retried delivery — WorkManager retry, callback replay, service restart —
     * reproduces the same id and therefore the same row, so the unique index turns duplicate
     * delivery into a no-op instead of a double credit.
     *
     * The interval bounds, not the wall clock, are the identity inputs: a clock change must
     * never make the same observation look like a new one.
     */
    fun eventIdFor(
        source: String,
        packageName: String,
        elapsedStartMs: Long,
        elapsedEndMs: Long,
        bootCount: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "$source|$packageName|$elapsedStartMs|$elapsedEndMs|$bootCount".toByteArray(),
        )
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * A CORRECTION is a fresh act, not a replay: two identical corrections issued at different
     * instants are two corrections and must both land. Its id is therefore keyed on the
     * correcting instant as well as the corrected event, which keeps redelivery of *one*
     * correction idempotent while leaving a repeated correction expressible.
     */
    fun correctionEventIdFor(targetEventId: String, wallClockTsMs: Long): String =
        "correction:$targetEventId:$wallClockTsMs"

    /**
     * A carryover owns no interval, so [eventIdFor]'s bounds give every top-up in a boot the
     * same id — the second reconcile after a pause collides with the first and is dropped
     * (owner-caught 2026-10-20: a repeated rule toggle lost 1 h 21 m of usage). The top-up is
     * identified by what it claims — the package, the platform total it raises to, and the
     * instant it closed — which keeps a retried reconcile of the same claim idempotent while
     * letting a later, larger claim land as its own row.
     */
    fun carryoverEventIdFor(packageName: String, wallClockTsMs: Long, targetTotalMs: Long): String =
        "carryover:$packageName:$wallClockTsMs:$targetTotalMs"

    /**
     * §8.2 validation, returning the rejection reason or null when the draft is insertable.
     * A rejected draft is never written and never silently dropped — the caller decides what
     * to do with the reason.
     */
    fun rejectionReason(draft: LedgerEventDraft): String? {
        if (draft.eventIdOverride?.isEmpty() == true) return "empty event_id"
        if (draft.packageName.isEmpty()) return "empty package_name"
        if (draft.packageName.length > MAX_PACKAGE_LENGTH) return "package_name over $MAX_PACKAGE_LENGTH"
        if (draft.kind !in KNOWN_KINDS) return "unknown kind ${draft.kind}"
        // §8.12 makes CORRECTION the one signed kind — it is a compensating adjustment and its
        // magnitude is deliberately unrelated to any interval it owns. §8.2's rejection list is
        // written for the observing kinds; where the two disagree, the specific rule wins.
        if (draft.creditedMs < 0 && draft.kind != UsageLedgerKind.CORRECTION) {
            return "negative credited_ms"
        }
        if (draft.elapsedEndMs < draft.elapsedStartMs) return "elapsed_end before elapsed_start"
        val span = draft.elapsedEndMs - draft.elapsedStartMs
        if ((draft.kind == UsageLedgerKind.ACCOUNTING || draft.kind == UsageLedgerKind.RECONCILIATION) &&
            draft.creditedMs > span
        ) {
            return "credited_ms exceeds elapsed span"
        }
        if (draft.referencesEventId != null && draft.referencesEventId.isEmpty()) {
            return "empty references_event_id"
        }
        if (draft.kind == UsageLedgerKind.CORRECTION && draft.referencesEventId == null) {
            return "correction without references_event_id"
        }
        return null
    }

    /**
     * §8.5/§8.6 day attribution. An interval whose wall-clock span crosses local midnight
     * becomes exactly two slices, each with its own frozen `day_key`; the credit is split in
     * proportion to the epoch-millisecond share on each side, with the remainder given to the
     * closing slice so the two shares always sum to [creditedMs] exactly.
     *
     * The wall start is derived by walking back the monotonic span from [wallClockTsMs], so
     * DST is handled without special-casing: both sides are epoch arithmetic, and only the
     * *labels* come from the zone.
     */
    fun splitAtDayBoundary(draft: LedgerEventDraft, zone: ZoneId): List<LedgerSlice> {
        val span = draft.elapsedEndMs - draft.elapsedStartMs
        val endDay = Instant.ofEpochMilli(draft.wallClockTsMs).atZone(zone).toLocalDate()
        if (span <= 0L) {
            return listOf(LedgerSlice(endDay.toString(), draft.creditedMs, draft.elapsedStartMs, draft.elapsedEndMs))
        }
        val wallStartMs = draft.wallClockTsMs - span
        val startDay = Instant.ofEpochMilli(wallStartMs).atZone(zone).toLocalDate()
        if (startDay == endDay) {
            return listOf(LedgerSlice(endDay.toString(), draft.creditedMs, draft.elapsedStartMs, draft.elapsedEndMs))
        }
        val boundaryMs = startDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val firstSpan = boundaryMs - wallStartMs
        // Outside the span on either side (a zone that skips or repeats a day, §8.5): the
        // interval is not splittable, so it is attributed to the day it closed on rather than
        // inventing a third day. `firstSpan == span` — the interval ends exactly at midnight —
        // is NOT this case: the half-open interval contains no instant of the new day, so it
        // falls through and the proportional split puts every millisecond on the opening day.
        if (firstSpan <= 0L || firstSpan > span) {
            return listOf(LedgerSlice(endDay.toString(), draft.creditedMs, draft.elapsedStartMs, draft.elapsedEndMs))
        }
        val firstCredit = draft.creditedMs * firstSpan / span
        return listOf(
            LedgerSlice(
                startDay.toString(),
                firstCredit,
                draft.elapsedStartMs,
                draft.elapsedStartMs + firstSpan,
            ),
            LedgerSlice(
                endDay.toString(),
                draft.creditedMs - firstCredit,
                draft.elapsedStartMs + firstSpan,
                draft.elapsedEndMs,
            ),
        )
    }

    /** Materializes one slice as an insertable row, deriving the id when the draft has no override. */
    fun toRow(
        draft: LedgerEventDraft,
        slice: LedgerSlice,
        sourceVersion: String,
    ): UsageLedger = UsageLedger(
        eventId = draft.eventIdOverride ?: eventIdFor(
            draft.source,
            draft.packageName,
            slice.elapsedStartMs,
            slice.elapsedEndMs,
            draft.bootCount,
        ),
        kind = draft.kind,
        wallClockTsMs = draft.wallClockTsMs,
        elapsedStartMs = slice.elapsedStartMs,
        elapsedEndMs = slice.elapsedEndMs,
        bootCount = draft.bootCount,
        packageName = draft.packageName,
        dayKey = slice.dayKey,
        creditedMs = slice.creditedMs,
        source = draft.source,
        sourceVersion = sourceVersion,
        referencesEventId = draft.referencesEventId,
    )

    /** §8.6's duplicate test for an id that is already present: same payload = the same
     *  observation arriving twice (safe no-op); anything else is an id collision, which is a
     *  bug in identity derivation and must never be mistaken for a retry. */
    fun samePayload(existing: UsageLedger, incoming: UsageLedger): Boolean =
        existing.kind == incoming.kind &&
            existing.creditedMs == incoming.creditedMs &&
            existing.elapsedStartMs == incoming.elapsedStartMs &&
            existing.elapsedEndMs == incoming.elapsedEndMs

    private val KNOWN_KINDS = setOf(
        UsageLedgerKind.ACCOUNTING,
        UsageLedgerKind.HEARTBEAT,
        UsageLedgerKind.RULE_EVALUATION,
        UsageLedgerKind.GAP_DETECTED,
        UsageLedgerKind.MIGRATION_CARRYOVER,
        UsageLedgerKind.RECONCILIATION,
        UsageLedgerKind.CORRECTION,
    )
}
