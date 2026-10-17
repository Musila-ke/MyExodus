package com.example.myexodus.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The [VpnLifecycle.decide] contract: the tunnel runs exactly while (a) at least one live
 * website rule exists — block-only or limited, the tunnel serves both — and (b) the one-time
 * system consent is on file. STOP is asymmetric on purpose — it never needs consent (stopping
 * is always allowed), and it must also catch a half-started tunnel whose last rule vanished
 * mid-establish.
 */
class VpnLifecycleTest {

    @Test
    fun liveRulesWithConsent_startFromIdleAndFailed() {
        assertEquals(VpnLifecycle.Action.START, decide(hasRules = true, consented = true, VpnEngineState.STOPPED))
        // A failed establish (e.g. another VPN took over) retries at the next boundary.
        assertEquals(VpnLifecycle.Action.START, decide(hasRules = true, consented = true, VpnEngineState.FAILED))
    }

    @Test
    fun liveRulesWithConsent_neverRestartAnAliveTunnel() {
        assertEquals(VpnLifecycle.Action.NONE, decide(true, true, VpnEngineState.STARTING))
        assertEquals(VpnLifecycle.Action.NONE, decide(true, true, VpnEngineState.ACTIVE))
        // DEGRADED is a live tunnel (Private DNS / DB trouble) — the in-app banner owns it.
        assertEquals(VpnLifecycle.Action.NONE, decide(true, true, VpnEngineState.DEGRADED))
    }

    @Test
    fun revokedTunnel_restartsOnlyOnceConsentIsBack() {
        // Device-found 2026-10-04: revoke→re-grant dead-ended — REVOKED was excluded from
        // START, so after the user re-granted, nothing ever brought the tunnel back and the
        // Rules row had already vanished (consent was on file again).
        assertEquals(VpnLifecycle.Action.START, decide(true, true, VpnEngineState.REVOKED))
        // Without a fresh grant nothing starts — the platform forgot the consent at revoke.
        assertEquals(VpnLifecycle.Action.NONE, decide(true, false, VpnEngineState.REVOKED))
    }

    @Test
    fun liveRulesWithoutConsent_neverStartsAutonomously() {
        for (state in VpnEngineState.entries) {
            assertEquals(state.name, VpnLifecycle.Action.NONE, decide(true, false, state))
        }
    }

    @Test
    fun noLiveRules_stopsAnyRunningTunnel() {
        assertEquals(VpnLifecycle.Action.STOP, decide(false, true, VpnEngineState.ACTIVE))
        assertEquals(VpnLifecycle.Action.STOP, decide(false, true, VpnEngineState.DEGRADED))
        // The last limit can vanish while a start is still establishing.
        assertEquals(VpnLifecycle.Action.STOP, decide(false, true, VpnEngineState.STARTING))
        // ...and even with consent revoked mid-flight, an idle-door tunnel still goes down.
        assertEquals(VpnLifecycle.Action.STOP, decide(false, false, VpnEngineState.ACTIVE))
    }

    @Test
    fun noLiveRules_leavesAlreadyDownStatesAlone() {
        assertEquals(VpnLifecycle.Action.NONE, decide(false, true, VpnEngineState.STOPPED))
        assertEquals(VpnLifecycle.Action.NONE, decide(false, true, VpnEngineState.REVOKED))
        assertEquals(VpnLifecycle.Action.NONE, decide(false, true, VpnEngineState.FAILED))
    }

    /**
     * Owner report 2026-10-17: the Rules page must make "website blocking is off" obvious —
     * a website rule created while the tunnel is off blocks nothing and counts no usage.
     */
    @Test
    fun websiteEngineBlocker_namesOnlyTheStatesThatNeedTheUser() {
        // Missing consent is the one class only the platform dialog can clear, in every state.
        for (state in VpnEngineState.entries) {
            assertEquals(
                state.name,
                VpnLifecycle.WebsiteEngineBlocker.NEEDS_CONSENT,
                VpnLifecycle.websiteEngineBlocker(consented = false, state),
            )
        }
        // Healthy and startable states are not alarms: STOPPED with consent starts at the next
        // ensure boundary, and DEGRADED is a live tunnel owned by the S9.4 engine banner.
        assertNull(VpnLifecycle.websiteEngineBlocker(consented = true, VpnEngineState.ACTIVE))
        assertNull(VpnLifecycle.websiteEngineBlocker(consented = true, VpnEngineState.STARTING))
        assertNull(VpnLifecycle.websiteEngineBlocker(consented = true, VpnEngineState.DEGRADED))
        assertNull(VpnLifecycle.websiteEngineBlocker(consented = true, VpnEngineState.STOPPED))
        // Terminal down states: only an explicit retry brings the tunnel back.
        assertEquals(
            VpnLifecycle.WebsiteEngineBlocker.DOWN,
            VpnLifecycle.websiteEngineBlocker(consented = true, VpnEngineState.FAILED),
        )
        assertEquals(
            VpnLifecycle.WebsiteEngineBlocker.DOWN,
            VpnLifecycle.websiteEngineBlocker(consented = true, VpnEngineState.REVOKED),
        )
    }

    private fun decide(hasRules: Boolean, consented: Boolean, state: VpnEngineState) =
        VpnLifecycle.decide(hasRules, consented, state)
}
