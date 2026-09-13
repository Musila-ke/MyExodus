package com.example.myexodus.accountability.crypto

import java.math.BigInteger

/**
 * §10 Low-S enforcement: verifiers MUST reject ECDSA signatures where S > N/2
 * (transcript-malleability protection). N is the P-256 (secp256r1) curve order.
 */
object EcdsaLowS {

    private val N = BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16,
    )

    fun isLowS(der: ByteArray): Boolean = decode(der).second <= N.shiftRight(1)

    /** Canonicalizes a DER ECDSA signature by replacing S with N-S when S > N/2. */
    fun normalize(der: ByteArray): ByteArray {
        val (r, s) = decode(der)
        return encode(r, if (s > N.shiftRight(1)) N.subtract(s) else s)
    }

    /** §10 test inverse: replaces S with N-S when S is already low, forcing a high-S signature. */
    fun forceHighS(der: ByteArray): ByteArray {
        val (r, s) = decode(der)
        return encode(r, if (s > N.shiftRight(1)) s else N.subtract(s))
    }

    /** Parses a DER-encoded ECDSA-Sig-Value SEQUENCE { INTEGER r, INTEGER s }. */
    fun decode(der: ByteArray): Pair<BigInteger, BigInteger> {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "not a DER SEQUENCE" }
        val bodyLen = der[1].toInt()
        require(bodyLen >= 0 && bodyLen == der.size - 2) { "malformed SEQUENCE length" }
        var pos = 2
        fun readInteger(): BigInteger {
            require(pos + 2 <= der.size && der[pos].toInt() == 0x02) { "expected INTEGER" }
            val len = der[pos + 1].toInt()
            require(len > 0 && pos + 2 + len <= der.size) { "malformed INTEGER" }
            val v = BigInteger(der.copyOfRange(pos + 2, pos + 2 + len))
            pos += 2 + len
            return v
        }
        val r = readInteger()
        val s = readInteger()
        require(pos == der.size) { "trailing bytes after signature" }
        return r to s
    }

    /** DER-encodes (r, s) with minimal-length INTEGER bodies. */
    fun encode(r: BigInteger, s: BigInteger): ByteArray {
        fun intBody(v: BigInteger): ByteArray {
            var b = v.toByteArray()
            var i = 0
            while (i < b.size - 1 && b[i] == 0.toByte() && (b[i + 1].toInt() and 0x80) == 0) i++
            b = b.copyOfRange(i, b.size)
            require(b.size < 128) { "INTEGER body too long for single-byte length" }
            return byteArrayOf(0x02, b.size.toByte()) + b
        }
        val body = intBody(r) + intBody(s)
        require(body.size < 128) { "signature too long for single-byte length" }
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
