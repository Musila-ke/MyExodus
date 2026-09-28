package com.example.myexodus.domainusage

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** U-4: the DNS parser is total — every structural violation is a result, never an exception. */
class DnsMessageTest {

    private fun parse(bytes: ByteArray): DnsMessage.Parsed = DnsMessage.parse(bytes)

    @Test
    fun validQueryParses() {
        val bytes = DnsFixtures.query("example.com", DnsMessage.TYPE_A, id = 0x1234)
        val parsed = parse(bytes) as DnsMessage.Parsed.Query
        assertEquals(0x1234, parsed.id)
        assertEquals("example.com", parsed.question.name)
        assertEquals(DnsMessage.TYPE_A, parsed.question.type)
        assertEquals(1, parsed.question.clazz)
        assertTrue(parsed.rd)
        assertArrayEquals(bytes.copyOfRange(12, bytes.size), parsed.questionSection)
    }

    @Test
    fun responsePreservesOwnerTypeClassTtlAndRdata() {
        val address = DnsFixtures.ip(93, 184, 216, 34)
        val bytes = DnsFixtures.response(
            "www.example.com",
            listOf(
                DnsFixtures.A(owner = "www.example.com", ip = address, ttl = 120L),
                DnsFixtures.Raw(owner = "www.example.com", type = 16, rdata = byteArrayOf(0, 1, 2, 3)),
            ),
        )
        val parsed = parse(bytes) as DnsMessage.Parsed.Response
        assertEquals(0, parsed.rcode)
        assertEquals("www.example.com", parsed.questionName)
        assertEquals(2, parsed.answers.size)
        val a = parsed.answers[0]
        assertEquals("www.example.com", a.owner)
        assertEquals(DnsMessage.TYPE_A, a.type)
        assertEquals(1, a.clazz)
        assertEquals(120L, a.ttlSeconds)
        assertArrayEquals(address, a.rdata)
        assertEquals(16, parsed.answers[1].type)
    }

    @Test
    fun backwardPointerResolvesAndTracksTheOwnersName() {
        val w = DnsFixtures.Writer()
        w.u16(0x2001)
        w.u16(0x8180)
        w.u16(1)
        w.u16(2)
        w.u16(0)
        w.u16(0)
        w.name("example.com")
        w.u16(DnsMessage.TYPE_A)
        w.u16(1)
        w.u16(0xC00C) // owner: the question name
        w.u16(DnsMessage.TYPE_CNAME)
        w.u16(1)
        w.u32(60L)
        val cnameStart = w.size
        w.u16(0)
        w.name("cdn.example.net")
        w.patchU16(cnameStart, w.size - cnameStart - 2)
        w.u8(0xC0) // second owner: pointer to the CNAME rdata name
        w.u8(cnameStart + 2)
        w.u16(DnsMessage.TYPE_A)
        w.u16(1)
        w.u32(60L)
        w.u16(4)
        w.bytes(DnsFixtures.ip(1, 2, 3, 4))

        val parsed = parse(w.toByteArray()) as DnsMessage.Parsed.Response
        assertEquals("example.com", parsed.answers[0].owner)
        assertEquals(5, parsed.answers[0].type)
        assertEquals("cdn.example.net", parsed.answers[0].cnameTarget)
        assertEquals("cdn.example.net", parsed.answers[1].owner)
    }

    @Test
    fun selfPointerAndForwardPointerFail() {
        val selfPointer = DnsFixtures.query("example.com", DnsMessage.TYPE_A).copyOf(16).also {
            it[12] = 0xC0.toByte() // pointer to offset 12 — itself
            it[13] = 0x0C.toByte()
        }
        assertEquals(DnsMessage.Parsed.Failed, parse(selfPointer))

        val forwardPointer = DnsFixtures.query("example.com", DnsMessage.TYPE_A).copyOf(16).also {
            it[12] = 0xC0.toByte()
            it[13] = 0x20.toByte()
        }
        assertEquals(DnsMessage.Parsed.Failed, parse(forwardPointer))
    }

    @Test
    fun truncatedInputsFail() {
        assertEquals(DnsMessage.Parsed.Failed, parse(ByteArray(0)))
        assertEquals(DnsMessage.Parsed.Failed, parse(ByteArray(11)))
        val headerOnlyQuery = DnsFixtures.query("example.com", DnsMessage.TYPE_A).copyOf(12)
        assertEquals(DnsMessage.Parsed.Failed, parse(headerOnlyQuery))
        val full = DnsFixtures.query("example.com", DnsMessage.TYPE_A)
        assertEquals(DnsMessage.Parsed.Failed, parse(full.copyOf(full.size - 3)))
        val withAnswer = DnsFixtures.response(
            "example.com",
            listOf(DnsFixtures.A("example.com", DnsFixtures.ip(1, 2, 3, 4))),
        )
        assertEquals(DnsMessage.Parsed.Failed, parse(withAnswer.copyOf(withAnswer.size - 2)))
    }

    @Test
    fun oversizeNamesFail() {
        val longLabel = DnsFixtures.Writer().apply {
            u16(0x1234); u16(0x0100); u16(1); u16(0); u16(0); u16(0)
            u8(64)
            bytes(ByteArray(64) { 'a'.code.toByte() })
            u8(0)
            u16(1); u16(1)
        }.toByteArray()
        assertEquals(DnsMessage.Parsed.Failed, parse(longLabel))

        val manyLabels = DnsFixtures.Writer().apply {
            u16(0x1234); u16(0x0100); u16(1); u16(0); u16(0); u16(0)
            repeat(5) {
                u8(63)
                bytes(ByteArray(63) { 'a'.code.toByte() })
            }
            u8(0)
            u16(1); u16(1)
        }.toByteArray()
        assertEquals(DnsMessage.Parsed.Failed, parse(manyLabels))
    }

    @Test
    fun nonStandardQueriesAreNotParseFailures() {
        val noQuestion = DnsFixtures.query("example.com", DnsMessage.TYPE_A).also {
            it[4] = 0; it[5] = 0
        }
        assertEquals(DnsMessage.Parsed.NotStandard, parse(noQuestion))

        val twoQuestions = DnsFixtures.query("example.com", DnsMessage.TYPE_A).also {
            it[4] = 0; it[5] = 2
        }
        assertEquals(DnsMessage.Parsed.NotStandard, parse(twoQuestions))

        val wrongOpcode = DnsFixtures.query("example.com", DnsMessage.TYPE_A).also {
            it[2] = (it[2].toInt() or 0x08).toByte()
        }
        assertEquals(DnsMessage.Parsed.NotStandard, parse(wrongOpcode))
    }

    @Test
    fun structuralMutationsAtEveryBoundaryNeverThrow() {
        val fixtures = listOf(
            DnsFixtures.query("example.com", DnsMessage.TYPE_A),
            DnsFixtures.response(
                "example.com",
                listOf(
                    DnsFixtures.Cname("example.com", "cdn.example.net"),
                    DnsFixtures.A("cdn.example.net", DnsFixtures.ip(1, 2, 3, 4)),
                ),
            ),
        )
        for (base in fixtures) {
            for (offset in 4..11) {
                for (value in intArrayOf(0x00, 0x01, 0x7F, 0xFF)) {
                    val mutated = base.copyOf()
                    mutated[offset] = value.toByte()
                    parse(mutated)
                }
            }
            for (i in base.indices) {
                val mutated = base.copyOf()
                mutated[i] = 0xC0.toByte()
                parse(mutated)
            }
        }
    }

    @Test
    fun fuzzMutationsNeverThrow() {
        val random = Random(20260920)
        val fixtures = listOf(
            DnsFixtures.query("example.com", DnsMessage.TYPE_A),
            DnsFixtures.response(
                "www.example.com",
                listOf(
                    DnsFixtures.Cname("www.example.com", "cdn.example.net", ttl = 30L),
                    DnsFixtures.A("cdn.example.net", DnsFixtures.ip(93, 184, 216, 34)),
                    DnsFixtures.Raw("cdn.example.net", 16, ByteArray(12)),
                ),
            ),
        )
        repeat(100_000) { i ->
            val base = fixtures[i % fixtures.size]
            val mutated = when (random.nextInt(4)) {
                0 -> base.copyOf().also { it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                1 -> base.copyOf(random.nextInt(base.size + 1))
                2 -> base + ByteArray(random.nextInt(16)) { random.nextInt(256).toByte() }
                else -> base.copyOf(base.size + 8).also {
                    for (j in base.size until it.size) it[j] = random.nextInt(256).toByte()
                }
            }
            DnsMessage.parse(mutated)
        }
    }
}
