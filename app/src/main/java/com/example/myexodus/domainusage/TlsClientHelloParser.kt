package com.example.myexodus.domainusage

/**
 * S4.1/S4.2: incremental SNI extraction from a TLS ClientHello.
 *
 * [feed] is a pure function of the caller's byte range — the caller (the TCP/443 tap) accumulates
 * arriving bytes in a pooled buffer and re-feeds the whole accumulated range each time, so no
 * parser state survives a call and a ClientHello split across records, segments, or every single
 * byte boundary parses identically. Records are walked as one logical handshake byte stream, with
 * every length checked against its enclosing structure; nothing ever reads past `off + len`.
 *
 * The parser never converts binary data to a string except the SNI name itself, which is decoded
 * as ASCII only after it has been validated.
 */
object TlsClientHelloParser {

    sealed interface Result {
        /** Not enough bytes yet to decide. */
        data object NeedMore : Result

        data class Sni(val host: String) : Result

        /** ECH is present; the plaintext SNI (if any) is meaningless and is ignored. */
        data object Ech : Result

        /** TLS, but no usable SNI — a non-ClientHello message, no extension, or an invalid name. */
        data object NoSni : Result

        data object NotTls : Result

        data object Malformed : Result

        /** The declared ClientHello length cannot fit an inspection buffer (D-13). */
        data object TooLarge : Result
    }

    /** §8 S4.6: the terminal result → counter mapping. [Result.NeedMore] counts nothing. */
    fun countInto(result: Result, counters: EngineCounters) {
        when (result) {
            is Result.Sni -> counters.inc(Counter.sni_ok)
            Result.Ech -> counters.inc(Counter.sni_ech)
            Result.NoSni -> counters.inc(Counter.sni_none)
            Result.NotTls -> counters.inc(Counter.sni_notls)
            Result.Malformed -> counters.inc(Counter.sni_malformed)
            Result.TooLarge -> counters.inc(Counter.sni_toolarge)
            Result.NeedMore -> Unit
        }
    }

    private const val RECORD_HEADER = 5
    private const val HANDSHAKE_HEADER = 4
    private const val RECORD_HANDSHAKE = 0x16
    private const val TLS_MAJOR = 0x03
    private const val HANDSHAKE_CLIENT_HELLO = 0x01
    private const val MAX_RECORD_PAYLOAD = 16_384
    private const val EXT_SERVER_NAME = 0x0000
    private const val EXT_ECH = 0xFE0D
    private const val NAME_TYPE_HOST = 0
    private const val MAX_HOST_BYTES = 253
    private const val MAX_LABEL_BYTES = 63

    fun feed(bytes: ByteArray, off: Int, len: Int): Result {
        if (len <= 0) return Result.NeedMore
        if (bytes[off] != RECORD_HANDSHAKE.toByte()) return Result.NotTls
        if (len < 2) return Result.NeedMore
        if (bytes[off + 1] != TLS_MAJOR.toByte()) return Result.NotTls

        val c = Cursor(bytes, off, off + len)
        val type = c.u8()
        if (type < 0) return c.result()
        val declaredLen = c.u24()
        if (declaredLen < 0) return c.result()
        if (HANDSHAKE_HEADER + declaredLen > MeteringConfig.INSPECT_MAX_BYTES) return Result.TooLarge
        if (type != HANDSHAKE_CLIENT_HELLO) return Result.NoSni
        val messageEnd = HANDSHAKE_HEADER + declaredLen

        if (!c.skip(2 + 32)) return c.result() // legacy_version, random
        val sessionIdLen = c.u8()
        if (sessionIdLen < 0) return c.result()
        if (!c.fits(sessionIdLen, messageEnd)) return Result.Malformed
        if (!c.skip(sessionIdLen)) return c.result()
        val cipherSuitesLen = c.u16()
        if (cipherSuitesLen < 0) return c.result()
        if (cipherSuitesLen % 2 != 0 || !c.fits(cipherSuitesLen, messageEnd)) return Result.Malformed
        if (!c.skip(cipherSuitesLen)) return c.result()
        val compressionLen = c.u8()
        if (compressionLen < 0) return c.result()
        if (!c.fits(compressionLen, messageEnd)) return Result.Malformed
        if (!c.skip(compressionLen)) return c.result()

        if (c.streamPos == messageEnd) return Result.NoSni // no extensions field at all
        val extensionsLen = c.u16()
        if (extensionsLen < 0) return c.result()
        if (c.streamPos + extensionsLen > messageEnd) return Result.Malformed

        val extensionsEnd = c.streamPos + extensionsLen
        while (c.streamPos < extensionsEnd) {
            if (extensionsEnd - c.streamPos < 4) return Result.Malformed
            val extType = c.u16()
            val extLen = c.u16()
            if (extType < 0 || extLen < 0) return c.result()
            if (c.streamPos + extLen > extensionsEnd) return Result.Malformed
            val extStart = c.streamPos
            when (extType) {
                EXT_SERVER_NAME -> readServerName(c, extLen)
                EXT_ECH -> c.ech = true
            }
            if (!c.skip(extStart + extLen - c.streamPos)) return c.result()
        }

        if (c.ech) return Result.Ech
        val host = c.sni ?: return Result.NoSni
        return Result.Sni(host)
    }

    /** RFC 6066: server_name_list, first `host_name` entry wins. Malformed sets the cursor problem. */
    private fun readServerName(c: Cursor, extLen: Int) {
        val extEnd = c.streamPos + extLen
        val listLen = c.u16()
        if (listLen < 0) return
        if (c.streamPos + listLen > extEnd) {
            c.failMalformed()
            return
        }
        val listEnd = c.streamPos + listLen
        while (c.streamPos < listEnd) {
            if (listEnd - c.streamPos < 3) {
                c.failMalformed()
                return
            }
            val nameType = c.u8()
            val nameLen = c.u16()
            if (nameType < 0 || nameLen < 0) return
            if (c.streamPos + nameLen > listEnd) {
                c.failMalformed()
                return
            }
            if (nameType == NAME_TYPE_HOST && !c.hostNameSeen) {
                c.hostNameSeen = true
                val raw = ByteArray(nameLen)
                for (i in 0 until nameLen) {
                    val b = c.u8()
                    if (b < 0) return
                    raw[i] = b.toByte()
                }
                validateHost(raw)?.let { c.sni = it }
                continue
            }
            if (!c.skip(nameLen)) return
        }
    }

    /** Lowercased, then `[a-z0-9._-]`, ≤ 253 bytes, labels 1..63; anything else ⇒ null (§8 S4.3). */
    private fun validateHost(raw: ByteArray): String? {
        if (raw.isEmpty() || raw.size > MAX_HOST_BYTES) return null
        val lower = ByteArray(raw.size)
        var labelLen = 0
        var previousWasDot = true
        for (i in raw.indices) {
            val b = raw[i].toInt() and 0xFF
            val ch = when {
                b >= 'A'.code && b <= 'Z'.code -> b + 32
                b == '.'.code -> {
                    if (labelLen == 0 || labelLen > MAX_LABEL_BYTES) return null
                    labelLen = 0
                    previousWasDot = true
                    b
                }
                else -> b
            }
            val ok = (ch >= 'a'.code && ch <= 'z'.code) || (ch >= '0'.code && ch <= '9'.code) ||
                ch == '.'.code || ch == '_'.code || ch == '-'.code
            if (!ok) return null
            if (ch != '.'.code) {
                labelLen++
                if (labelLen > MAX_LABEL_BYTES) return null
                previousWasDot = false
            }
            lower[i] = ch.toByte()
        }
        if (previousWasDot) return null
        return String(lower, Charsets.US_ASCII)
    }

    /**
     * Per-call state: walks the TLS records as one logical handshake byte stream, validating each
     * record header as it opens. One instance per [feed] call; never shared across threads.
     */
    private class Cursor(private val bytes: ByteArray, off: Int, private val end: Int) {

        var streamPos = 0
            private set

        var ech = false
        var sni: String? = null
        var hostNameSeen = false

        private var problem = 0
        private var recordStart = off
        private var payloadEnd = -1
        private var next = 0

        fun u8(): Int {
            if (!ensure()) return -1
            streamPos++
            return bytes[next++].toInt() and 0xFF
        }

        fun u16(): Int {
            val hi = u8()
            val lo = u8()
            return if (hi < 0 || lo < 0) -1 else (hi shl 8) or lo
        }

        fun u24(): Int {
            val a = u8()
            val b = u8()
            val c = u8()
            return if (a < 0 || b < 0 || c < 0) -1 else (a shl 16) or (b shl 8) or c
        }

        fun skip(count: Int): Boolean {
            for (i in 0 until count) {
                if (!ensure()) return false
                next++
                streamPos++
            }
            return true
        }

        /** Whether `count` more bytes fit before the enclosing structure's end. */
        fun fits(count: Int, structureEnd: Int): Boolean = count >= 0 && streamPos + count <= structureEnd

        fun failMalformed() {
            if (problem == 0) problem = PROBLEM_MALFORMED
        }

        fun result(): Result = when (problem) {
            PROBLEM_MALFORMED -> Result.Malformed
            else -> Result.NeedMore
        }

        private fun ensure(): Boolean {
            while (true) {
                if (payloadEnd < 0) {
                    if (recordStart + RECORD_HEADER > end) {
                        problem = PROBLEM_NEED_MORE
                        return false
                    }
                    if (bytes[recordStart] != RECORD_HANDSHAKE.toByte() ||
                        bytes[recordStart + 1] != TLS_MAJOR.toByte()
                    ) {
                        problem = PROBLEM_MALFORMED
                        return false
                    }
                    val payloadLen = ((bytes[recordStart + 3].toInt() and 0xFF) shl 8) or
                        (bytes[recordStart + 4].toInt() and 0xFF)
                    if (payloadLen > MAX_RECORD_PAYLOAD) {
                        problem = PROBLEM_MALFORMED
                        return false
                    }
                    payloadEnd = recordStart + RECORD_HEADER + payloadLen
                    next = recordStart + RECORD_HEADER
                }
                if (next < payloadEnd) {
                    // A record may be cut short by the caller's range; the parse decides on NeedMore
                    // only when it actually needs a byte that has not arrived yet.
                    if (next >= end) {
                        problem = PROBLEM_NEED_MORE
                        return false
                    }
                    return true
                }
                recordStart = payloadEnd
                payloadEnd = -1
                if (recordStart >= end) {
                    problem = PROBLEM_NEED_MORE
                    return false
                }
            }
        }

        private companion object {
            const val PROBLEM_NEED_MORE = 1
            const val PROBLEM_MALFORMED = 2
        }
    }
}
