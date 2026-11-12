package com.example.myexodus.accountability.delivery

import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.PartnerEventJournal
import java.util.UUID

/** §13.3 staged transports for one journaled rule-lifecycle row. */
enum class PartnerRuleChangeTransport {
    /** The shipped signed-chain path — the default while the staged flag is off. */
    LEGACY,
    /** Handed to the service outbox under the journal row's own event id. */
    SIGNED,
    /** The signed copy was already accepted end to end; nothing left to do. */
    ACKNOWLEDGED,
    /** Not deliverable this pass (no account, queue full, unparseable): the row stays. */
    DEFERRED,
}

/**
 * Phase 7 producer switch (plan §13.3/§13.4): converts a journaled rule-lifecycle row into a
 * `RULE_CHANGE_ATTEMPT` service-outbox event when the staged flag is on, and reports which
 * transport the reconcile pass must use for that row.
 *
 * The journal row's own UUID is the outbox event id, so a migrated record keeps a stable
 * identity across both transports; the mailbox document id embeds the same UUID and the
 * recipient feed's `(pairId, eventId)` primary key makes a cross-path duplicate a no-op.
 * Envelopes are sealed for the row's route pair only — a rule assigned to one partner never
 * fans out to the others (§13.4 consent boundary).
 */
class PartnerRuleChangeProducer(
    private val outbox: ServiceEventOutbox,
    private val recorder: PartnerEventRecorder,
    private val storeFactory: AppRulesStoreFactory,
    private val staged: suspend () -> Boolean,
    private val resolveRuleLabel: (String, String) -> String = { _, target -> target },
) {

    suspend fun transport(row: PartnerEventJournal): PartnerRuleChangeTransport {
        // A row already in the signed outbox (a crash after enqueue, before the journal row
        // was deleted) keeps its signed path — never re-sign a migrated record as legacy.
        outbox.event(row.eventId)?.let { existing ->
            return if (existing.status == PendingPartnerEvent.STATUS_ACKED) {
                PartnerRuleChangeTransport.ACKNOWLEDGED
            } else {
                PartnerRuleChangeTransport.SIGNED
            }
        }
        if (!staged()) return PartnerRuleChangeTransport.LEGACY
        val ruleId = row.ruleId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return PartnerRuleChangeTransport.DEFERRED
        val change = row.ruleChange?.let(RuleChange::fromWire)
            ?: return PartnerRuleChangeTransport.DEFERRED
        // The reconcile pass that reaches here already holds an open vault, so the migration
        // only ever runs under the normal foreground unlock (§13.4).
        val identity = row.ruleIdentityOrNull() ?: legacyRuleIdentity(row)
        val payload = PartnerRuleChangePayload.encode(
            ruleId = ruleId,
            change = change,
            identity = identity,
            occurredAtMinute = row.occurredAtMinute,
        )
        return when (
            recorder.record(
                kind = PartnerEventKind.RULE_CHANGE_ATTEMPT,
                safeMetadata = mapOf("ruleChange" to change.wire),
                sensitivePayload = payload,
                recipientPairId = row.routePairId,
                requestedEventId = row.eventId,
            )
        ) {
            is PartnerEventRecorder.RecordResult.Recorded -> PartnerRuleChangeTransport.SIGNED
            else -> PartnerRuleChangeTransport.DEFERRED
        }
    }

    /** A v1 journal row queued before the identity upgrade: resolve the target now, exactly
     *  as the legacy emitter did, so a migrated row still names what changed. */
    private suspend fun legacyRuleIdentity(row: PartnerEventJournal): RuleIdentity {
        val rule = runCatching {
            storeFactory.open()?.restrictionRuleDao()?.getById(row.ruleId.orEmpty())
        }.getOrNull() ?: return RuleIdentity(label = "Restriction rule", targetType = RuleTargetType.APP)
        return RuleIdentity.of(
            targetType = rule.targetType,
            target = rule.blockedPackage,
            label = resolveRuleLabel(rule.targetType, rule.blockedPackage),
            batchName = rule.batchName,
        )
    }
}
