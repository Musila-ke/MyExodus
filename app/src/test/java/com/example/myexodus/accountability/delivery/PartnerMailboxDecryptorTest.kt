package com.example.myexodus.accountability.delivery

import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §8.6 Phase 5 recipient half: the mirror of [ExistingE2eeRecipientEncryptor]. Null is the
 * single "cannot open on this device" answer (missing enrollment, shredded pair key, foreign
 * envelope, malformed payload) — the receiver turns it into a terminal UNDECRYPTABLE receipt,
 * so every case here is one AC07 classification rather than an infinite retry.
 */
class PartnerMailboxDecryptorTest {

    private val cipher = EventCipher()
    private val cek = ByteArray(32) { (it + 1).toByte() }
    private val kind = PartnerEventKind.BLOCK_ATTEMPT

    private fun enrollment(pairId: String = "pair-1") = EnrollmentRecord(
        pairId = pairId,
        mySenderKeyId = "a".repeat(64),
        partnerSigningKeyId = "b".repeat(64),
        partnerSigningPubKey = byteArrayOf(1),
        partnerAgreementPubKey = byteArrayOf(2),
        createdAtEpochMillis = 1,
        myAgreementKeyAlias = "alias-1",
    )

    private fun document(
        payload: String,
        eventId: String = "evt-1",
        senderInstallationId: String = "sender-inst-1",
        senderOwnerUid: String = "sender-uid",
        recipientKeyId: String = "key-1",
        pairId: String = "pair-1",
        kindWire: String = kind.wire,
        encoding: String = PartnerEventProtocol.ENCODING_EXISTING_E2EE,
    ) = PartnerMailboxDocument(
        messageId = "${eventId}__recipient-inst-1",
        eventId = eventId,
        senderInstallationId = senderInstallationId,
        senderOwnerUid = senderOwnerUid,
        kind = kindWire,
        recipientKeyId = recipientKeyId,
        payload = payload,
        encoding = encoding,
        pairId = pairId,
        createdAtMs = 10,
    )

    private fun sealed(
        plaintext: String,
        eventId: String = "evt-1",
        senderInstallationId: String = "sender-inst-1",
        senderOwnerUid: String = "sender-uid",
        recipientKeyId: String = "key-1",
        sealCek: ByteArray = cek,
    ): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        cipher.encryptWithAad(
            sealCek,
            PartnerEventEnvelopeAad.of(eventId, senderInstallationId, senderOwnerUid, kind, recipientKeyId),
            plaintext.toByteArray(),
        ),
    )

    private fun decryptor(
        record: EnrollmentRecord? = enrollment(),
        lookup: suspend (String) -> EnrollmentRecord? = { record },
    ) = PartnerMailboxDecryptor(
        enrollmentFor = lookup,
        deriveCek = { cek },
        cipher = cipher,
    )

    @Test
    fun open_roundTripsAnEnvelopeSealedForThisPair() = runBlocking {
        val payload = "{\"category\":\"BLOCKED_APP_ATTEMPT\"}"
        assertArrayEquals(
            payload.toByteArray(),
            decryptor().open(document(sealed(payload))),
        )
    }

    @Test
    fun open_returnsNull_whenThePairHasNoEnrollment() = runBlocking {
        assertNull(decryptor(lookup = { null }).open(document(sealed("x"))))
    }

    @Test
    fun open_returnsNull_whenTheEnrollmentLookupThrows() = runBlocking {
        val decryptor = decryptor(lookup = { error("db unavailable") })
        assertNull(decryptor.open(document(sealed("x"))))
    }

    @Test
    fun open_returnsNull_whenTheCeKIsWrong() = runBlocking {
        assertNull(decryptor().open(document(sealed("x", sealCek = ByteArray(32) { 9 }))))
    }

    @Test
    fun open_returnsNull_whenTheSenderAadFieldsDoNotMatch() = runBlocking {
        val payload = sealed("x")
        // Transplanted envelopes must fail the tag: the AAD binds sender installation and owner.
        assertNull(
            decryptor().open(document(payload, senderInstallationId = "other-inst")),
        )
        assertNull(
            decryptor().open(document(payload, senderOwnerUid = "other-uid")),
        )
        assertNull(
            decryptor().open(document(payload, eventId = "other-event")),
        )
        assertNull(
            decryptor().open(document(payload, recipientKeyId = "other-key")),
        )
    }

    @Test
    fun open_returnsNull_whenAnyRequiredFieldIsMissing() = runBlocking {
        val payload = sealed("x")
        val d = document(payload)
        val missing = listOf(
            d.copy(eventId = null),
            d.copy(senderInstallationId = null),
            d.copy(senderOwnerUid = null),
            d.copy(recipientKeyId = null),
            d.copy(pairId = null),
            d.copy(payload = null),
            d.copy(kind = null),
            d.copy(kind = "NOT_A_KIND"),
        )
        for (doc in missing) {
            assertNull(doc.toString(), decryptor().open(doc))
        }
    }

    @Test
    fun open_returnsNull_whenThePayloadIsNotBase64url() = runBlocking {
        assertNull(decryptor().open(document("!!!not-base64!!!")))
        assertNull(decryptor().open(document("")))
    }
}
