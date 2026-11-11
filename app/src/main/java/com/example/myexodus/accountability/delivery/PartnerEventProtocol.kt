package com.example.myexodus.accountability.delivery

import com.example.myexodus.accountability.serialization.AccountabilityEventCategory
import com.example.myexodus.accountability.serialization.RuleChange
import java.security.MessageDigest
import java.util.Base64

/** §8.2 kind allowlist. Server-generated kinds never travel on this wire. */
enum class PartnerEventKind(val wire: String) {
    BLOCK_ATTEMPT("BLOCK_ATTEMPT"),
    CHECK_IN("CHECK_IN"),
    RULE_CHANGE_ATTEMPT("RULE_CHANGE_ATTEMPT"),
    ;

    companion object {
        fun fromWire(value: String): PartnerEventKind? = entries.firstOrNull { it.wire == value }
    }
}

/** §8.4 per-event callable response codes. */
enum class PartnerEventResultCode(val wire: String) {
    ACCEPTED("ACCEPTED"),
    ALREADY_ACCEPTED("ALREADY_ACCEPTED"),
    RECIPIENT_KEY_STALE("RECIPIENT_KEY_STALE"),
    RETRYABLE("RETRYABLE"),
    REENROLL_REQUIRED("REENROLL_REQUIRED"),
    PERMANENT_REJECTED("PERMANENT_REJECTED"),
    ;

    companion object {
        fun fromWire(value: String): PartnerEventResultCode? = entries.firstOrNull { it.wire == value }

        /** Only these two outcomes may destroy the locally wrapped source content. */
        fun isAcknowledged(code: PartnerEventResultCode): Boolean =
            code == ACCEPTED || code == ALREADY_ACCEPTED

        /**
         * I11/§8.1: recipient ciphertext and the signature may be regenerated only after a
         * definitive pre-effect rejection. Never for RETRYABLE or any unknown outcome — the
         * event may already have been accepted.
         */
        fun allowsCiphertextRegeneration(code: PartnerEventResultCode): Boolean =
            code == RECIPIENT_KEY_STALE
    }
}

/** Server and client validate the same envelope rules; strings are the cross-language contract. */
enum class PartnerEventIssue(val wire: String) {
    VERSION_UNSUPPORTED("VERSION_UNSUPPORTED"),
    EVENT_ID_NOT_UUID_V4("EVENT_ID_NOT_UUID_V4"),
    INSTALLATION_ID_NOT_UUID_V4("INSTALLATION_ID_NOT_UUID_V4"),
    OWNER_UID_INVALID("OWNER_UID_INVALID"),
    SEQUENCE_INVALID("SEQUENCE_INVALID"),
    KIND_UNSUPPORTED("KIND_UNSUPPORTED"),
    OCCURRED_AT_INVALID("OCCURRED_AT_INVALID"),
    METADATA_INVALID("METADATA_INVALID"),
    RECIPIENTS_INVALID("RECIPIENTS_INVALID"),
    SUBMISSION_INVALID("SUBMISSION_INVALID"),
    TOO_LARGE("TOO_LARGE"),
}

/** One E2EE recipient entry. `recipientEncryptionKeyId` is a registered partner key ID. */
data class RecipientEnvelopeV1(
    val recipientEncryptionKeyId: String,
    val version: Int,
    val encoding: String,
    val payload: String,
)

/** §8.2 version-1 envelope: the exact bytes JCS-canonicalized and signed. */
data class PartnerEventV1(
    val eventId: String,
    val installationId: String,
    val ownerUid: String,
    val sequence: Long,
    val kind: PartnerEventKind,
    val deviceOccurredAtMs: Long,
    val recipientEnvelopes: List<RecipientEnvelopeV1>,
    val metadata: Map<String, Any> = emptyMap(),
    val v: Int = PartnerEventProtocol.VERSION,
) {
    fun toJcsValue(): Map<String, Any?> = linkedMapOf(
        "v" to v,
        "eventId" to eventId,
        "installationId" to installationId,
        "ownerUid" to ownerUid,
        "sequence" to sequence,
        "kind" to kind.wire,
        "deviceOccurredAtMs" to deviceOccurredAtMs,
        "metadata" to metadata.toSortedMap(),
        "recipientEnvelopes" to recipientEnvelopes.map { it.toJcsValue() },
    )
}

fun RecipientEnvelopeV1.toJcsValue(): Map<String, Any?> = linkedMapOf(
    "recipientEncryptionKeyId" to recipientEncryptionKeyId,
    "version" to version,
    "encoding" to encoding,
    "payload" to payload,
)

/** §8.2 wire submission to `ingestPartnerEvents`. */
data class PartnerEventWireV1(
    val keyId: String,
    val event: PartnerEventV1,
    val signature: String,
)

data class PartnerEventResultV1(
    val eventId: String,
    val code: PartnerEventResultCode,
    /** Populated by RECIPIENT_KEY_STALE: the currently authorized key IDs. */
    val currentRecipientKeyIds: List<String> = emptyList(),
    val currentKeyRevision: Int? = null,
)

data class PartnerEventBatchV1(val events: List<PartnerEventWireV1>) {
    init {
        require(events.isNotEmpty() && events.size <= PartnerEventProtocol.MAX_BATCH_EVENTS) {
            "batch must carry 1..${PartnerEventProtocol.MAX_BATCH_EVENTS} events"
        }
    }
}

data class PartnerEventBatchResultV1(val results: List<PartnerEventResultV1>)

object PartnerEventProtocol {
    const val VERSION = 1
    const val ENCODING_EXISTING_E2EE = "existing-e2ee"
    const val MAX_EVENT_BYTES = 16 * 1024
    const val MAX_BATCH_EVENTS = 20
    const val MAX_BATCH_BYTES = 256 * 1024

    private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val FIREBASE_UID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")

    /** base64url(SHA-256(SPKI DER)) is exactly 43 characters, unpadded. */
    private val KEY_ID = Regex("^[A-Za-z0-9_-]{43}$")

    /**
     * Closed safe-metadata allowlist. A value must be one of the shipped closed-set wire
     * names; free text, URLs, notes and identifiers are structurally impossible.
     */
    val METADATA_VALUES: Map<String, Set<String>> = mapOf(
        "category" to setOf(
            AccountabilityEventCategory.BLOCKED_APP_ATTEMPT.wire,
            AccountabilityEventCategory.BLOCKED_DOMAIN_ATTEMPT.wire,
        ),
        "ruleChange" to RuleChange.entries.map { it.wire }.toSet(),
    )

    fun canonicalBytes(event: PartnerEventV1): ByteArray =
        Jcs.canonicalize(event.toJcsValue()).toByteArray(Charsets.UTF_8)

    fun digest(event: PartnerEventV1): String =
        MessageDigest.getInstance("SHA-256").digest(canonicalBytes(event))
            .joinToString("") { "%02x".format(it) }

    /** Null = valid. Order matters only for diagnostics; every rule is mandatory. */
    fun validate(event: PartnerEventV1): PartnerEventIssue? {
        if (event.v != VERSION) return PartnerEventIssue.VERSION_UNSUPPORTED
        if (!UUID_V4.matches(event.eventId)) return PartnerEventIssue.EVENT_ID_NOT_UUID_V4
        if (!UUID_V4.matches(event.installationId)) return PartnerEventIssue.INSTALLATION_ID_NOT_UUID_V4
        if (!FIREBASE_UID.matches(event.ownerUid)) return PartnerEventIssue.OWNER_UID_INVALID
        if (event.sequence < 1 || event.sequence > MAX_SAFE_INTEGER) return PartnerEventIssue.SEQUENCE_INVALID
        if (event.deviceOccurredAtMs < 1 || event.deviceOccurredAtMs > MAX_SAFE_INTEGER) {
            return PartnerEventIssue.OCCURRED_AT_INVALID
        }
        metadataIssue(event.kind, event.metadata)?.let { return it }
        recipientIssue(event.recipientEnvelopes)?.let { return it }
        val size = try {
            canonicalBytes(event).size
        } catch (_: IllegalArgumentException) {
            return PartnerEventIssue.METADATA_INVALID
        }
        if (size > MAX_EVENT_BYTES) return PartnerEventIssue.TOO_LARGE
        return null
    }

    fun validateSubmission(wire: PartnerEventWireV1): PartnerEventIssue? {
        if (!KEY_ID.matches(wire.keyId)) return PartnerEventIssue.SUBMISSION_INVALID
        if (!BASE64URL.matches(wire.signature)) return PartnerEventIssue.SUBMISSION_INVALID
        return validate(wire.event)
    }

    /** Batch limits from §8.2: 20 events and a 256 KiB encoded body. */
    fun validateBatch(events: List<PartnerEventWireV1>): PartnerEventIssue? {
        if (events.isEmpty() || events.size > MAX_BATCH_EVENTS) return PartnerEventIssue.TOO_LARGE
        var total = 0
        events.forEach { wire ->
            validateSubmission(wire)?.let { return it }
            total += canonicalBytes(wire.event).size
            if (total > MAX_BATCH_BYTES) return PartnerEventIssue.TOO_LARGE
        }
        return null
    }

    /** Recorder-side pre-validation: rejects a capture before any crypto or DB work. */
    internal fun metadataIssue(kind: PartnerEventKind, metadata: Map<String, Any>): PartnerEventIssue? {
        if (metadata.size > METADATA_VALUES.size) return PartnerEventIssue.METADATA_INVALID
        metadata.forEach { (key, value) ->
            val allowed = METADATA_VALUES[key] ?: return PartnerEventIssue.METADATA_INVALID
            if (value !is String || value !in allowed) return PartnerEventIssue.METADATA_INVALID
        }
        return when (kind) {
            PartnerEventKind.BLOCK_ATTEMPT ->
                if ("category" !in metadata) PartnerEventIssue.METADATA_INVALID else null
            PartnerEventKind.RULE_CHANGE_ATTEMPT ->
                if ("ruleChange" !in metadata) PartnerEventIssue.METADATA_INVALID else null
            // No safe metadata is defined for a check-in; the content rides the E2EE payload.
            PartnerEventKind.CHECK_IN ->
                if (metadata.isNotEmpty()) PartnerEventIssue.METADATA_INVALID else null
        }
    }

    private fun recipientIssue(envelopes: List<RecipientEnvelopeV1>): PartnerEventIssue? {
        if (envelopes.isEmpty()) return PartnerEventIssue.RECIPIENTS_INVALID
        val seen = HashSet<String>(envelopes.size)
        envelopes.forEach { envelope ->
            if (!KEY_ID.matches(envelope.recipientEncryptionKeyId)) return PartnerEventIssue.RECIPIENTS_INVALID
            if (!seen.add(envelope.recipientEncryptionKeyId)) return PartnerEventIssue.RECIPIENTS_INVALID
            if (envelope.version < 1) return PartnerEventIssue.RECIPIENTS_INVALID
            if (envelope.encoding != ENCODING_EXISTING_E2EE) return PartnerEventIssue.RECIPIENTS_INVALID
            if (!BASE64URL.matches(envelope.payload)) return PartnerEventIssue.RECIPIENTS_INVALID
        }
        return null
    }

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
