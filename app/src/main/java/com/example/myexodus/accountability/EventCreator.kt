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
import com.example.myexodus.accountability.state.SessionKeyManager
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
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
    /** (enrollment's per-pair key alias — null for pre-v12 records) -> ECDH private key. */
    private val agreementPrivateKey: (alias: String?) -> PrivateKey,
    private val sessionKeyManager: SessionKeyManager,
) {

    sealed interface CreateResult {
        /** Appended; [sequence] links the attempt-log row to this chain event (§14), and
         *  [messageId] addresses its outbox row / mailbox document (the Step 13 unpair journal
         *  needs it to poll the partner's ack). */
        data class Created(val sequence: Long, val messageId: String) : CreateResult

        /** Identical event already chained (idempotent re-creation). */
        data object Duplicate : CreateResult

        data class Failed(val reason: String) : CreateResult
    }

    private val createMutex = Mutex()

    /** [messageId] pins the outbox/mailbox id the caller already journaled (the Step 14 unpair
     *  protocol writes its journal row before the event exists, so the ack address is stable
     *  across a crash); null mints a fresh id.
     *
     *  [eventId] is the Step 3 wire-contract UUID for accountability events — the caller
     *  journaled it before this call, so a crash between journal and append reconciles by the
     *  same id. Null for pre-contract payload types (SOS, attempt logs). */
    suspend fun create(
        pairId: String,
        payload: EventPayload,
        signature: Signature,
        messageId: String? = null,
        eventId: String? = null,
    ): CreateResult = createBody(pairId, { encodePayload(payload) }, signature, messageId, eventId)

    /**
     * [bodyFor] receives the sequence allocated inside the critical section and returns the
     * plaintext body. The Step 7 monitoring snapshot needs exactly that — its signed body
     * carries its own `relationship_seq` and the latest committed one, which is this very
     * allocation — and the caller cannot know it before the mutex.
     */
    suspend fun createBody(
        pairId: String,
        bodyFor: (sequence: Long) -> ByteArray,
        signature: Signature,
        messageId: String? = null,
        eventId: String? = null,
    ): CreateResult {
        // sequence read + append must be one critical section: concurrent creates would
        // compute the same sequence and fork the chain.
        return createMutex.withLock { createLocked(pairId, bodyFor, signature, messageId, eventId) }
    }

    private suspend fun createLocked(
        pairId: String,
        bodyFor: (sequence: Long) -> ByteArray,
        signature: Signature,
        pinnedMessageId: String?,
        eventId: String?,
    ): CreateResult {
        val enrollment = enrollmentDao.getByPairId(pairId)
            ?: return CreateResult.Failed("not enrolled")
        val mySenderKeyId = enrollment.mySenderKeyId
        // The chain head IS this sender's last local event — one row, not a full chain scan
        // (1000-event cap, but this runs on every block attempt).
        val head = store.localHead(pairId, mySenderKeyId)
        val sequence = (head?.lastSequence ?: 0L) + 1
        val previousHash = head?.lastEventHash

        // AD-12 (Step 15): the rotation check runs in the same critical section as the append,
        // so no envelope can escape under an epoch that was already due for rotation.
        val nowMillis = System.currentTimeMillis()
        val rotated = sessionKeyManager.rotateIfDue(pairId, mySenderKeyId, nowMillis)
        val keyEpoch = rotated ?: head?.keyEpoch ?: 0

        val cek = cekDerivation.deriveCek(
            pairId,
            agreementPrivateKey(enrollment.myAgreementKeyAlias),
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(enrollment.partnerAgreementPubKey)),
        )
        val sessionKey = cekDerivation.deriveSessionKey(cek, keyEpoch)
        val unsigned = UnsignedEnvelope(
            senderKeyId = mySenderKeyId,
            epoch = epochStore.current(),
            sequence = sequence,
            previousEventHash = previousHash,
            nonce = eventCipher.nonce(sessionKey, sequence),
            ciphertext = eventCipher.encrypt(sessionKey, sequence, bodyFor(sequence)),
            createdAtEpochMillis = nowMillis,
            keyEpoch = keyEpoch,
        )
        signature.update(eventHash(unsigned))
        val envelope = SignedEnvelope(unsigned, signer.finishSign(signature))

        val messageId = pinnedMessageId ?: UUID.randomUUID().toString()
        return when (store.appendLocal(pairId, envelope, messageId, eventId)) {
            is ChainStateStore.AppendResult.Appended -> CreateResult.Created(sequence, messageId)
            is ChainStateStore.AppendResult.Duplicate -> CreateResult.Duplicate
            is ChainStateStore.AppendResult.Gap -> CreateResult.Failed("chain gap")
            is ChainStateStore.AppendResult.Fork -> CreateResult.Failed("forked chain")
            is ChainStateStore.AppendResult.Rejected -> CreateResult.Failed("rejected")
        }
    }
}
