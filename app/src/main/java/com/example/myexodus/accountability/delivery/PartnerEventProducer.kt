package com.example.myexodus.accountability.delivery

import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.PartnerEventJournal
import java.util.UUID

/** §13.3 staged transports for one locally observable partner-visible event. */
enum class PartnerEventTransport {
    /** The shipped signed-chain path — the default while the staged flag is off. */
    LEGACY,
    /** Handed to the service outbox under the event's deterministic/own id. */
    SIGNED,
    /** The signed copy was already accepted end to end; nothing left to do. */
    ACKNOWLEDGED,
    /** Not deliverable this pass (no account, queue full, unparseable): the row stays. */
    DEFERRED,
}

/** One queued event a reconcile pass is about to deliver. */
class PartnerEventRecord(
    val eventId: String,
    val kind: PartnerEventKind,
    val safeMetadata: Map<String, Any> = emptyMap(),
    val sensitivePayload: ByteArray? = null,
    /** Scopes envelopes to the route pairing; null fans out to every enrolled recipient. */
    val recipientPairId: String? = null,
    /** §8.2 device occurrence instant; null means "staged now" (the reporter's own act). */
    val occurredAtEpochMillis: Long? = null,
)

/**
 * Phase 7 producer switch (plan §13.3/§13.4): every partner-visible queue hands its row to
 * this switch instead of signing it with the §8.1 owner key. A staged row is sealed and
 * signed by the auth-free service key ([PartnerEventRecorder]), so delivery never raises a
 * biometric prompt. The id is the row's own persisted UUID (or a deterministic one derived
 * from the source row), so a crash after enqueue keeps a stable identity: the mailbox
 * document id embeds the same UUID and the recipient feed's `(pairId, eventId)` key makes a
 * cross-path duplicate a no-op. Envelopes are sealed for the route pair only — an event
 * assigned to one partner never fans out to the others (§13.4 consent boundary).
 */
class PartnerEventProducer(
    private val outbox: ServiceEventOutbox,
    private val recorder: PartnerEventRecorder,
    private val storeFactory: AppRulesStoreFactory,
    private val staged: suspend () -> Boolean,
    private val resolveRuleLabel: (String, String) -> String = { _, target -> target },
) {

    /** Generic §13.3 switch for a non-rule queue row. */
    suspend fun stage(record: PartnerEventRecord): PartnerEventTransport {
        // A record already in the signed outbox (a crash after enqueue, before the source row
        // was retired) keeps its signed path — never re-sign a migrated record as legacy.
        outbox.event(record.eventId)?.let { existing ->
            return if (existing.status == PendingPartnerEvent.STATUS_ACKED) {
                PartnerEventTransport.ACKNOWLEDGED
            } else {
                PartnerEventTransport.SIGNED
            }
        }
        if (!staged()) return PartnerEventTransport.LEGACY
        return when (
            recorder.record(
                kind = record.kind,
                safeMetadata = record.safeMetadata,
                sensitivePayload = record.sensitivePayload,
                recipientPairId = record.recipientPairId,
                requestedEventId = record.eventId,
                occurredAtMs = record.occurredAtEpochMillis,
            )
        ) {
            is PartnerEventRecorder.RecordResult.Recorded -> PartnerEventTransport.SIGNED
            else -> PartnerEventTransport.DEFERRED
        }
    }

    /** Rule-lifecycle rows carry their own wire metadata and legacy identity fallback. */
    suspend fun transport(row: PartnerEventJournal): PartnerEventTransport {
        outbox.event(row.eventId)?.let { existing ->
            return if (existing.status == PendingPartnerEvent.STATUS_ACKED) {
                PartnerEventTransport.ACKNOWLEDGED
            } else {
                PartnerEventTransport.SIGNED
            }
        }
        if (!staged()) return PartnerEventTransport.LEGACY
        val ruleId = row.ruleId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return PartnerEventTransport.DEFERRED
        val change = row.ruleChange?.let(RuleChange::fromWire)
            ?: return PartnerEventTransport.DEFERRED
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
            is PartnerEventRecorder.RecordResult.Recorded -> PartnerEventTransport.SIGNED
            else -> PartnerEventTransport.DEFERRED
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
