package com.example.myexodus.accountability.serialization

import com.upokecenter.cbor.CBORObject

/**
 * The activity payload carried inside the encrypted envelope (§6.2.1): NFC-normalized and
 * encoded as deterministic CBOR BEFORE encryption. Control events (bilateral deletion,
 * §10) travel the same channel with a different type.
 */
data class EventPayload(
    val type: String = TYPE_ACTIVITY,
    val text: String,
) {
    companion object {
        const val TYPE_ACTIVITY = "activity"
        const val TYPE_DELETION_REQUEST = "deletion_request"
    }
}

private const val K_TYPE = "ty"
private const val K_TEXT = "tx"

fun encodePayload(p: EventPayload): ByteArray = CanonicalCbor.encodeMap(
    listOf(
        K_TYPE to CanonicalCbor.text(p.type),
        K_TEXT to CanonicalCbor.text(p.text),
    ),
)

fun decodePayload(bytes: ByteArray): EventPayload {
    val obj = CanonicalCbor.decodeMap(bytes)
    return EventPayload(
        type = obj[K_TYPE].AsString(),
        text = obj[K_TEXT].AsString(),
    )
}
