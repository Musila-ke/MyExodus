package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.sync.FirestoreSchema
import com.example.myexodus.accountability.sync.PairingExchange
import java.security.PrivateKey
import java.security.Signature
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §10 pairing end-to-end, v2 model (§8 Step 3). Both devices run the real [PairingFlow]
 * against a fake relay + fake registerPairing that mirror the deployed shapes: token
 * document as transport (single-use response, rules-enforced), signed proofs to the
 * function, activation on matching transcript hashes within the window. The durable
 * pairId is min_max(uids), computed independently on both sides and by the "server".
 * The attestation verifier is stubbed per the §12 on-device convention.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingFlowTest {

    @Test
    fun fullRoundTrip_pairsBothDevices_sameSas_durablePairId_tokenDeleted() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val partnerDao = FakeEnrollmentDao()
        val initiatorSecrets = FakeSecrets()
        val partnerSecrets = FakeSecrets()
        val initiatorFlow = flow(initiator, cloud, testScheduler, initiatorDao, initiatorSecrets) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, partnerDao, partnerSecrets) { nowEpochMillis }

        val initiation = initiatorFlow.initiate("uid-a")
        val sessionId = initiation.token.sessionId
        val durablePairId = EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b")

        val partnerJob = async {
            partnerFlow.complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000)) {
            "initiator never saw the partner's token response"
        }
        val initiatorOutcome = initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", signing(initiator.signing.private))
        val partnerOutcome = partnerJob.await()

        // §6.1: both devices agree on the same SAS for the out-of-band comparison; the
        // pair is keyed by the AD-02 durable id, not the per-attempt session id.
        assertEquals(PairingFlow.Outcome.Paired(durablePairId, await.sas), initiatorOutcome)
        val partnerPaired = partnerOutcome as PairingFlow.Outcome.Paired
        assertEquals(durablePairId, partnerPaired.pairId)
        assertEquals(await.sas, partnerPaired.sas)
        assertEquals(5, await.sas.length)
        assertEquals(sessionId, await.sessionId)
        assertTrue("QR carries only the token (payload rides the token doc)", initiation.qrBytes.size < 200)
        assertEquals("active", cloud.pairings.getValue(durablePairId).status)

        // Both sides persisted the mirror enrollment record and the same pairing key.
        val initiatorRecord = checkNotNull(initiatorDao.getByPairId(durablePairId))
        val partnerRecord = checkNotNull(partnerDao.getByPairId(durablePairId))
        assertArrayEquals(partner.signing.public.encoded, initiatorRecord.partnerSigningPubKey)
        assertArrayEquals(initiator.signing.public.encoded, partnerRecord.partnerSigningPubKey)
        assertEquals(initiatorRecord.mySenderKeyId, partnerRecord.partnerSigningKeyId)
        assertArrayEquals(
            checkNotNull(initiatorSecrets.pairingKey.value),
            checkNotNull(partnerSecrets.pairingKey.value),
        )
        assertEquals(32, checkNotNull(partnerSecrets.pairingKey.value).size)

        assertTrue("token deleted after activation", cloud.tokenDocs.isEmpty())
    }

    /**
     * §19.5 (ADR #106): each pairing owns its agreement key — generated under a session-scoped
     * alias at the start of the attempt and recorded on the enrollment — so a later unpair
     * shreds exactly one pair and every CEK derivation resolves the same key.
     */
    @Test
    fun fullRoundTrip_generatesOnePairKeyPerSide_recordsItsAliasOnBothEnrollments() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val partnerDao = FakeEnrollmentDao()
        val initiatorGenerated = mutableListOf<String>()
        val partnerGenerated = mutableListOf<String>()
        val initiatorFlow = flow(initiator, cloud, testScheduler, initiatorDao, FakeSecrets(),
            generateAgreementKey = initiatorGenerated::add) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, partnerDao, FakeSecrets(),
            generateAgreementKey = partnerGenerated::add) { nowEpochMillis }

        val initiation = initiatorFlow.initiate("uid-a")
        val durablePairId = EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b")
        val alias = AccountabilityKeyStore.pairAgreementAliasOf(initiation.token.sessionId)

        val partnerJob = async { partnerFlow.complete(initiation.qrBytes, "uid-b", signing(partner.signing.private)) }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", signing(initiator.signing.private))
        assertTrue(partnerJob.await() is PairingFlow.Outcome.Paired)

        assertEquals(listOf(alias), initiatorGenerated)
        assertEquals(listOf(alias), partnerGenerated)
        assertEquals(alias, initiatorDao.getByPairId(durablePairId)!!.myAgreementKeyAlias)
        assertEquals(alias, partnerDao.getByPairId(durablePairId)!!.myAgreementKeyAlias)
    }

    /**
     * Regression (2026-09-28, two-device rig): production had no caller of the keystore's
     * `ensureKeys`, so a fresh install carried no signing key — invite threw inside the
     * ceremony's catch-all (rendered as "Couldn't reach the server") and the joiner's
     * biometric sign returned null, leaving both phones stuck. Each role must mint the
     * device identity at its own entry point, exactly once, and only for a viable join.
     */
    @Test
    fun bothRoles_mintTheDeviceIdentity_atTheirEntryPoint_onlyForAViableJoin() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        var initiatorMints = 0
        var partnerMints = 0
        fun minted(): AccountabilityKeyStore.GenerationResult =
            AccountabilityKeyStore.GenerationResult.Success(strongBox = false)
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets(),
            ensureIdentityKeys = { initiatorMints += 1; minted() }) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets(),
            ensureIdentityKeys = { partnerMints += 1; minted() }) { nowEpochMillis }

        val initiation = initiatorFlow.initiate("uid-a")
        assertEquals("invite mints before reading its own chain", 1, initiatorMints)

        // A dead attempt mints nothing: an unregistered token is rejected before the mint point.
        val dead = partnerFlow.prepareJoinWithCode(
            PairingCode.encode(PairingToken.generate(nowEpochMillis)),
            "uid-b",
        )
        assertTrue(dead is PairingFlow.JoinPreparationResult.Failed)
        assertEquals("a rejected join writes no device state", 0, partnerMints)

        val partnerJob = async {
            partnerFlow.completeWithCode(PairingCode.encode(initiation.token), "uid-b", signing(partner.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        val initiatorOutcome =
            initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", signing(initiator.signing.private))
        assertTrue(partnerJob.await() is PairingFlow.Outcome.Paired)
        assertTrue(initiatorOutcome is PairingFlow.Outcome.Paired)
        assertEquals("join mints exactly once", 1, partnerMints)
        assertEquals("one mint per attempt, not per key read", 1, initiatorMints)
    }

    @Test
    fun typedCode_fullRoundTrip_matchesTheQrPath() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val partnerDao = FakeEnrollmentDao()
        val initiatorFlow = flow(initiator, cloud, testScheduler, initiatorDao, FakeSecrets()) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, partnerDao, FakeSecrets()) { nowEpochMillis }

        val initiation = initiatorFlow.initiate("uid-a")
        val durablePairId = EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b")
        // §19.4 item 1: the code is a rendering of the same token, so typed entry with real
        // input noise (lowercase, I/L and O aliases, spaces as separators) must reach the
        // identical exchange the QR reaches — same session id, same SAS, same durable pair.
        val typed = PairingCode.encode(initiation.token)
            .lowercase()
            .replace('1', 'l')
            .replace('0', 'o')
            .replace("-", " ")

        val partnerJob = async { partnerFlow.completeWithCode(typed, "uid-b", signing(partner.signing.private)) }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        val initiatorOutcome = initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", signing(initiator.signing.private))
        val partnerOutcome = partnerJob.await()

        assertEquals(PairingFlow.Outcome.Paired(durablePairId, await.sas), initiatorOutcome)
        val partnerPaired = partnerOutcome as PairingFlow.Outcome.Paired
        assertEquals(durablePairId, partnerPaired.pairId)
        assertEquals(await.sas, partnerPaired.sas)
        assertEquals("active", cloud.pairings.getValue(durablePairId).status)
        // A successful activation proves the code carried the session id correctly: the
        // transcript hashes only match if both sides signed over the same session.
        val initiatorRecord = checkNotNull(initiatorDao.getByPairId(durablePairId))
        val partnerRecord = checkNotNull(partnerDao.getByPairId(durablePairId))
        assertEquals(initiatorRecord.mySenderKeyId, partnerRecord.partnerSigningKeyId)
        assertTrue("token deleted after activation", cloud.tokenDocs.isEmpty())
    }

    @Test
    fun typedCode_badCheckCharacter_isRejectedBeforeTheRelay() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .initiate("uid-a")

        // 'A' (index 10) → 'B' (11): the weighted sum is unchanged, the check mismatches.
        val outcome = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .completeWithCode("1234-5678-9ABC-B", "uid-b", signing(partner.signing.private))
        assertEquals(PairingFlow.Outcome.Rejected("invalid invitation code: CHECK"), outcome)
        assertTrue("nothing consumed before the local check", cloud.tokenDocs.isNotEmpty())
    }

    @Test
    fun typedCode_unknownToken_isAlreadyUsedWithNoSessionId() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val partner = DeviceKeys()

        // A well-formed code for a token that was never registered: the document read finds
        // nothing, and the failure precedes any session knowledge — hence the null session id.
        val unregistered = PairingCode.encode(PairingToken.generate(nowEpochMillis))
        val outcome = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .completeWithCode(unregistered, "uid-b", signing(partner.signing.private))
        assertEquals(PairingFlow.Outcome.TokenAlreadyUsed(null), outcome)
    }

    @Test
    fun typedCode_expiredToken_isExpired() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiation = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .initiate("uid-a")

        nowEpochMillis += PairingToken.TTL_MILLIS + 1
        val outcome = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .completeWithCode(PairingCode.encode(initiation.token), "uid-b", signing(partner.signing.private))
        // Rules deny reads of expired documents, so the session id never reaches this side.
        assertEquals(PairingFlow.Outcome.TokenExpired(null), outcome)
        assertTrue("expired token never consumed", cloud.tokenDocs.isNotEmpty())
    }

    @Test
    fun typedCode_alreadyRespondedToken_isAlreadyUsed() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val firstPartner = DeviceKeys()
        val secondPartner = DeviceKeys()
        val initiation = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .initiate("uid-a")

        cloud.tokenDocs.getValue(FirestoreSchema.pairingTokenDocumentId(initiation.token)).partnerResponse =
            PairingExchange.PartnerResponse("uid-x", ownPayload(firstPartner, initiation.token.sessionId), ByteArray(8))

        val outcome = flow(secondPartner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .completeWithCode(PairingCode.encode(initiation.token), "uid-b", signing(secondPartner.signing.private))
        assertEquals(PairingFlow.Outcome.TokenAlreadyUsed(null), outcome)
        assertTrue("conflicting token response untouched", cloud.pairings.isEmpty())
    }

    @Test
    fun expiredToken_neverTouchesTheRelay() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        nowEpochMillis += PairingToken.TTL_MILLIS + 1 // §10 step 1: time-limited
        val outcome = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))

        assertEquals(PairingFlow.Outcome.TokenExpired(initiation.token.sessionId), outcome)
        assertTrue("expired token never consumed", cloud.tokenDocs.isNotEmpty())
    }

    @Test
    fun alreadyRespondedToken_isAlreadyUsedWithoutSigning() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val firstPartner = DeviceKeys()
        val secondPartner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        // Another device already answered on the token document (single-use).
        cloud.tokenDocs.getValue(FirestoreSchema.pairingTokenDocumentId(initiation.token)).partnerResponse =
            PairingExchange.PartnerResponse("uid-x", ownPayload(firstPartner, initiation.token.sessionId), ByteArray(8))

        val outcome = flow(secondPartner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .complete(initiation.qrBytes, "uid-b", signing(secondPartner.signing.private))
        assertEquals(PairingFlow.Outcome.TokenAlreadyUsed(initiation.token.sessionId), outcome)
        assertTrue("conflicting token response untouched", cloud.pairings.isEmpty())
    }

    @Test
    fun responseWriteDeniedBySingleUseRule_isAlreadyUsed() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        cloud.denyNextRespond = true // concurrent second responder: rules deny the write
        val outcome = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        assertEquals(PairingFlow.Outcome.TokenAlreadyUsed(initiation.token.sessionId), outcome)
        assertTrue(cloud.pairings.isEmpty())
    }

    @Test
    fun corruptPartnerPayload_initiatorAbortsAndDeletesTheToken() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDestroyed = mutableListOf<String>()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets(),
            destroyAgreementKey = initiatorDestroyed::add) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")
        val alias = AccountabilityKeyStore.pairAgreementAliasOf(initiation.token.sessionId)

        cloud.corruptPartnerPayload = true
        val partnerJob = async {
            flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
                .complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        assertNull(
            "initiator rejects unverifiable partner material",
            initiatorFlow.awaitPartner(initiation.token, 30_000),
        )
        assertTrue("abort deletes the token document", cloud.tokenDocs.isEmpty())
        assertTrue("no pairing ever activates", cloud.pairings.values.none { it.status == "active" })
        assertTrue("partner never sees activation", partnerJob.await() is PairingFlow.Outcome.Rejected)
        assertEquals("the aborted attempt key is destroyed", listOf(alias), initiatorDestroyed)
    }

    @Test
    fun corruptPartnerTranscriptSignature_initiatorAbortsAndDeletesTheToken() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        cloud.corruptPartnerResponseSignature = true
        val partnerJob = async {
            flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
                .complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        assertNull(
            "initiator rejects a forged transcript signature",
            initiatorFlow.awaitPartner(initiation.token, 30_000),
        )
        assertTrue(cloud.tokenDocs.isEmpty())
        assertTrue(partnerJob.await() is PairingFlow.Outcome.Rejected)
    }

    @Test
    fun transcriptMismatchAtTheFunction_rejectsBothDevices_noEnrollmentRecord() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val initiatorFlow = flow(initiator, cloud, testScheduler, initiatorDao, FakeSecrets()) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        // The initiator's proof arrives with a transcript hash the function cannot match
        // to the partner's stored proof (mirrors the server's TRANSCRIPT_MISMATCH).
        cloud.tamperTranscriptHashForUid = "uid-a"
        val partnerJob = async {
            partnerFlow.complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        val initiatorOutcome = initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", signing(initiator.signing.private))

        assertEquals(PairingFlow.Outcome.Rejected("TRANSCRIPT_MISMATCH"), initiatorOutcome)
        assertTrue("no enrollment record persisted", initiatorDao.records.isEmpty())
        assertEquals("pending", cloud.pairings.getValue(EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b")).status)
        assertTrue(partnerJob.await() is PairingFlow.Outcome.Rejected)
    }

    @Test
    fun partnerRegistrationLost_initiatorSignsIntoPending_andAborts() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val initiatorFlow = flow(initiator, cloud, testScheduler, initiatorDao, FakeSecrets()) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        // The partner answers on the token but its proof call dies: only one proof ever
        // reaches the function, so the pairing can never activate.
        cloud.failNextRegistration = true
        val partnerJob = async {
            partnerFlow.complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        val initiatorOutcome = initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", signing(initiator.signing.private))

        assertTrue(initiatorOutcome is PairingFlow.Outcome.Rejected)
        assertTrue("no enrollment record persisted", initiatorDao.records.isEmpty())
        assertTrue(partnerJob.await() is PairingFlow.Outcome.Rejected)
    }

    @Test
    fun qrClaimingADifferentSession_isRejectedBeforeTheRelay() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        val content = PairingCodec.decode(initiation.qrBytes)
        val tampered = PairingCodec.encodeWithSessionId(content.token, "deadbeefdeadbeef")

        val outcome = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .complete(tampered, "uid-b", signing(partner.signing.private))
        assertTrue(outcome is PairingFlow.Outcome.Rejected)
        assertTrue("nothing consumed before the codec check", cloud.tokenDocs.isNotEmpty())
    }

    @Test
    fun awaitPartner_timesOutWhenNobodyResponds() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        assertNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        assertTrue(cloud.pairings.isEmpty())
        assertFalse("timeout alone does not consume the token", cloud.tokenDocs.isEmpty())
    }

    /**
     * §7 step 14 / §19.4 item 4: the production ceremony splits the partner's half so the
     * humans compare the SAS *before* any signature exists. Preparation must therefore be
     * read-only — a token response written here would let a partner who never checked the
     * code bind the pair.
     */
    @Test
    fun twoPhaseJoin_exposesTheSasBeforeAnyWrite() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")
        val durablePairId = EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b")

        val prepared = partnerFlow.prepareJoin(initiation.qrBytes, "uid-b")
        val ready = prepared as PairingFlow.JoinPreparationResult.Ready
        assertEquals(initiation.token.sessionId, ready.preparation.token.sessionId)
        assertEquals("uid-a", ready.preparation.initiatorUid)
        assertEquals(5, ready.preparation.sas.length)
        assertNull(
            "no response may exist before the human check",
            cloud.tokenDocs.getValue(FirestoreSchema.pairingTokenDocumentId(initiation.token)).partnerResponse,
        )
        assertTrue("no proof may be submitted before the human check", cloud.pairings.isEmpty())

        val partnerJob = async {
            partnerFlow.finishJoin(ready.preparation, "uid-b", signing(partner.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        // Both humans are looking at the same five characters.
        assertEquals(await.sas, ready.preparation.sas)
        val initiatorOutcome = initiatorFlow.submitInitiatorSignature(
            await, initiation.token, "uid-a", signing(initiator.signing.private),
        )
        assertEquals(PairingFlow.Outcome.Paired(durablePairId, await.sas), initiatorOutcome)
        assertEquals(PairingFlow.Outcome.Paired(durablePairId, ready.preparation.sas), partnerJob.await())
    }

    /** §19.5 item 3: labels cross in both directions, sanitized at the point of entry — the
     *  partner's label lands on this device's record and vice versa. */
    @Test
    fun labels_areExchanged_andLandOnBothEnrollmentRecords() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val partnerDao = FakeEnrollmentDao()
        val initiatorFlow = flow(initiator, cloud, testScheduler, initiatorDao, FakeSecrets()) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, partnerDao, FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a", myLabel = "Ana Maria")
        val durablePairId = EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b")

        val ready = partnerFlow.prepareJoin(initiation.qrBytes, "uid-b")
            as PairingFlow.JoinPreparationResult.Ready
        assertEquals("Ana Maria", ready.preparation.initiatorLabel)

        // The partner types a name with a bidi override and padding: what crosses the wire is
        // already clean, and the initiator's dashboard shows exactly that.
        val partnerJob = async {
            partnerFlow.finishJoin(ready.preparation, "uid-b", signing(partner.signing.private), myLabel = " Brian\u202E ")
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        assertEquals("Brian", await.partnerLabel)
        initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", signing(initiator.signing.private))
        partnerJob.await()

        assertEquals("Brian", initiatorDao.getByPairId(durablePairId)?.partnerLabel)
        assertEquals("Ana Maria", partnerDao.getByPairId(durablePairId)?.partnerLabel)
    }

    /** A label-less pair (a build that predates names, or a user who declined) stores null and
     *  falls back to the key-id fragment at render time — it must never invent a name. */
    @Test
    fun noLabels_leaveBothRecordsNull() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val partnerDao = FakeEnrollmentDao()
        val initiatorFlow = flow(initiator, cloud, testScheduler, initiatorDao, FakeSecrets()) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, partnerDao, FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")
        val durablePairId = EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b")

        val partnerJob = async { partnerFlow.complete(initiation.qrBytes, "uid-b", signing(partner.signing.private)) }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", signing(initiator.signing.private))
        partnerJob.await()

        assertNull(await.partnerLabel)
        assertNull(initiatorDao.getByPairId(durablePairId)?.partnerLabel)
        assertNull(partnerDao.getByPairId(durablePairId)?.partnerLabel)
    }

    /**
     * The SAS conversation takes human time: the token can expire between preparation (the SAS
     * was shown) and finishJoin (the signature). The re-check exists so the user gets the typed
     * outcome instead of a server-side denial.
     */
    @Test
    fun finishJoin_afterTheTokenExpiredDuringTheHumanCheck_isTokenExpired() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        val ready = partnerFlow.prepareJoin(initiation.qrBytes, "uid-b")
            as PairingFlow.JoinPreparationResult.Ready
        nowEpochMillis += PairingToken.TTL_MILLIS + 1
        val outcome = partnerFlow.finishJoin(ready.preparation, "uid-b", signing(partner.signing.private))

        assertEquals(PairingFlow.Outcome.TokenExpired(initiation.token.sessionId), outcome)
        assertTrue("nothing written on the expired path", cloud.pairings.isEmpty())
        assertTrue("no response written either", cloud.tokenDocs.isNotEmpty())
    }

    /** §19.5 (ADR #106): the join attempt key is generated at preparation time; an abort at
     *  finish (expired token) must destroy it — nothing else ever will. */
    @Test
    fun finishJoin_whenTheOutcomeIsNotPaired_destroysTheAttemptKey() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val partnerGenerated = mutableListOf<String>()
        val partnerDestroyed = mutableListOf<String>()
        val partnerFlow = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets(),
            generateAgreementKey = partnerGenerated::add, destroyAgreementKey = partnerDestroyed::add) { nowEpochMillis }
        val initiation = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .initiate("uid-a")
        val alias = AccountabilityKeyStore.pairAgreementAliasOf(initiation.token.sessionId)

        val ready = partnerFlow.prepareJoin(initiation.qrBytes, "uid-b") as PairingFlow.JoinPreparationResult.Ready
        assertEquals("key generated with the preparation", listOf(alias), partnerGenerated)

        nowEpochMillis += PairingToken.TTL_MILLIS + 1
        val outcome = partnerFlow.finishJoin(ready.preparation, "uid-b", signing(partner.signing.private))

        assertEquals(PairingFlow.Outcome.TokenExpired(initiation.token.sessionId), outcome)
        assertEquals("the abandoned attempt key must not survive", listOf(alias), partnerDestroyed)
    }

    /** Cancelling an invitation kills the token: a stale QR or code is no longer joinable, and
     *  the partner's read reports it as gone rather than waiting out the TTL. */
    @Test
    fun cancel_deletesTheToken_andALateJoinerSeesItAsAlreadyUsed() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        initiatorFlow.cancel(initiation.token)
        assertTrue(cloud.tokenDocs.isEmpty())

        val outcome = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        assertEquals(PairingFlow.Outcome.TokenAlreadyUsed(initiation.token.sessionId), outcome)
        assertTrue(cloud.pairings.isEmpty())
    }

    /** §19.5 (ADR #106): the attempt key's alias never reaches an enrollment on cancel — the
     *  Keystore must not accumulate keys no record references. */
    @Test
    fun cancel_destroysTheAttemptKey() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val generated = mutableListOf<String>()
        val destroyed = mutableListOf<String>()
        val initiatorFlow = flow(DeviceKeys(), cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets(),
            generateAgreementKey = generated::add, destroyAgreementKey = destroyed::add) { nowEpochMillis }

        val initiation = initiatorFlow.initiate("uid-a")
        val alias = AccountabilityKeyStore.pairAgreementAliasOf(initiation.token.sessionId)
        initiatorFlow.cancel(initiation.token)

        assertEquals(listOf(alias), generated)
        assertEquals(listOf(alias), destroyed)
    }

    /**
     * The reason the ceremony cancels: the humans saw *different* SAS values (a relay
     * substituted material). The initiator's cancel must make the partner's signature — taken
     * against the prepared transcript — un-writable, so a mismatch can never bind a pair.
     */
    @Test
    fun cancelAfterTheSasWasSeen_thePartnerCannotBindThePair() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        val ready = partnerFlow.prepareJoin(initiation.qrBytes, "uid-b")
            as PairingFlow.JoinPreparationResult.Ready
        initiatorFlow.cancel(initiation.token)

        val outcome = partnerFlow.finishJoin(ready.preparation, "uid-b", signing(partner.signing.private))
        assertEquals(PairingFlow.Outcome.TokenAlreadyUsed(initiation.token.sessionId), outcome)
        assertTrue("no pair, no enrollment, no proof", cloud.pairings.isEmpty())
    }

    /**
     * The ceremony's back-out path (X6532, 2026-09-28): the screen leaves composition the
     * instant it exits, so a cancel launched on its remembered scope died before the network
     * call. [PairingFlow.cancelInBackground] must complete with no UI lifetime behind it.
     */
    @Test
    fun cancelInBackground_deletesTheToken_withNoCallerScopeAlive() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiatorFlow = flow(DeviceKeys(), cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")
        val docId = FirestoreSchema.pairingTokenDocumentId(initiation.token)
        assertTrue(cloud.tokenDocs.containsKey(docId))

        initiatorFlow.cancelInBackground(initiation.token)

        // The flow's cancel scope is a real dispatcher, so poll wall-clock, not the scheduler.
        val deadline = System.nanoTime() + 2_000_000_000
        while (cloud.tokenDocs.containsKey(docId) && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        assertFalse("the token must not stay joinable", cloud.tokenDocs.containsKey(docId))
    }

    private fun ownPayload(keys: DeviceKeys, sessionId: String) = pairingTestOwnPayload(keys, sessionId)

    private fun flow(
        keys: DeviceKeys,
        cloud: FakeCloud,
        scheduler: TestCoroutineScheduler,
        dao: FakeEnrollmentDao,
        secrets: FakeSecrets,
        generateAgreementKey: (String) -> Unit = { },
        destroyAgreementKey: (String) -> Unit = { },
        ensureIdentityKeys: () -> AccountabilityKeyStore.GenerationResult = {
            AccountabilityKeyStore.GenerationResult.Success(strongBox = false)
        },
        nowMillis: () -> Long,
    ): PairingFlow = pairingTestFlow(
        keys, cloud, scheduler, dao, secrets,
        nowMillis = nowMillis,
        generateAgreementKey = generateAgreementKey,
        destroyAgreementKey = destroyAgreementKey,
        ensureIdentityKeys = ensureIdentityKeys,
    )

    private fun signing(privateKey: PrivateKey): Signature = pairingTestSigning(privateKey)

}
