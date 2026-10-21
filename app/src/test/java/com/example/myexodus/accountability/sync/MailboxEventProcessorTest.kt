package com.example.myexodus.accountability.sync

import android.app.Application
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.EcdsaLowS
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.UnsignedEnvelope
import com.example.myexodus.accountability.serialization.encodePayload
import com.example.myexodus.accountability.serialization.encodeSigned
import com.example.myexodus.accountability.serialization.eventHash
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §7.5 receiving pipeline, real crypto: verify-then-decrypt with the partner's attested key.
 * Covers §10 Low-S rejection (raw JCA signatures are high-S ~50% of the time) and the
 * §9 contract that only fully-valid envelopes reach the chain layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MailboxEventProcessorTest {

    private val signer = AccountabilitySigner()
    private val cipher = EventCipher()
    private val processor = MailboxEventProcessor(signer, cipher)
    private val cek = ByteArray(32) { it.toByte() }
    private val now = 1_700_000_000_000L

    private lateinit var partnerKeys: KeyPair
    private lateinit var partnerKeyId: String

    @Before
    fun setUp() {
        partnerKeys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        partnerKeyId = sha256Hex(partnerKeys.public.encoded)
    }

    private fun envelope(
        sequence: Long,
        previousHash: ByteArray? = null,
        signWith: KeyPair = partnerKeys,
        lowS: Boolean = true,
    ): SignedEnvelope {
        val unsigned = UnsignedEnvelope(
            senderKeyId = partnerKeyId,
            epoch = 0,
            sequence = sequence,
            previousEventHash = previousHash,
            nonce = cipher.nonce(cek, sequence),
            ciphertext = cipher.encrypt(
                cek,
                sequence,
                encodePayload(EventPayload(type = "activity", text = "check-in $sequence")),
            ),
            createdAtEpochMillis = now + sequence,
        )
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(signWith.private)
        sig.update(eventHash(unsigned))
        val raw = sig.sign()
        return SignedEnvelope(unsigned, if (lowS) EcdsaLowS.normalize(raw) else raw)
    }

    @Test
    fun validEnvelope_isVerifiedAndPassedThrough() {
        val result = processor.process(partnerKeys.public, { cek }, encodeSigned(envelope(1)))
        assertTrue(result is MailboxEventProcessor.EnvelopeResult.Valid)
        val valid = result as MailboxEventProcessor.EnvelopeResult.Valid
        assertEquals(1L, valid.signed.unsigned.sequence)
        assertEquals(partnerKeyId, valid.signed.unsigned.senderKeyId)
        assertEquals(EventPayload.TYPE_ACTIVITY, valid.payloadType)
    }

    @Test
    fun deletionRequestEnvelope_exposesPayloadType() {
        val unsigned = UnsignedEnvelope(
            senderKeyId = partnerKeyId,
            epoch = 0,
            sequence = 2,
            previousEventHash = eventHash(envelope(1).unsigned),
            nonce = cipher.nonce(cek, 2),
            ciphertext = cipher.encrypt(
                cek,
                2,
                encodePayload(EventPayload(type = EventPayload.TYPE_DELETION_REQUEST, text = "")),
            ),
            createdAtEpochMillis = now + 2,
        )
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(partnerKeys.private)
        sig.update(eventHash(unsigned))
        val signed = SignedEnvelope(unsigned, EcdsaLowS.normalize(sig.sign()))

        val valid = processor.process(partnerKeys.public, { cek }, encodeSigned(signed)) as MailboxEventProcessor.EnvelopeResult.Valid
        // §10: the receiver reacts to this exact type (FirestoreMailboxReceiver triggers shredding).
        assertEquals(EventPayload.TYPE_DELETION_REQUEST, valid.payloadType)
    }

    @Test
    fun tamperedCiphertext_isRejectedBeforeDecryption() {
        val good = encodeSigned(envelope(1))
        val tampered = good.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 0x01).toByte() }
        assertEquals(MailboxEventProcessor.EnvelopeResult.InvalidSignature, processor.process(partnerKeys.public, { cek }, tampered))
    }

    @Test
    fun signatureFromAnotherKey_isRejected() {
        val other = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signed = encodeSigned(envelope(1, signWith = other))
        assertEquals(MailboxEventProcessor.EnvelopeResult.InvalidSignature, processor.process(partnerKeys.public, { cek }, signed))
    }

    @Test
    fun highSSignature_isRejectedPerLowS() {
        val envelope = envelope(1, lowS = false)
        // Raw JCA signatures are already low-S ~50% of the time — force the high-S variant
        // so this test exercises §10 rejection deterministically.
        val forced = SignedEnvelope(envelope.unsigned, EcdsaLowS.forceHighS(envelope.signature))
        assertEquals(MailboxEventProcessor.EnvelopeResult.InvalidSignature, processor.process(partnerKeys.public, { cek }, encodeSigned(forced)))
    }

    @Test
    fun wrongCek_isCorrupt_notInvalid() {
        val otherCek = ByteArray(32) { (it + 1).toByte() }
        val signed = encodeSigned(envelope(1))
        assertEquals(MailboxEventProcessor.EnvelopeResult.Corrupt, processor.process(partnerKeys.public, { otherCek }, signed))
    }

    @Test
    fun malformedBytes_areCorrupt() {
        assertEquals(MailboxEventProcessor.EnvelopeResult.Corrupt, processor.process(partnerKeys.public, { cek }, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun refusedEpoch_isUnknownKeyEpoch_itsOwnResult() {
        // Step 15 receive policy: a refused epoch is neither corrupt nor tamper — plan §8:
        // "UNKNOWN_KEY_EPOCH -> logSecurityEvent(UNKNOWN_KEY_EPOCH); do not apply".
        val result = processor.process(partnerKeys.public, { null }, encodeSigned(envelope(1)))
        assertEquals(MailboxEventProcessor.EnvelopeResult.UnknownKeyEpoch, result)
    }

    @Test
    fun resolvesTheKeyForTheEnvelopesDeclaredEpoch_deterministically() {
        val cekDerivation = CekDerivation()
        // AD-12: traffic under epoch 3 is encrypted with HKDF(CEK, "StrictMode-session-key"||3).
        val sessionKey = cekDerivation.deriveSessionKey(cek, 3)
        val unsigned = UnsignedEnvelope(
            senderKeyId = partnerKeyId,
            epoch = 0,
            sequence = 1,
            previousEventHash = null,
            nonce = cipher.nonce(sessionKey, 1),
            ciphertext = cipher.encrypt(sessionKey, 1, encodePayload(EventPayload(type = "activity", text = "rotated"))),
            createdAtEpochMillis = now,
            keyEpoch = 3,
        )
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(partnerKeys.private)
        sig.update(eventHash(unsigned))
        val signed = SignedEnvelope(unsigned, EcdsaLowS.normalize(sig.sign()))

        val asked = mutableListOf<Int>()
        val result = processor.process(
            partnerKeys.public,
            { epoch -> asked += epoch; cekDerivation.deriveSessionKey(cek, epoch) },
            encodeSigned(signed),
        )
        val valid = result as MailboxEventProcessor.EnvelopeResult.Valid
        assertEquals(3, valid.signed.unsigned.keyEpoch)
        assertEquals("rotated", valid.payload!!.text)
        // Exactly the declared epoch is probed — never a trial decryption across epochs.
        assertEquals(listOf(3), asked)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
