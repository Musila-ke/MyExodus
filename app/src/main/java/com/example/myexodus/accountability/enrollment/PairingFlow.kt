package com.example.myexodus.accountability.enrollment

import android.util.Log
import com.example.myexodus.accountability.crypto.AttestationVerifier
import com.example.myexodus.accountability.sync.PairingExchange
import com.example.myexodus.accountability.sync.PairingRegistration
import com.example.myexodus.secrets.SecretsRepository
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate

/**
 * §10 pairing orchestration, v2 model (§8 Step 3). The BiometricPrompt, SAS display, and
 * QR rendering are UI concerns owned by the calling screen/harness; this class owns the
 * protocol: token issuance + QR payload (initiator), the token-document exchange with
 * mutual verification (both roles), the signed registerPairing proof submission, CEK
 * derivation, EnrollmentRecord persistence, and pairing-key storage via SecretsRepository.
 *
 * Two ids, deliberately distinct: the *session id* is the token's random per-attempt id —
 * it binds the transcript/SAS/CEK and dies with the attempt. The *durable pair id* is
 * min(uidA,uidB)+"_"+max(uidA,uidB) (AD-02), computed independently on both devices and
 * by registerPairing; it is what /pairings, mailboxes, and EnrollmentRecord are keyed by.
 *
 * Ordering (§6.1): neither side can sign the transcript before both agreement keys exist.
 * The partner reads the initiator's key from the token document (the QR cannot carry the
 * attestation chain, see [PairingCodec]) and responds with its material + transcript
 * signature; the initiator verifies that material, shows the SAS, and only after the
 * out-of-band SAS check signs its proof (biometric, caller-supplied [Signature]).
 */
class PairingFlow(
    private val enrollmentFlow: EnrollmentFlow,
    private val enrollmentDao: EnrollmentDao,
    private val exchange: PairingExchange,
    private val registration: PairingRegistration,
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
        /** [pairId] is the durable AD-02 id both devices and the relay now use. */
        data class Paired(val pairId: String, val sas: String) : Outcome
        data class TokenExpired(val sessionId: String) : Outcome
        data class TokenAlreadyUsed(val sessionId: String) : Outcome
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
        val sessionId: String,
        val partnerUid: String,
        val sas: String,
        val transcriptBytes: ByteArray,
        val partnerSigningPublicKey: PublicKey,
        val partnerAgreementPublicKey: PublicKey,
    )

    suspend fun initiate(myUid: String): Initiation {
        val token = PairingToken.generate(nowEpochMillis())
        val payload = ownPayload(token.sessionId)
        exchange.registerToken(token, myUid, payload)
        // QR carries the token only: real attestation chains overflow QR capacity (2953 B
        // ceiling), so the payload rides in the token document and is read by the partner.
        return Initiation(token, payload, PairingCodec.encode(token))
    }

    /**
     * Initiator step 2 (§10 step 2): wait for the partner's response on the token document,
     * then verify the partner's material (attestation + transcript signature). Returns null
     * on timeout or a failed verification (in which case the token is deleted).
     */
    suspend fun awaitPartner(token: PairingToken, timeoutMillis: Long): InitiatorAwait? {
        val response = exchange.pollTokenResponse(token, timeoutMillis) ?: return null
        val transcript = transcriptBytes(token.sessionId, response.partnerPayload.agreementPublicKey)
        val verification = enrollmentFlow.verifyPartnerMaterial(
            token.sessionId,
            response.partnerPayload,
            transcript,
            response.partnerSignature,
        )
        if (verification !is EnrollmentFlow.PartnerVerification.Verified) {
            // The caller only sees null; without this the rejection reason is discarded
            // (diagnosed blind on the 2026-09-16 emulator-partner run). The cast is safe:
            // Rejected is the only other PartnerVerification subtype.
            Log.w(TAG, "initiator rejected partner material: ${(verification as EnrollmentFlow.PartnerVerification.Rejected).reason}")
            exchange.deleteToken(token)
            return null
        }
        val partnerAgreementKey = try {
            EnrollmentTranscript.parsePublicKey(response.partnerPayload.agreementPublicKey)
        } catch (e: Exception) {
            Log.w(TAG, "initiator rejected partner material: agreement key unparseable — ${e.message}")
            exchange.deleteToken(token)
            return null
        }
        return InitiatorAwait(
            sessionId = token.sessionId,
            partnerUid = response.partnerUid,
            sas = enrollmentFlow.deriveSas(token.sessionId, agreementPublicKey(), partnerAgreementKey),
            transcriptBytes = transcript,
            partnerSigningPublicKey = verification.signingPublicKey,
            partnerAgreementPublicKey = partnerAgreementKey,
        )
    }

    /**
     * Initiator step 3 (§10 step 3): sign the pairing proof (biometric, by the caller
     * after the SAS check) and submit it to registerPairing. The function activates the
     * pairing once both proofs are in; if the partner's proof is slow, wait briefly for
     * it. On success the pair is registered and finalized locally.
     */
    suspend fun submitInitiatorSignature(
        await: InitiatorAwait,
        token: PairingToken,
        myUid: String,
        signature: Signature,
    ): Outcome {
        require(await.sessionId == token.sessionId) { "await does not belong to this token's session" }
        val durablePairId = EnrollmentTranscript.durablePairIdOf(myUid, await.partnerUid)
        val proof = buildProof(myUid, await.partnerUid, await.transcriptBytes, signature)
        return when (val submitted = registration.submit(proof)) {
            is PairingRegistration.SubmitResult.Registered -> {
                if (submitted.pairId != durablePairId) {
                    return Outcome.Rejected("registration bound an unexpected pair id")
                }
                val sas = finalizePairing(
                    await.sessionId,
                    durablePairId,
                    await.partnerSigningPublicKey,
                    await.partnerAgreementPublicKey,
                    submitted.pairingEpoch,
                )
                exchange.deleteToken(token)
                Outcome.Paired(durablePairId, sas)
            }

            PairingRegistration.SubmitResult.AwaitingPartner ->
                // The partner submits right after its token response, so pending here
                // almost always means that call failed — but give a straggler a moment.
                when (
                    val status = exchange.awaitPairingActivation(
                        durablePairId,
                        myUid,
                        proof.transcriptHash,
                        ACTIVATION_WAIT_MILLIS,
                    )
                ) {
                    is PairingExchange.PairingStatus.Active -> {
                        val sas = finalizePairing(
                            await.sessionId,
                            durablePairId,
                            await.partnerSigningPublicKey,
                            await.partnerAgreementPublicKey,
                            status.pairingEpoch,
                        )
                        exchange.deleteToken(token)
                        Outcome.Paired(durablePairId, sas)
                    }
                    PairingExchange.PairingStatus.ActiveDifferentSession ->
                        Outcome.Rejected("an active pairing with different material already exists")
                    PairingExchange.PairingStatus.Pending ->
                        Outcome.Rejected("partner registration never completed — pairing aborted")
                }

            is PairingRegistration.SubmitResult.Rejected -> Outcome.Rejected(submitted.reason)
            is PairingRegistration.SubmitResult.Failed ->
                Outcome.Rejected("could not register pairing: ${submitted.message}")
        }
    }

    /**
     * Partner half (§10 step 2): decode the QR, read the initiator's material from the
     * token document, verify its hardware identity, respond with own material + transcript
     * signature (single-use, rules-enforced), submit the signed proof, and finalize once
     * the pairing document is active. [signature] comes from the caller's BiometricPrompt.
     */
    suspend fun complete(qrBytes: ByteArray, myUid: String, signature: Signature): Outcome {
        val content = try {
            PairingCodec.decode(qrBytes)
        } catch (e: Exception) {
            return Outcome.Rejected("malformed pairing QR: ${e.message}")
        }
        val token = content.token
        if (token.isExpired(nowEpochMillis())) return Outcome.TokenExpired(token.sessionId)
        return when (val read = exchange.readToken(token)) {
            is PairingExchange.TokenReadResult.Found -> {
                if (read.partnerResponse != null) {
                    // Another device (or our own earlier attempt) already responded; the
                    // response write would be denied anyway — fail before signing.
                    return Outcome.TokenAlreadyUsed(token.sessionId)
                }
                completeFound(token, read.creatorUid, read.initiatorPayload, myUid, signature)
            }
            PairingExchange.TokenReadResult.NotFound -> Outcome.TokenAlreadyUsed(token.sessionId)
            PairingExchange.TokenReadResult.Denied -> Outcome.TokenExpired(token.sessionId)
            is PairingExchange.TokenReadResult.Failed -> Outcome.Rejected(read.reason)
        }
    }

    private suspend fun completeFound(
        token: PairingToken,
        initiatorUid: String,
        initiatorPayload: EnrollmentPayload,
        myUid: String,
        signature: Signature,
    ): Outcome {
        // The initiator's enrollment payload returns from the token document — the QR
        // cannot carry it (attestation chains overflow QR capacity, 2953 B ceiling).
        val initiatorSigningPublicKey = when (val identity = enrollmentFlow.verifyHardwareIdentity(initiatorPayload)) {
            is EnrollmentFlow.PartnerVerification.Rejected -> {
                exchange.deleteToken(token)
                return Outcome.Rejected(identity.reason)
            }
            is EnrollmentFlow.PartnerVerification.Verified -> identity.signingPublicKey
        }
        val initiatorAgreementKey = try {
            EnrollmentTranscript.parsePublicKey(initiatorPayload.agreementPublicKey)
        } catch (e: Exception) {
            exchange.deleteToken(token)
            return Outcome.Rejected("initiator agreement key unparseable")
        }
        val transcript = transcriptBytes(token.sessionId, initiatorPayload.agreementPublicKey)
        // Sign both messages now, before any network write: one biometric warm window
        // (§NFR4, 900s) covers every signature, and neither depends on the write outcome.
        val partnerTranscriptSignature = enrollmentFlow.signMessage(signature, transcript)
        val proof = buildProof(myUid, initiatorUid, transcript, signature)
        when (val responded = exchange.respondOnToken(token, myUid, ownPayload(token.sessionId), partnerTranscriptSignature)) {
            PairingExchange.RespondResult.AlreadyUsed -> return Outcome.TokenAlreadyUsed(token.sessionId)
            is PairingExchange.RespondResult.Failed -> return Outcome.Rejected(responded.reason)
            PairingExchange.RespondResult.Written -> Unit
        }
        val durablePairId = EnrollmentTranscript.durablePairIdOf(myUid, initiatorUid)
        return when (val submitted = registration.submit(proof)) {
            is PairingRegistration.SubmitResult.Registered -> {
                if (submitted.pairId != durablePairId) {
                    return Outcome.Rejected("registration bound an unexpected pair id")
                }
                exchange.deleteToken(token)
                val sas = finalizePairing(
                    token.sessionId,
                    durablePairId,
                    initiatorSigningPublicKey,
                    initiatorAgreementKey,
                    submitted.pairingEpoch,
                )
                Outcome.Paired(durablePairId, sas)
            }

            PairingRegistration.SubmitResult.AwaitingPartner ->
                // The initiator signs only after the humans compare the SAS — allow the
                // full token lifetime for that to happen (the function's own 5-minute
                // window is the real bound and reports as Pending).
                when (
                    val status = exchange.awaitPairingActivation(
                        durablePairId,
                        myUid,
                        proof.transcriptHash,
                        PairingToken.TTL_MILLIS,
                    )
                ) {
                    is PairingExchange.PairingStatus.Active -> {
                        exchange.deleteToken(token)
                        val sas = finalizePairing(
                            token.sessionId,
                            durablePairId,
                            initiatorSigningPublicKey,
                            initiatorAgreementKey,
                            status.pairingEpoch,
                        )
                        Outcome.Paired(durablePairId, sas)
                    }
                    PairingExchange.PairingStatus.ActiveDifferentSession ->
                        Outcome.Rejected("an active pairing with different material already exists")
                    PairingExchange.PairingStatus.Pending ->
                        Outcome.Rejected("initiator did not confirm — pairing aborted")
                }

            is PairingRegistration.SubmitResult.Rejected -> Outcome.Rejected(submitted.reason)
            is PairingRegistration.SubmitResult.Failed ->
                Outcome.Rejected("could not register pairing: ${submitted.message}")
        }
    }

    private fun ownPayload(sessionId: String): EnrollmentPayload {
        val chain = signingCertificateChain()
        val challenge = AttestationVerifier.embeddedChallengeOf(chain[0])
            ?: error("signing key certificate lacks the attestation challenge")
        return EnrollmentPayload(
            sessionId = sessionId,
            signingChain = chain.map { it.encoded },
            attestationChallenge = challenge,
            agreementPublicKey = EnrollmentTranscript.pubKeyBytes(agreementPublicKey()),
        )
    }

    private fun transcriptBytes(sessionId: String, partnerAgreementPubKeyBytes: ByteArray): ByteArray =
        EnrollmentTranscript.transcript(
            sessionId,
            EnrollmentTranscript.pubKeyBytes(agreementPublicKey()),
            partnerAgreementPubKeyBytes,
        )

    /** §8 Step 3: the signed registration statement for this device's side of the pairing. */
    private fun buildProof(
        ownUid: String,
        partnerUid: String,
        transcriptBytes: ByteArray,
        signature: Signature,
    ): PairingProof {
        val mySigningPubKeyBytes = EnrollmentTranscript.pubKeyBytes(signingPublicKey())
        val publicKeyHash = EnrollmentTranscript.senderKeyId(mySigningPubKeyBytes)
        val transcriptHash = EnrollmentTranscript.sha256Hex(transcriptBytes)
        val message = PairingProof.canonicalMessageOf(
            ownUid = ownUid,
            partnerUid = partnerUid,
            publicKeyHash = publicKeyHash,
            transcriptHash = transcriptHash,
            protocolVersion = PairingProof.PROTOCOL_VERSION,
        )
        return PairingProof(
            ownUid = ownUid,
            partnerUid = partnerUid,
            publicKeyHash = publicKeyHash,
            transcriptHash = transcriptHash,
            protocolVersion = PairingProof.PROTOCOL_VERSION,
            signingPublicKey = mySigningPubKeyBytes,
            signature = enrollmentFlow.signMessage(signature, message),
        )
    }

    /** §10 step 2 endgame: derive the CEK, persist the enrollment record, store the pairing key. */
    private suspend fun finalizePairing(
        sessionId: String,
        durablePairId: String,
        partnerSigningPublicKey: PublicKey,
        partnerAgreementPublicKey: PublicKey,
        pairingEpoch: Int,
    ): String {
        val cek = enrollmentFlow.deriveCek(sessionId, agreementPrivateKey(), partnerAgreementPublicKey)
        val mySigningPubKeyBytes = EnrollmentTranscript.pubKeyBytes(signingPublicKey())
        enrollmentDao.upsert(
            EnrollmentRecord(
                pairId = durablePairId,
                mySenderKeyId = EnrollmentTranscript.senderKeyId(mySigningPubKeyBytes),
                partnerSigningKeyId = EnrollmentTranscript.senderKeyId(partnerSigningPublicKey.encoded),
                partnerSigningPubKey = partnerSigningPublicKey.encoded,
                partnerAgreementPubKey = partnerAgreementPublicKey.encoded,
                createdAtEpochMillis = nowEpochMillis(),
                pairingEpoch = pairingEpoch,
            ),
        )
        secretsRepository.storePairingKey(enrollmentFlow.derivePairingKey(cek))
        return enrollmentFlow.deriveSas(sessionId, agreementPublicKey(), partnerAgreementPublicKey)
    }

    private companion object {
        /** Same tag as the harness so one `adb logcat -s MyExodusPairing` stream captures both. */
        const val TAG = "MyExodusPairing"

        /** Straggler window for the partner's proof after our own registered as pending. */
        const val ACTIVATION_WAIT_MILLIS = 30_000L
    }
}
