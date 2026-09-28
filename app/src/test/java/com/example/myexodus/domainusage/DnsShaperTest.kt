package com.example.myexodus.domainusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** U-6: the §6.1 decision table and the §6.2 alias-chain walk. */
class DnsShaperTest {

    private val clock = FakeMonotonicClock()
    private val counters = EngineCounters()
    private val observations = DnsObservationStore(clock)
    private val shaper = DnsShaper(counters, observations)

    private val exhaustedRule = DomainRule("r1", 0, "example.com", 3_600_000, enforcedToday = true)
    private val liveRule = DomainRule("r2", 0, "example.com", 3_600_000, enforcedToday = true)
    private val exhaustedIndex = RuleIndex.build(listOf(exhaustedRule), counters)
    private val liveIndex = RuleIndex.build(listOf(liveRule), counters)

    private fun queryBytes(name: String, type: Int, id: Int = 0x1234): ByteArray =
        DnsFixtures.query(name, type, id)

    private fun decide(
        name: String = "example.com",
        type: Int = DnsMessage.TYPE_A,
        mode: EngineMode = EngineMode.ENFORCE,
        index: RuleIndex = liveIndex,
        isExhausted: Boolean = false,
    ): DnsShaper.Decision =
        shaper.decide(queryBytes(name, type), mode, index) { isExhausted }

    // --- §6.1 truth table ---

    @Test
    fun offAndAccountNeverShapeAnyQtypeInAnyRuleState() {
        val emptyIndex = RuleIndex.build(emptyList(), counters)
        // no match / enabled-not-exhausted / exhausted
        val states = listOf("no-match" to emptyIndex, "live" to liveIndex, "exhausted" to exhaustedIndex)
        for (mode in listOf(EngineMode.OFF, EngineMode.ACCOUNT)) {
            for (type in listOf(
                DnsMessage.TYPE_A,
                DnsMessage.TYPE_AAAA,
                DnsMessage.TYPE_SVCB,
                DnsMessage.TYPE_HTTPS,
                15,
            )) {
                for ((label, index) in states) {
                    for (exhausted in listOf(false, true)) {
                        assertEquals(
                            "mode=$mode type=$type state=$label exhausted=$exhausted",
                            DnsShaper.Decision.Forward,
                            decide(type = type, mode = mode, index = index, isExhausted = exhausted),
                        )
                    }
                }
            }
        }
        assertEquals(0L, counters.get(Counter.dns_nxdomain_served))
        assertEquals(0L, counters.get(Counter.dns_nodata_aaaa))
        assertEquals(0L, counters.get(Counter.dns_nodata_svcb))
        assertEquals(0L, counters.get(Counter.dns_nodata_https))
    }

    @Test
    fun enforceGivesNxdomainForAnExhaustedRuleAtEveryQtype() {
        for (type in listOf(DnsMessage.TYPE_A, DnsMessage.TYPE_AAAA, DnsMessage.TYPE_SVCB, DnsMessage.TYPE_HTTPS, 15)) {
            val decision = decide(type = type, index = exhaustedIndex, isExhausted = true)
            val bytes = (decision as DnsShaper.Decision.Respond).bytes
            val reparsed = DnsMessage.parse(bytes) as DnsMessage.Parsed.Response
            assertEquals("type=$type", DnsMessage.RCODE_NXDOMAIN, reparsed.rcode)
            assertTrue(reparsed.answers.isEmpty())
        }
        assertEquals(5L, counters.get(Counter.dns_nxdomain_served))
    }

    @Test
    fun nxdomainWinsOverTheEnabledNotExhaustedNodataBranch() {
        val decision = decide(type = DnsMessage.TYPE_AAAA, index = exhaustedIndex, isExhausted = true)
        val reparsed = DnsMessage.parse((decision as DnsShaper.Decision.Respond).bytes) as DnsMessage.Parsed.Response
        assertEquals(DnsMessage.RCODE_NXDOMAIN, reparsed.rcode)
        assertEquals(0L, counters.get(Counter.dns_nodata_aaaa))
    }

    @Test
    fun enforceGivesNodataOnlyForAaaaSvcbHttpsWhileNotExhausted() {
        val shaped = mapOf(
            DnsMessage.TYPE_AAAA to Counter.dns_nodata_aaaa,
            DnsMessage.TYPE_SVCB to Counter.dns_nodata_svcb,
            DnsMessage.TYPE_HTTPS to Counter.dns_nodata_https,
        )
        for ((type, counter) in shaped) {
            val decision = decide(type = type)
            val reparsed = DnsMessage.parse((decision as DnsShaper.Decision.Respond).bytes) as DnsMessage.Parsed.Response
            assertEquals("type=$type rcode", DnsMessage.RCODE_NOERROR, reparsed.rcode)
            assertTrue(reparsed.answers.isEmpty())
            assertEquals("type=$type counter", 1L, counters.get(counter))
        }
        for (type in listOf(DnsMessage.TYPE_A, DnsMessage.TYPE_CNAME, 15, 16, 33)) {
            assertEquals("type=$type forwards", DnsShaper.Decision.Forward, decide(type = type))
        }
    }

    @Test
    fun unmatchedNamesNeverShape() {
        for (type in listOf(DnsMessage.TYPE_A, DnsMessage.TYPE_AAAA, DnsMessage.TYPE_SVCB, DnsMessage.TYPE_HTTPS)) {
            for (exhausted in listOf(false, true)) {
                assertEquals(
                    "type=$type exhausted=$exhausted",
                    DnsShaper.Decision.Forward,
                    decide(name = "unrelated.example.net", type = type, isExhausted = exhausted),
                )
            }
        }
    }

    @Test
    fun subdomainsMatchAndCasingDoesNotBypassShaping() {
        assertEquals(
            DnsShaper.Decision.Forward,
            decide(name = "example.com", type = DnsMessage.TYPE_A),
        )
        assertTrue(
            "subdomain of the rule",
            decide(name = "deep.sub.example.com", type = DnsMessage.TYPE_AAAA) is DnsShaper.Decision.Respond,
        )
        assertTrue(
            "mixed casing",
            decide(name = "WWW.Example.COM", type = DnsMessage.TYPE_AAAA) is DnsShaper.Decision.Respond,
        )
    }

    @Test
    fun neverBlockAndResolverHostsAreForwardedEvenUnderEnforce() {
        val index = RuleIndex.build(
            listOf(
                DomainRule("r", 0, "gstatic.com", 3_600_000, enforcedToday = true),
                DomainRule("r2", 0, "dns.google", 3_600_000, enforcedToday = true),
            ),
            counters,
        )
        assertEquals(
            DnsShaper.Decision.Forward,
            decide(name = "connectivitycheck.gstatic.com", type = DnsMessage.TYPE_AAAA, index = index),
        )
        assertEquals(
            DnsShaper.Decision.Forward,
            decide(name = "dns.google", type = DnsMessage.TYPE_AAAA, index = index),
        )
    }

    @Test
    fun parseFailureAndNonStandardQueriesForwardAndCount() {
        assertEquals(
            DnsShaper.Decision.Forward,
            shaper.decide(ByteArray(5), EngineMode.ENFORCE, exhaustedIndex) { true },
        )
        assertEquals(1L, counters.get(Counter.dns_parse_fail))

        val twoQuestions = queryBytes("example.com", DnsMessage.TYPE_A).also { it[4] = 0; it[5] = 2 }
        assertEquals(
            DnsShaper.Decision.Forward,
            shaper.decide(twoQuestions, EngineMode.ENFORCE, exhaustedIndex) { true },
        )
        assertEquals("NotStandard is not a parse failure", 1L, counters.get(Counter.dns_parse_fail))
    }

    @Test
    fun everyQueryIncrementsTheQueryCounterAtAnyMode() {
        shaper.decide(queryBytes("example.com", DnsMessage.TYPE_A), EngineMode.OFF, liveIndex) { false }
        shaper.decide(queryBytes("example.com", DnsMessage.TYPE_AAAA), EngineMode.ACCOUNT, liveIndex) { false }
        shaper.decide(queryBytes("example.com", DnsMessage.TYPE_AAAA), EngineMode.ENFORCE, liveIndex) { false }
        assertEquals(3L, counters.get(Counter.dns_queries))
    }

    // --- §6.2 response walk ---

    private fun countObserved(ip: ByteArray): Int = observations.candidates(ip).size

    @Test
    fun aliasesChainFromTheQuestionNameBeforeAnARecordIsStored() {
        val ip = DnsFixtures.ip(93, 184, 216, 34)
        val response = DnsFixtures.response(
            "www.example.com",
            listOf(
                DnsFixtures.Cname("www.example.com", "cdn.example.net", ttl = 60L),
                DnsFixtures.A("cdn.example.net", ip, ttl = 60L),
            ),
        )
        shaper.onResponse(response)
        assertEquals(listOf("www.example.com"), observations.candidates(ip))
    }

    @Test
    fun anAOutsideTheChainIsIgnored() {
        val ip = DnsFixtures.ip(93, 184, 216, 34)
        val outside = DnsFixtures.response(
            "www.example.com",
            listOf(DnsFixtures.A("elsewhere.example.net", ip, ttl = 60L)),
        )
        shaper.onResponse(outside)
        assertEquals(0, countObserved(ip))

        val noCname = DnsFixtures.response(
            "www.example.com",
            listOf(DnsFixtures.A("cdn.example.net", ip, ttl = 60L)),
        )
        shaper.onResponse(noCname)
        assertEquals(0, countObserved(ip))
    }

    @Test
    fun cnameWithoutARecordStoresNothingAndNonZeroRcodeIsIgnored() {
        val ip = DnsFixtures.ip(93, 184, 216, 34)
        shaper.onResponse(
            DnsFixtures.response("www.example.com", listOf(DnsFixtures.Cname("www.example.com", "cdn.example.net"))),
        )
        assertEquals(0, countObserved(ip))

        val nxdomain = DnsFixtures.response(
            "www.example.com",
            listOf(DnsFixtures.A("www.example.com", ip, ttl = 60L)),
            rcode = DnsMessage.RCODE_NXDOMAIN,
        )
        shaper.onResponse(nxdomain)
        assertEquals(0, countObserved(ip))
    }

    @Test
    fun aaaaRecordsAreRecordedSoV6FlowsCanBeAttributed() {
        // Without this the DNS-fallback path is IPv4-only: no IPv6 flow is ever attributed,
        // so a v6-only network gets no metering and no enforcement at all (§6.4, I-13).
        val ip = ByteArray(16) { (it + 1).toByte() }
        shaper.onResponse(
            DnsFixtures.response(
                "www.example.com",
                listOf(DnsFixtures.Aaaa("www.example.com", ip, ttl = 60L)),
            ),
        )
        assertEquals(listOf("www.example.com"), observations.candidates(ip))
    }

    @Test
    fun anAaaaRecordWhoseRdataIsNotSixteenBytesIsNeverRecorded() {
        val notAnIpv6 = DnsFixtures.ip(93, 184, 216, 34)
        shaper.onResponse(
            DnsFixtures.response(
                "www.example.com",
                listOf(DnsFixtures.Raw("www.example.com", DnsMessage.TYPE_AAAA, notAnIpv6)),
            ),
        )
        assertEquals(0, countObserved(notAnIpv6))
    }

    @Test
    fun anARecordWhoseRdataIsNotFourBytesIsNeverRecorded() {
        val notAnIpv4 = ByteArray(16) { it.toByte() }
        shaper.onResponse(
            DnsFixtures.response(
                "www.example.com",
                listOf(DnsFixtures.Raw("www.example.com", DnsMessage.TYPE_A, notAnIpv4)),
            ),
        )
        assertEquals(0, countObserved(notAnIpv4))
    }

    @Test
    fun malformedResponseCountsAParseFailure() {
        shaper.onResponse(ByteArray(9))
        assertEquals(1L, counters.get(Counter.dns_parse_fail))
    }

    @Test
    fun aNonAResponseIsNotAParseFailure() {
        val query = DnsFixtures.query("example.com", DnsMessage.TYPE_A)
        shaper.onResponse(query)
        assertEquals(0L, counters.get(Counter.dns_parse_fail))
        assertEquals(0L, counters.get(Counter.dns_queries))
    }

    @Test
    fun theTtlTravelsToTheObservationStore() {
        val ip = DnsFixtures.ip(93, 184, 216, 34)
        shaper.onResponse(
            DnsFixtures.response(
                "example.com",
                listOf(DnsFixtures.A("example.com", ip, ttl = 120L)),
            ),
        )
        clock.now = 119_000L
        assertEquals(listOf("example.com"), observations.candidates(ip))
        clock.now = 121_000L
        assertEquals(emptyList<String>(), observations.candidates(ip))
    }
}
