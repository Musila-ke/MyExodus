package com.example.myexodus.accountability.delivery

import androidx.room3.withWriteTransaction
import com.example.myexodus.accountability.state.AccountabilityDatabase
import java.util.UUID

/** §8.1 queue bound; injectable so the capacity path is testable without 10,000 rows. */
data class ServiceOutboxLimits(
    val maxUnacknowledgedRows: Int = 10_000,
    val maxUnacknowledgedBytes: Long = 50L * 1024 * 1024,
    val cleanupBatchSize: Int = 200,
)

/**
 * Partner delivery plan §8.1 owner-bound service outbox repository. Holds only queue state:
 * sequence allocation, the insert transaction, the capacity incident, acknowledgement erasure,
 * account-change suspension and bounded retention cleanup. Crypto lives in
 * [PartnerEventRecorder]; Phase 4's signer reads rows through [dueRows] and writes results
 * through [acknowledge].
 */
class ServiceEventOutbox(
    private val db: AccountabilityDatabase,
    private val limits: ServiceOutboxLimits = ServiceOutboxLimits(),
    private val newInstallationId: () -> String = { UUID.randomUUID().toString() },
) {

    private val dao: ServiceEventOutboxDao get() = db.serviceEventOutboxDao()

    data class EnqueueRequest(
        val ownerUid: String,
        val eventId: String,
        val kind: PartnerEventKind,
        val safeMetadataJson: String,
        val deviceOccurredAtMs: Long,
        val elapsedRealtimeAtCaptureMs: Long?,
        val recipientEnvelopes: List<RecipientEnvelopeV1>?,
        val recipientKeyRevision: Int?,
        val sealedSource: ByteArray?,
        val status: String,
        val lastErrorCode: String?,
    )

    sealed interface EnqueueOutcome {
        data class Enqueued(val eventId: String, val installationId: String, val sequence: Long) : EnqueueOutcome
        data class CapacityExceeded(val health: ServiceOutboxHealth) : EnqueueOutcome
    }

    /** One installation per account; a second account never reuses or overwrites the first's. */
    suspend fun ensureInstallation(ownerUid: String): InstallationState =
        dao.installationForOwner(ownerUid) ?: db.withWriteTransaction {
            dao.installationForOwner(ownerUid) ?: InstallationState(
                installationId = newInstallationId(),
                ownerUid = ownerUid,
                currentSequence = 0,
            ).also { dao.insertInstallation(it) }
        }

    /**
     * §8.1: sequence allocation and the row insert are one transaction — the event exists only
     * after it commits. Capacity is checked in the same transaction, and hitting it records a
     * persistent incident instead of silently overwriting an unacknowledged row.
     */
    suspend fun enqueue(request: EnqueueRequest, nowMs: Long): EnqueueOutcome =
        db.withWriteTransaction {
            val installation = dao.installationForOwner(request.ownerUid)
                ?: InstallationState(
                    installationId = newInstallationId(),
                    ownerUid = request.ownerUid,
                    currentSequence = 0,
                ).also { dao.insertInstallation(it) }
            val envelopesBlob = request.recipientEnvelopes?.let(ExistingE2eeRecipientEncryptor::encode)
            val rowBytes = (request.sealedSource?.size ?: 0).toLong() + (envelopesBlob?.size ?: 0).toLong()

            val rowCount = dao.unacknowledgedRowCount()
            val byteCount = dao.unacknowledgedByteCount()
            if (rowCount + 1 > limits.maxUnacknowledgedRows ||
                byteCount + rowBytes > limits.maxUnacknowledgedBytes
            ) {
                val health = ServiceOutboxHealth(
                    incidentCode = ServiceOutboxHealth.QUEUE_CAPACITY_EXCEEDED,
                    raisedAtMs = nowMs,
                    pendingRowCount = rowCount,
                    pendingByteCount = byteCount,
                )
                dao.upsertHealth(health)
                return@withWriteTransaction EnqueueOutcome.CapacityExceeded(health)
            }
            if (dao.health() != null) dao.clearHealth()

            dao.bumpSequence(installation.installationId)
            val sequence = dao.currentSequence(installation.installationId)
                ?: error("installation vanished inside its own transaction")
            dao.insertEvent(
                PendingPartnerEvent(
                    eventId = request.eventId,
                    installationId = installation.installationId,
                    ownerUid = request.ownerUid,
                    sequence = sequence,
                    schemaVersion = PartnerEventProtocol.VERSION,
                    kind = request.kind.wire,
                    safeMetadataJson = request.safeMetadataJson,
                    recipientEnvelopesBlob = envelopesBlob,
                    temporaryLocalCiphertext = request.sealedSource,
                    deviceOccurredAtMs = request.deviceOccurredAtMs,
                    elapsedRealtimeAtCaptureMs = request.elapsedRealtimeAtCaptureMs,
                    status = request.status,
                    recipientKeyRevision = request.recipientKeyRevision,
                    lastErrorCode = request.lastErrorCode,
                    createdLocalAtMs = nowMs,
                ),
            )
            EnqueueOutcome.Enqueued(request.eventId, installation.installationId, sequence)
        }

    /** §8.1 account change: another account's rows are suspended before any submission path
     *  can see them; the active account's own suspended rows become due again. */
    suspend fun applyActiveOwner(activeOwnerUid: String): Int = db.withWriteTransaction {
        val suspended = dao.suspendOtherOwners(activeOwnerUid)
        dao.resumeOwner(activeOwnerUid)
        suspended
    }

    /** Phase 4 read contract: due rows in stable `(sequence, eventId)` order. */
    suspend fun dueRows(ownerUid: String, nowMs: Long, limit: Int): List<PendingPartnerEvent> =
        dao.dueRows(ownerUid, nowMs, limit)

    suspend fun event(eventId: String): PendingPartnerEvent? = dao.event(eventId)

    /**
     * §6.1 step 8: only ACCEPTED / ALREADY_ACCEPTED reach here, and only they erase the
     * retained source. Every other outcome keeps the row resendable.
     */
    suspend fun acknowledge(eventId: String, code: PartnerEventResultCode, ackedAtMs: Long): Boolean {
        val outcome = when (code) {
            PartnerEventResultCode.ACCEPTED -> PendingPartnerEvent.WIRE_ACCEPTED
            PartnerEventResultCode.ALREADY_ACCEPTED -> PendingPartnerEvent.WIRE_ALREADY_ACCEPTED
            else -> return false
        }
        return dao.markAcked(eventId, outcome, ackedAtMs) > 0
    }

    /** Guarded §8.1 regeneration write; returns false when the possibility of acceptance
     *  closed the window between the caller's read and this update. */
    suspend fun applyRegeneratedEnvelopes(
        eventId: String,
        envelopes: List<RecipientEnvelopeV1>?,
        recipientKeyRevision: Int?,
    ): Boolean =
        dao.replaceRecipientEnvelopes(
            eventId,
            envelopes?.let(ExistingE2eeRecipientEncryptor::encode),
            recipientKeyRevision,
        ) > 0

    /** §8.1: ACKED rows lose their content at acknowledgement and the empty row after seven
     *  days. Bounded per call. */
    suspend fun cleanupAcked(nowMs: Long): Int =
        dao.deleteAckedBefore(nowMs - PendingPartnerEvent.ACKED_RETENTION_MILLIS, limits.cleanupBatchSize)

    suspend fun health(): ServiceOutboxHealth? = dao.health()
}
