package com.example.myexodus.domainusage

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Engine's four Baseline-facing hooks (§4.2, §6.1–6.4): mode scoping, the DNS-fallback
 * admission at open, the UDP drop-before-anything-happens rule, and the DNS query/response seams.
 * The tap internals live in `InspectingTcpTapTest`; here the Engine facade is the unit.
 */
class DomainUsageEngineHooksTest {

    private class RecordingControl : FlowControl {
        var resets = 0
        override fun reset() {
            resets++
        }
    }

    private class Harness(mode: EngineMode = EngineMode.ENFORCE) {
        val counters = EngineCounters()
        val mono = FakeMonotonicClock()
        val wall = FakeWallClock()
        val engine = DomainUsageEngine(mode, mono, counters)
        val ledger = UsageLedger(mono, wall, counters, engine.registry, mode, ZoneId.of("UTC"), "2026-09-20")
        val control = RecordingControl()
        val ip = DnsFixtures.ip(93, 184, 216, 34)

        init {
            engine.ledger = ledger
        }

        fun use(vararg patterns: String) {
            engine.updateRules(patterns.mapIndexed { i, p -> DomainRule("r${i + 1}", 0L, p, 3_600_000L, true) })
        }

        fun counter(id: String = "r1") = ledger.counterFor(id, 0L)!!
    }

    // --- OFF is a hard short-circuit ---

    @Test
    fun offModeShortCircuitsEveryHook() {
        val h = Harness(EngineMode.OFF)
        h.use("example.com")
        val query = DnsFixtures.query("example.com", DnsMessage.TYPE_A)

        assertSame(PassThroughTap, h.engine.tcpFlowOpened(h.ip, 443, h.control, FlowOwner.BROWSER))
        assertSame(PassThroughTap, h.engine.tcpFlowOpened(h.ip, 80, h.control, FlowOwner.BROWSER))
        val admission = h.engine.udpFlowOpened(h.ip, 53, h.control, FlowOwner.BROWSER) as UdpAdmission.Allow
        assertSame(PassThroughTap, admission.tap)
        assertSame(DnsQueryResult.Forward, h.engine.onDnsQuery(query, query.size))

        assertEquals(0L, h.counters.snapshot().values.sum())
    }

    // --- TCP admission (§6.3/§6.4 for non-443) ---

    @Test
    fun aNon443FlowIsAttributedByDnsAtOpenAndMetered() {
        val h = Harness()
        h.use("limited.example")
        h.engine.observations.record(h.ip, "limited.example", 300)

        val tap = h.engine.tcpFlowOpened(h.ip, 80, h.control, FlowOwner.BROWSER)
        assertEquals(ClientDataResult.PassThrough, tap.onClientData(ByteArray(1_000), 0, 1_000))
        h.mono.now = 1_000
        h.ledger.tick()

        assertEquals(1_000L, h.counter().totalMs)
        assertEquals(1L, h.counters.get(Counter.flows_tcp_total))
        assertEquals(1L, h.counters.get(Counter.flows_attributed_total))
    }

    @Test
    fun aNon443FlowWithoutEvidenceIsUnattributedAndUnmetered() {
        val h = Harness()
        h.use("limited.example")

        val tap = h.engine.tcpFlowOpened(h.ip, 80, h.control, FlowOwner.BROWSER)
        assertEquals(ClientDataResult.PassThrough, tap.onClientData(ByteArray(1_000), 0, 1_000))
        h.mono.now = 1_000
        h.ledger.tick()

        assertEquals(1L, h.counters.get(Counter.flows_unattributed))
        assertEquals(0L, h.counter().totalMs)
    }

    @Test
    fun anExhaustedRuleCutsANon443FlowAtAttribution() {
        val h = Harness()
        h.use("limited.example")
        h.engine.observations.record(h.ip, "limited.example", 300)
        h.counter().exhausted = true

        h.engine.tcpFlowOpened(h.ip, 80, h.control, FlowOwner.BROWSER)

        assertEquals(1, h.control.resets)
        assertEquals(1L, h.counters.get(Counter.flow_resets_new))
    }

    // --- UDP admission (§6.4, ADR #70's 5-tuple key is the Baseline's) ---

    @Test
    fun anExhaustedRuleDropsTheUdpFlowBeforeAnythingIsOpened() {
        val h = Harness()
        h.use("limited.example")
        h.engine.observations.record(h.ip, "limited.example", 300)
        h.counter().exhausted = true

        assertSame(UdpAdmission.Drop, h.engine.udpFlowOpened(h.ip, 443, h.control, FlowOwner.BROWSER))
        assertEquals(1L, h.counters.get(Counter.udp_dropped))
        assertEquals("the drop is protocol-level, not a socket teardown", 0, h.control.resets)
        assertTrue(h.engine.registry.isEmpty(h.counter().key))
    }

    @Test
    fun anAttributedUdpFlowIsAllowedAndRegistered() {
        val h = Harness()
        h.use("limited.example")
        h.engine.observations.record(h.ip, "limited.example", 300)

        val admission = h.engine.udpFlowOpened(h.ip, 443, h.control, FlowOwner.BROWSER) as UdpAdmission.Allow
        assertEquals(ClientDataResult.PassThrough, admission.tap.onClientData(ByteArray(600), 0, 600))
        h.mono.now = 1_000
        h.ledger.tick()

        assertEquals(1_000L, h.counter().totalMs)
        admission.tap.onClosed()
        assertTrue(h.engine.registry.isEmpty(h.counter().key))
    }

    @Test
    fun anUnattributedUdpFlowIsAllowedWithoutMetering() {
        val h = Harness()
        h.use("limited.example")

        val admission = h.engine.udpFlowOpened(h.ip, 443, h.control, FlowOwner.BROWSER) as UdpAdmission.Allow
        assertSame(PassThroughTap, admission.tap)
        assertEquals(0L, h.counters.get(Counter.flows_attributed_total))
        assertEquals(1L, h.counters.get(Counter.flows_owner_browser))
    }

    @Test
    fun accountModeAllowsAnExhaustedUdpFlow() {
        val h = Harness(EngineMode.ACCOUNT)
        h.use("limited.example")
        h.engine.observations.record(h.ip, "limited.example", 300)
        h.counter().exhausted = true

        val admission = h.engine.udpFlowOpened(h.ip, 443, h.control, FlowOwner.BROWSER) as UdpAdmission.Allow
        assertEquals(ClientDataResult.PassThrough, admission.tap.onClientData(ByteArray(600), 0, 600))
        assertEquals(0L, h.counters.get(Counter.udp_dropped))
    }

    // --- ADR #189: website rules meter browser traffic only ---

    @Test
    fun aNonBrowserFlowIsPassedThroughWithoutMeteringOrAttribution() {
        val h = Harness()
        h.use("limited.example")
        h.engine.observations.record(h.ip, "limited.example", 300)

        assertSame(PassThroughTap, h.engine.tcpFlowOpened(h.ip, 443, h.control, FlowOwner.NON_BROWSER))
        assertSame(PassThroughTap, h.engine.tcpFlowOpened(h.ip, 80, h.control, FlowOwner.NON_BROWSER))
        val admission = h.engine.udpFlowOpened(h.ip, 443, h.control, FlowOwner.NON_BROWSER) as UdpAdmission.Allow
        assertSame(PassThroughTap, admission.tap)
        h.mono.now = 1_000
        h.ledger.tick()

        assertEquals("the site's own app must not move the website counter", 0L, h.counter().totalMs)
        assertEquals(0L, h.counters.get(Counter.flows_attributed_total))
        assertEquals(3L, h.counters.get(Counter.flows_owner_non_browser))
        assertTrue(h.engine.registry.isEmpty(h.counter().key))
    }

    @Test
    fun anUnknownOwnerIsNeverMeteredAndNeverReset() {
        val h = Harness()
        h.use("limited.example")
        h.engine.observations.record(h.ip, "limited.example", 300)
        h.counter().exhausted = true

        assertSame(PassThroughTap, h.engine.tcpFlowOpened(h.ip, 80, h.control, FlowOwner.UNKNOWN))
        val admission = h.engine.udpFlowOpened(h.ip, 443, h.control, FlowOwner.UNKNOWN) as UdpAdmission.Allow
        assertSame(PassThroughTap, admission.tap)
        h.mono.now = 1_000
        h.ledger.tick()

        assertEquals("an unresolved owner is never cut by a website quota", 0, h.control.resets)
        assertEquals(0L, h.counters.get(Counter.flow_resets_new))
        assertEquals(0L, h.counters.get(Counter.udp_dropped))
        assertEquals(0L, h.counter().totalMs)
        assertEquals(2L, h.counters.get(Counter.flows_owner_unknown))
    }

    @Test
    fun anExhaustedRuleStillCutsABrowserFlow() {
        val h = Harness()
        h.use("limited.example")
        h.engine.observations.record(h.ip, "limited.example", 300)
        h.counter().exhausted = true

        h.engine.tcpFlowOpened(h.ip, 80, h.control, FlowOwner.BROWSER)

        assertEquals(1, h.control.resets)
        assertEquals(1L, h.counters.get(Counter.flow_resets_new))
    }

    // --- DNS seams (§6.1/§6.2) ---

    @Test
    fun dnsQueriesAreShapedOnlyByAnExhaustedRuleInEnforce() {
        val enforce = Harness()
        enforce.use("limited.example")
        val query = DnsFixtures.query("limited.example", DnsMessage.TYPE_A)

        assertSame(DnsQueryResult.Forward, enforce.engine.onDnsQuery(query, query.size))

        enforce.counter().exhausted = true
        assertTrue(enforce.engine.onDnsQuery(query, query.size) is DnsQueryResult.Respond)
        assertEquals(1L, enforce.counters.get(Counter.dns_nxdomain_served))

        val account = Harness(EngineMode.ACCOUNT)
        account.use("limited.example")
        account.counter().exhausted = true
        assertSame(DnsQueryResult.Forward, account.engine.onDnsQuery(query, query.size))
        assertEquals(0L, account.counters.get(Counter.dns_nxdomain_served))
    }

    @Test
    fun aForwardedQueryPrefixIsParsedByItsLengthNotTheBufferSize() {
        val h = Harness()
        h.use("limited.example")
        val query = DnsFixtures.query("limited.example", DnsMessage.TYPE_A)
        val padded = query + ByteArray(64) { 0xFF.toByte() }
        h.counter().exhausted = true

        assertTrue(h.engine.onDnsQuery(padded, query.size) is DnsQueryResult.Respond)
    }

    @Test
    fun aResponseFeedsTheObservationStoreThroughTheEngineSeam() {
        val h = Harness()
        val ip = DnsFixtures.ip(93, 184, 216, 34)
        val response = DnsFixtures.response("www.limited.example", listOf(DnsFixtures.A("www.limited.example", ip, ttl = 60L)))

        h.engine.onDnsResponse(response, response.size)

        assertEquals(listOf("www.limited.example"), h.engine.observations.candidates(ip))
    }

    @Test
    fun withoutALedgerEveryHookStaysPassThrough_untilTheStartupLoadSucceeds() {
        val h = Harness()
        h.use("limited.example")
        h.counter().exhausted = true
        h.engine.ledger = null // §6.8: the load has not finished (or failed)
        val query = DnsFixtures.query("limited.example", DnsMessage.TYPE_A)
        val aaaa = DnsFixtures.query("limited.example", DnsMessage.TYPE_AAAA)

        assertSame(DnsQueryResult.Forward, h.engine.onDnsQuery(query, query.size))
        assertSame(
            "no AAAA suppression before the totals are known",
            DnsQueryResult.Forward,
            h.engine.onDnsQuery(aaaa, aaaa.size),
        )
        assertSame(PassThroughTap, h.engine.tcpFlowOpened(h.ip, 80, h.control, FlowOwner.BROWSER))
        val admission = h.engine.udpFlowOpened(h.ip, 443, h.control, FlowOwner.BROWSER) as UdpAdmission.Allow
        assertSame(PassThroughTap, admission.tap)
        assertEquals("an exhausted rule cannot cut a flow against totals that never loaded", 0, h.control.resets)
        assertEquals(0L, h.counters.get(Counter.dns_nxdomain_served))
        assertEquals(0L, h.counters.get(Counter.dns_nodata_aaaa))
        assertEquals(0L, h.counters.get(Counter.flows_attributed_total))
    }

    @Test
    fun theCounterSetIsUntouchedByAnEmptyRuleSnapshot() {
        val h = Harness()
        val query = DnsFixtures.query("limited.example", DnsMessage.TYPE_A)

        h.engine.tcpFlowOpened(h.ip, 80, h.control, FlowOwner.BROWSER)
        val admission = h.engine.udpFlowOpened(h.ip, 443, h.control, FlowOwner.BROWSER) as UdpAdmission.Allow
        h.engine.onDnsQuery(query, query.size)

        assertSame("no rule ⇒ nothing is ever held, dropped or shaped (FR-12)", PassThroughTap, admission.tap)
        assertEquals(0L, h.counters.get(Counter.flows_attributed_total))
        assertEquals(0L, h.counters.get(Counter.dns_nxdomain_served))
    }

    /** §6.10: the packet layer's report is one of exactly two counters, never both. */
    @Test
    fun theIcmpNudgeReport_splitsInjectedFromBuildFailure() {
        val h = Harness()

        h.engine.onIcmpNudge(true)
        h.engine.onIcmpNudge(true)
        assertEquals(2L, h.counters.get(Counter.icmp_injected))
        assertEquals("a written nudge is not a build failure", 0L, h.counters.get(Counter.icmp_build_fail))

        h.engine.onIcmpNudge(false)
        assertEquals(2L, h.counters.get(Counter.icmp_injected))
        assertEquals(1L, h.counters.get(Counter.icmp_build_fail))

        h.engine.onRstNudge(false)
        h.engine.onRstNudge(true)
        assertEquals(1L, h.counters.get(Counter.rst_injected))
        assertEquals(1L, h.counters.get(Counter.rst_build_fail))
    }
}
