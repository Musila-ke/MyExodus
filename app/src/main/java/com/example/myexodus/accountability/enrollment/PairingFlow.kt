package com.example.myexodus.accountability.enrollment

import android.util.Log
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.crypto.AttestationVerifier
import com.example.myexodus.accountability.sync.PairingExchange
import com.example.myexodus.accountability.sync.PairingRegistration
import com.example.myexodus.secrets.SecretsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.UUID

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
    // §8.1 device identity: the signing + agreement keys must exist before any key material
    // is produced. Fixed 2026-09-28 — production had no mint caller at all, so a fresh
    // install could neither invite (ownPayload threw into the screen's catch-all, shown as
    // "Couldn't reach the server") nor join (the biometric sign returned null and the screen
    // stayed silent). Idempotent: an existing keypair is left alone.
    private val ensureIdentityKeys: () -> AccountabilityKeyStore.GenerationResult,
    // Keystore accessors as seams (EventCreator pattern): AndroidKeyStore does not exist
    // in Robolectric, so the protocol must be testable with plain JCA key material.
    private val signingCertificateChain: () -> Array<X509Certificate>,
    private val signingPublicKey: () -> PublicKey,
    // §19.5 (ADR #106): every leg below works on THIS attempt's own agreement keypair, named
    // by the pairing session id — the initiator's key must exist before the partner (and so
    // the durable pairId) is known, so the session id is what scopes the alias. The alias is
    // recorded on the enrollment; unpair shreds exactly this key.
    private val generateAgreementKey: (alias: String) -> Unit,
    private val agreementPublicKey: (alias: String) -> PublicKey,
    private val agreementPrivateKey: (alias: String) -> PrivateKey,
    private val destroyAgreementKey: (alias: String) -> Unit,
    // Owner ruling 2026-09-29: fan-out is bounded to the pairing moment, so the newcomer needs
    // one fresh state snapshot at pair-complete or their dashboard waits for the first
    // transition. Defaulted: the debug harness has no rules store to snapshot from, and the
    // emission must never be able to fail a pairing that is already durable.
    private val onEnrolled: suspend (pairId: String) -> Unit = {},
    // AD-22: the wipe canary is minted here — only a durable enrollment proves a pairing
    // completed. Attempt paths (start, cancel, failure) must never mint it: the ceremony's
    // identity keys are created before the server round-trip, so an aborted attempt that set
    // this marker would read as a wiped database on the next start (AVD-caught 2026-10-23).
    private val markPairingCompleted: () -> Unit = {},
    // AD-07: a re-pair reuses the durable pairId, so a previous session's partner-view rows
    // would otherwise render under this brand-new session. Cleared before the enrollment lands
    // so every pairing starts with a clean slate regardless of how the last session ended.
    private val prunePartnerActivity: suspend (pairId: String) -> Unit = {},
) {

    sealed interface Outcome {
        /** [pairId] is the durable AD-02 id both devices and the relay now use. */
        data class Paired(val pairId: String, val sas: String) : Outcome

        /** Null session id when the failure precedes the token read (typed-code entry). */
        data class TokenExpired(val sessionId: String?) : Outcome
        data class TokenAlreadyUsed(val sessionId: String?) : Outcome
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
        /** §19.5: the partner's self-chosen label; null when they sent none. */
        val partnerLabel: String? = null,
    )

    /**
     * §7 step 14 (both roles): what the SAS comparison needs, verified, before ANY signature.
     * The shipped one-shot [complete] signs first and shows the SAS after — the production
     * ceremony needs the human check to gate the proof, exactly as the initiator half already
     * does, so the partner half is split into [prepareJoin] (verify + derive SAS) and
     * [finishJoin] (sign + register). [complete] is now literally the two composed.
     */
    data class JoinPreparation(
        val token: PairingToken,
        val initiatorUid: String,
        val initiatorSigningPublicKey: PublicKey,
        val initiatorAgreementKey: PublicKey,
        val initiatorLabel: String?,
        val transcriptBytes: ByteArray,
        val sas: String,
    )

    sealed interface JoinPreparationResult {
        data class Ready(val preparation: JoinPreparation) : JoinPreparationResult

        /** Terminal for this attempt — the only [Outcome] subtypes produced are
         *  TokenExpired/TokenAlreadyUsed/Rejected. */
        data class Failed(val outcome: Outcome) : JoinPreparationResult
    }

    /** Idempotent device-identity mint; a failed generation is logged here and surfaces at the
     *  first key read ("signing key missing"), the same failure shape as before this existed. */
    private fun ensureDeviceIdentity() {
        val result = ensureIdentityKeys()
        if (result is AccountabilityKeyStore.GenerationResult.Failed) {
            Log.w(TAG, "device identity key generation failed: ${result.error}")
        }
    }

    suspend fun initiate(myUid: String, myLabel: String? = null): Initiation {
        val token = PairingToken.generate(nowEpochMillis())
        ensureDeviceIdentity()
        generateAgreementKey(aliasOf(token.sessionId))
        val payload = ownPayload(token.sessionId, myLabel)
        runCatching { exchange.registerToken(token, myUid, payload) }
            .onFailure { discardAttemptKey(token.sessionId) }
            .getOrThrow()
        // QR carries the token only: real attestation chains overflow QR capacity (2953 B
        // ceiling), so the payload rides in the token document and is read by the partner.
        return Initiation(token, payload, PairingCodec.encode(token))
    }

    /**
     * Initiator step 2 (§10 step 2): wait for the partner's response on the token document,
     * then verify the partner's material (attestation + transcript signature). Returns null
     * on timeout or a failed verification (in which case the token is deleted).
     *
     * [timeoutMillis] is the nominal human-facing wait, NOT the server's deadline: the token's
     * `expiresAt` was minted from this device's clock, and the rules compare it against the
     * server's clock. A device running fast therefore mints an expiry the server honours later
     * than this device's own TTL would suggest — with a fixed wait the initiator declared the
     * invitation dead while the server still accepted the partner's write (observed on the rig,
     * 2026-10-23: creator clock +3m42s, initiator gave up 3m42s early). The wait carries a skew
     * allowance so the rules' denial — the only clock both sides agree on — is what ends it;
     * the allowance then only bounds a pathological clock offset. Polls that end in `Denied`
     * mean the rules' TTL gate fired (or App Check refused the read, which already terminated
     * the wait before this change too).
     */
    suspend fun awaitPartner(token: PairingToken, timeoutMillis: Long): InitiatorAwait? {
        val response = exchange.pollTokenResponse(token, timeoutMillis + CLOCK_SKEW_ALLOWANCE_MILLIS)
        if (response == null) {
            // Timeout ends the attempt: the token is dead server-side (its TTL lapses, so it
            // is deliberately not deleted), and the screen drops its token reference — no
            // back-out or retry can reach cancel from here, so the key must go now
            // (§19.5 / ADR #106).
            discardAttemptKey(token.sessionId)
            return null
        }
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
            discardAttemptKey(token.sessionId)
            return null
        }
        val partnerAgreementKey = try {
            EnrollmentTranscript.parsePublicKey(response.partnerPayload.agreementPublicKey)
        } catch (e: Exception) {
            Log.w(TAG, "initiator rejected partner material: agreement key unparseable — ${e.message}")
            exchange.deleteToken(token)
            discardAttemptKey(token.sessionId)
            return null
        }
        return InitiatorAwait(
            sessionId = token.sessionId,
            partnerUid = response.partnerUid,
            sas = enrollmentFlow.deriveSas(token.sessionId, agreementPublicKey(aliasOf(token.sessionId)), partnerAgreementKey),
            transcriptBytes = transcript,
            partnerSigningPublicKey = verification.signingPublicKey,
            partnerAgreementPublicKey = partnerAgreementKey,
            partnerLabel = response.partnerPayload.label,
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
        // Any terminal outcome that is not Paired means this attempt is over: its per-pair
        // key would never be reachable from an enrollment again (§19.5 / ADR #106).
        val outcome = try {
            submitInitiatorSignatureInternal(await, token, myUid, signature)
        } catch (t: Throwable) {
            // A throw (e.g. a transport failure inside the activation wait) is terminal too,
            // and the screen clears its token reference on failure — cancel is unreachable
            // from there, so this catch is the discard's only remaining chance.
            discardAttemptKey(await.sessionId)
            throw t
        }
        if (outcome !is Outcome.Paired) discardAttemptKey(await.sessionId)
        return outcome
    }

    private suspend fun submitInitiatorSignatureInternal(
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
                    await.partnerLabel,
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
                            await.partnerLabel,
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
     * Partner half (§10 step 2), one-shot form: [prepareJoin] then [finishJoin] with the
     * caller's signature. Kept as the protocol's atomic entry (and the debug harness's
     * path); the production ceremony uses the two phases so the human SAS check gates the
     * signature.
     */
    suspend fun complete(
        qrBytes: ByteArray,
        myUid: String,
        signature: Signature,
        myLabel: String? = null,
    ): Outcome =
        when (val prepared = prepareJoin(qrBytes, myUid)) {
            is JoinPreparationResult.Ready -> finishJoin(prepared.preparation, myUid, signature, myLabel)
            is JoinPreparationResult.Failed -> prepared.outcome
        }

    /**
     * User-initiated cancellation of an in-flight ceremony: the token is deleted, so the code
     * and QR both die with it — a cancelled invitation is never joinable, and the partner's
     * next read reports it as no-longer-active instead of waiting out the TTL. Used by the
     * ceremony UI on cancel and on a failed SAS comparison; a leftover token (process death)
     * is still bounded by the 10-minute TTL and the scheduled cleanup.
     */
    suspend fun cancel(token: PairingToken) {
        exchange.deleteToken(token)
        // The token is gone, so this attempt can never finalize: its key is unreachable now.
        discardAttemptKey(token.sessionId)
    }

    /**
     * The ceremony's back-out path: the screen leaves composition the moment it exits, taking
     * its remembered scope with it, so a cancel launched there dies before the network call
     * (observed on the X6532, 2026-09-28). The flow outlives every screen, so the deletion
     * runs here — the token must not stay joinable.
     */
    fun cancelInBackground(token: PairingToken) {
        cancelScope.launch {
            runCatching { cancel(token) }.onFailure { Log.w(TAG, "background cancel failed", it) }
        }
    }

    private val cancelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One-shot typed-code form of [complete] (§19.4 item 1). */
    suspend fun completeWithCode(
        code: String,
        myUid: String,
        signature: Signature,
        myLabel: String? = null,
    ): Outcome =
        when (val prepared = prepareJoinWithCode(code, myUid)) {
            is JoinPreparationResult.Ready -> finishJoin(prepared.preparation, myUid, signature, myLabel)
            is JoinPreparationResult.Failed -> prepared.outcome
        }

    /**
     * Partner half, phase 1: decode the QR, read the initiator's material from the token
     * document, verify its hardware identity, and derive the SAS — no signature, no write.
     * Everything this returns was verified; a rejection deletes the dead token, as before.
     */
    suspend fun prepareJoin(qrBytes: ByteArray, myUid: String): JoinPreparationResult {
        val content = try {
            PairingCodec.decode(qrBytes)
        } catch (e: Exception) {
            return JoinPreparationResult.Failed(Outcome.Rejected("malformed pairing QR: ${e.message}"))
        }
        val token = content.token
        if (token.isExpired(nowEpochMillis())) {
            return JoinPreparationResult.Failed(Outcome.TokenExpired(token.sessionId))
        }
        return when (val read = exchange.readToken(token)) {
            is PairingExchange.TokenReadResult.Found -> {
                if (read.partnerResponse != null) {
                    // Another device (or our own earlier attempt) already responded; the
                    // response write would be denied anyway — fail before signing.
                    return JoinPreparationResult.Failed(Outcome.TokenAlreadyUsed(token.sessionId))
                }
                verifyInitiatorMaterial(myUid, token, read.creatorUid, read.initiatorPayload)
            }
            PairingExchange.TokenReadResult.NotFound ->
                JoinPreparationResult.Failed(Outcome.TokenAlreadyUsed(token.sessionId))
            PairingExchange.TokenReadResult.Denied ->
                JoinPreparationResult.Failed(Outcome.TokenExpired(token.sessionId))
            is PairingExchange.TokenReadResult.Failed ->
                JoinPreparationResult.Failed(Outcome.Rejected(read.reason))
        }
    }

    /**
     * Typed-code phase 1 (§19.4 item 1 — the V1 entry point, alongside the QR): the code
     * decodes to the same token value the QR would carry, so from the first read onward the
     * path is [prepareJoin]'s. The token is hydrated from the document (the code cannot
     * carry a session id or expiry).
     */
    suspend fun prepareJoinWithCode(code: String, myUid: String): JoinPreparationResult {
        val tokenBytes = when (val parsed = PairingCode.parse(code)) {
            is PairingCode.ParseResult.Invalid ->
                return JoinPreparationResult.Failed(Outcome.Rejected("invalid invitation code: ${parsed.reason}"))
            is PairingCode.ParseResult.Valid -> parsed.tokenBytes
        }
        return when (val read = exchange.readTokenByValue(tokenBytes)) {
            is PairingExchange.TokenReadResult.Found -> {
                if (read.partnerResponse != null) {
                    return JoinPreparationResult.Failed(Outcome.TokenAlreadyUsed(null))
                }
                val token = PairingToken(tokenBytes, read.initiatorPayload.sessionId, read.expiresAtEpochMillis)
                if (token.isExpired(nowEpochMillis())) {
                    return JoinPreparationResult.Failed(Outcome.TokenExpired(token.sessionId))
                }
                verifyInitiatorMaterial(myUid, token, read.creatorUid, read.initiatorPayload)
            }
            PairingExchange.TokenReadResult.NotFound ->
                JoinPreparationResult.Failed(Outcome.TokenAlreadyUsed(null))
            PairingExchange.TokenReadResult.Denied ->
                JoinPreparationResult.Failed(Outcome.TokenExpired(null))
            is PairingExchange.TokenReadResult.Failed ->
                JoinPreparationResult.Failed(Outcome.Rejected(read.reason))
        }
    }

    private suspend fun verifyInitiatorMaterial(
        myUid: String,
        token: PairingToken,
        initiatorUid: String,
        initiatorPayload: EnrollmentPayload,
    ): JoinPreparationResult {
        // Owner report 2026-11-09: nothing stopped a second ceremony between two people who
        // are already actively paired — the refusal only arrived at registerPairing, after
        // the safety code. The pair record is deterministic from the two uids, and the joiner
        // is a member of it, so the state is readable here. The token is deliberately NOT
        // deleted: the inviter may have meant it for someone else.
        if (exchange.existingActivePair(myUid, initiatorUid)) {
            return JoinPreparationResult.Failed(Outcome.Rejected("PAIRING_ACTIVE"))
        }
        // The initiator's enrollment payload returns from the token document — the QR
        // cannot carry it (attestation chains overflow QR capacity, 2953 B ceiling).
        val initiatorSigningPublicKey = when (val identity = enrollmentFlow.verifyHardwareIdentity(initiatorPayload)) {
            is EnrollmentFlow.PartnerVerification.Rejected -> {
                exchange.deleteToken(token)
                return JoinPreparationResult.Failed(Outcome.Rejected(identity.reason))
            }
            is EnrollmentFlow.PartnerVerification.Verified -> identity.signingPublicKey
        }
        val initiatorAgreementKey = try {
            EnrollmentTranscript.parsePublicKey(initiatorPayload.agreementPublicKey)
        } catch (e: Exception) {
            exchange.deleteToken(token)
            return JoinPreparationResult.Failed(Outcome.Rejected("initiator agreement key unparseable"))
        }
        // The joiner's device identity must exist before the ceremony's biometric sign and
        // [finishJoin]'s key reads; minted past every rejection return above, so an aborted
        // join writes no device state it does not need yet.
        ensureDeviceIdentity()
        // The attempt's own key is minted only now — every rejection above returns before
        // this point, so an aborted join leaves no orphan key behind.
        generateAgreementKey(aliasOf(token.sessionId))
        return JoinPreparationResult.Ready(
            JoinPreparation(
                token = token,
                initiatorUid = initiatorUid,
                initiatorSigningPublicKey = initiatorSigningPublicKey,
                initiatorAgreementKey = initiatorAgreementKey,
                initiatorLabel = initiatorPayload.label,
                transcriptBytes = transcriptBytes(token.sessionId, initiatorPayload.agreementPublicKey),
                sas = enrollmentFlow.deriveSas(token.sessionId, agreementPublicKey(aliasOf(token.sessionId)), initiatorAgreementKey),
            ),
        )
    }

    /**
     * Partner half, phase 2: sign the transcript + proof with the caller's biometric
     * [Signature], respond on the token (single-use, rules-enforced), submit the proof, and
     * finalize once the pairing document is active. Both messages are signed inside one
     * warm window — with the SAS check already done by the caller, which is why this half
     * must only run after [JoinPreparationResult.Ready].
     */
    suspend fun finishJoin(
        preparation: JoinPreparation,
        myUid: String,
        signature: Signature,
        myLabel: String? = null,
    ): Outcome {
        val outcome = try {
            finishJoinInternal(preparation, myUid, signature, myLabel)
        } catch (t: Throwable) {
            // Same as the initiator half: a throw is terminal and bypasses every other
            // discard path (§19.5 / ADR #106).
            discardAttemptKey(preparation.token.sessionId)
            throw t
        }
        if (outcome !is Outcome.Paired) discardAttemptKey(preparation.token.sessionId)
        return outcome
    }

    private suspend fun finishJoinInternal(
        preparation: JoinPreparation,
        myUid: String,
        signature: Signature,
        myLabel: String? = null,
    ): Outcome {
        val token = preparation.token
        // Re-check expiry: the SAS conversation takes human time, and the rules enforce the
        // document TTL server-side — surface it as the typed outcome rather than a denial.
        if (token.isExpired(nowEpochMillis())) return Outcome.TokenExpired(token.sessionId)
        val partnerTranscriptSignature = enrollmentFlow.signMessage(signature, preparation.transcriptBytes)
        val proof = buildProof(myUid, preparation.initiatorUid, preparation.transcriptBytes, signature)
        when (
            val responded = exchange.respondOnToken(
                token,
                myUid,
                ownPayload(token.sessionId, myLabel),
                partnerTranscriptSignature,
            )
        ) {
            PairingExchange.RespondResult.AlreadyUsed -> return Outcome.TokenAlreadyUsed(token.sessionId)
            is PairingExchange.RespondResult.Failed -> return Outcome.Rejected(responded.reason)
            PairingExchange.RespondResult.Written -> Unit
        }
        val durablePairId = EnrollmentTranscript.durablePairIdOf(myUid, preparation.initiatorUid)
        return when (val submitted = registration.submit(proof)) {
            is PairingRegistration.SubmitResult.Registered -> {
                if (submitted.pairId != durablePairId) {
                    return Outcome.Rejected("registration bound an unexpected pair id")
                }
                exchange.deleteToken(token)
                val sas = finalizePairing(
                    token.sessionId,
                    durablePairId,
                    preparation.initiatorSigningPublicKey,
                    preparation.initiatorAgreementKey,
                    submitted.pairingEpoch,
                    preparation.initiatorLabel,
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
                            preparation.initiatorSigningPublicKey,
                            preparation.initiatorAgreementKey,
                            status.pairingEpoch,
                            preparation.initiatorLabel,
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

    private fun ownPayload(sessionId: String, label: String?): EnrollmentPayload {
        val chain = signingCertificateChain()
        val challenge = AttestationVerifier.embeddedChallengeOf(chain[0])
            ?: error("signing key certificate lacks the attestation challenge")
        return EnrollmentPayload(
            sessionId = sessionId,
            signingChain = chain.map { it.encoded },
            attestationChallenge = challenge,
            agreementPublicKey = EnrollmentTranscript.pubKeyBytes(agreementPublicKey(aliasOf(sessionId))),
            // Sanitized here, once, for every path that builds our payload.
            label = PartnerLabel.sanitize(label),
        )
    }

    private fun transcriptBytes(sessionId: String, partnerAgreementPubKeyBytes: ByteArray): ByteArray =
        EnrollmentTranscript.transcript(
            sessionId,
            EnrollmentTranscript.pubKeyBytes(agreementPublicKey(aliasOf(sessionId))),
            partnerAgreementPubKeyBytes,
        )

    private fun aliasOf(sessionId: String): String =
        AccountabilityKeyStore.pairAgreementAliasOf(sessionId)

    /** Best-effort cleanup — a Keystore hiccup must never mask the pairing outcome itself. */
    private fun discardAttemptKey(sessionId: String) {
        runCatching { destroyAgreementKey(aliasOf(sessionId)) }
            .onFailure { Log.w(TAG, "attempt key cleanup failed for session $sessionId", it) }
    }

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
        // Same chain the enrollment payload carries — the server re-verifies it against
        // the pinned roots (owner directive 2026-09-27) and demands challenge equality.
        val chain = signingCertificateChain()
        val challenge = AttestationVerifier.embeddedChallengeOf(chain[0])
            ?: error("signing key certificate lacks the attestation challenge")
        return PairingProof(
            ownUid = ownUid,
            partnerUid = partnerUid,
            publicKeyHash = publicKeyHash,
            transcriptHash = transcriptHash,
            protocolVersion = PairingProof.PROTOCOL_VERSION,
            signingPublicKey = mySigningPubKeyBytes,
            signature = enrollmentFlow.signMessage(signature, message),
            attestationChain = chain.map { it.encoded },
            attestationChallenge = challenge,
            // §9: one id per built proof; the submit path retries with this same proof,
            // so the server-side replay cache sees the identical requestId.
            requestId = UUID.randomUUID().toString(),
        )
    }

    /** §10 step 2 endgame: derive the CEK, persist the enrollment record, store the pairing key. */
    private suspend fun finalizePairing(
        sessionId: String,
        durablePairId: String,
        partnerSigningPublicKey: PublicKey,
        partnerAgreementPublicKey: PublicKey,
        pairingEpoch: Int,
        partnerLabel: String?,
    ): String {
        // ADR #106: the CEK is derived over the DURABLE pair id, exactly as EventCreator and
        // the mailbox receiver re-derive it — the vault's pairing key is HKDF of the same CEK
        // the runtime encrypts under, not of a session-scoped variant.
        val cek = enrollmentFlow.deriveCek(durablePairId, agreementPrivateKey(aliasOf(sessionId)), partnerAgreementPublicKey)
        val mySigningPubKeyBytes = EnrollmentTranscript.pubKeyBytes(signingPublicKey())
        // Best effort, pre-durable: stale rows are a display defect, never a reason to fail a
        // pairing whose keys are already agreed.
        runCatching { prunePartnerActivity(durablePairId) }
            .onFailure { Log.w(TAG, "pairing-time activity prune failed for $durablePairId", it) }
        enrollmentDao.upsert(
            EnrollmentRecord(
                pairId = durablePairId,
                mySenderKeyId = EnrollmentTranscript.senderKeyId(mySigningPubKeyBytes),
                partnerSigningKeyId = EnrollmentTranscript.senderKeyId(partnerSigningPublicKey.encoded),
                partnerSigningPubKey = partnerSigningPublicKey.encoded,
                partnerAgreementPubKey = partnerAgreementPublicKey.encoded,
                createdAtEpochMillis = nowEpochMillis(),
                pairingEpoch = pairingEpoch,
                partnerLabel = partnerLabel,
                myAgreementKeyAlias = aliasOf(sessionId),
            ),
        )
        // Post-durable, like the two writes below: the canary must never be able to fail a
        // pairing that is already committed. A miss here re-mints on the next app start from
        // the enrollment (AccountabilityModule's detector wiring).
        runCatching { markPairingCompleted() }
            .onFailure { Log.w(TAG, "pairing canary write failed for $durablePairId", it) }
        // Post-durable like the snapshot below: the enrollment row and agreement key are already
        // committed, and the vault value is derivable from them (HKDF of the CEK), so a vault
        // write failure (disk-full class) is logged and swallowed — before this guard it threw
        // out of finalizePairing and the ceremony rendered the connectivity failure copy over a
        // successfully paired device (finding #12, 2026-10-16).
        runCatching { secretsRepository.storePairingKey(enrollmentFlow.derivePairingKey(cek)) }
            .onFailure { Log.w(TAG, "pairing-key vault write failed for $durablePairId", it) }
        // Post-durable: the enrollment and key are already committed, so a snapshot failure is
        // logged and swallowed — the pairing outcome must reflect the pairing, not the extra.
        runCatching { onEnrolled(durablePairId) }
            .onFailure { Log.w(TAG, "pairing snapshot emission failed for $durablePairId", it) }
        return enrollmentFlow.deriveSas(sessionId, agreementPublicKey(aliasOf(sessionId)), partnerAgreementPublicKey)
    }

    companion object {
        /** Same tag as the harness so one `adb logcat -s MyExodusPairing` stream captures both. */
        const val TAG = "MyExodusPairing"

        /** Straggler window for the partner's proof after our own registered as pending. */
        const val ACTIVATION_WAIT_MILLIS = 30_000L

        /**
         * Tolerance added to the initiator's nominal wait. `expiresAt` is minted on the creator's
         * clock while the rules judge it on the server's; a fast creator clock lengthens the
         * server-side window by the same offset. Wide enough for any realistically skewed phone,
         * small enough that a broken clock cannot keep the invitation screen open indefinitely.
         */
        const val CLOCK_SKEW_ALLOWANCE_MILLIS = 5 * 60_000L
    }
}
