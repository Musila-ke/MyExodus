package com.example.myexodus.vpn

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsUpstreamPolicyTest {

    private val v4Primary = InetAddress.getByName("8.8.8.8")
    private val v6Primary = InetAddress.getByName("2001:4860:4860::8888")
    private val netV4a = InetAddress.getByName("192.168.1.1")
    private val netV4b = InetAddress.getByName("10.0.0.1")
    private val netV6 = InetAddress.getByName("fe80::1")
    private val sinkholeV4 = InetAddress.getByName("192.0.2.53")
    private val sinkholeV6 = InetAddress.getByName("fd00:14:14::53")

    @Test
    fun candidates_primaryFirstThenNetworkOfSameFamily() {
        val got = DnsUpstreamPolicy.candidates(v4Primary, listOf(netV6, netV4a, netV4b), isV6 = false, exclude = emptySet())
        assertEquals(listOf(v4Primary, netV4a, netV4b), got)
    }

    @Test
    fun candidates_v6QueryGetsOnlyV6Candidates() {
        val got = DnsUpstreamPolicy.candidates(v6Primary, listOf(netV4a, netV6), isV6 = true, exclude = emptySet())
        assertEquals(listOf(v6Primary, netV6), got)
    }

    @Test
    fun candidates_dedupesPrimaryRepeatedByNetwork() {
        val got = DnsUpstreamPolicy.candidates(v4Primary, listOf(v4Primary, netV4a), isV6 = false, exclude = emptySet())
        assertEquals(listOf(v4Primary, netV4a), got)
    }

    @Test
    fun candidates_excludesSinkholeResolvers() {
        // A fallback must never loop back into our own TUN resolver.
        val exclude = setOf(sinkholeV4, sinkholeV6)
        assertEquals(
            listOf(v4Primary),
            DnsUpstreamPolicy.candidates(v4Primary, listOf(sinkholeV4), isV6 = false, exclude = exclude),
        )
        assertEquals(
            listOf(v6Primary),
            DnsUpstreamPolicy.candidates(v6Primary, listOf(sinkholeV6), isV6 = true, exclude = exclude),
        )
    }

    @Test
    fun pick_roundRobinsAndWraps() {
        val list = listOf(v4Primary, netV4a)
        assertEquals(v4Primary, DnsUpstreamPolicy.pick(list, 0))
        assertEquals(netV4a, DnsUpstreamPolicy.pick(list, 1))
        assertEquals(v4Primary, DnsUpstreamPolicy.pick(list, 2))
        assertNull(DnsUpstreamPolicy.pick(emptyList(), 0))
    }

    @Test
    fun hasGlobalV6Path_ignoresLinkLocalAndSiteLocal() {
        // The emulator hands out fec0::/64 with no v6 DNS path; counting it as one wedged
        // health at DEGRADED forever after one died AAAA query (device leg 2026-11-23).
        val siteLocal = InetAddress.getByName("fec0::5054:ff:fe12:3456")
        val linkLocal = InetAddress.getByName("fe80::5054:ff:fe12:3456")
        assertFalse(DnsUpstreamPolicy.hasGlobalV6Path(listOf(siteLocal, linkLocal)))
        assertFalse(DnsUpstreamPolicy.hasGlobalV6Path(listOf(v4Primary)))
        assertFalse(DnsUpstreamPolicy.hasGlobalV6Path(emptyList()))
        assertTrue(DnsUpstreamPolicy.hasGlobalV6Path(listOf(linkLocal, v6Primary)))
    }
}
