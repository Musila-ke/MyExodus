package com.example.myexodus.accountability.delivery

import com.example.myexodus.accountability.delivery.ExistingE2eeRecipientEncryptor.Outcome.RecipientKeyUnavailable
import com.example.myexodus.accountability.delivery.ExistingE2eeRecipientEncryptor.Outcome.Encrypted
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * §6.1 step 1 capture seam. Detection components call [record] on a non-blocking application
 * scope; the call never throws and enforcement never depends on its result. The pipeline is:
 * validate the kind + safe metadata, allocate UUIDv4 identity, AES-GCM-seal the sensitive
 * source with the independent service key, encrypt it once per authorized recipient with the
 * existing E2EE codec, enforce the §8.2 size budget, then commit id + sequence + row in one
 * outbox transaction.
 */
class PartnerEventRecorder(
    private val outbox: ServiceEventOutbox,
    private val protector: ServicePayloadProtector,
    private val encryptor: ExistingE2eeRecipientEncryptor,
    private val clock: PartnerDeliveryClock,
    private val ownerUid: () -> String?,
    private val newEventId: () -> String = { UUID.randomUUID().toString() },
    /** §6.1 step 4 post-commit signal; must never change the record's own outcome. */
    private val onRecorded: suspend () -> Unit = {},
) {

    sealed interface RecordResult {
        data class Recorded(
            val eventId: String,
            val sequence: Long,
            val status: String,
        ) : RecordResult

        data class Rejected(val issue: PartnerEventIssue) : RecordResult

        /** No signed-in owner: nothing is recorded under an unattributed account. */
        data object NoAccount : RecordResult

        data class CapacityExceeded(val pendingRowCount: Int, val pendingByteCount: Long) : RecordResult

        data object Failed : RecordResult
    }

    sealed interface RegenerationResult {
        data object Regenerated : RegenerationResult
        /** The re-encryption produced exactly the key set the server just rejected: resending
         *  cannot change the answer, so the row parks instead of re-firing the rejection. */
        data object RegeneratedUnchanged : RegenerationResult
        data object NoSuchEvent : RegenerationResult
        /** Not owned by the active account, not definitive-no-effect, or already terminal. */
        data object NotPermitted : RegenerationResult
        /** The sealed source is gone or no longer opens — the original bytes can only resend. */
        data object SourceUnavailable : RegenerationResult
        data object RecipientKeyUnavailable : RegenerationResult
        data object NoAccount : RegenerationResult
        data object Failed : RegenerationResult
    }

    suspend fun record(
        kind: PartnerEventKind,
        safeMetadata: Map<String, Any> = emptyMap(),
        sensitivePayload: ByteArray? = null,
        /** Scopes recipient envelopes to one pairing; null means every enrolled recipient. */
        recipientPairId: String? = null,
        /** §13.4 stable identity: a migrated record keeps its persisted legacy event ID. */
        requestedEventId: String? = null,
        /** §8.2 device occurrence instant; null = the act is being staged now. */
        occurredAtMs: Long? = null,
    ): RecordResult {
        return try {
            val uid = ownerUid() ?: return RecordResult.NoAccount
            PartnerEventProtocol.metadataIssue(kind, safeMetadata)?.let {
                return RecordResult.Rejected(it)
            }
            val eventId = requestedEventId ?: newEventId()
            val installation = outbox.ensureInstallation(uid)
            val occurredAt = occurredAtMs ?: clock.wallClockMs()
            val content = sensitivePayload ?: ByteArray(0)

            // §8.1: the source seal is retained until ACKED so a definitive RECIPIENT_KEY_STALE
            // rejection can re-encrypt. A Keystore failure degrades regeneration, not delivery —
            // the row records LOCAL_SEAL_FAILED rather than dropping an accountability event.
            val sealedSource = try {
                protector.seal(sourceSealAad(eventId, installation.installationId, uid, kind), content)
            } catch (_: Exception) {
                null
            }

            var encryption = encryptor.encryptForAll(
                eventId, installation.installationId, uid, kind, content,
                recipientPairId = recipientPairId,
            )
            var detailOmitted = false
            if (encryption is Encrypted) {
                // Probe at the largest legal sequence: the committed event is never larger.
                val probeIssue = PartnerEventProtocol.validate(
                    eventOf(
                        eventId, installation.installationId, uid, PROBE_SEQUENCE,
                        kind, occurredAt, safeMetadata, encryption.envelopes,
                    ),
                )
                when (probeIssue) {
                    null -> Unit
                    PartnerEventIssue.TOO_LARGE -> {
                        val marker = encryptor.encryptForAll(
                            eventId, installation.installationId, uid, kind,
                            ExistingE2eeRecipientEncryptor.OMITTED_DETAIL_MARKER,
                            recipientPairId = recipientPairId,
                        )
                        if (marker !is Encrypted) {
                            encryption = marker
                        } else {
                            val markerIssue = PartnerEventProtocol.validate(
                                eventOf(
                                    eventId, installation.installationId, uid, PROBE_SEQUENCE,
                                    kind, occurredAt, safeMetadata, marker.envelopes,
                                ),
                            )
                            if (markerIssue != null) return RecordResult.Rejected(markerIssue)
                            encryption = marker
                            detailOmitted = true
                        }
                    }
                    else -> return RecordResult.Rejected(probeIssue)
                }
            }

            val envelopes = (encryption as? Encrypted)?.envelopes
            val status = if (envelopes != null) {
                PendingPartnerEvent.STATUS_PENDING
            } else {
                PendingPartnerEvent.STATUS_WAITING_FOR_RECIPIENT_KEY
            }
            val lastErrorCode = when {
                envelopes == null && sealedSource == null ->
                    PartnerEventRecorder.ERROR_LOCAL_SEAL_FAILED
                detailOmitted -> PendingPartnerEvent.ERROR_PAYLOAD_TOO_LARGE
                sealedSource == null -> PartnerEventRecorder.ERROR_LOCAL_SEAL_FAILED
                else -> null
            }

            when (
                val outcome = outbox.enqueue(
                    ServiceEventOutbox.EnqueueRequest(
                        ownerUid = uid,
                        eventId = eventId,
                        kind = kind,
                        safeMetadataJson = Jcs.canonicalize(safeMetadata),
                        deviceOccurredAtMs = occurredAt,
                        elapsedRealtimeAtCaptureMs = clock.elapsedRealtimeMs(),
                        recipientEnvelopes = envelopes,
                        recipientKeyRevision = null,
                        sealedSource = sealedSource,
                        status = status,
                        lastErrorCode = lastErrorCode,
                        recipientPairId = recipientPairId,
                    ),
                    clock.wallClockMs(),
                )
            ) {
                is ServiceEventOutbox.EnqueueOutcome.Enqueued -> {
                    // The row is durable; a failed kick only delays it to the periodic pass.
                    try {
                        onRecorded()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    RecordResult.Recorded(outcome.eventId, outcome.sequence, status)
                }
                is ServiceEventOutbox.EnqueueOutcome.CapacityExceeded ->
                    RecordResult.CapacityExceeded(
                        outcome.health.pendingRowCount, outcome.health.pendingByteCount,
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RecordResult.Failed
        }
    }

    /**
     * §8.1 `RECIPIENT_KEY_STALE` path: re-encrypt the retained source for the currently
     * authorized recipients, keeping event ID, sequence and occurrence time untouched. The
     * outbox write itself re-checks that the event was never possibly accepted.
     */
    suspend fun regenerateRecipientEnvelopes(
        eventId: String,
        recipientKeyRevision: Int? = null,
        authorizedRecipientKeyIds: Set<String>? = null,
    ): RegenerationResult {
        return try {
            val uid = ownerUid() ?: return RegenerationResult.NoAccount
            val row = outbox.event(eventId) ?: return RegenerationResult.NoSuchEvent
            if (row.ownerUid != uid) return RegenerationResult.NotPermitted
            if (row.status == PendingPartnerEvent.STATUS_ACKED ||
                row.status == PendingPartnerEvent.STATUS_PERMANENT_REJECTED ||
                row.status == PendingPartnerEvent.STATUS_OWNER_AUTH_REQUIRED
            ) {
                return RegenerationResult.NotPermitted
            }
            if (row.wireOutcome != PendingPartnerEvent.WIRE_NOT_SENT &&
                row.wireOutcome != PendingPartnerEvent.WIRE_RECIPIENT_KEY_STALE
            ) {
                return RegenerationResult.NotPermitted
            }
            val kind = PartnerEventKind.fromWire(row.kind) ?: return RegenerationResult.NotPermitted
            val sealed = row.temporaryLocalCiphertext ?: return RegenerationResult.SourceUnavailable
            val source = try {
                // The row's own schema version, not the current one: an app upgrade that bumps
                // the protocol still has to open sources sealed by the older schema.
                protector.open(
                    sourceSealAad(eventId, row.installationId, uid, kind, row.schemaVersion),
                    sealed,
                )
            } catch (_: Exception) {
                return RegenerationResult.SourceUnavailable
            }
            when (
                val encryption = encryptor.encryptForAll(
                    eventId, row.installationId, uid, kind, source,
                    authorizedRecipientKeyIds, recipientPairId = row.recipientPairId,
                )
            ) {
                is Encrypted -> {
                    val previousKeyIds = runCatching {
                        row.recipientEnvelopesBlob?.let { ExistingE2eeRecipientEncryptor.decode(it) }
                    }.getOrNull().orEmpty().map { it.recipientEncryptionKeyId }.toSet()
                    val newKeyIds = encryption.envelopes.map { it.recipientEncryptionKeyId }.toSet()
                    if (newKeyIds == previousKeyIds) {
                        // The server rejected exactly this authorized set, so re-encrypting for
                        // the same recipients can only earn the same rejection: park instead.
                        outbox.applyRegeneratedEnvelopes(eventId, null, recipientKeyRevision)
                        RegenerationResult.RegeneratedUnchanged
                    } else if (
                        outbox.applyRegeneratedEnvelopes(eventId, encryption.envelopes, recipientKeyRevision)
                    ) {
                        RegenerationResult.Regenerated
                    } else {
                        RegenerationResult.NotPermitted
                    }
                }
                RecipientKeyUnavailable -> {
                    outbox.applyRegeneratedEnvelopes(eventId, null, recipientKeyRevision)
                    RegenerationResult.RecipientKeyUnavailable
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RegenerationResult.Failed
        }
    }

    private fun eventOf(
        eventId: String,
        installationId: String,
        ownerUid: String,
        sequence: Long,
        kind: PartnerEventKind,
        occurredAtMs: Long,
        metadata: Map<String, Any>,
        envelopes: List<RecipientEnvelopeV1>,
    ): PartnerEventV1 = PartnerEventV1(
        eventId = eventId,
        installationId = installationId,
        ownerUid = ownerUid,
        sequence = sequence,
        kind = kind,
        deviceOccurredAtMs = occurredAtMs,
        recipientEnvelopes = envelopes,
        metadata = metadata,
    )

    companion object {
        /** §8.1 source-seal AAD: the five identity fields, '|'-free by construction. */
        internal fun sourceSealAad(
            eventId: String,
            installationId: String,
            ownerUid: String,
            kind: PartnerEventKind,
            schemaVersion: Int = PartnerEventProtocol.VERSION,
        ): ByteArray = (
            "partner_event_source|$eventId|$installationId|$ownerUid|" +
                "${kind.wire}|$schemaVersion"
            ).toByteArray(Charsets.UTF_8)

        const val ERROR_LOCAL_SEAL_FAILED = "LOCAL_SEAL_FAILED"

        private const val PROBE_SEQUENCE = 9_007_199_254_740_991L
    }
}
