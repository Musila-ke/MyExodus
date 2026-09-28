package com.example.myexodus.domainusage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** U-5: the negative answers re-parse cleanly, echo the question, and carry the SOA. */
class DnsReplyBuilderTest {

    private val queryBytes: ByteArray =
        DnsFixtures.query("MixedCase.Example.com", DnsMessage.TYPE_AAAA, id = 0xBEEF)
    private val query: DnsMessage.Parsed.Query =
        DnsMessage.parse(queryBytes) as DnsMessage.Parsed.Query

    @Test
    fun nxdomainReparsesEchoesTheQuestionAndSetsFlags() {
        val reply = DnsMessage.buildNegativeReply(query, DnsMessage.RCODE_NXDOMAIN, MeteringConfig.NXDOMAIN_NEG_TTL_S)

        val reparsed = DnsMessage.parse(reply) as DnsMessage.Parsed.Response
        assertEquals(0xBEEF, reparsed.id)
        assertEquals(DnsMessage.RCODE_NXDOMAIN, reparsed.rcode)
        assertEquals("mixedcase.example.com", reparsed.questionName)
        assertTrue(reparsed.answers.isEmpty())

        assertArrayEquals(
            "question section is echoed byte-for-byte (casing preserved)",
            queryBytes.copyOfRange(12, queryBytes.size),
            reply.copyOfRange(12, queryBytes.size),
        )
        assertEquals(1, u16(reply, 4))
        assertEquals(0, u16(reply, 6))
        assertEquals(1, u16(reply, 8))
        assertEquals(0, u16(reply, 10))
        val flags = u16(reply, 2)
        assertEquals("QR", 1, (flags ushr 15) and 1)
        assertEquals("AA", 0, (flags ushr 10) and 1)
        assertEquals("TC", 0, (flags ushr 9) and 1)
        assertEquals("RD copied", 1, (flags ushr 8) and 1)
        assertEquals("RA", 1, (flags ushr 7) and 1)
    }

    @Test
    fun soaCarriesTheNegativeTtlAsTtlAndMinimum() {
        val reply = DnsMessage.buildNegativeReply(query, DnsMessage.RCODE_NXDOMAIN, MeteringConfig.NXDOMAIN_NEG_TTL_S)
        val soa = readAuthoritySoa(reply, query.questionSection.size)

        assertEquals(0xC00C, soa.ownerPointer)
        assertEquals(6, soa.type)
        assertEquals(1, soa.clazz)
        assertEquals(MeteringConfig.NXDOMAIN_NEG_TTL_S.toLong(), soa.ttl)
        assertEquals("ns.invalid", soa.mname)
        assertEquals("hostmaster.invalid", soa.rname)
        assertEquals(1L, soa.serial)
        assertEquals(3600L, soa.refresh)
        assertEquals(600L, soa.retry)
        assertEquals(86400L, soa.expire)
        assertEquals(MeteringConfig.NXDOMAIN_NEG_TTL_S.toLong(), soa.minimum)
        assertEquals(soa.rdLength, reply.size - (12 + query.questionSection.size + 12))
    }

    @Test
    fun nodataHasRcodeZeroAndItsOwnTtl() {
        val reply = DnsMessage.buildNegativeReply(query, DnsMessage.RCODE_NOERROR, MeteringConfig.NODATA_NEG_TTL_S)
        val reparsed = DnsMessage.parse(reply) as DnsMessage.Parsed.Response
        assertEquals(DnsMessage.RCODE_NOERROR, reparsed.rcode)
        assertTrue(reparsed.answers.isEmpty())

        val soa = readAuthoritySoa(reply, query.questionSection.size)
        assertEquals(MeteringConfig.NODATA_NEG_TTL_S.toLong(), soa.ttl)
        assertEquals(MeteringConfig.NODATA_NEG_TTL_S.toLong(), soa.minimum)
        assertEquals(1, u16(reply, 8))
    }

    private class Soa(
        val ownerPointer: Int,
        val type: Int,
        val clazz: Int,
        val ttl: Long,
        val rdLength: Int,
        val mname: String,
        val rname: String,
        val serial: Long,
        val refresh: Long,
        val retry: Long,
        val expire: Long,
        val minimum: Long,
    )

    private fun readAuthoritySoa(reply: ByteArray, questionBytes: Int): Soa {
        var off = 12 + questionBytes
        val ownerPointer = u16(reply, off); off += 2
        val type = u16(reply, off); off += 2
        val clazz = u16(reply, off); off += 2
        val ttl = u32(reply, off); off += 4
        val rdLength = u16(reply, off); off += 2
        val rdataStart = off
        val mnameRead = readUncompressedName(reply, off)
        val mname = mnameRead.first
        off = mnameRead.second
        val rnameRead = readUncompressedName(reply, off)
        val rname = rnameRead.first
        off = rnameRead.second
        val serial = u32(reply, off); off += 4
        val refresh = u32(reply, off); off += 4
        val retry = u32(reply, off); off += 4
        val expire = u32(reply, off); off += 4
        val minimum = u32(reply, off); off += 4
        assertEquals("rdlength matches the SOA rdata", rdLength, off - rdataStart)
        return Soa(ownerPointer, type, clazz, ttl, rdLength, mname, rname, serial, refresh, retry, expire, minimum)
    }

    private fun readUncompressedName(bytes: ByteArray, start: Int): Pair<String, Int> {
        var off = start
        val name = StringBuilder()
        while (true) {
            val length = bytes[off].toInt() and 0xFF
            off += 1
            if (length == 0) break
            if (name.isNotEmpty()) name.append('.')
            name.append(String(bytes, off, length, Charsets.US_ASCII))
            off += length
        }
        return name.toString() to off
    }

    private fun u16(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, off: Int): Long =
        ((bytes[off].toLong() and 0xFF) shl 24) or ((bytes[off + 1].toLong() and 0xFF) shl 16) or
            ((bytes[off + 2].toLong() and 0xFF) shl 8) or (bytes[off + 3].toLong() and 0xFF)
}
