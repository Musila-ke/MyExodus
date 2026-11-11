package com.example.myexodus.accountability.delivery

import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import java.util.Base64
import kotlinx.coroutines.CancellationException

/**
 * §8.6 Phase 5 recipient half: opens one `partnerMailboxes` document with the pair's own
 * agreement key (the mirror of [ExistingE2eeRecipientEncryptor]). Null means "cannot open on
 * this device" — the receiver records that as terminal UNDECRYPTABLE instead of retrying a
 * document that will never become readable (AC07), which would stall the watermark.
 */
class PartnerMailboxDecryptor(
    private val enrollmentFor: suspend (pairId: String) -> EnrollmentRecord?,
    private val deriveCek: (EnrollmentRecord) -> ByteArray,
    private val cipher: EventCipher,
) {

    suspend fun open(document: PartnerMailboxDocument): ByteArray? {
        val kind = PartnerEventKind.fromWire(document.kind ?: "") ?: return null
        val eventId = document.eventId ?: return null
        val senderInstallationId = document.senderInstallationId ?: return null
        val senderOwnerUid = document.senderOwnerUid ?: return null
        val recipientKeyId = document.recipientKeyId ?: return null
        val pairId = document.pairId ?: return null
        val payload = document.payload ?: return null
        val enrollment = try {
            enrollmentFor(pairId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            cipher.decryptWithAad(
                deriveCek(enrollment),
                PartnerEventEnvelopeAad.of(
                    eventId, senderInstallationId, senderOwnerUid, kind, recipientKeyId,
                ),
                Base64.getUrlDecoder().decode(payload),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
