package com.example.myexodus.domainusage

/**
 * §4.2: the per-flow contract between the Baseline and the Engine. The Baseline owns the flow;
 * the Engine never touches sockets or packets, only the byte ranges it is handed.
 */
sealed interface ClientDataResult {
    /** Consumed by the tap (held for inspection); the Baseline forwards nothing for this chunk. */
    data object Hold : ClientDataResult

    /**
     * Forward these bytes instead of the caller's; the caller MUST call `data.release()` once it
     * has fully written or queued them (INV-13).
     */
    class Forward(val data: OwnedBytes) : ClientDataResult

    /** Forward the caller's bytes unchanged; nothing to release. */
    data object PassThrough : ClientDataResult

    /** TCP: the flow was reset — forward nothing. UDP: drop this datagram. */
    data object Reset : ClientDataResult
}

/** One TCP flow's or UDP 5-tuple's Engine-side hook (see [ClientDataResult] for the contract). */
interface FlowTap {
    fun onClientData(data: ByteArray, off: Int, len: Int): ClientDataResult

    /** Server→client chunk length, for activity accounting. No bytes are copied for it. */
    fun onServerData(len: Int)

    /** The Baseline's flow is gone: release whatever the tap still owns, exactly once. */
    fun onClosed()
}

/** §4.2: the UDP flow-admission decision, taken once per 5-tuple. */
sealed interface UdpAdmission {
    data object Drop : UdpAdmission

    class Allow(val tap: FlowTap) : UdpAdmission
}

/** §4.2: the DNS hook's answer — forward upstream, or write these reply bytes to the TUN. */
sealed interface DnsQueryResult {
    data object Forward : DnsQueryResult

    class Respond(val bytes: ByteArray, val length: Int) : DnsQueryResult
}

/** Dormant/unattributed flows get this: nothing held, nothing metered (FR-12). Stateless. */
data object PassThroughTap : FlowTap {
    override fun onClientData(data: ByteArray, off: Int, len: Int): ClientDataResult = ClientDataResult.PassThrough
    override fun onServerData(len: Int) = Unit
    override fun onClosed() = Unit
}

/**
 * §4.6 for every non-443 TCP flow: DNS-fallback attribution is decided at open, so the tap only
 * has to count activity. Registration is per flow (never per chunk) — the relay hot path touches
 * its [RuleCounter] and nothing else (INV-12).
 */
internal class ActivityTap(
    private val engine: DomainUsageEngine,
    private val counter: RuleCounter,
    private val control: FlowControl,
) : FlowTap {

    override fun onClientData(data: ByteArray, off: Int, len: Int): ClientDataResult {
        counter.touchChunk(len, engine.clock)
        return ClientDataResult.PassThrough
    }

    override fun onServerData(len: Int) = counter.touchChunk(len, engine.clock)

    override fun onClosed() = engine.registry.unregister(counter.key, control)
}

/**
 * §6.4 for UDP flows attributed to a rule. A UDP flow has no reader thread of its own, so the
 * plan's "mark the flow blocked, drop later datagrams" is realized here, on every datagram: the
 * ledger's exhausted flag is the single source of truth (INV-4), and `control.reset()` — which the
 * registry sweep still calls — has nothing left to do for UDP.
 */
internal class UdpActivityTap(
    private val engine: DomainUsageEngine,
    private val counter: RuleCounter,
    private val control: FlowControl,
) : FlowTap {

    override fun onClientData(data: ByteArray, off: Int, len: Int): ClientDataResult {
        if (engine.mode == EngineMode.ENFORCE && counter.exhausted) {
            engine.counters.inc(Counter.udp_dropped)
            return ClientDataResult.Reset
        }
        counter.touchChunk(len, engine.clock)
        return ClientDataResult.PassThrough
    }

    override fun onServerData(len: Int) = counter.touchChunk(len, engine.clock)

    override fun onClosed() = engine.registry.unregister(counter.key, control)
}

/**
 * NFR-2/INV-6 for taps: the Baseline calls tap methods directly (they are not reached through
 * [DomainUsageEngine.hook]), so the Engine hands out wrapped taps. Ordinary exceptions are counted
 * and fall back to pass-through; a `VirtualMachineError` is counted and rethrown (D-35). The
 * fallback also closes the inner tap, so a guard failure can never strand a pooled buffer or a
 * registry entry. Taps that can recover more gracefully (held bytes) do so before they get here.
 */
internal class GuardedTap(private val engine: DomainUsageEngine, private val inner: FlowTap) : FlowTap {

    override fun onClientData(data: ByteArray, off: Int, len: Int): ClientDataResult =
        try {
            inner.onClientData(data, off, len)
        } catch (e: VirtualMachineError) {
            engine.counters.inc(Counter.fatal_vm_error)
            throw e
        } catch (e: Exception) {
            engine.onHookException()
            onClosed()
            ClientDataResult.PassThrough
        }

    override fun onServerData(len: Int) {
        try {
            inner.onServerData(len)
        } catch (e: VirtualMachineError) {
            engine.counters.inc(Counter.fatal_vm_error)
            throw e
        } catch (e: Exception) {
            engine.onHookException()
        }
    }

    override fun onClosed() {
        try {
            inner.onClosed()
        } catch (e: VirtualMachineError) {
            engine.counters.inc(Counter.fatal_vm_error)
            throw e
        } catch (e: Exception) {
            engine.onHookException()
        }
    }
}

/** §4.7: a relayed chunk counts as activity only at or above [MeteringConfig.MIN_ACTIVITY_BYTES]. */
internal fun RuleCounter.touchChunk(len: Int, clock: MonotonicClock) {
    if (len >= MeteringConfig.MIN_ACTIVITY_BYTES) touch(clock.elapsedMs())
}
