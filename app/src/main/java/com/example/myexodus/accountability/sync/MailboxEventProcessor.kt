package com.example.myexodus.accountability.sync

import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.decodePayload
import com.example.myexodus.accountability.serialization.decodeSigned
import com.example.myexodus.accountability.serialization.eventHash
import java.security.PublicKey

/**
 * §7.5 receiving side, verify-then-decrypt pipeline for one mailbox document. Pure and
 * Firebase-free so the §10 rules (domain separation, Low-S) are unit-testable: signature
 * over the envelope hash is checked BEFORE decryption, and a document that fails any step
 * is skipped — it can never advance the chain.
 */
class MailboxEventProcessor(
    private val signer: AccountabilitySigner,
    private val eventCipher: EventCipher,
) {

    sealed interface EnvelopeResult {
        data object InvalidSignature : EnvelopeResult

        /**
         * §8 Step 15 / AD-12: no acceptable key for the envelope's `keyEpoch` — the epoch is
         * older than the accepted window (the sender should have stopped using it), or the
         * key material is unavailable. Logged as a security event and never applied; kept
         * distinct from [Corrupt] and from tamper (AD-08: a key-epoch mismatch is never
         * TAMPER_SUSPECTED).
         */
        data object UnknownKeyEpoch : EnvelopeResult

        /** Malformed CBOR, undecryptable ciphertext, or malformed plaintext. */
        data object Corrupt : EnvelopeResult

        /** Fully verified + decrypted; the payload drives control handling (§10 deletion,
         *  Step 11 sync requests) and [payloadType] the notification decision. */
        data class Valid(val signed: SignedEnvelope, val payload: EventPayload) : EnvelopeResult {
            val payloadType: String get() = payload.type
        }
    }

    /**
     * [partnerSigningKey] is the partner's attested signing public key; [keyForEpoch] resolves
     * the session key for the envelope's declared `keyEpoch` (AD-12 — deterministic selection,
     * never trial decryption) and returns null for an epoch the receive policy refuses. The
     * envelope is validated in full (signature first, then key policy) before [Valid] is
     * returned — receivers never append unverified bytes.
     */
    fun process(
        partnerSigningKey: PublicKey,
        keyForEpoch: (Int) -> ByteArray?,
        envelopeBytes: ByteArray,
    ): EnvelopeResult {
        val signed = try {
            decodeSigned(envelopeBytes)
        } catch (e: Exception) {
            return EnvelopeResult.Corrupt
        }
        val unsigned = signed.unsigned
        if (!signer.verify(partnerSigningKey, eventHash(unsigned), signed.signature)) {
            return EnvelopeResult.InvalidSignature
        }
        val key = keyForEpoch(unsigned.keyEpoch) ?: return EnvelopeResult.UnknownKeyEpoch
        val plaintext = try {
            eventCipher.decrypt(key, unsigned.sequence, unsigned.ciphertext)
        } catch (e: Exception) {
            return EnvelopeResult.Corrupt
        }
        return try {
            EnvelopeResult.Valid(signed, decodePayload(plaintext))
        } catch (e: Exception) {
            EnvelopeResult.Corrupt
        }
    }
}
