package com.example.myexodus.accountability.enrollment

import android.util.Log
import com.example.myexodus.accountability.crypto.AttestationVerifier
import com.example.myexodus.accountability.sync.PairingExchange
import com.example.myexodus.secrets.SecretsRepository
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate

/**
 * §10 pairing orchestration (§7 step 8). The BiometricPrompt, SAS display, and QR
 * rendering are UI concerns owned by the calling screen/harness; this class owns the
 * protocol: token issuance + QR payload (initiator), the relay exchange with mutual
 * verification (both roles), CEK derivation, EnrollmentRecord persistence, and pairing-key
 * storage via SecretsRepository.
 *
 * Ordering (§6.1): neither side can sign the transcript before both agreement keys exist.
 * The partner signs right after redemption (its transcript is buildable once the initiator's
 * key arrives from the token document) and lands the signature via confirmPartnerSignature;
 * the initiator signs after the SAS check, landing it via [submitInitiatorSignature]. The
 * SAS is displayed on both devices; users confirm it out-of-band before the initiator signs.
 */
class PairingFlow(
    private val enrollmentFlow: EnrollmentFlow,
    private val enrollmentDao: EnrollmentDao,
    private val exchange: PairingExchange,
    private val secretsRepository: SecretsRepository,
    private val nowEpochMillis: () -> Long,
    // Keystore accessors as seams (EventCreator pattern): AndroidKeyStore does not exist
    // in Robolectric, so the protocol must be testable with plain JCA key material.
    private val signingCertificateChain: () -> Array<X509Certificate>,
    private val signingPublicKey: () -> PublicKey,
    private val agreementPublicKey: () -> PublicKey,
    private val agreementPrivateKey: () -> PrivateKey,
) {

    sealed interface Outcome {
        data class Paired(val pairId: String, val sas: String) : Outcome
        data class TokenExpired(val pairId: String) : Outcome
        data class TokenAlreadyUsed(val pairId: String) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    /** Initiator step 1 (§10 step 1): token generated + registered, QR content ready to show. */
    data class Initiation(
        val token: PairingToken,
        val payload: EnrollmentPayload,
        val qrBytes: ByteArray,
    )

    /** Initiator step 2 result: partner material verified; SAS ready for out-of-band comparison. */
    data class InitiatorAwait(
        val pairId: String,
        val sas: String,
        val transcriptBytes: ByteArray,
        val partnerSigningPublicKey: PublicKey,
        val partnerAgreementPublicKey: PublicKey,
    )

    suspend fun initiate(myUid: String): Initiation {
        val token = PairingToken.generate(nowEpochMillis())
        val chain = signingCertificateChain()
        val challenge = AttestationVerifier.embeddedChallengeOf(chain[0])
            ?: error("signing key certificate lacks the attestation challenge")
        val payload = EnrollmentPayload(
            pairId = token.pairId,
            signingChain = chain.map { it.encoded },
            attestationChallenge = challenge,
            agreementPublicKey = EnrollmentTranscript.pubKeyBytes(agreementPublicKey()),
        )
        exchange.registerToken(token, myUid, payload)
        // QR carries the token only: real attestation chains overflow QR capacity (2953 B
        // ceiling), so the payload rides in the token document and returns at redemption.
        return Initiation(token, payload, PairingCodec.encode(token))
    }

    /**
     * Initiator step 2 (§10 step 2): wait for the partner's redemption, then verify the
     * partner's material (attestation + transcript signature). Returns null on timeout or
     * a failed verification (in which case the pair document is deleted).
     */
    suspend fun awaitPartner(pairId: String, timeoutMillis: Long): InitiatorAwait? {
        val state = exchange.pollPairState(pairId, timeoutMillis, requireInitiatorSignature = false, requirePartnerSignature = true)
            ?: return null
        val partnerSignature = state.partnerSignature ?: run {
            exchange.deletePairState(pairId)
            return null
        }
        val transcript = transcriptBytes(pairId, state.enrollmentPayload.agreementPublicKey)
        val verification = enrollmentFlow.verifyPartnerMaterial(pairId, state.enrollmentPayload, transcript, partnerSignature)
        if (verification !is EnrollmentFlow.PartnerVerification.Verified) {
            // The caller only sees null; without this the rejection reason is discarded
            // (diagnosed blind on the 2026-09-16 emulator-partner run). The cast is safe:
            // Rejected is the only other PartnerVerification subtype.
            Log.w(TAG, "initiator rejected partner material: ${(verification as EnrollmentFlow.PartnerVerification.Rejected).reason}")
            exchange.deletePairState(pairId)
            return null
        }
        val partnerAgreementKey = try {
            EnrollmentTranscript.parsePublicKey(state.enrollmentPayload.agreementPublicKey)
        } catch (e: Exception) {
            Log.w(TAG, "initiator rejected partner material: agreement key unparseable — ${e.message}")
            exchange.deletePairState(pairId)
            return null
        }
        return InitiatorAwait(
            pairId = pairId,
            sas = enrollmentFlow.deriveSas(pairId, agreementPublicKey(), partnerAgreementKey),
            transcriptBytes = transcript,
            partnerSigningPublicKey = verification.signingPublicKey,
            partnerAgreementPublicKey = partnerAgreementKey,
        )
    }

    /**
     * Initiator step 3 (§10 step 3): sign the transcript (biometric, by the caller), land
     * the signature in the pair document, and finalize — the pair is now registered.
     */
    suspend fun submitInitiatorSignature(await: InitiatorAwait, myUid: String, signature: Signature): Outcome {
        val signatureBytes = enrollmentFlow.signTranscript(signature, await.transcriptBytes)
        try {
            exchange.confirmPairing(await.pairId, signatureBytes)
        } catch (e: Exception) {
            return Outcome.Rejected("could not confirm pairing with the relay: ${e.message}")
        }
        val sas = finalizePairing(await.pairId, await.partnerSigningPublicKey, await.partnerAgreementPublicKey)
        return Outcome.Paired(await.pairId, sas)
    }

    /**
     * Partner half (§10 step 2): decode the QR, verify the initiator's hardware identity,
     * sign the transcript, redeem the token (single-use), verify the initiator's transcript
     * signature, and finalize. [signature] comes from the caller's BiometricPrompt.
     */
    suspend fun complete(qrBytes: ByteArray, myUid: String, signature: Signature): Outcome {
        val content = try {
            PairingCodec.decode(qrBytes)
        } catch (e: Exception) {
            return Outcome.Rejected("malformed pairing QR: ${e.message}")
        }
        val token = content.token
        if (token.isExpired(nowEpochMillis())) return Outcome.TokenExpired(token.pairId)
        val myPayload = ownPayload(token.pairId)
        return when (val redeemed = exchange.redeem(token, myUid, myPayload)) {
            is PairingExchange.RedeemResult.Redeemed -> {
                // The initiator's enrollment payload returns from the token document — the QR
                // cannot carry it (attestation chains overflow QR capacity, 2953 B ceiling).
                val initiatorPayload = redeemed.initiatorPayload
                val initiatorSigningPublicKey = when (val identity = enrollmentFlow.verifyHardwareIdentity(initiatorPayload)) {
                    is EnrollmentFlow.PartnerVerification.Rejected -> {
                        exchange.deletePairState(token.pairId)
                        return Outcome.Rejected(identity.reason)
                    }
                    is EnrollmentFlow.PartnerVerification.Verified -> identity.signingPublicKey
                }
                val transcript = transcriptBytes(token.pairId, initiatorPayload.agreementPublicKey)
                // Sign only now: the transcript needs the initiator's agreement key from the
                // redemption (§6.1 ordering). The biometric Signature object was initialized
                // by the caller; the keystore's 900s auth window covers this.
                exchange.confirmPartnerSignature(
                    token.pairId,
                    enrollmentFlow.signTranscript(signature, transcript),
                )
                val state = exchange.pollPairState(
                    token.pairId,
                    PairingToken.TTL_MILLIS,
                    requireInitiatorSignature = true,
                )
                if (state == null || state.initiatorSignature == null) {
                    exchange.deletePairState(token.pairId)
                    return Outcome.Rejected("initiator did not confirm — pairing aborted")
                }
                if (!enrollmentFlow.verifyTranscriptSignature(
                        initiatorSigningPublicKey,
                        transcript,
                        state.initiatorSignature,
                    )
                ) {
                    exchange.deletePairState(token.pairId)
                    return Outcome.Rejected("initiator transcript signature invalid")
                }
                val partnerAgreementKey = try {
                    EnrollmentTranscript.parsePublicKey(initiatorPayload.agreementPublicKey)
                } catch (e: Exception) {
                    exchange.deletePairState(token.pairId)
                    return Outcome.Rejected("initiator agreement key unparseable")
                }
                val sas = finalizePairing(token.pairId, initiatorSigningPublicKey, partnerAgreementKey)
                Outcome.Paired(token.pairId, sas)
            }

            is PairingExchange.RedeemResult.Expired -> Outcome.TokenExpired(token.pairId)
            is PairingExchange.RedeemResult.AlreadyUsed -> Outcome.TokenAlreadyUsed(token.pairId)
            is PairingExchange.RedeemResult.Failed -> Outcome.Rejected(redeemed.reason)
        }
    }

    private fun ownPayload(pairId: String): EnrollmentPayload {
        val chain = signingCertificateChain()
        val challenge = AttestationVerifier.embeddedChallengeOf(chain[0])
            ?: error("signing key certificate lacks the attestation challenge")
        return EnrollmentPayload(
            pairId = pairId,
            signingChain = chain.map { it.encoded },
            attestationChallenge = challenge,
            agreementPublicKey = EnrollmentTranscript.pubKeyBytes(agreementPublicKey()),
        )
    }

    private fun transcriptBytes(pairId: String, partnerAgreementPubKeyBytes: ByteArray): ByteArray =
        EnrollmentTranscript.transcript(
            pairId,
            EnrollmentTranscript.pubKeyBytes(agreementPublicKey()),
            partnerAgreementPubKeyBytes,
        )

    /** §10 step 2 endgame: derive the CEK, persist the enrollment record, store the pairing key. */
    private suspend fun finalizePairing(
        pairId: String,
        partnerSigningPublicKey: PublicKey,
        partnerAgreementPublicKey: PublicKey,
    ): String {
        val cek = enrollmentFlow.deriveCek(pairId, agreementPrivateKey(), partnerAgreementPublicKey)
        val mySigningPubKeyBytes = EnrollmentTranscript.pubKeyBytes(signingPublicKey())
        enrollmentDao.upsert(
            EnrollmentRecord(
                pairId = pairId,
                mySenderKeyId = EnrollmentTranscript.senderKeyId(mySigningPubKeyBytes),
                partnerSigningKeyId = EnrollmentTranscript.senderKeyId(partnerSigningPublicKey.encoded),
                partnerSigningPubKey = partnerSigningPublicKey.encoded,
                partnerAgreementPubKey = partnerAgreementPublicKey.encoded,
                createdAtEpochMillis = nowEpochMillis(),
            ),
        )
        secretsRepository.storePairingKey(enrollmentFlow.derivePairingKey(cek))
        return enrollmentFlow.deriveSas(pairId, agreementPublicKey(), partnerAgreementPublicKey)
    }

    private companion object {
        /** Same tag as the harness so one `adb logcat -s MyExodusPairing` stream captures both. */
        const val TAG = "MyExodusPairing"
    }
}
