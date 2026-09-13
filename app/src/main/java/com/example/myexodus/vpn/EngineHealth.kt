package com.example.myexodus.vpn

/**
 * Engine health classification (plan §8.4): "do not conflate 'VPN interface up' with
 * 'filtering functioning' — check both independently before reporting ACTIVE". All inputs
 * are independent signals; the classifier is pure so the mapping is unit-testable.
 *
 * State mapping:
 *  - FAILED  — hard setup failure (TUN, DNS socket bind). The engine cannot run at all.
 *  - DEGRADED — the engine runs (VPN up, user keeps connectivity) but filtering is
 *    known-incomplete: policy failed to load (FAILED_POLICY_LOAD), a DNS receive loop died
 *    (DNS_LOOP_DOWN), or Private DNS/DoT is active and its traffic bypasses the sinkhole
 *    (PRIVATE_DNS_ACTIVE — the plan §6/§8.4 gap, disclosed rather than silently under-delivered).
 *  - ACTIVE — TUN up AND both DNS loops alive AND policy loaded AND no disclosed bypass.
 */
object EngineHealth {
    const val FAILED_TUN_SETUP = "FAILED_TUN_SETUP"
    const val FAILED_DNS_BIND = "FAILED_DNS_BIND"
    const val FAILED_POLICY_LOAD = "FAILED_POLICY_LOAD"
    const val DNS_LOOP_DOWN = "DNS_LOOP_DOWN"
    const val PRIVATE_DNS_ACTIVE = "PRIVATE_DNS_ACTIVE"

    /** Higher priority first: a hard failure beats a disclosed gap beats all-good. */
    fun decide(
        tunUp: Boolean,
        dnsLoopsAlive: Boolean,
        policyLoaded: Boolean,
        privateDnsActive: Boolean,
    ): Pair<VpnEngineState, String> = when {
        !tunUp -> VpnEngineState.FAILED to FAILED_TUN_SETUP
        !policyLoaded -> VpnEngineState.DEGRADED to FAILED_POLICY_LOAD
        !dnsLoopsAlive -> VpnEngineState.DEGRADED to DNS_LOOP_DOWN
        privateDnsActive -> VpnEngineState.DEGRADED to PRIVATE_DNS_ACTIVE
        else -> VpnEngineState.ACTIVE to ""
    }
}
