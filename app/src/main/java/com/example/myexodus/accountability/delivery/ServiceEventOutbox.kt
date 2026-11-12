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
        val recipientPairId: String? = null,
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
     * §6.3: the contact's strictly increasing counter, allocated and persisted in one
     * transaction so two workers racing cannot both sign the same value. Null when there is no
     * installation or the counter is exhausted — the caller then skips, never reuses.
     */
    suspend fun allocateHeartbeatCounter(installationId: String): Long? = db.withWriteTransaction {
        val current = dao.heartbeatCounter(installationId) ?: return@withWriteTransaction null
        if (current >= DeviceContactProtocol.MAX_COUNTER) return@withWriteTransaction null
        val next = current + 1
        dao.setHeartbeatCounter(installationId, next)
        next
    }

    /** A STALE reply means the server already holds a higher counter; adopt it so the next
     *  contact is above anything the server has seen. Never lowers the local value. */
    suspend fun alignHeartbeatCounter(installationId: String, serverCounter: Long) {
        if (serverCounter <= 0 || serverCounter > DeviceContactProtocol.MAX_COUNTER) return
        db.withWriteTransaction {
            val current = dao.heartbeatCounter(installationId)
            if (current != null && serverCounter > current) {
                dao.setHeartbeatCounter(installationId, serverCounter)
            }
        }
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
                    recipientPairId = request.recipientPairId,
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

    suspend fun installation(ownerUid: String): InstallationState? = dao.installationForOwner(ownerUid)

    suspend fun installationById(installationId: String): InstallationState? = dao.installation(installationId)

    suspend fun markRegistered(
        installationId: String,
        keyId: String,
        recipientKeyId: String,
    ): Boolean =
        dao.updateRegistration(
            installationId, keyId, recipientKeyId, InstallationState.REGISTRATION_ACTIVE,
        ) > 0

    /** §8.3.8: suspend the whole installation and keep every row for re-signing. */
    suspend fun markReenrollRequired(installationId: String): Int = db.withWriteTransaction {
        dao.updateRegistrationState(
            installationId, InstallationState.REGISTRATION_REENROLL_REQUIRED,
        )
        dao.suspendForReenroll(installationId)
    }

    /**
     * §4.3 lease claim. Expired leases are reclaimed and each due row is taken by a guarded
     * update in the same write transaction, so two drains can never carry the same row.
     */
    suspend fun claimDue(
        ownerUid: String,
        nowMs: Long,
        limit: Int,
        leaseUntilMs: Long,
    ): List<PendingPartnerEvent> = db.withWriteTransaction {
        dao.resetExpiredLeases(nowMs)
        dao.dueRows(ownerUid, nowMs, limit).filter { dao.leaseRow(it.eventId, leaseUntilMs) > 0 }
    }

    suspend fun releaseLeases(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        db.withWriteTransaction { eventIds.forEach { dao.releaseLease(it) } }
    }

    suspend fun resetAllLeases(): Int = dao.resetAllLeases()

    suspend fun waitingRows(ownerUid: String, nowMs: Long, limit: Int): List<PendingPartnerEvent> =
        dao.waitingRows(ownerUid, nowMs, limit)

    suspend fun deferWaiting(eventId: String, nextAttemptAtMs: Long): Boolean =
        dao.deferWaitingRow(eventId, nextAttemptAtMs) > 0

    suspend fun recordSignature(
        eventId: String,
        signedEnvelope: ByteArray,
        signature: ByteArray,
        keyId: String,
    ): Boolean = dao.recordSignature(eventId, signedEnvelope, signature, keyId) > 0

    suspend fun scheduleRetry(
        eventId: String,
        wireOutcome: String,
        nextAttemptAtMs: Long,
        errorCode: String?,
        wireDigest: String?,
    ): Boolean =
        dao.retryRow(eventId, wireOutcome, nextAttemptAtMs, errorCode, wireDigest) > 0

    /** Terminal: erases the retained source in the same write; the shell expires separately. */
    suspend fun permanentReject(eventId: String, errorCode: String?, terminalAtMs: Long): Boolean =
        dao.permanentRejectRow(eventId, errorCode, terminalAtMs) > 0

    /** §8.3.8 after a successful re-enrollment: rebuild signatures, keep event identity. */
    suspend fun resumeAfterReenroll(installationId: String): Int = dao.resumeAfterReenroll(installationId)

    suspend fun earliestNextAttempt(ownerUid: String): Long? = dao.earliestNextAttempt(ownerUid)

    /** True only for the false→true transition; the caller may then append one work request. */
    suspend fun requestWake(): Boolean = db.withWriteTransaction {
        dao.ensureWakeRow(ServiceOutboxWakeState(wakeRequested = false))
        dao.armWake() > 0
    }

    suspend fun clearWake(): Int = dao.clearWake()

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

    /** §8.1: a terminal rejection keeps only its diagnostic shell, and only for a week. */
    suspend fun cleanupRejected(nowMs: Long): Int =
        dao.deleteRejectedBefore(nowMs - PendingPartnerEvent.REJECTED_RETENTION_MILLIS, limits.cleanupBatchSize)

    suspend fun health(): ServiceOutboxHealth? = dao.health()
}
