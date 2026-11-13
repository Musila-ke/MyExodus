package com.example.myexodus.accountability.delivery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.random.Random

/** §8.4 whole-request rate limiting. Firebase callables expose no `Retry-After`, so the drain
 *  uses a longer fixed defer instead — the one translation the transport performs. */
class DeliveryRateLimitedException(cause: Throwable) : Exception(cause)

/**
 * §4.3/§8.4/§9 drain: claim → sign → submit → reconcile, with leases, per-index reconciliation,
 * exponential backoff and the three key-invalidation states. Never mutates ciphertext or the
 * signature for anything except a definitive `RECIPIENT_KEY_STALE` — a thrown or ambiguous
 * outcome keeps the original signed bytes (§8.2 immutable after possible acceptance).
 *
 * Every method is suspend and the instance is process-wide; the Mutex makes concurrent triggers
 * (worker, foreground, capture kick) line up behind one another instead of double-submitting.
 */
class PartnerEventDrain(
    private val outbox: ServiceEventOutbox,
    private val keyStore: PartnerEventKeyStore,
    private val transport: PartnerDeliveryTransport,
    private val registrar: PartnerEventRegistrar,
    private val recorder: PartnerEventRecorder,
    private val clock: PartnerDeliveryClock,
    private val ownerUid: () -> String?,
    private val limits: DrainLimits = DrainLimits(),
    /** Jitter over the exponential delay; overridden in tests to keep timings exact. */
    private val jitterMs: (Long) -> Long = { delay ->
        Random.nextLong(0, (delay / 4).coerceAtLeast(1) + 1)
    },
    /** §14 recipient-key stale re-encryption count: one call per successful regeneration. */
    private val onRecipientKeyRegenerated: () -> Unit = {},
    /** The agreement-key id the newest enrollment would register right now; null = not
     *  readable (never paired, or a transient failure) and disables the freshness check. */
    private val currentRecipientKeyId: suspend () -> String? = { null },
) {

    data class DrainLimits(
        val batchSize: Int = PartnerEventProtocol.MAX_BATCH_EVENTS,
        val maxRounds: Int = 6,
        /** §4.3 lease bound; a killed process reclaims through `claimDue`'s expiry reset. */
        val leaseMs: Long = 120_000,
        val maxBatchBytes: Int = PartnerEventProtocol.MAX_BATCH_BYTES,
        val baseBackoffMs: Long = 30_000,
        val maxBackoffMs: Long = 1_800_000,
        /** `WAITING_FOR_RECIPIENT_KEY` re-probe cadence (no recipient key yet). */
        val waitingBackoffMs: Long = 900_000,
        /** Retry floor after a stale-key rejection whose regeneration itself failed. */
        val regenerationDeferMs: Long = 10_000,
        /** No `Retry-After` on callables: a fixed long defer for RESOURCE_EXHAUSTED. */
        val rateLimitedDeferMs: Long = 300_000,
    )

    data class Result(
        val claimed: Int = 0,
        val accepted: Int = 0,
        val retried: Int = 0,
        val rejected: Int = 0,
        val registrationRequired: Boolean = false,
        val earliestNextAttemptMs: Long? = null,
    )

    private val mutex = Mutex()

    /**
     * [allowRegistration] is the §8.3.1 foreground gate: enrollment/rotation signs a fresh
     * challenge and must run from a foreground drain, never from the background worker.
     */
    suspend fun run(allowRegistration: Boolean): Result = mutex.withLock {
        val uid = ownerUid() ?: return@withLock Result()
        outbox.applyActiveOwner(uid)
        // §4.3 retention: ACKED rows keep only their empty shell for seven days. Cleanup
        // never blocks delivery.
        runCatching {
            val now = clock.wallClockMs()
            outbox.cleanupAcked(now)
            outbox.cleanupRejected(now)
        }
        val installation = outbox.ensureInstallation(uid)
        var signingKeyId = runCatching {
            if (keyStore.hasKey(installation.installationId)) {
                keyStore.keyId(installation.installationId)
            } else {
                null
            }
        }.getOrNull()

        val signingHealthy = installation.registrationState == InstallationState.REGISTRATION_ACTIVE &&
            signingKeyId != null &&
            installation.activeKeyId == signingKeyId
        if (!signingHealthy) {
            if (signingKeyId == null || installation.activeKeyId != signingKeyId) {
                // §8.3.8: a missing or mismatched signing key suspends the installation and
                // keeps every row for re-signing; it is never dropped or re-encrypted here.
                outbox.markReenrollRequired(installation.installationId)
            }
            if (!allowRegistration) {
                return@withLock Result(
                    registrationRequired = true,
                    earliestNextAttemptMs = outbox.earliestNextAttempt(uid),
                )
            }
            val outcome = registrar.register(installation.installationId)
            if (outcome !is PartnerEventRegistrar.Outcome.Registered) {
                return@withLock Result(
                    registrationRequired = true,
                    earliestNextAttemptMs = outbox.earliestNextAttempt(uid),
                )
            }
            outbox.resumeAfterReenroll(installation.installationId)
            signingKeyId = runCatching {
                keyStore.keyId(installation.installationId)
            }.getOrNull()
                ?: return@withLock Result(
                    registrationRequired = true,
                    earliestNextAttemptMs = outbox.earliestNextAttempt(uid),
                )
        } else if (allowRegistration) {
            // A successful pairing mints a new pair-scoped agreement key while the signing key
            // and registration state still look healthy, so the server keeps advertising the
            // previous enrollment's recipient key. A partner holding the key from the new
            // pairing then encrypts to an id the server does not authorize, and its stale
            // recovery cannot regenerate (its only local record is the new key). Refresh the
            // registration; best-effort, because outbound rows do not depend on it — only a
            // stale server view of what partners may encrypt for this device does.
            val enrollmentRecipientKeyId = runCatching { currentRecipientKeyId() }.getOrNull()
            if (enrollmentRecipientKeyId != null &&
                installation.recipientKeyId != enrollmentRecipientKeyId) {
                val outcome = registrar.register(installation.installationId)
                val refreshedKeyId = runCatching {
                    keyStore.keyId(installation.installationId)
                }.getOrNull()
                if (outcome is PartnerEventRegistrar.Outcome.Registered && refreshedKeyId != null) {
                    signingKeyId = refreshedKeyId
                } else if (refreshedKeyId == null || refreshedKeyId != installation.activeKeyId) {
                    // A key minted mid-registration that the server never accepted cannot sign
                    // anything it would accept — suspend for a full re-enrollment instead.
                    outbox.markReenrollRequired(installation.installationId)
                    return@withLock Result(
                        registrationRequired = true,
                        earliestNextAttemptMs = outbox.earliestNextAttempt(uid),
                    )
                }
            }
        }

        // Every path above either proves the key present or returns; this is the compiler's
        // view of that guarantee.
        val keyId = signingKeyId ?: return@withLock Result(
            registrationRequired = true,
            earliestNextAttemptMs = outbox.earliestNextAttempt(uid),
        )

        regenerateWaiting(uid)

        var claimed = 0
        var accepted = 0
        var retried = 0
        var rejected = 0
        var registrationRequired = false
        var stop = false

        for (round in 0 until limits.maxRounds) {
            if (stop) break
            val nowMs = clock.wallClockMs()
            val rows = outbox.claimDue(uid, nowMs, limits.batchSize, nowMs + limits.leaseMs)
            if (rows.isEmpty()) break
            claimed += rows.size

            val batch = mutableListOf<Prepared.Ready>()
            val handled = HashSet<String>(rows.size)
            var batchBytes = 0
            var capped = false
            for (row in rows) {
                if (capped) break
                when (val prepared = prepare(row, keyId)) {
                    is Prepared.Rejected -> {
                        outbox.permanentReject(prepared.eventId, prepared.code, clock.wallClockMs())
                        rejected++
                        handled += prepared.eventId
                    }
                    Prepared.Reenroll -> {
                        outbox.markReenrollRequired(installation.installationId)
                        registrationRequired = true
                        stop = true
                        capped = true
                    }
                    is Prepared.Ready -> {
                        if (batch.isNotEmpty() &&
                            batchBytes + prepared.byteSize > limits.maxBatchBytes
                        ) {
                            capped = true
                        } else {
                            batch += prepared
                            batchBytes += prepared.byteSize
                            handled += prepared.eventId
                        }
                    }
                }
            }
            outbox.releaseLeases(rows.map { it.eventId }.filterNot { it in handled })
            if (stop) break
            if (batch.isEmpty()) continue

            val batchResult: PartnerEventBatchResultV1
            try {
                batchResult = transport.submit(
                    installation.installationId,
                    PartnerEventBatchV1(batch.map { it.wire }),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // §8.2: a whole-request failure may have committed server-side, so every row
                // keeps its exact signed bytes and is retried unchanged.
                val atMs = clock.wallClockMs()
                val rateLimited = e is DeliveryRateLimitedException
                batch.forEach { prepared ->
                    outbox.scheduleRetry(
                        prepared.eventId,
                        PendingPartnerEvent.WIRE_UNKNOWN,
                        atMs + if (rateLimited) {
                            limits.rateLimitedDeferMs
                        } else {
                            backoffMs(prepared.attempts + 1)
                        },
                        if (rateLimited) "RESOURCE_EXHAUSTED" else "TRANSPORT_UNKNOWN",
                        runCatching { PartnerEventProtocol.digest(prepared.event) }.getOrNull(),
                    )
                    retried++
                }
                stop = true
                continue
            }

            val byIndex = batchResult.results.associateBy { it.index }
            val atMs = clock.wallClockMs()
            for ((index, prepared) in batch.withIndex()) {
                val rowResult = byIndex[index]
                when (rowResult?.code) {
                    PartnerEventResultCode.ACCEPTED,
                    PartnerEventResultCode.ALREADY_ACCEPTED,
                    -> if (outbox.acknowledge(prepared.eventId, rowResult.code, atMs)) accepted++

                    PartnerEventResultCode.RECIPIENT_KEY_STALE -> {
                        // §8.1: a definitive pre-effect rejection re-encrypts from the retained
                        // sealed source for the server's current authorized key set only.
                        val revision = rowResult.currentRecipientKeys
                            .mapNotNull { it.revision }.maxOrNull()
                        // Empty is passed through as an empty set, never `null`: `null` means
                        // "every local enrollment", which would re-encrypt for revoked keys.
                        val authorized = rowResult.currentRecipientKeys.map { it.keyId }.toSet()
                        outbox.scheduleRetry(
                            prepared.eventId,
                            PendingPartnerEvent.WIRE_RECIPIENT_KEY_STALE,
                            atMs + limits.regenerationDeferMs,
                            "RECIPIENT_KEY_STALE",
                            runCatching { PartnerEventProtocol.digest(prepared.event) }.getOrNull(),
                        )
                        when (
                            recorder.regenerateRecipientEnvelopes(
                                prepared.eventId, revision, authorized,
                            )
                        ) {
                            PartnerEventRecorder.RegenerationResult.Regenerated ->
                                onRecipientKeyRegenerated()
                            // No local action can produce a different authorized set, so the
                            // slow waiting backoff replaces a resend that would earn the same
                            // rejection again.
                            PartnerEventRecorder.RegenerationResult.RegeneratedUnchanged,
                            PartnerEventRecorder.RegenerationResult.RecipientKeyUnavailable,
                            PartnerEventRecorder.RegenerationResult.SourceUnavailable,
                            PartnerEventRecorder.RegenerationResult.Failed,
                            -> {
                                outbox.applyRegeneratedEnvelopes(prepared.eventId, null, revision)
                                outbox.deferWaiting(
                                    prepared.eventId,
                                    clock.wallClockMs() + limits.waitingBackoffMs,
                                )
                            }
                            else -> Unit
                        }
                        retried++
                    }

                    PartnerEventResultCode.RETRYABLE -> {
                        outbox.scheduleRetry(
                            prepared.eventId,
                            PendingPartnerEvent.WIRE_RETRYABLE,
                            atMs + backoffMs(prepared.attempts + 1),
                            "RETRYABLE",
                            runCatching { PartnerEventProtocol.digest(prepared.event) }.getOrNull(),
                        )
                        retried++
                    }

                    PartnerEventResultCode.REENROLL_REQUIRED -> {
                        outbox.markReenrollRequired(installation.installationId)
                        registrationRequired = true
                        stop = true
                        break
                    }

                    PartnerEventResultCode.PERMANENT_REJECTED -> {
                        outbox.permanentReject(
                            prepared.eventId, rowResult.issue ?: rowResult.reason, atMs,
                        )
                        rejected++
                    }

                    // No result for this index: the outcome is unknown, never an acceptance
                    // and never a regeneration permit.
                    null -> {
                        outbox.scheduleRetry(
                            prepared.eventId,
                            PendingPartnerEvent.WIRE_UNKNOWN,
                            atMs + backoffMs(prepared.attempts + 1),
                            "RESULT_MISSING",
                            runCatching { PartnerEventProtocol.digest(prepared.event) }.getOrNull(),
                        )
                        retried++
                    }
                }
            }
            if (stop) break
            if (rows.size < limits.batchSize) break
        }

        Result(
            claimed = claimed,
            accepted = accepted,
            retried = retried,
            rejected = rejected,
            registrationRequired = registrationRequired,
            earliestNextAttemptMs = outbox.earliestNextAttempt(uid),
        )
    }

    /** Waiting rows re-probe once per drain; a still-missing recipient key defers, no hot loop. */
    private suspend fun regenerateWaiting(uid: String) {
        val waiting = outbox.waitingRows(uid, clock.wallClockMs(), limits.batchSize)
        waiting.forEach { row ->
            when (recorder.regenerateRecipientEnvelopes(row.eventId)) {
                PartnerEventRecorder.RegenerationResult.Regenerated ->
                    onRecipientKeyRegenerated()
                PartnerEventRecorder.RegenerationResult.RegeneratedUnchanged,
                PartnerEventRecorder.RegenerationResult.RecipientKeyUnavailable,
                PartnerEventRecorder.RegenerationResult.Failed,
                PartnerEventRecorder.RegenerationResult.SourceUnavailable,
                -> outbox.deferWaiting(row.eventId, clock.wallClockMs() + limits.waitingBackoffMs)
                else -> Unit
            }
        }
    }

    private sealed interface Prepared {
        data class Ready(
            val eventId: String,
            val event: PartnerEventV1,
            val wire: PartnerEventWireV1,
            val byteSize: Int,
            val attempts: Int,
        ) : Prepared

        data class Rejected(val eventId: String, val code: String) : Prepared

        /** The signing key cannot sign right now: suspend the installation, keep every row. */
        data object Reenroll : Prepared
    }

    private suspend fun prepare(row: PendingPartnerEvent, keyId: String): Prepared {
        val kind = PartnerEventKind.fromWire(row.kind)
            ?: return Prepared.Rejected(row.eventId, PartnerEventIssue.KIND_UNSUPPORTED.wire)
        val envelopes = row.recipientEnvelopesBlob
            ?.let { runCatching { ExistingE2eeRecipientEncryptor.decode(it) }.getOrNull() }
            ?: return Prepared.Rejected(row.eventId, ROW_UNREADABLE)
        val metadata = parseMetadata(row.safeMetadataJson)
            ?: return Prepared.Rejected(row.eventId, PartnerEventIssue.METADATA_INVALID.wire)
        // The recorded schema version, not the current constant: an event recorded under an
        // older schema must not be re-canonicalized under a newer one.
        val event = PartnerEventV1(
            eventId = row.eventId,
            installationId = row.installationId,
            ownerUid = row.ownerUid,
            sequence = row.sequence,
            kind = kind,
            deviceOccurredAtMs = row.deviceOccurredAtMs,
            recipientEnvelopes = envelopes,
            metadata = metadata,
            v = row.schemaVersion,
        )
        PartnerEventProtocol.validate(event)?.let {
            return Prepared.Rejected(row.eventId, it.wire)
        }
        val canonical = try {
            PartnerEventProtocol.canonicalBytes(event)
        } catch (_: Exception) {
            return Prepared.Rejected(row.eventId, PartnerEventIssue.METADATA_INVALID.wire)
        }
        val signature = try {
            val reusable = row.signingKeyId == keyId &&
                row.signingSignature != null &&
                row.signedEnvelope?.contentEquals(canonical) == true
            if (reusable) {
                row.signingSignature!!
            } else {
                keyStore.sign(row.installationId, canonical).also { fresh ->
                    outbox.recordSignature(row.eventId, canonical, fresh, keyId)
                }
            }
        } catch (_: Exception) {
            return Prepared.Reenroll
        }
        return Prepared.Ready(
            eventId = row.eventId,
            event = event,
            wire = PartnerEventWireV1(
                keyId = keyId,
                event = event,
                signature = PartnerEventProtocol.base64Url(signature),
            ),
            byteSize = canonical.size,
            attempts = row.attempts,
        )
    }

    /** Closed allowlist values are strings; anything else is an unreadable row. */
    private fun parseMetadata(json: String): Map<String, Any>? {
        return try {
            val objectJson = JSONObject(json)
            val values = LinkedHashMap<String, Any>()
            val keys = objectJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = objectJson.get(key)
                if (value !is String) return null
                values[key] = value
            }
            values
        } catch (_: Exception) {
            null
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 20)
        val capped = (limits.baseBackoffMs shl exponent).coerceAtMost(limits.maxBackoffMs)
        return (capped + jitterMs(capped)).coerceAtMost(limits.maxBackoffMs)
    }

    private companion object {
        const val ROW_UNREADABLE = "ROW_UNREADABLE"
    }
}
