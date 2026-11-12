package com.example.myexodus.accountability.delivery

import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import java.security.MessageDigest
import java.util.Base64

/**
 * §8.2 recipient-entry AAD, fixed on both sides: the recipient derives it from the mailbox
 * path and the envelope itself, so no secret travels. `recipientKeyId` binds the ciphertext
 * to the key it was made for — an envelope transplanted to another recipient fails the tag.
 */
object PartnerEventEnvelopeAad {
    fun of(
        eventId: String,
        installationId: String,
        ownerUid: String,
        kind: PartnerEventKind,
        recipientKeyId: String,
    ): ByteArray =
        "partner_event|v1|$eventId|$installationId|$ownerUid|${kind.wire}|$recipientKeyId"
            .toByteArray(Charsets.UTF_8)
}

/**
 * §8.2/Phase 3 "existing E2EE adapter": seals one sensitive payload once per enrolled pair
 * with the already-shipped [EventCipher] + CEK derivation, keyed by the recipient's registered
 * encryption-key ID (`base64url(SHA-256(partner agreement SPKI DER))` — the same ID the
 * server registers in `partnerRecipientKeys`).
 *
 * A missing enrollment, a missing Keystore agreement key or a key that cannot derive a CEK is
 * a `RecipientKeyUnavailable` outcome, never a partial envelope set: §6.1 forbids submitting
 * incomplete ciphertext, so the row waits for public-key synchronization instead.
 */
class ExistingE2eeRecipientEncryptor(
    private val enrollments: suspend () -> List<EnrollmentRecord>,
    private val deriveCek: (EnrollmentRecord) -> ByteArray,
    private val cipher: EventCipher,
) {

    sealed interface Outcome {
        data class Encrypted(val envelopes: List<RecipientEnvelopeV1>) : Outcome
        data object RecipientKeyUnavailable : Outcome
    }

    suspend fun encryptForAll(
        eventId: String,
        installationId: String,
        ownerUid: String,
        kind: PartnerEventKind,
        plaintext: ByteArray,
        /** Non-null restricts the set to the server's current authorized recipient key IDs. */
        authorizedRecipientKeyIds: Set<String>? = null,
        /** Non-null restricts the set to one pairing: a rule assigned to one partner must
         *  never be encrypted to the others (§13.4 consent boundary). */
        recipientPairId: String? = null,
    ): Outcome {
        val all = try {
            enrollments()
        } catch (_: Exception) {
            return Outcome.RecipientKeyUnavailable
        }
        val records = all
            .filter { recipientPairId == null || it.pairId == recipientPairId }
            .filter {
                authorizedRecipientKeyIds == null ||
                    recipientKeyIdOf(it.partnerAgreementPubKey) in authorizedRecipientKeyIds
            }
        if (records.isEmpty()) return Outcome.RecipientKeyUnavailable
        return try {
            val envelopes = records
                .map { record ->
                    val recipientKeyId = recipientKeyIdOf(record.partnerAgreementPubKey)
                    RecipientEnvelopeV1(
                        recipientEncryptionKeyId = recipientKeyId,
                        version = ENVELOPE_VERSION,
                        encoding = PartnerEventProtocol.ENCODING_EXISTING_E2EE,
                        payload = PartnerEventProtocol.base64Url(
                            cipher.encryptWithAad(
                                deriveCek(record),
                                PartnerEventEnvelopeAad.of(
                                    eventId, installationId, ownerUid, kind, recipientKeyId,
                                ),
                                plaintext,
                            ),
                        ),
                    )
                }
                .distinctBy { it.recipientEncryptionKeyId }
                .sortedBy { it.recipientEncryptionKeyId }
            Outcome.Encrypted(envelopes)
        } catch (_: Exception) {
            // Any derivation/encryption failure means at least one pair could not receive a
            // complete envelope. Wait for the key to be repaired rather than under-deliver.
            Outcome.RecipientKeyUnavailable
        }
    }

    companion object {
        const val ENVELOPE_VERSION = 1

        fun recipientKeyIdOf(spkiDer: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(spkiDer))

        /**
         * `recipientKeyId|version|encoding|payload` per line. All four fields are closed-set
         * or base64url, so the encoding is unambiguous and the Phase 4 signer can rebuild the
         * envelope list without a JSON parser.
         */
        fun encode(envelopes: List<RecipientEnvelopeV1>): ByteArray =
            envelopes.joinToString("\n") {
                "${it.recipientEncryptionKeyId}|${it.version}|${it.encoding}|${it.payload}"
            }.toByteArray(Charsets.UTF_8)

        fun decode(blob: ByteArray): List<RecipientEnvelopeV1> =
            blob.toString(Charsets.UTF_8).lineSequence()
                .filter { it.isNotEmpty() }
                .map { line ->
                    val parts = line.split('|')
                    require(parts.size == 4) { "malformed recipient envelope row" }
                    RecipientEnvelopeV1(
                        recipientEncryptionKeyId = parts[0],
                        version = parts[1].toInt(),
                        encoding = parts[2],
                        payload = parts[3],
                    )
                }
                .toList()

        /** §8.2 "record the event without that detail": the oversized path seals this marker
         *  instead of the sensitive content, and the local diagnostic stays in the row. */
        val OMITTED_DETAIL_MARKER: ByteArray = "detail-omitted:PAYLOAD_TOO_LARGE".toByteArray(Charsets.UTF_8)
    }
}
