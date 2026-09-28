package com.example.myexodus.domainusage

/**
 * S3.1/S3.2: the DNS wire layer — a total parser (never throws, never reads past the input)
 * and the negative-answer builder. Every structural violation surfaces as [Parsed.Failed]; a
 * query that is well-formed but not the standard one-question/opcode-0 shape surfaces as
 * [Parsed.NotStandard] so the caller can forward it without counting it as a parse failure.
 *
 * Answer records keep their owner name, type, class, TTL and RDATA so the alias-chain walk
 * (§6.2) can validate that an A record belongs to the query's chain. Types other than A and
 * CNAME are kept by RDLENGTH only — their owner name is still tracked.
 */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28
    const val TYPE_SVCB = 64
    const val TYPE_HTTPS = 65

    const val RCODE_NOERROR = 0
    const val RCODE_NXDOMAIN = 3

    private const val HEADER_BYTES = 12
    private const val MAX_ANSWER_RECORDS = 64
    private const val MAX_POINTER_HOPS = 32
    private const val MAX_NAME_BYTES = 255
    private const val MAX_LABEL_BYTES = 63

    sealed interface Parsed {
        data class Query(
            val id: Int,
            val opcode: Int,
            val rd: Boolean,
            val question: Question,
            /** Exact question-section bytes, so the reply echoes the query's own casing. */
            val questionSection: ByteArray,
        ) : Parsed

        data class Response(
            val id: Int,
            val rcode: Int,
            val questionName: String,
            val answers: List<Record>,
        ) : Parsed

        /** Well-formed but not a standard one-question, opcode-0 query/response. */
        data object NotStandard : Parsed

        data object Failed : Parsed
    }

    data class Question(val name: String, val type: Int, val clazz: Int)

    data class Record(
        val owner: String,
        val type: Int,
        val clazz: Int,
        val ttlSeconds: Long,
        val rdata: ByteArray,
        /** Parsed RDATA name for CNAME records; null otherwise. */
        val cnameTarget: String?,
    )

    /** Parses the exact bytes of one DNS message (UDP payload or response body). */
    fun parse(bytes: ByteArray): Parsed {
        if (bytes.size < HEADER_BYTES) return Parsed.Failed
        val id = u16(bytes, 0)
        val flags = u16(bytes, 2)
        val qdCount = u16(bytes, 4)
        val anCount = u16(bytes, 6)
        if (((flags ushr 11) and 0xF) != 0) return Parsed.NotStandard
        if (qdCount != 1) return Parsed.NotStandard

        val name = readName(bytes, HEADER_BYTES) ?: return Parsed.Failed
        if (name.next + 4 > bytes.size) return Parsed.Failed
        val question = Question(name.value, u16(bytes, name.next), u16(bytes, name.next + 2))
        val questionEnd = name.next + 4

        if ((flags ushr 15) and 1 == 0) {
            return Parsed.Query(
                id = id,
                opcode = 0,
                rd = (flags and 0x0100) != 0,
                question = question,
                questionSection = bytes.copyOfRange(HEADER_BYTES, questionEnd),
            )
        }

        val answers = ArrayList<Record>(minOf(anCount, MAX_ANSWER_RECORDS))
        var off = questionEnd
        for (i in 0 until minOf(anCount, MAX_ANSWER_RECORDS)) {
            val owner = readName(bytes, off) ?: return Parsed.Failed
            off = owner.next
            if (off + 10 > bytes.size) return Parsed.Failed
            val type = u16(bytes, off)
            val clazz = u16(bytes, off + 2)
            val ttl = u32(bytes, off + 4)
            val rdLength = u16(bytes, off + 8)
            off += 10
            if (off + rdLength > bytes.size) return Parsed.Failed
            val rdata = bytes.copyOfRange(off, off + rdLength)
            val cnameTarget = if (type == TYPE_CNAME) {
                readName(bytes, off)?.value ?: return Parsed.Failed
            } else {
                null
            }
            answers += Record(owner.value, type, clazz, ttl, rdata, cnameTarget)
            off += rdLength
        }
        return Parsed.Response(id, flags and 0xF, question.name, answers)
    }

    /**
     * S3.2: the negative answer for [query] — NXDOMAIN or NODATA, identical but for the RCODE.
     * One SOA in the authority section carries the negative TTL as both its TTL and its MINIMUM
     * field (RFC 2308), so resolvers cache the negative answer for the intended window.
     */
    fun buildNegativeReply(query: Parsed.Query, rcode: Int, negativeTtlSeconds: Int): ByteArray {
        val ttl = negativeTtlSeconds.toLong()
        val out = ByteArray(HEADER_BYTES + query.questionSection.size + 2 + 10 + SOA_RDATA_BYTES)
        putU16(out, 0, query.id)
        putU16(
            out, 2,
            0x8000 or // QR
                (query.opcode shl 11) or
                (if (query.rd) 0x0100 else 0) or
                0x0080 or // RA
                (rcode and 0xF),
        )
        putU16(out, 4, 1) // QDCOUNT
        putU16(out, 6, 0) // ANCOUNT
        putU16(out, 8, 1) // NSCOUNT
        putU16(out, 10, 0) // ARCOUNT
        query.questionSection.copyInto(out, HEADER_BYTES)

        var off = HEADER_BYTES + query.questionSection.size
        putU16(out, off, 0xC00C) // owner: pointer to the question name at offset 12
        putU16(out, off + 2, 6) // type SOA
        putU16(out, off + 4, 1) // class IN
        putU32(out, off + 6, ttl) // SOA TTL = negative TTL
        putU16(out, off + 10, SOA_RDATA_BYTES)
        off += 12
        NS_INVALID.copyInto(out, off); off += NS_INVALID.size
        HOSTMASTER_INVALID.copyInto(out, off); off += HOSTMASTER_INVALID.size
        putU32(out, off, 1) // serial
        putU32(out, off + 4, 3600) // refresh
        putU32(out, off + 8, 600) // retry
        putU32(out, off + 12, 86400) // expire
        putU32(out, off + 16, ttl) // minimum = negative TTL
        return out
    }

    private val NS_INVALID =
        byteArrayOf(2) + "ns".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(7) + "invalid".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private val HOSTMASTER_INVALID =
        byteArrayOf(10) + "hostmaster".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(7) + "invalid".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private val SOA_RDATA_BYTES = NS_INVALID.size + HOSTMASTER_INVALID.size + 20

    private class NameResult(val value: String, val next: Int)

    /**
     * Reads a (possibly compressed) name at [start]. Pointers must strictly point backward and
     * are capped at [MAX_POINTER_HOPS]; that ordering alone makes loops impossible, the cap is
     * the second line of defence. Returns null on every structural violation.
     */
    private fun readName(bytes: ByteArray, start: Int): NameResult? {
        val name = StringBuilder()
        var off = start
        var next = -1
        var hops = 0
        var totalBytes = 0
        while (true) {
            if (off >= bytes.size) return null
            val length = bytes[off].toInt() and 0xFF
            when {
                length == 0 -> {
                    if (next < 0) next = off + 1
                    return NameResult(name.toString().lowercase(), next)
                }
                (length and 0xC0) == 0xC0 -> {
                    if (off + 1 >= bytes.size) return null
                    val pointer = ((length and 0x3F) shl 8) or (bytes[off + 1].toInt() and 0xFF)
                    if (pointer >= off) return null
                    if (++hops > MAX_POINTER_HOPS) return null
                    if (next < 0) next = off + 2
                    off = pointer
                }
                length and 0xC0 != 0 -> return null
                else -> {
                    if (length > MAX_LABEL_BYTES) return null
                    if (off + 1 + length > bytes.size) return null
                    totalBytes += length + 1
                    if (totalBytes > MAX_NAME_BYTES) return null
                    if (name.isNotEmpty()) name.append('.')
                    name.append(String(bytes, off + 1, length, Charsets.ISO_8859_1))
                    off += 1 + length
                }
            }
        }
    }

    private fun u16(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, off: Int): Long =
        ((bytes[off].toLong() and 0xFF) shl 24) or ((bytes[off + 1].toLong() and 0xFF) shl 16) or
            ((bytes[off + 2].toLong() and 0xFF) shl 8) or (bytes[off + 3].toLong() and 0xFF)

    private fun putU16(bytes: ByteArray, off: Int, value: Int) {
        bytes[off] = ((value ushr 8) and 0xFF).toByte()
        bytes[off + 1] = (value and 0xFF).toByte()
    }

    private fun putU32(bytes: ByteArray, off: Int, value: Long) {
        bytes[off] = ((value ushr 24) and 0xFF).toByte()
        bytes[off + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[off + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[off + 3] = (value and 0xFF).toByte()
    }
}
