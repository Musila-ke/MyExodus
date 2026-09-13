package com.example.myexodus.vpn

import java.nio.ByteBuffer

/**
 * Minimal DNS message codec for the sinkhole spike (plan §6 / §7 step 3). Only the query
 * shape we answer is parsed (QDCOUNT=1, no qname compression in queries); anything else
 * on port 53 is dropped locally — the device resolver retries or falls back.
 */
object DnsMessages {

    data class Query(val id: Int, val qname: String, val flags: Int, val questionEnd: Int)

    private fun readShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun parseQuery(packet: ByteArray, offset: Int): Query? {
        if (offset + 12 > packet.size) return null
        val id = readShort(packet, offset) and 0xFFFF
        val flags = readShort(packet, offset + 2) and 0xFFFF
        if (readShort(packet, offset + 4) and 0xFFFF != 1) return null // QDCOUNT
        var i = offset + 12
        val sb = StringBuilder()
        while (true) {
            if (i >= packet.size) return null
            val len = packet[i].toInt() and 0xFF
            if (len == 0) { i += 1; break }
            if (len and 0xC0 != 0) return null // compression pointer: illegal in a query qname
            i += 1
            if (i + len > packet.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 0 until len) sb.append((packet[i + k].toInt() and 0xFF).toChar())
            i += len
        }
        if (i + 4 > packet.size) return null // QTYPE + QCLASS
        val qname = sb.toString().lowercase()
        if (qname.length > 253) return null
        return Query(id, qname, flags, i + 4)
    }

    /** NXDOMAIN response: header + verbatim question echo; QR set, RD echoed, RCODE=3. */
    fun buildNxDomain(query: Query, packet: ByteArray, offset: Int): ByteArray {
        val questionLen = query.questionEnd - (offset + 12)
        val flags = 0x8000 or (query.flags and 0x0100) or 0x0003
        val buf = ByteBuffer.allocate(12 + questionLen)
        buf.putShort(query.id.toShort())
        buf.putShort(flags.toShort())
        buf.putShort(1) // QDCOUNT — question echoed
        buf.putShort(0); buf.putShort(0); buf.putShort(0) // AN, NS, AR
        buf.put(packet, offset + 12, questionLen)
        return buf.array()
    }

    /** Plain UDP query (RD set) — used by the device harness and unit tests. */
    fun buildQuery(qname: String, id: Int, qtype: Int = 1): ByteArray {
        // Label bytes + their length bytes = qname.length + 1 (qname.length counts the dots;
        // the length-byte count makes up for them). Header 12 + that + root(1) + QTYPE/QCLASS(4).
        val buf = ByteBuffer.allocate(12 + qname.length + 1 + 1 + 4)
        buf.putShort(id.toShort())
        buf.putShort(0x0100) // RD
        buf.putShort(1); buf.putShort(0); buf.putShort(0); buf.putShort(0)
        for (label in qname.split('.')) {
            buf.put(label.length.toByte())
            buf.put(label.toByteArray(Charsets.US_ASCII))
        }
        buf.put(0)
        buf.putShort(qtype.toShort()); buf.putShort(1)
        return buf.array()
    }

    fun isReply(packet: ByteArray, offset: Int): Boolean = readShort(packet, offset + 2) and 0x8000 != 0
    fun rcode(packet: ByteArray, offset: Int): Int = readShort(packet, offset + 2) and 0xF
    fun answerCount(packet: ByteArray, offset: Int): Int = readShort(packet, offset + 6) and 0xFFFF
}
