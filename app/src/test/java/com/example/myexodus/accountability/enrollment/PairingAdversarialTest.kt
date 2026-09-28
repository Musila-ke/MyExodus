package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.sync.FirestoreSchema
import java.util.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task #48 — the pairing adversarial matrix (user ask 2026-09-27: "stress test, try breaking,
 * think of edge cases as many as possible and scenarios"). Every test here is one matrix row
 * that the existing suites (`PairingFlowTest` 21, `PairingTokenTest`, `PairingCodeTest` 13,
 * the §12.2 emulator suite) did *not* already pin — the dedupe is deliberate, per the #47
 * precedent (`UsageLedgerAdversarialTest`).
 *
 * The relay/function semantics come from the shared [PairingTestHarness] so these rows exercise
 * byte-identical fakes to the main suite. The deployed expiry gate is `expiresAt > request.time`
 * — deny AT the deadline — which [PairingToken.isExpired] mirrors as `>=` (boundary fix landed
 * with this matrix; see ADR #102).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingAdversarialTest {

    // Row 1: the boundary millisecond itself. The client used to treat `now == expiresAt` as
    // live (strict `>`), while the deployed rules deny at it: the read path recovered as
    // TokenExpired, but the respond path surfaced the denial as "already used". One predicate
    // now agrees with the rules on every path.
    @Test
    fun expiryBoundary_deniedAtTheDeadlineInstant_onEveryEntryPoint() = runTest {
        var clock = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = pairingTestFlow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { clock }
        val initiation = initiatorFlow.initiate("uid-a")
        val deadline = initiation.token.expiresAtEpochMillis

        // One millisecond left: the SAS conversation is still legitimately in progress.
        clock = deadline - 1
        val partnerFlow = pairingTestFlow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { clock }
        val ready = partnerFlow.prepareJoin(initiation.qrBytes, "uid-b")
        assertTrue("pre-deadline preparation must succeed", ready is PairingFlow.JoinPreparationResult.Ready)

        clock = deadline
        assertEquals(
            "finishJoin at the deadline must surface the typed expiry, not a write denial",
            PairingFlow.Outcome.TokenExpired(initiation.token.sessionId),
            partnerFlow.finishJoin(
                (ready as PairingFlow.JoinPreparationResult.Ready).preparation,
                "uid-b",
                pairingTestSigning(partner.signing.private),
            ),
        )
        assertEquals(
            PairingFlow.JoinPreparationResult.Failed(PairingFlow.Outcome.TokenExpired(initiation.token.sessionId)),
            partnerFlow.prepareJoin(initiation.qrBytes, "uid-b"),
        )
        assertNull("the initiator's poll reads the denial as a plain timeout", initiatorFlow.awaitPartner(initiation.token, 30_000))

        assertTrue("expiry never consumes or deletes the token doc", cloud.tokenDocs.isNotEmpty())
        assertTrue(cloud.pairings.isEmpty())
    }

    // Row 2: a stale await from a previous ceremony (app restart / back mid-ceremony, then a
    // fresh invite) must not be signable into the new token — and refusing it must leave both
    // sessions untouched.
    @Test
    fun staleAwait_fromAnEarlierSession_isRefusedWithoutConsumingAnything() = runTest {
        var clock = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val initiatorFlow = pairingTestFlow(initiator, cloud, testScheduler, initiatorDao, FakeSecrets()) { clock }
        val partnerFlow = pairingTestFlow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { clock }

        val first = initiatorFlow.initiate("uid-a")
        val ready = partnerFlow.prepareJoin(first.qrBytes, "uid-b")
            as PairingFlow.JoinPreparationResult.Ready
        val partnerJob = async {
            partnerFlow.finishJoin(ready.preparation, "uid-b", pairingTestSigning(partner.signing.private))
        }
        val staleAwait = checkNotNull(initiatorFlow.awaitPartner(first.token, 30_000))

        // A second invitation exists; the user submits the FIRST ceremony's await against it.
        val second = initiatorFlow.initiate("uid-a")
        val stateBefore = cloud.pairings.mapValues { (_, state) -> state.proofs.keys.toSet() }
        val refused = runCatching {
            initiatorFlow.submitInitiatorSignature(staleAwait, second.token, "uid-a", pairingTestSigning(initiator.signing.private))
        }
        assertTrue("the session-id guard must throw, not register", refused.exceptionOrNull() is IllegalArgumentException)

        assertEquals(
            "the refused call wrote nothing",
            stateBefore,
            cloud.pairings.mapValues { (_, state) -> state.proofs.keys.toSet() },
        )
        assertTrue(initiatorDao.records.isEmpty())
        assertTrue("the new token is untouched", cloud.tokenDocs.containsKey(FirestoreSchema.pairingTokenDocumentId(second.token)))

        // The real ceremony the await belongs to still completes.
        val paired = initiatorFlow.submitInitiatorSignature(
            staleAwait, first.token, "uid-a", pairingTestSigning(initiator.signing.private),
        )
        assertEquals(PairingFlow.Outcome.Paired(EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b"), staleAwait.sas), paired)
        assertEquals(paired, partnerJob.await())
    }

    // Row 3: two devices of the SAME account (the user scans their own QR). The client cannot
    // tell; the function's SELF_PAIRING guard must — with no pair, no record, no activation.
    @Test
    fun sameAccountEchoPartner_cannotBindThePair() = runTest {
        var clock = 1_700_000_000_000L
        val cloud = FakeCloud()
        val deviceA = DeviceKeys()
        val deviceB = DeviceKeys()
        val daoA = FakeEnrollmentDao()
        val daoB = FakeEnrollmentDao()
        val deviceAFlow = pairingTestFlow(deviceA, cloud, testScheduler, daoA, FakeSecrets()) { clock }
        val deviceBFlow = pairingTestFlow(deviceB, cloud, testScheduler, daoB, FakeSecrets()) { clock }

        val initiation = deviceAFlow.initiate("uid-a")
        val ready = deviceBFlow.prepareJoin(initiation.qrBytes, "uid-a")
            as PairingFlow.JoinPreparationResult.Ready
        val deviceBJob = async {
            deviceBFlow.finishJoin(ready.preparation, "uid-a", pairingTestSigning(deviceB.signing.private))
        }
        val await = checkNotNull(deviceAFlow.awaitPartner(initiation.token, 30_000))
        assertEquals("uid-a", await.partnerUid)

        assertEquals(
            PairingFlow.Outcome.Rejected("SELF_PAIRING"),
            deviceAFlow.submitInitiatorSignature(await, initiation.token, "uid-a", pairingTestSigning(deviceA.signing.private)),
        )
        assertEquals(PairingFlow.Outcome.Rejected("SELF_PAIRING"), deviceBJob.await())
        assertTrue(cloud.pairings.isEmpty())
        assertTrue("neither side may hold a record", daoA.records.isEmpty() && daoB.records.isEmpty())
    }

    // Row 4: the initiator's submit is replayed (process death after the signature, retry with
    // the same await). The function's same-hash re-submission is idempotent, and the local
    // upsert replaces — never duplicates — the enrollment record.
    @Test
    fun replayedInitiatorSubmit_isIdempotent_neverDuplicatesTheRecord() = runTest {
        var clock = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val initiatorFlow = pairingTestFlow(initiator, cloud, testScheduler, initiatorDao, FakeSecrets()) { clock }
        val partnerFlow = pairingTestFlow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { clock }
        val initiation = initiatorFlow.initiate("uid-a")
        val durablePairId = EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b")

        val partnerJob = async {
            partnerFlow.complete(initiation.qrBytes, "uid-b", pairingTestSigning(partner.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        val first = initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", pairingTestSigning(initiator.signing.private))
        partnerJob.await()
        assertEquals(PairingFlow.Outcome.Paired(durablePairId, await.sas), first)

        val replay = initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", pairingTestSigning(initiator.signing.private))
        assertEquals("the replay lands on the identical paired state", first, replay)
        assertEquals(1, cloud.pairings.size)
        assertEquals(1, initiatorDao.records.size)
        assertEquals(durablePairId, initiatorDao.records.single().pairId)
    }

    // Row 5 (§19.5 at the pairing layer): a second partner on the same device must not disturb
    // the first relationship's record or relay state.
    @Test
    fun secondPartner_leavesTheFirstRelationshipUntouched() = runTest {
        var clock = 1_700_000_000_000L
        val cloud = FakeCloud()
        val alice = DeviceKeys()
        val bob = DeviceKeys()
        val carol = DeviceKeys()
        val aliceDao = FakeEnrollmentDao()
        val bobDao = FakeEnrollmentDao()
        val carolDao = FakeEnrollmentDao()
        val aliceFlow = pairingTestFlow(alice, cloud, testScheduler, aliceDao, FakeSecrets()) { clock }
        val bobFlow = pairingTestFlow(bob, cloud, testScheduler, bobDao, FakeSecrets()) { clock }
        val carolFlow = pairingTestFlow(carol, cloud, testScheduler, carolDao, FakeSecrets()) { clock }

        val abId = pair(aliceFlow, bobFlow, alice, bob, "uid-a", "uid-b")
        val abRecordBefore = checkNotNull(aliceDao.getByPairId(abId))
        val abProofsBefore = cloud.pairings.getValue(abId).proofs.toMap()

        clock += 60_000
        val acId = pair(aliceFlow, carolFlow, alice, carol, "uid-a", "uid-c")

        assertNotEquals(abId, acId)
        assertEquals(2, aliceDao.records.size)
        assertEquals("the first relationship's record is byte-identical", abRecordBefore, aliceDao.getByPairId(abId))
        assertEquals("the first relationship's server proofs are untouched", abProofsBefore, cloud.pairings.getValue(abId).proofs)
        assertEquals("active", cloud.pairings.getValue(abId).status)
        assertEquals("active", cloud.pairings.getValue(acId).status)
        assertEquals(1, bobDao.records.size)
        assertEquals(1, carolDao.records.size)
    }

    // Row 6: unpair one of N, then re-pair it. Only that relationship's state is replaced; the
    // sibling pair survives untouched and no duplicate row appears. Function-side unpair legs
    // are the §12.2 emulator suite's; this row models the client+relay state transition.
    @Test
    fun rePairAfterUnpair_replacesOnlyThatRelationship() = runTest {
        var clock = 1_700_000_000_000L
        val cloud = FakeCloud()
        val alice = DeviceKeys()
        val bob = DeviceKeys()
        val carol = DeviceKeys()
        val aliceDao = FakeEnrollmentDao()
        val aliceFlow = pairingTestFlow(alice, cloud, testScheduler, aliceDao, FakeSecrets()) { clock }
        val bobFlow = pairingTestFlow(bob, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { clock }
        val carolFlow = pairingTestFlow(carol, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { clock }

        val abId = pair(aliceFlow, bobFlow, alice, bob, "uid-a", "uid-b")
        val acId = pair(aliceFlow, carolFlow, alice, carol, "uid-a", "uid-c")
        val abRecordBefore = checkNotNull(aliceDao.getByPairId(abId))
        val acRecordBefore = checkNotNull(aliceDao.getByPairId(acId))
        val acProofsBefore = cloud.pairings.getValue(acId).proofs.toMap()

        // Unpair A–B: relay-side relationship removal + local record removal.
        FakeExchange(cloud, { clock }, testScheduler).terminatePair(abId)
        aliceDao.delete(abRecordBefore)
        assertNull(aliceDao.getByPairId(abId))

        clock += 3_600_000
        val abIdAgain = pair(aliceFlow, bobFlow, alice, bob, "uid-a", "uid-b")

        assertEquals(abId, abIdAgain)
        assertEquals(2, aliceDao.records.size)
        val abRecordAfter = checkNotNull(aliceDao.getByPairId(abId))
        assertNotEquals("the record was replaced, not kept", abRecordBefore.createdAtEpochMillis, abRecordAfter.createdAtEpochMillis)
        assertEquals("the sibling relationship's record is untouched", acRecordBefore, aliceDao.getByPairId(acId))
        assertEquals("the sibling relationship's server proofs are untouched", acProofsBefore, cloud.pairings.getValue(acId).proofs)
        assertEquals("active", cloud.pairings.getValue(abId).status)
    }

    // Row 7: the partner half completes, the initiator never confirms (phone died, user walked
    // away). The straggler must degrade to Rejected after the token TTL — never a half-pair.
    @Test
    fun partnerHalf_givesUpWhenTheInitiatorNeverConfirms_leavingNothingActive() = runTest {
        var clock = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val partnerDao = FakeEnrollmentDao()
        val initiatorFlow = pairingTestFlow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { clock }
        val partnerFlow = pairingTestFlow(partner, cloud, testScheduler, partnerDao, FakeSecrets()) { clock }
        val initiation = initiatorFlow.initiate("uid-a")
        val durablePairId = EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b")

        val partnerJob = async {
            partnerFlow.complete(initiation.qrBytes, "uid-b", pairingTestSigning(partner.signing.private))
        }
        val outcome = partnerJob.await()

        assertEquals(PairingFlow.Outcome.Rejected("initiator did not confirm — pairing aborted"), outcome)
        assertEquals("pending", cloud.pairings.getValue(durablePairId).status)
        assertTrue(partnerDao.records.isEmpty())
        assertTrue("the token is left to the TTL cleanup, not consumed", cloud.tokenDocs.isNotEmpty())
    }

    // Row 8: the initiator's poll after a cancel (the cancel test covers the partner side).
    @Test
    fun awaitAfterCancel_returnsNullQuietly() = runTest {
        var clock = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val initiatorFlow = pairingTestFlow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { clock }
        val initiation = initiatorFlow.initiate("uid-a")

        initiatorFlow.cancel(initiation.token)
        assertNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        assertTrue(cloud.pairings.isEmpty())
    }

    // Row 9: client-clock lag against the deployed rules. The client still believes the token
    // is live while the server has hit the deadline: the read path recovers as TokenExpired,
    // but the respond path can only read the denial as AlreadyUsed. Documented limit, pinned
    // so a future change to the mapping is a conscious one (the copy is the only delta —
    // both outcomes are terminal with the same "request a fresh code" action).
    @Test
    fun respondDeniedUnderClientClockLag_readsAsAlreadyUsed_documentedLimit() = runTest {
        var clientClock = 1_700_000_000_000L
        var serverClock = clientClock
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = pairingTestFlow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { clientClock }
        val partnerFlow = pairingTestFlow(
            partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets(),
            nowMillis = { clientClock }, exchangeNowMillis = { serverClock },
        )
        val initiation = initiatorFlow.initiate("uid-a")
        val deadline = initiation.token.expiresAtEpochMillis

        // Skew: at read time the server is a millisecond shy of the deadline, the client is far behind.
        serverClock = deadline - 1
        clientClock = deadline - 1
        val ready = partnerFlow.prepareJoin(initiation.qrBytes, "uid-b")
            as PairingFlow.JoinPreparationResult.Ready

        // By respond time the server has crossed the deadline; the client (lagging) has not.
        serverClock = deadline
        clientClock = deadline - 1
        assertEquals(
            PairingFlow.Outcome.TokenAlreadyUsed(initiation.token.sessionId),
            partnerFlow.finishJoin(ready.preparation, "uid-b", pairingTestSigning(partner.signing.private)),
        )
        assertTrue("no pair may exist from a denied response", cloud.pairings.isEmpty())
    }

    // Row 10: a seeded stream of ceremonies over a random uid-pairing matrix — the durable pair
    // id must be order-independent, and `Paired` must never be reported without matching
    // server activation and mirrored local records.
    @Test
    fun seededCeremonyStream_pairedAlwaysMatchesServerAndLocalState() = runTest {
        val random = Random(42)
        val uids = listOf("uid-a", "uid-b", "uid-z", "uid-0")

        repeat(24) { iteration ->
            val cloud = FakeCloud()
            val alice = DeviceKeys()
            val bob = DeviceKeys()
            val aliceDao = FakeEnrollmentDao()
            val bobDao = FakeEnrollmentDao()
            var clock = 1_700_000_000_000L
            val myUid = uids[random.nextInt(uids.size)]
            val partnerUid = uids.filter { it != myUid }[random.nextInt(uids.size - 1)]
            val aliceFlow = pairingTestFlow(alice, cloud, testScheduler, aliceDao, FakeSecrets()) { clock }
            val bobFlow = pairingTestFlow(bob, cloud, testScheduler, bobDao, FakeSecrets()) { clock }
            val initiation = aliceFlow.initiate(myUid)
            val durablePairId = EnrollmentTranscript.durablePairIdOf(myUid, partnerUid)

            when (random.nextInt(3)) {
                0 -> {
                    val bobJob = async {
                        bobFlow.complete(initiation.qrBytes, partnerUid, pairingTestSigning(bob.signing.private))
                    }
                    val await = checkNotNull(aliceFlow.awaitPartner(initiation.token, 30_000)) { "iteration $iteration" }
                    val aliceOutcome = aliceFlow.submitInitiatorSignature(
                        await, initiation.token, myUid, pairingTestSigning(alice.signing.private),
                    )
                    val bobOutcome = bobJob.await() as PairingFlow.Outcome.Paired
                    assertTrue("iteration $iteration: $aliceOutcome", aliceOutcome is PairingFlow.Outcome.Paired)
                    assertEquals("iteration $iteration: SAS", await.sas, bobOutcome.sas)
                    assertEquals("iteration $iteration: durable id", durablePairId, bobOutcome.pairId)
                    assertEquals("active", cloud.pairings.getValue(durablePairId).status)
                    assertEquals("iteration $iteration", 1, aliceDao.records.size)
                    assertEquals("iteration $iteration", 1, bobDao.records.size)
                    assertEquals(durablePairId, aliceDao.records.single().pairId)
                    assertEquals(durablePairId, bobDao.records.single().pairId)
                    assertEquals("A and B must point at each other", alice.senderKeyId(), bobDao.records.single().partnerSigningKeyId)
                    assertTrue("iteration $iteration: token consumed", cloud.tokenDocs.isEmpty())
                }

                1 -> {
                    clock = initiation.token.expiresAtEpochMillis + random.nextInt(2)
                    val bobOutcome = bobFlow.complete(initiation.qrBytes, partnerUid, pairingTestSigning(bob.signing.private))
                    assertTrue("iteration $iteration: $bobOutcome", bobOutcome is PairingFlow.Outcome.TokenExpired)
                    assertTrue("iteration $iteration", aliceDao.records.isEmpty() && bobDao.records.isEmpty())
                    assertTrue("iteration $iteration", cloud.pairings.isEmpty())
                }

                else -> {
                    aliceFlow.cancel(initiation.token)
                    val bobOutcome = bobFlow.complete(initiation.qrBytes, partnerUid, pairingTestSigning(bob.signing.private))
                    assertTrue("iteration $iteration: $bobOutcome", bobOutcome is PairingFlow.Outcome.TokenAlreadyUsed)
                    assertTrue("iteration $iteration", cloud.pairings.isEmpty())
                    assertTrue("iteration $iteration", aliceDao.records.isEmpty() && bobDao.records.isEmpty())
                }
            }

            cloud.pairings.values.forEach {
                assertEquals("iteration $iteration: no half-pair may linger", "active", it.status)
            }
        }
    }

    /** One full A-initiated ceremony against [otherFlow]; returns the durable pair id. */
    private suspend fun TestScope.pair(
        initiatorFlow: PairingFlow,
        otherFlow: PairingFlow,
        initiatorKeys: DeviceKeys,
        otherKeys: DeviceKeys,
        initiatorUid: String,
        otherUid: String,
    ): String {
        val initiation = initiatorFlow.initiate(initiatorUid)
        val otherJob = async {
            otherFlow.complete(initiation.qrBytes, otherUid, pairingTestSigning(otherKeys.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        val durablePairId = EnrollmentTranscript.durablePairIdOf(initiatorUid, otherUid)
        assertEquals(
            PairingFlow.Outcome.Paired(durablePairId, await.sas),
            initiatorFlow.submitInitiatorSignature(await, initiation.token, initiatorUid, pairingTestSigning(initiatorKeys.signing.private)),
        )
        otherJob.await()
        return durablePairId
    }

    private fun DeviceKeys.senderKeyId(): String =
        EnrollmentTranscript.senderKeyId(EnrollmentTranscript.pubKeyBytes(signing.public))
}
