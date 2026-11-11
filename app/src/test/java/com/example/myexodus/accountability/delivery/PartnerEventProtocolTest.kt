package com.example.myexodus.accountability.delivery

import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan §7 Phase 1 / §8.2 security-negative contract. Reads the fixture shared with
 * `functions/test/partner-events.test.js`, including a Node-signed P-256 signature that
 * Kotlin JCA must verify over the JCS bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PartnerEventProtocolTest {

    private val fixture = run {
        val file = listOf(
            File("../functions/test/fixtures/partner-event-jcs-v1.json"),
            File("functions/test/fixtures/partner-event-jcs-v1.json"),
        ).firstOrNull { it.isFile } ?: error("shared fixture not found from ${File(".").absolutePath}")
        JSONObject(file.readText())
    }

    @Test
    fun `golden events canonicalize and digest identically to the shared fixture`() {
        val vectors = fixture.getJSONArray("events")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val event = vector.getJSONObject("event").toEvent()
            assertEquals(vector.getString("name"), vector.getString("canonical"), canonicalString(event))
            assertEquals(vector.getString("name"), vector.getString("sha256"), PartnerEventProtocol.digest(event))
            assertNull(vector.getString("name"), PartnerEventProtocol.validate(event))
        }
    }

    @Test
    fun `each shared invalid vector is rejected with its documented issue`() {
        val vectors = fixture.getJSONArray("invalidEvents")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            if (!vector.optBoolean("kotlin", true)) continue
            assertEquals(
                vector.getString("name"),
                vector.getString("issue"),
                PartnerEventProtocol.validate(vector.getJSONObject("event").toEvent())?.wire,
            )
        }
    }

    @Test
    fun `node-signed P-256 signature verifies over the JCS bytes`() {
        val signatureVector = fixture.getJSONObject("signature")
        val event = fixture.getJSONArray("events")
            .let { events -> (0 until events.length()).map { events.getJSONObject(it) } }
            .first { it.getString("name") == signatureVector.getString("eventName") }
            .getJSONObject("event")
            .toEvent()

        assertTrue(verify(event, signatureVector))
        assertFalse(verify(event.copy(sequence = event.sequence + 1), signatureVector))
        assertFalse(verify(event, signatureVector, wrongKey()))
    }

    @Test
    fun `ownerUid is inside the signed bytes, so rebinding changes the digest`() {
        val event = firstEvent()
        val rebound = event.copy(ownerUid = "anotherUid_456")
        assertNull(PartnerEventProtocol.validate(rebound))
        assertNotEquals(PartnerEventProtocol.digest(event), PartnerEventProtocol.digest(rebound))
    }

    @Test
    fun `submission rejects invalid key IDs, signatures and padded base64`() {
        val event = firstEvent()
        val keyId = fixture.getJSONObject("signature").getString("keyId")
        val signature = fixture.getJSONObject("signature").getString("signatureBase64Url")
        assertNull(PartnerEventProtocol.validateSubmission(PartnerEventWireV1(keyId, event, signature)))

        assertEquals(
            PartnerEventIssue.SUBMISSION_INVALID,
            PartnerEventProtocol.validateSubmission(PartnerEventWireV1(keyId.dropLast(1), event, signature)),
        )
        assertEquals(
            PartnerEventIssue.SUBMISSION_INVALID,
            PartnerEventProtocol.validateSubmission(PartnerEventWireV1(keyId, event, "$signature=")),
        )
    }

    @Test
    fun `batch constructor enforces one to twenty events`() {
        val wire = PartnerEventWireV1("key", firstEvent(), "signature")
        assertThrows(IllegalArgumentException::class.java) { PartnerEventBatchV1(emptyList()) }
        assertEquals(20, PartnerEventBatchV1(List(20) { wire }).events.size)
        assertThrows(IllegalArgumentException::class.java) { PartnerEventBatchV1(List(21) { wire }) }
    }

    @Test
    fun `result classification encodes the no-effect immutable-after-acceptance contract`() {
        assertTrue(PartnerEventResultCode.isAcknowledged(PartnerEventResultCode.ACCEPTED))
        assertTrue(PartnerEventResultCode.isAcknowledged(PartnerEventResultCode.ALREADY_ACCEPTED))
        PartnerEventResultCode.entries
            .filter { it != PartnerEventResultCode.ACCEPTED && it != PartnerEventResultCode.ALREADY_ACCEPTED }
            .forEach { assertFalse(it.name, PartnerEventResultCode.isAcknowledged(it)) }

        // Ciphertext regeneration is legal only on the definitive pre-effect rejection.
        assertTrue(PartnerEventResultCode.allowsCiphertextRegeneration(PartnerEventResultCode.RECIPIENT_KEY_STALE))
        PartnerEventResultCode.entries
            .filter { it != PartnerEventResultCode.RECIPIENT_KEY_STALE }
            .forEach { assertFalse(it.name, PartnerEventResultCode.allowsCiphertextRegeneration(it)) }
    }

    @Test
    fun `kind allowlist excludes every server-generated kind`() {
        assertEquals(
            setOf("BLOCK_ATTEMPT", "CHECK_IN", "RULE_CHANGE_ATTEMPT"),
            PartnerEventKind.entries.map { it.wire }.toSet(),
        )
        listOf("RULE_CHANGE_APPLIED", "PARTNER_ADDED", "PARTNER_REMOVED", "PARTNER_APPROVAL")
            .forEach { assertNull(PartnerEventKind.fromWire(it)) }
    }

    @Test
    fun `unknown result codes are treated as failure, never as acceptance`() {
        assertNull(PartnerEventResultCode.fromWire("SOMETHING_NEW"))
        assertFalse(PartnerEventResultCode.isAcknowledged(PartnerEventResultCode.RETRYABLE))
        assertFalse(PartnerEventResultCode.allowsCiphertextRegeneration(PartnerEventResultCode.RETRYABLE))
    }

    private fun firstEvent(): PartnerEventV1 =
        fixture.getJSONArray("events").getJSONObject(0).getJSONObject("event").toEvent()

    private fun canonicalString(event: PartnerEventV1): String =
        String(PartnerEventProtocol.canonicalBytes(event), Charsets.UTF_8)

    private fun verify(
        event: PartnerEventV1,
        signatureVector: JSONObject,
        publicKeyBytes: ByteArray = Base64.getUrlDecoder()
            .decode(signatureVector.getString("publicKeySpkiBase64Url")),
    ): Boolean {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(key)
            update(PartnerEventProtocol.canonicalBytes(event))
        }
        return signature.verify(Base64.getUrlDecoder().decode(signatureVector.getString("signatureBase64Url")))
    }

    private fun wrongKey(): ByteArray =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded

    @Suppress("UNCHECKED_CAST")
    private fun JSONObject.toEvent(): PartnerEventV1 {
        val metadataJson = optJSONObject("metadata") ?: JSONObject()
        val metadata = metadataJson.keys().asSequence().associateWith { metadataJson.get(it) } as Map<String, Any>
        val envelopesJson = getJSONArray("recipientEnvelopes")
        val envelopes = (0 until envelopesJson.length()).map { index ->
            val envelope = envelopesJson.getJSONObject(index)
            RecipientEnvelopeV1(
                recipientEncryptionKeyId = envelope.getString("recipientEncryptionKeyId"),
                version = envelope.getInt("version"),
                encoding = envelope.getString("encoding"),
                payload = envelope.getString("payload"),
            )
        }
        return PartnerEventV1(
            eventId = getString("eventId"),
            installationId = getString("installationId"),
            ownerUid = getString("ownerUid"),
            sequence = getLong("sequence"),
            kind = requireNotNull(PartnerEventKind.fromWire(getString("kind"))),
            deviceOccurredAtMs = getLong("deviceOccurredAtMs"),
            recipientEnvelopes = envelopes,
            metadata = metadata,
            v = getInt("v"),
        )
    }
}
