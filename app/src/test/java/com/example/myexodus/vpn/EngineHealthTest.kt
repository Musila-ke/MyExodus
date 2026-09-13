package com.example.myexodus.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineHealthTest {

    @Test
    fun allGood_active() {
        val (state, reason) = EngineHealth.decide(tunUp = true, dnsLoopsAlive = true, policyLoaded = true, privateDnsActive = false)
        assertEquals(VpnEngineState.ACTIVE, state)
        assertEquals("", reason)
    }

    @Test
    fun tunDown_failed() {
        // TUN setup failure dominates everything else.
        val (state, reason) = EngineHealth.decide(tunUp = false, dnsLoopsAlive = true, policyLoaded = true, privateDnsActive = true)
        assertEquals(VpnEngineState.FAILED, state)
        assertEquals(EngineHealth.FAILED_TUN_SETUP, reason)
    }

    @Test
    fun policyNotLoaded_degradedEvenWhenTunUp() {
        // The §8.4 core: "VPN interface up" != "filtering functioning".
        val (state, reason) = EngineHealth.decide(tunUp = true, dnsLoopsAlive = true, policyLoaded = false, privateDnsActive = false)
        assertEquals(VpnEngineState.DEGRADED, state)
        assertEquals(EngineHealth.FAILED_POLICY_LOAD, reason)
    }

    @Test
    fun dnsLoopDown_degraded() {
        val (state, reason) = EngineHealth.decide(tunUp = true, dnsLoopsAlive = false, policyLoaded = true, privateDnsActive = false)
        assertEquals(VpnEngineState.DEGRADED, state)
        assertEquals(EngineHealth.DNS_LOOP_DOWN, reason)
    }

    @Test
    fun privateDnsActive_degraded() {
        // The disclosed blocking gap (plan §6/§8.4): never ACTIVE while DoT can bypass.
        val (state, reason) = EngineHealth.decide(tunUp = true, dnsLoopsAlive = true, policyLoaded = true, privateDnsActive = true)
        assertEquals(VpnEngineState.DEGRADED, state)
        assertEquals(EngineHealth.PRIVATE_DNS_ACTIVE, reason)
    }

    @Test
    fun policyFailureBeatsPrivateDnsGap() {
        val (state, reason) = EngineHealth.decide(tunUp = true, dnsLoopsAlive = true, policyLoaded = false, privateDnsActive = true)
        assertEquals(VpnEngineState.DEGRADED, state)
        assertEquals(EngineHealth.FAILED_POLICY_LOAD, reason)
    }
}
