package com.example.myexodus.accountability.sync

import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.EventCipher
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
        /** Malformed CBOR, undecryptable ciphertext, or malformed plaintext. */
        data object Corrupt : EnvelopeResult

        /** Fully verified + decrypted; [payloadType] drives control handling (§10 deletion). */
        data class Valid(val signed: SignedEnvelope, val payloadType: String) : EnvelopeResult
    }

    /**
     * [partnerSigningKey] is the partner's attested signing public key; [cek] the pairwise
     * CEK (§6.1 step 5). The envelope is validated in full before [Valid] is returned —
     * receivers never append unverified bytes.
     */
    fun process(
        partnerSigningKey: PublicKey,
        cek: ByteArray,
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
        val plaintext = try {
            eventCipher.decrypt(cek, unsigned.sequence, unsigned.ciphertext)
        } catch (e: Exception) {
            return EnvelopeResult.Corrupt
        }
        return try {
            EnvelopeResult.Valid(signed, decodePayload(plaintext).type)
        } catch (e: Exception) {
            EnvelopeResult.Corrupt
        }
    }
}
