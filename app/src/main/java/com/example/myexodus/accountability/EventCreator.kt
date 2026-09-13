package com.example.myexodus.accountability

import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.crypto.IdentityEpochStore
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.UnsignedEnvelope
import com.example.myexodus.accountability.serialization.encodePayload
import com.example.myexodus.accountability.serialization.eventHash
import com.example.myexodus.accountability.state.ChainStateStore
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * FR3 (§6.2): creates one chain event — sequence + nonce + AES-GCM + envelope + signature —
 * and appends it atomically with the chain head. The [Signature] comes from
 * [AccountabilitySigner.beginSign] via the BiometricPrompt (user auth for signing, §8.1);
 * the caller supplies the CEK-free key access via [agreementPrivateKey] so key material
 * stays behind the Keystore seam (unit-testable with plain keys).
 */
class EventCreator(
    private val store: ChainStateStore,
    private val enrollmentDao: EnrollmentDao,
    private val cekDerivation: CekDerivation,
    private val eventCipher: EventCipher,
    private val signer: AccountabilitySigner,
    private val epochStore: IdentityEpochStore,
    private val agreementPrivateKey: () -> PrivateKey,
) {

    sealed interface CreateResult {
        /** Appended; [sequence] links the attempt-log row to this chain event (§14). */
        data class Created(val sequence: Long) : CreateResult

        /** Identical event already chained (idempotent re-creation). */
        data object Duplicate : CreateResult

        data class Failed(val reason: String) : CreateResult
    }

    private val createMutex = Mutex()

    suspend fun create(pairId: String, payload: EventPayload, signature: Signature): CreateResult {
        // sequence read + append must be one critical section: concurrent creates would
        // compute the same sequence and fork the chain.
        return createMutex.withLock { createLocked(pairId, payload, signature) }
    }

    private suspend fun createLocked(pairId: String, payload: EventPayload, signature: Signature): CreateResult {
        val enrollment = enrollmentDao.getByPairId(pairId)
            ?: return CreateResult.Failed("not enrolled")
        val mySenderKeyId = enrollment.mySenderKeyId
        // The chain head IS this sender's last local event — one row, not a full chain scan
        // (1000-event cap, but this runs on every block attempt).
        val head = store.localHead(pairId, mySenderKeyId)
        val sequence = (head?.lastSequence ?: 0L) + 1
        val previousHash = head?.lastEventHash

        val cek = cekDerivation.deriveCek(
            pairId,
            agreementPrivateKey(),
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(enrollment.partnerAgreementPubKey)),
        )
        val unsigned = UnsignedEnvelope(
            senderKeyId = mySenderKeyId,
            epoch = epochStore.current(),
            sequence = sequence,
            previousEventHash = previousHash,
            nonce = eventCipher.nonce(cek, sequence),
            ciphertext = eventCipher.encrypt(cek, sequence, encodePayload(payload)),
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        signature.update(eventHash(unsigned))
        val envelope = SignedEnvelope(unsigned, signer.finishSign(signature))

        return when (store.appendLocal(pairId, envelope)) {
            is ChainStateStore.AppendResult.Appended -> CreateResult.Created(sequence)
            is ChainStateStore.AppendResult.Duplicate -> CreateResult.Duplicate
            is ChainStateStore.AppendResult.Gap -> CreateResult.Failed("chain gap")
            is ChainStateStore.AppendResult.Fork -> CreateResult.Failed("forked chain")
            is ChainStateStore.AppendResult.Rejected -> CreateResult.Failed("rejected")
        }
    }
}
