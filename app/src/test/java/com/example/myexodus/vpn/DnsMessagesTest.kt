package com.example.myexodus.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessagesTest {

    @Test
    fun parseQuery_roundTrip() {
        val packet = DnsMessages.buildQuery("www.example.com", 0x1234)
        val query = DnsMessages.parseQuery(packet, 0)!!
        assertEquals(0x1234, query.id)
        assertEquals("www.example.com", query.qname)
        assertEquals(packet.size, query.questionEnd)
    }

    @Test
    fun parseQuery_lowercasesAndStripsTrailingDotCase() {
        val packet = DnsMessages.buildQuery("Blocked.Example.COM", 7)
        val query = DnsMessages.parseQuery(packet, 0)!!
        assertEquals("blocked.example.com", query.qname)
        assertTrue(DomainBlocklist.of("blocked.example.com").isBlocked(query.qname))
    }

    @Test
    fun parseQuery_rejectsMalformed() {
        val packet = DnsMessages.buildQuery("example.com", 1)
        // QDCOUNT = 2.
        val two = packet.clone()
        two[5] = 2
        assertNull(DnsMessages.parseQuery(two, 0))
        // Compression pointer in the qname.
        val compressed = packet.clone()
        compressed[12] = 0xC0.toByte()
        assertNull(DnsMessages.parseQuery(compressed, 0))
        // Truncated question (no QTYPE/QCLASS).
        assertNull(DnsMessages.parseQuery(packet.copyOf(packet.size - 1), 0))
    }

    @Test
    fun buildNxDomain_echoesQuestionWithRcode3() {
        val packet = DnsMessages.buildQuery("blocked.example.com", 0xBEEF)
        val query = DnsMessages.parseQuery(packet, 0)!!
        val reply = DnsMessages.buildNxDomain(query, packet, 0)
        assertEquals(packet.size, reply.size)
        assertTrue("QR must be set", DnsMessages.isReply(reply, 0))
        assertTrue("RD must be echoed", readRd(reply))
        assertEquals(3, DnsMessages.rcode(reply, 0))
        assertEquals(1, readShortAt(reply, 4)) // QDCOUNT echoes the question
        assertEquals(0, DnsMessages.answerCount(reply, 0))
        assertTrue("question section must be verbatim", packet.copyOfRange(12, packet.size).contentEquals(reply.copyOfRange(12, reply.size)))
        assertEquals(0xBEEF, readShortAt(reply, 0))
    }

    @Test
    fun blocklist_suffixMatching() {
        // The spike's blocked targets, matched via the engine matcher (the allowed test
        // target example.com is deliberately not in the set).
        val list = DomainBlocklist.of("blocked.example.com", "betting.example.com", "gambling.example.com")
        assertTrue(list.isBlocked("blocked.example.com"))
        assertTrue(list.isBlocked("www.blocked.example.com"))
        assertTrue(list.isBlocked("betting.example.com"))
        assertTrue(list.isBlocked("gambling.example.com"))
        assertFalse("the allowed test target must resolve", list.isBlocked("example.com"))
        assertFalse(list.isBlocked("www.example.com"))
        assertFalse(list.isBlocked("notblocked.example.com"))
        assertFalse(list.isBlocked("example.com.evil.net"))
        assertFalse(list.isBlocked(""))
    }

    private fun readRd(packet: ByteArray): Boolean =
        readShortAt(packet, 2) and 0x0100 != 0

    private fun readShortAt(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
}
