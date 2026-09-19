package com.example.myexodus.accountability.serialization

import com.upokecenter.cbor.CBOREncodeOptions
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import java.text.Normalizer

/**
 * §6.2.1/§7.2: NFC-normalized, RFC 8949 deterministic CBOR. Both devices (and the reference
 * server) must produce byte-identical encodings of the same logical envelope — asserted in
 * tests via the RFC 8949 canonical vectors (§12). petero-cbor's Default options are
 * deterministic (shortest-form ints, length-then-bytewise key sort, no indefinite lengths).
 */
object CanonicalCbor {

    private val OPTIONS = CBOREncodeOptions.Default

    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** Encodes a map with canonical key sorting and minimal-length encodings (RFC 8949 §4.2.1). */
    fun encodeMap(entries: List<Pair<String, CBORObject>>): ByteArray {
        val map = CBORObject.NewMap()
        for ((key, value) in entries) {
            map[CBORObject.FromObject(key)] = value
        }
        return map.EncodeToBytes(OPTIONS)
    }

    fun encode(value: CBORObject): ByteArray = value.EncodeToBytes(OPTIONS)

    /** Decodes raw bytes; all malformed-input failures surface as [IllegalArgumentException]. */
    fun decode(bytes: ByteArray): CBORObject = try {
        CBORObject.DecodeFromBytes(bytes)
    } catch (e: com.upokecenter.cbor.CBORException) {
        throw IllegalArgumentException("malformed CBOR", e)
    }

    /** Decodes raw bytes as a map, the wire shape of every envelope-family payload. */
    fun decodeMap(bytes: ByteArray): CBORObject {
        val obj = decode(bytes)
        require(obj.type == CBORType.Map) { "payload is not a map" }
        return obj
    }

    fun text(value: String): CBORObject = CBORObject.FromObject(nfc(value))

    fun bytes(value: ByteArray): CBORObject = CBORObject.FromObject(value)

    fun int(value: Long): CBORObject = CBORObject.FromObject(value)

    /** CBOR simple values 20/21 (0xF4/0xF5) — deterministic, no form choices. */
    fun bool(value: Boolean): CBORObject = CBORObject.FromObject(value)
}
