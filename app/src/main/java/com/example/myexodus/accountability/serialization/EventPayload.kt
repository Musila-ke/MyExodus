package com.example.myexodus.accountability.serialization

import com.upokecenter.cbor.CBORObject

/**
 * The event payload carried inside the encrypted envelope (§6.2.1): NFC-normalized and
 * encoded as deterministic CBOR BEFORE encryption. Control events (bilateral deletion,
 * §10; disable approval, AD-07) travel the same channel with a different type.
 *
 * [requestEventId] / [pairingEpoch] (AD-07) are the §8 Step 4 `DisableApprovalResponse`
 * contract: the response references the persisted request event by id and carries the
 * partnership epoch so a response is rejected after a re-pair. Encoded only when non-null,
 * so activity/deletion payloads keep their exact prior bytes (hash-chain stability).
 *
 * [expectedPreviousSequence] / [expectedPreviousHash] are the AD-08b Step 11 repair request
 * contract: a [`TYPE_SYNC_REQUEST`] carries the requester's last known sequence + hash for
 * the *sender's* chain so the returned segment is provable against the exact chain link, not
 * a bare sequence number. Also absent-when-unset (byte stability for every other type).
 *
 * Step 13 disable-approval flow (§8 Step 12, FR2): a [`TYPE_APPROVAL_REQUEST`] carries the
 * requesting user's message and its [pairingEpoch]; the answering [`TYPE_APPROVAL_RESPONSE`]
 * references the request by [requestEventId] plus [pairingEpoch] (AD-07) and carries the
 * [decision]. The request's own event id is deliberately NOT self-reported — the responder
 * derives it canonically from the envelope's (senderKeyId, sequence), which the sender
 * cannot spoof.
 */
data class EventPayload(
    val type: String = TYPE_ACTIVITY,
    val text: String,
    val requestEventId: String? = null,
    val pairingEpoch: Int? = null,
    val expectedPreviousSequence: Long? = null,
    val expectedPreviousHash: ByteArray? = null,
    /** Approval response only: true = approved, false = rejected. */
    val decision: Boolean? = null,
) {
    companion object {
        const val TYPE_ACTIVITY = "activity"
        const val TYPE_DELETION_REQUEST = "deletion_request"

        /** AD-08/AD-08b: "please replay your chain after (sequence, hash)". */
        const val TYPE_SYNC_REQUEST = "sync_request"

        /** §8 Step 12: the disable-approval request/response pair (AD-07). */
        const val TYPE_APPROVAL_REQUEST = "approval_request"
        const val TYPE_APPROVAL_RESPONSE = "approval_response"
    }
}

private const val K_TYPE = "ty"
private const val K_TEXT = "tx"
private const val K_REQUEST_EVENT_ID = "rid"
private const val K_PAIRING_EPOCH = "pe"
private const val K_EXPECTED_PREV_SEQUENCE = "ps"
private const val K_EXPECTED_PREV_HASH = "ph"
private const val K_DECISION = "dec"

fun encodePayload(p: EventPayload): ByteArray = CanonicalCbor.encodeMap(
    buildList {
        add(K_TYPE to CanonicalCbor.text(p.type))
        add(K_TEXT to CanonicalCbor.text(p.text))
        p.requestEventId?.let { add(K_REQUEST_EVENT_ID to CanonicalCbor.text(it)) }
        p.pairingEpoch?.let { add(K_PAIRING_EPOCH to CanonicalCbor.int(it.toLong())) }
        p.expectedPreviousSequence?.let { add(K_EXPECTED_PREV_SEQUENCE to CanonicalCbor.int(it)) }
        p.expectedPreviousHash?.let { add(K_EXPECTED_PREV_HASH to CanonicalCbor.bytes(it)) }
        p.decision?.let { add(K_DECISION to CanonicalCbor.bool(it)) }
    },
)

fun decodePayload(bytes: ByteArray): EventPayload {
    val obj = CanonicalCbor.decodeMap(bytes)
    return EventPayload(
        type = obj[K_TYPE].AsString(),
        text = obj[K_TEXT].AsString(),
        requestEventId = obj.get(K_REQUEST_EVENT_ID)?.takeIf { !it.isNull }?.AsString(),
        pairingEpoch = obj.get(K_PAIRING_EPOCH)?.takeIf { !it.isNull }?.AsInt32(),
        expectedPreviousSequence = obj.get(K_EXPECTED_PREV_SEQUENCE)?.takeIf { !it.isNull }?.AsInt64(),
        expectedPreviousHash = obj.get(K_EXPECTED_PREV_HASH)?.takeIf { !it.isNull }?.GetByteString(),
        decision = obj.get(K_DECISION)?.takeIf { !it.isNull }?.AsBoolean(),
    )
}
